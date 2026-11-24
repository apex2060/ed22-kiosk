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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * policy.command screenshot: capture (whole screen via [ShotService], else Little Explorer's own window) and a JPEG data
 * URL in memory; [DeviceSync] writes it to the device's items/screenshot doc in Firestore. Silent: no toast, sound, flash
 * or notification. Nothing goes to files or the gallery (only Firestore's own app-private cache, like every synced doc).
 * Work runs on a background thread; only the window pixel copy uses the main thread.
 */
object Screenshot {
    private const val TAG = "Screenshot"
    private const val CAPTURE_TIMEOUT_MS = 15_000L

    /** Success: [at]..[data] for the subdoc and status.screenshot. Failure: [why] for status.remote. */
    class Result(
        val ok: Boolean, val why: String = "", val at: Long = 0, val w: Int = 0, val h: Int = 0,
        val scope: String = "", val app: ScreenshotPlan.AppRef? = null, val jpeg: ByteArray = ByteArray(0), val data: String = "",
    )

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-screenshot").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** Call on the main thread. [done] is called once, on the main thread. */
    fun take(ctx: Context, done: (Result) -> Unit) {
        val app = ctx.applicationContext
        val once = AtomicBoolean(false)
        val finish: (Result) -> Unit = { r -> if (once.compareAndSet(false, true)) main.post { done(r) } }
        val interactive = app.getSystemService(PowerManager::class.java)?.isInteractive != false
        val plan = ScreenshotPlan.plan(Build.VERSION.SDK_INT, ShotService.ready, interactive, MainActivity.inForeground,
            frontApp(app), app.packageName)
        if (!plan.ok) return finish(Result(false, plan.why))
        // The timeout covers the capture only (the Firestore write has its own, in DeviceSync).
        val captureDone = AtomicBoolean(false)
        val timeout = Runnable { if (captureDone.compareAndSet(false, true)) finish(Result(false, "Screenshot timed out")) }
        main.postDelayed(timeout, CAPTURE_TIMEOUT_MS)
        val captured: (Bitmap?, String?, String) -> Unit = captured@{ bmp, err, scope ->
            val at = System.currentTimeMillis()
            if (!captureDone.compareAndSet(false, true)) { bmp?.recycle(); return@captured }
            main.removeCallbacks(timeout)
            if (bmp == null) finish(Result(false, err ?: "Screenshot failed"))
            else worker.execute { finish(encode(bmp, at, scope, plan.app)) }
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

    /** Background thread: JPEG in memory, stepping quality and size down ([ScreenshotPlan.STEPS]) until the data URL fits. */
    private fun encode(bmp: Bitmap, at: Long, scope: String, app: ScreenshotPlan.AppRef?): Result {
        val scaledBySide = HashMap<Int, Bitmap>()
        try {
            val fit = ScreenshotPlan.firstFit({ step ->
                val (w, h) = ScreenshotPlan.scaled(bmp.width, bmp.height, step.maxSide)
                val scaled = scaledBySide.getOrPut(step.maxSide) {
                    scaledBySide.values.forEach { if (it !== bmp) it.recycle() }
                    scaledBySide.clear()
                    if (w == bmp.width && h == bmp.height) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
                }
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, step.quality, out)
                Triple(out.toByteArray(), w, h)
            }, { it.first.size }) ?: return Result(false, ScreenshotPlan.WHY_TOO_LARGE)
            val (jpeg, w, h) = fit.second
            if (fit.first != ScreenshotPlan.STEPS.first()) Log.i(TAG, "Screenshot fit at ${fit.first}")
            return Result(true, at = at, w = w, h = h, scope = scope, app = app, jpeg = jpeg, data = ScreenshotPlan.dataUrl(jpeg))
        } catch (e: Exception) {
            return Result(false, "Could not make the image (${e.javaClass.simpleName})")
        } finally {
            scaledBySide.values.forEach { if (it !== bmp) it.recycle() }
            bmp.recycle()
        }
    }
}
