package org.ed22.kiosk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Self-updater: checks [KioskConfig.UPDATE_MANIFEST_URL] every few hours (and at app start), downloads
 * a newer APK, checks its SHA-256 and installs it with PackageInstaller. Installs are silent only when
 * the app is device owner; otherwise the update is just reported in the parent menu.
 */
object Updater {
    private const val TAG = "Updater"
    private const val PERIODIC = "kiosk-update-periodic"
    private const val NOW = "kiosk-update-now"
    private const val PREFS = "kiosk_update"
    private const val MAX_APK_BYTES = 200L * 1024 * 1024

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Schedules the periodic check and runs one now. Safe to call on every start. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(KioskConfig.UPDATE_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(network).build()
        )
        checkNow(ctx, replace = false)
    }

    fun checkNow(ctx: Context, replace: Boolean = true) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            NOW, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(network).build()
        )
    }

    /** Human-readable status for the parent menu. */
    fun statusText(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getLong("lastCheck", 0)
        val lastText = if (last == 0L) "never" else DateFormat.getDateTimeInstance().format(Date(last))
        return "Installed: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "Last check: $lastText\n" +
            "Status: ${p.getString("status", "—")}"
    }

    fun setStatus(ctx: Context, status: String, checked: Boolean = false) {
        Log.i(TAG, status)
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("status", status)
        if (checked) e.putLong("lastCheck", System.currentTimeMillis())
        e.apply()
    }

    /** Runs on a WorkManager thread. */
    internal fun check(ctx: Context) {
        val manifestUrl = URL(KioskConfig.UPDATE_MANIFEST_URL)
        val manifest = JSONObject(httpGet(manifestUrl).use { it.inputStream.bufferedReader().readText() })
        val code = manifest.getLong("versionCode")
        val name = manifest.optString("versionName", code.toString())
        if (code <= BuildConfig.VERSION_CODE) {
            setStatus(ctx, "Up to date (latest is $name)", checked = true)
            return
        }
        if (!KioskPolicy.isDeviceOwner(ctx)) {
            setStatus(ctx, "Update $name available, but automatic install needs device owner", checked = true)
            return
        }
        val apkUrl = URL(manifestUrl, manifest.getString("url"))
        val expected = manifest.getString("sha256").lowercase()
        val apk = File(ctx.cacheDir, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        httpGet(apkUrl).use { conn ->
            conn.inputStream.use { input ->
                apk.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_APK_BYTES) { "APK too large" }
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            apk.delete()
            setStatus(ctx, "Downloaded $name but its SHA-256 did not match; will retry", checked = true)
            return
        }
        setStatus(ctx, "Installing $name…", checked = true)
        install(ctx, apk)
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val result = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallResultReceiver::class.java), flags
            )
            session.commit(result.intentSender)
        }
        apk.delete()
    }

    private fun httpGet(url: URL): Response {
        require(url.protocol == "https") { "Updates must use https: $url" }
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.useCaches = false
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw java.io.IOException("HTTP $code from $url")
        }
        return Response(conn)
    }

    class Response(private val conn: HttpURLConnection) : java.io.Closeable {
        val inputStream get() = conn.inputStream
        override fun close() = conn.disconnect()
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = try {
        Updater.check(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Updater.setStatus(applicationContext, "Check failed: ${e.message ?: e.javaClass.simpleName}", checked = true)
        Result.retry()
    }
}

/** Receives the PackageInstaller result. On success Android restarts us via MY_PACKAGE_REPLACED. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Updater.setStatus(
            context,
            when (status) {
                PackageInstaller.STATUS_SUCCESS -> "Updated to ${BuildConfig.VERSION_NAME}"
                PackageInstaller.STATUS_PENDING_USER_ACTION -> "Install needs confirmation (not device owner?)"
                else -> "Install failed ($status): ${message ?: "unknown error"}"
            }
        )
    }
}
