package org.ed22.kiosk

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
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
 *
 * For the allowed-apps rules it also keeps every session with its child: {pkg, studentId?, startMs, endMs}
 * (no studentId = parent mode), split at local midnight and when the active child changes, for [DAYS] days.
 * [usedMinutes] feeds the rule limits; [usageByChildMap] is `status.appUsageByChild` ([CHILD_DAYS] days).
 */
object AppUsage {
    private const val TAG = "AppUsage"
    private const val PREFS = "kiosk_usage"
    private const val KEY_SESSION = "session"
    private const val KEY_DAYS = "days"
    private const val KEY_SESSIONS = "sessions"
    private const val CHILD_DAYS = 7
    private const val DAYS = 14
    private const val MIN_MS = 2_000L
    private const val MAX_MS = 12 * 3_600_000L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dateKey(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    /**
     * Called after [pkg] was started. Ends any open session first. [resumed] = the same app is still in front after
     * the screen came back on: counted for the rules and appUsageByChild (no new open), not added to appUsage.
     */
    @Synchronized
    fun start(ctx: Context, pkg: String, resumed: Boolean = false) {
        end(ctx)
        val s = JSONObject().put("pkg", pkg).put("startMs", System.currentTimeMillis())
            .put("studentId", Prefs.activeStudentId(ctx) ?: JSONObject.NULL)
        if (resumed) s.put("resumed", true)
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
        val sid = if (s.isNull("studentId")) null else s.optString("studentId")
        recordSession(ctx, pkg, sid, start, start + minOf(ms, MAX_MS), s.optBoolean("resumed"))
        if (s.optBoolean("resumed")) {
            DeviceSync.reportStatus(ctx.applicationContext)
            return
        }
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

    /** The package of the open session, if any. */
    fun openPkg(ctx: Context): String? = openSession(ctx)?.optString("pkg")?.takeIf { it.isNotBlank() }

    private fun openSession(ctx: Context): JSONObject? =
        prefs(ctx).getString(KEY_SESSION, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Ends the open session for the old child and, if an app was open, starts one for the new child around [change]. */
    @Synchronized
    fun switchChild(ctx: Context, change: () -> Unit) {
        val pkg = openPkg(ctx)
        end(ctx)
        change()
        if (pkg != null) start(ctx, pkg)
    }

    /** Stores [startMs, endMs) as one piece per local day; `cont` marks pieces that are not a new open. */
    private fun recordSession(ctx: Context, pkg: String, sid: String?, startMs: Long, endMs: Long, resumed: Boolean) {
        val list = sessions(ctx)
        val zone = ZoneId.systemDefault()
        var from = startMs
        while (from < endMs) {
            val midnight = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = minOf(endMs, midnight)
            list.put(JSONObject().put("pkg", pkg).put("studentId", sid ?: JSONObject.NULL)
                .put("startMs", from).put("endMs", to).apply { if (from != startMs || resumed) put("cont", true) })
            from = to
        }
        val oldest = dateKey(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(DAYS - 1)) }.timeInMillis)
        val kept = JSONArray()
        for (i in 0 until list.length()) list.optJSONObject(i)?.let { if (dateKey(it.optLong("startMs")) >= oldest) kept.put(it) }
        prefs(ctx).edit().putString(KEY_SESSIONS, kept.toString()).apply()
    }

    private fun sessions(ctx: Context): JSONArray =
        prefs(ctx).getString(KEY_SESSIONS, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()

    /** Minutes [studentId] (null = anyone) spent in [pkg] within [fromMs, toMs), the open session included. */
    fun usedMinutes(ctx: Context, pkg: String, studentId: String?, fromMs: Long, toMs: Long): Double {
        val now = System.currentTimeMillis()
        val all = sessions(ctx).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } +
            listOfNotNull(openSession(ctx)?.let { JSONObject(it.toString()).put("endMs", now) })
        return all.filter { it.optString("pkg") == pkg && (studentId == null || (!it.isNull("studentId") && it.optString("studentId") == studentId)) }
            .sumOf { maxOf(0L, minOf(toMs, it.optLong("endMs")) - maxOf(fromMs, it.optLong("startMs"))) } / 60_000.0
    }

    /** `{ "yyyy-MM-dd": { "<studentId>"|"parent": { "<package>": {seconds, opens} } } }` for the last [CHILD_DAYS] days. */
    fun usageByChildMap(ctx: Context): Map<String, Any> {
        val oldest = dateKey(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(CHILD_DAYS - 1)) }.timeInMillis)
        val ms = TreeMap<String, TreeMap<String, TreeMap<String, LongArray>>>() // [ms, opens]
        val list = sessions(ctx)
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val day = dateKey(s.optLong("startMs"))
            if (day < oldest) continue
            val who = if (s.isNull("studentId")) "parent" else s.optString("studentId", "parent")
            val e = ms.getOrPut(day) { TreeMap() }.getOrPut(who) { TreeMap() }.getOrPut(s.optString("pkg")) { LongArray(2) }
            e[0] += s.optLong("endMs") - s.optLong("startMs")
            if (!s.optBoolean("cont")) e[1]++
        }
        return ms.mapValues { (_, kids) ->
            kids.mapValues { (_, apps) -> apps.mapValues { (_, e) -> linkedMapOf("seconds" to e[0] / 1000, "opens" to e[1]) } }
        }
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
