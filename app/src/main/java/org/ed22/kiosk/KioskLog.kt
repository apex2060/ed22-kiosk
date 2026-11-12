package org.ed22.kiosk

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A small persisted ring of device events (about [Recovery.LOG_MAX]) for "why did it leave the kiosk / go offline".
 * Uploaded by [DeviceSync.reportStatus] as `status.log` (newest [Recovery.UPLOAD_MAX]) and `status.lastExit {at, why}`.
 * Writes are synchronous and tiny, so a crash entry is on disk before the process dies.
 */
object KioskLog {
    private const val TAG = "KioskLog"
    private const val FILE = "kiosk-log.json"
    private const val PREFS = "kiosk_log"
    private const val KEY_LAST_EXIT = "lastExit"
    private const val KEY_EXIT_SEEN = "exitInfoSeenAt"
    private val lock = Any()
    private var cache: JSONArray? = null

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(ctx: Context): JSONArray =
        cache ?: Recovery.parse(runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull()).also { cache = it }

    /** Records an event. [important] also asks [DeviceSync] for an upload soon. */
    fun add(ctx: Context, kind: String, msg: String, detail: String? = null, important: Boolean = false) {
        Log.i(TAG, "$kind: $msg")
        try {
            synchronized(lock) {
                val list = Recovery.append(load(ctx), System.currentTimeMillis(), kind, msg, detail)
                cache = list
                val f = file(ctx)
                val tmp = File(f.parentFile, "$FILE.tmp")
                tmp.writeText(list.toString())
                if (!tmp.renameTo(f)) f.writeText(list.toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write the device log", e)
        }
        if (important) runCatching { DeviceSync.reportSoon() }
    }

    /** Records why the kiosk last left the screen unexpectedly (status.lastExit). */
    fun exit(ctx: Context, why: String) {
        prefs(ctx).edit().putString(KEY_LAST_EXIT, Recovery.lastExit(System.currentTimeMillis(), why).toString()).commit()
    }

    fun lastExit(ctx: Context): JSONObject? =
        prefs(ctx).getString(KEY_LAST_EXIT, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun entries(ctx: Context): JSONArray = synchronized(lock) { JSONArray(load(ctx).toString()) }

    /** status.log value. */
    fun forUpload(ctx: Context): List<Map<String, Any>> = Recovery.forUpload(entries(ctx))

    /**
     * Android 11+: logs how earlier runs of this app ended (crash, ANR, low memory, killed, updated…), once each.
     * This is the system's own record, so it also covers kills that no code in the app could see.
     */
    fun recordPastExits(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val seen = prefs(ctx).getLong(KEY_EXIT_SEEN, 0L)
            val infos = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
                .filter { it.timestamp > seen }
                .sortedBy { it.timestamp }
            if (infos.isEmpty()) return
            infos.forEach { info ->
                val name = Recovery.exitReasonName(info.reason)
                val fg = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                val msg = "Previous run ended: $name" + (if (fg) " (in front)" else " (in background)") +
                    (info.description?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: "") +
                    " pss=${info.pss / 1024}MB"
                add(ctx, "pastExit", msg)
                // Our own crash handler already wrote a better lastExit just before it ended the process.
                val known = lastExit(ctx)?.optLong("at") ?: 0L
                if (!Recovery.isExpectedExit(info.reason) && known < info.timestamp - 10_000) {
                    prefs(ctx).edit().putString(KEY_LAST_EXIT, Recovery.lastExit(info.timestamp, msg).toString()).commit()
                }
            }
            prefs(ctx).edit().putLong(KEY_EXIT_SEEN, infos.maxOf { it.timestamp }).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read past exit reasons", e)
        }
    }
}
