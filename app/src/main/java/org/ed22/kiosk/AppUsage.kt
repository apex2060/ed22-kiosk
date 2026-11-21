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
 *
 * Usage accuracy (Ryan, Nov 20 2026):
 * - A launched session shorter than [MIN_MS] (15 s) is not in the parent's report (no seconds, no open): a launch that bounces straight
 *   back to the kiosk, a tap by mistake. It still counts for the allowed-apps limits from [RULES_MIN_MS] (2 s, as before), so short
 *   bursts cannot dodge a limit. A launch the kiosk never left (the app did not come up) is dropped by [dropIfBounced] a few seconds later.
 * - "Resumed" sessions (the screen woke with an app already open) are counted exactly as before: full length, from 2 s, on battery and
 *   on the charger (Ryan, Nov 20 2026: "Screen on is counted").
 * - [usageByChildMap] adds `lastMs` per app per day: when the newest counted use ended (epoch ms). Older servers read only seconds/opens.
 */
object AppUsage {
    private const val TAG = "AppUsage"
    private const val PREFS = "kiosk_usage"
    private const val KEY_SESSION = "session"
    private const val KEY_PAUSED = "pausedPkg"
    private const val KEY_DAYS = "days"
    private const val KEY_SESSIONS = "sessions"
    private const val CHILD_DAYS = 7
    private const val DAYS = 14
    /** Shorter sessions are left out of the parent's report (bounces back to the kiosk, mis-taps). Was 2 s. */
    const val MIN_MS = 15_000L
    /** Shorter sessions are not kept at all (also not for the rule limits): the old MIN_MS. */
    const val RULES_MIN_MS = 2_000L
    /** How long after a launch [dropIfBounced] checks that the kiosk actually left the screen. */
    const val BOUNCE_CHECK_MS = 3_000L
    private const val MAX_MS = 12 * 3_600_000L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dateKey(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    /**
     * Called after [pkg] was started. Ends any open session first. [resumed] = the same app is still in front after
     * the screen came back on: counted for the rules and appUsageByChild (no new open), not added to appUsage.
     */
    @Synchronized
    fun start(ctx: Context, pkg: String, resumed: Boolean = false): Long {
        end(ctx)
        val now = System.currentTimeMillis()
        val s = JSONObject().put("pkg", pkg).put("startMs", now)
            .put("studentId", Prefs.activeStudentId(ctx) ?: JSONObject.NULL)
        if (resumed) s.put("resumed", true)
        prefs(ctx).edit().putString(KEY_SESSION, s.toString()).commit()
        return now
    }

    /**
     * Called [BOUNCE_CHECK_MS] after a launch: if the kiosk is still (or again) in front and that launch's session is still open, the app
     * never took the screen, so the session is dropped without counting (MainActivity.onResume normally ends it, but only if the kiosk paused).
     */
    @Synchronized
    fun dropIfBounced(ctx: Context, pkg: String, startMs: Long) {
        if (!MainActivity.inForeground) return
        val s = openSession(ctx) ?: return
        if (s.optString("pkg") != pkg || s.optLong("startMs") != startMs) return
        prefs(ctx).edit().remove(KEY_SESSION).commit()
        Log.i(TAG, "$pkg: launch bounced, not counted")
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
        if (start <= 0 || ms < RULES_MIN_MS) return
        val sid = if (s.isNull("studentId")) null else s.optString("studentId")
        val length = minOf(ms, MAX_MS)
        // Only a launched session can be "short"; a resumed one counts as before (from RULES_MIN_MS, full length).
        val short = !s.optBoolean("resumed") && ms < MIN_MS
        recordSession(ctx, pkg, sid, start, start + length, s.optBoolean("resumed"), short)
        if (short) {
            Log.i(TAG, "$pkg: ${ms / 1000}s, under ${MIN_MS / 1000}s: counted for the limits only")
            return
        }
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
    /** The app that was open when the screen turned off (kept across a process restart), for the "resumed" session. */
    fun setPausedPkg(ctx: Context, pkg: String) { prefs(ctx).edit().putString(KEY_PAUSED, pkg).commit() }

    fun takePausedPkg(ctx: Context): String? {
        val p = prefs(ctx)
        val pkg = p.getString(KEY_PAUSED, null)
        if (pkg != null) p.edit().remove(KEY_PAUSED).commit()
        return pkg?.takeIf { it.isNotBlank() }
    }

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

    /**
     * Stores [startMs, endMs) as one piece per local day; `cont` marks pieces that are not a new open. [short] = a launched session under
     * [MIN_MS]: its pieces carry `short` and `creditMs` 0, so the limits count them but the parent's report does not. Every other piece
     * (and every piece stored by older builds) counts in full.
     */
    private fun recordSession(ctx: Context, pkg: String, sid: String?, startMs: Long, endMs: Long, resumed: Boolean, short: Boolean = false) {
        val list = sessions(ctx)
        val zone = ZoneId.systemDefault()
        var from = startMs
        while (from < endMs) {
            val midnight = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = minOf(endMs, midnight)
            list.put(JSONObject().put("pkg", pkg).put("studentId", sid ?: JSONObject.NULL)
                .put("startMs", from).put("endMs", to).apply {
                    if (from != startMs || resumed) put("cont", true)
                    if (short) put("short", true).put("creditMs", 0L)
                })
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

    /**
     * `{ "yyyy-MM-dd": { "<studentId>"|"parent": { "<package>": {seconds, opens, lastMs?} } } }` for the last [CHILD_DAYS] days.
     * seconds = counted time (launched and resumed sessions in full, as before; a launch under [MIN_MS] not at all); lastMs = when the
     * newest counted use that day ended (epoch ms), left out when nothing counted. A short launch adds no entry (no seconds, no open).
     */
    fun usageByChildMap(ctx: Context): Map<String, Any> = usageByChildFrom(sessions(ctx),
        dateKey(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(CHILD_DAYS - 1)) }.timeInMillis)) { dateKey(it) }

    /** [usageByChildMap] over stored pieces (pure: unit-tested). [day] maps a piece's startMs to its local date. */
    fun usageByChildFrom(list: JSONArray, oldest: String, day: (Long) -> String): Map<String, Any> {
        val ms = TreeMap<String, TreeMap<String, TreeMap<String, LongArray>>>() // [counted ms, opens, last ms]
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val d = day(s.optLong("startMs"))
            if (d < oldest) continue
            val raw = maxOf(0L, s.optLong("endMs") - s.optLong("startMs"))
            val credit = if (s.has("creditMs")) s.optLong("creditMs").coerceIn(0L, raw) else raw
            val open = !s.optBoolean("cont") && !s.optBoolean("short")
            if (credit <= 0L && !open) continue
            val who = if (s.isNull("studentId")) "parent" else s.optString("studentId", "parent")
            val e = ms.getOrPut(d) { TreeMap() }.getOrPut(who) { TreeMap() }.getOrPut(s.optString("pkg")) { LongArray(3) }
            e[0] += credit
            if (open) e[1]++
            if (credit > 0L) e[2] = maxOf(e[2], s.optLong("startMs") + credit)
        }
        return ms.mapValues { (_, kids) ->
            kids.mapValues { (_, apps) ->
                apps.mapValues { (_, e) ->
                    linkedMapOf<String, Any>("seconds" to e[0] / 1000, "opens" to e[1]).apply { if (e[2] > 0L) put("lastMs", e[2]) }
                }
            }
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
