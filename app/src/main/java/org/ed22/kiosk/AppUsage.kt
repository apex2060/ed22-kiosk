package org.ed22.kiosk

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TreeMap

/**
 * Rough time spent in apps opened through [DeviceSync.launch], without the Usage access permission.
 * A session runs from a successful launch until the kiosk is back in front (MainActivity.onResume),
 * the screen turns off, or the next launch. Seconds are added to the session's start date
 * (device timezone). The open session and the last [DAYS] days are kept in prefs.
 */
object AppUsage {
    private const val TAG = "AppUsage"
    private const val PREFS = "kiosk_usage"
    private const val KEY_SESSION = "session"
    private const val KEY_DAYS = "days"
    private const val DAYS = 14
    private const val MIN_MS = 2_000L
    private const val MAX_MS = 12 * 3_600_000L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dateKey(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    /** Called after [pkg] was started. Ends any open session first. */
    @Synchronized
    fun start(ctx: Context, pkg: String) {
        end(ctx)
        val s = JSONObject().put("pkg", pkg).put("startMs", System.currentTimeMillis())
        prefs(ctx).edit().putString(KEY_SESSION, s.toString()).commit()
    }

    /** Ends the open session, if any, and reports status when time was added. */
    @Synchronized
    fun end(ctx: Context) {
        val p = prefs(ctx)
        val raw = p.getString(KEY_SESSION, null) ?: return
        p.edit().remove(KEY_SESSION).commit()
        val s = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val pkg = s.optString("pkg").takeIf { it.isNotBlank() } ?: return
        val start = s.optLong("startMs", 0L)
        val ms = System.currentTimeMillis() - start
        if (start <= 0 || ms < MIN_MS) return
        val seconds = (minOf(ms, MAX_MS) / 1000).toInt()
        val label = runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val days = load(ctx)
        val day = days.optJSONObject(dateKey(start)) ?: JSONObject().also { days.put(dateKey(start), it) }
        val e = day.optJSONObject(pkg) ?: JSONObject()
        day.put(
            pkg, JSONObject()
                .put("seconds", e.optInt("seconds") + seconds)
                .put("label", label)
                .put("opens", e.optInt("opens") + 1)
        )
        p.edit().putString(KEY_DAYS, prune(days).toString()).apply()
        Log.i(TAG, "$pkg: ${seconds}s")
        DeviceSync.reportStatus(ctx.applicationContext)
    }

    private fun load(ctx: Context): JSONObject =
        prefs(ctx).getString(KEY_DAYS, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    /** Keeps only the last [DAYS] local dates, today included. */
    private fun prune(days: JSONObject): JSONObject {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(DAYS - 1)) }
        val oldest = dateKey(cal.timeInMillis)
        val out = JSONObject()
        days.keys().forEach { k -> if (k >= oldest) days.optJSONObject(k)?.let { out.put(k, it) } }
        return out
    }

    /** `{ "yyyy-MM-dd": { "<package>": {seconds, label, opens} } }` for the last [DAYS] days. */
    fun usage(ctx: Context): JSONObject = prune(load(ctx))

    /** Same as [usage], as sorted maps for Firestore (and a stable hash). */
    fun usageMap(ctx: Context): Map<String, Any> {
        val days = usage(ctx)
        val out = TreeMap<String, Any>()
        days.keys().forEach { d ->
            val day = days.optJSONObject(d) ?: return@forEach
            val apps = TreeMap<String, Any>()
            day.keys().forEach { pkg ->
                val e = day.optJSONObject(pkg) ?: return@forEach
                apps[pkg] = linkedMapOf(
                    "seconds" to e.optInt("seconds"),
                    "label" to e.optString("label", pkg),
                    "opens" to e.optInt("opens"),
                )
            }
            out[d] = apps
        }
        return out
    }
}
