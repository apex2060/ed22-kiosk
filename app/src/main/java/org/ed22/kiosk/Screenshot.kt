package org.ed22.kiosk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.PixelCopy
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * policy.command screenshot: capture (whole screen via [ShotService], else Little Explorer's own window), JPEG in memory,
 * unsigned Cloudinary upload. Silent: no toast, sound, flash or notification. Nothing is written to disk or the gallery.
 * Work runs on a background thread; only the window pixel copy uses the main thread.
 */
object Screenshot {
    private const val TAG = "Screenshot"
    private const val CAPTURE_TIMEOUT_MS = 15_000L

    /** Success: [url]..[app] for status.screenshot. Failure: [why] for status.remote. */
    data class Result(
        val ok: Boolean, val why: String = "", val url: String = "", val at: Long = 0, val w: Int = 0, val h: Int = 0,
        val scope: String = "", val app: ScreenshotPlan.AppRef? = null,
    )

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-screenshot").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** Call on the main thread. [done] is called once, on the main thread. */
    fun take(ctx: Context, uid: String, deviceDocId: String, done: (Result) -> Unit) {
        val app = ctx.applicationContext
        val once = AtomicBoolean(false)
        val finish: (Result) -> Unit = { r -> if (once.compareAndSet(false, true)) main.post { done(r) } }
        val interactive = app.getSystemService(PowerManager::class.java)?.isInteractive != false
        val plan = ScreenshotPlan.plan(Build.VERSION.SDK_INT, ShotService.ready, interactive, MainActivity.inForeground,
            frontApp(app), app.packageName)
        if (!plan.ok) return finish(Result(false, plan.why))
        // The timeout covers the capture only (not the upload, which has its own HTTP timeouts).
        val captureDone = AtomicBoolean(false)
        val timeout = Runnable { if (captureDone.compareAndSet(false, true)) finish(Result(false, "Screenshot timed out")) }
        main.postDelayed(timeout, CAPTURE_TIMEOUT_MS)
        val captured: (Bitmap?, String?, String) -> Unit = captured@{ bmp, err, scope ->
            val at = System.currentTimeMillis()
            if (!captureDone.compareAndSet(false, true)) { bmp?.recycle(); return@captured }
            main.removeCallbacks(timeout)
            if (bmp == null) finish(Result(false, err ?: "Screenshot failed"))
            else worker.execute { finish(encodeAndUpload(bmp, at, scope, plan.app, uid, deviceDocId)) }
        }
        if (plan.scope == ScreenshotPlan.SCOPE_SCREEN && Build.VERSION.SDK_INT >= ScreenshotPlan.FULL_SCREEN_SDK) {
            val started = ShotService.capture(worker) { bmp, err ->
                if (bmp == null && MainActivity.inForeground) {
                    // The service failed but the kiosk is in front: its own window is the same picture.
                    Log.w(TAG, "Full-screen capture failed ($err); using the kiosk window")
                    main.post { captureWindow { b, e -> captured(b, e, ScreenshotPlan.SCOPE_APP) } }
                } else captured(bmp, err, ScreenshotPlan.SCOPE_SCREEN)
            }
            if (started) return
            if (!MainActivity.inForeground) return finish(Result(false, "Little Explorer's Accessibility access is off"))
        }
        captureWindow { b, e -> captured(b, e, ScreenshotPlan.SCOPE_APP) }
    }

    /** The app the child opened from the kiosk (it is in front when the kiosk is not), with its label. */
    private fun frontApp(ctx: Context): ScreenshotPlan.AppRef? {
        val pkg = AppUsage.openPkg(ctx) ?: return null
        val label = runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull() ?: pkg
        return ScreenshotPlan.AppRef(pkg, label)
    }

    /** Little Explorer's own window (main thread): PixelCopy (includes the WebView surface), else drawing the view tree. */
    private fun captureWindow(done: (Bitmap?, String?) -> Unit) {
        val activity = MainActivity.current
        if (activity == null || !MainActivity.inForeground || activity.isFinishing) return done(null, "Little Explorer is not on screen")
        val window = activity.window
        val decor = window.decorView
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0) return done(null, "Little Explorer is not on screen")
        val fallback = {
            runCatching {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
            }.fold({ done(it, null) }, { done(null, "Could not capture Little Explorer (${it.javaClass.simpleName})") })
        }
        try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            PixelCopy.request(window, bmp, { result ->
                if (result == PixelCopy.SUCCESS) done(bmp, null) else { bmp.recycle(); fallback() }
            }, main)
        } catch (e: Exception) {
            Log.w(TAG, "PixelCopy failed", e)
            fallback()
        }
    }

    /** Background thread: scale, JPEG in memory, upload. */
    private fun encodeAndUpload(bmp: Bitmap, at: Long, scope: String, app: ScreenshotPlan.AppRef?, uid: String, deviceDocId: String): Result {
        val (w, h) = ScreenshotPlan.scaled(bmp.width, bmp.height)
        val jpeg = try {
            val scaled = if (w == bmp.width && h == bmp.height) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, ScreenshotPlan.JPEG_QUALITY, out)
            if (scaled !== bmp) scaled.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            return Result(false, "Could not make the image (${e.javaClass.simpleName})")
        } finally {
            bmp.recycle()
        }
        val url = try {
            upload(jpeg, uid, deviceDocId)
        } catch (e: Exception) {
            Log.w(TAG, "Upload failed: ${e.javaClass.simpleName}: ${e.message}")
            return Result(false, if (e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException)
                "Upload failed: the tablet is offline" else "Upload failed")
        } ?: return Result(false, "Upload failed")
        return Result(true, url = url, at = at, w = w, h = h, scope = scope, app = app)
    }

    /** Unsigned Cloudinary upload (public preset), streamed from memory. Returns secure_url or null. */
    private fun upload(jpeg: ByteArray, uid: String, deviceDocId: String): String? {
        val boundary = "kiosk" + UUID.randomUUID().toString().replace("-", "")
        val body = ScreenshotPlan.multipart(boundary,
            ScreenshotPlan.formFields(KioskConfig.CLOUDINARY_PRESET, uid, deviceDocId), "screenshot.jpg", jpeg)
        val conn = URL(ScreenshotPlan.uploadUrl(KioskConfig.CLOUDINARY_CLOUD)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            conn.useCaches = false
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }
            if (code !in 200..299) {
                Log.w(TAG, "Cloudinary HTTP $code")
                return null
            }
            return ScreenshotPlan.secureUrl(text)
        } finally {
            conn.disconnect()
        }
    }
}
