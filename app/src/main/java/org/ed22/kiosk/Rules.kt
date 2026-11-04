package org.ed22.kiosk

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.ceil
import kotlin.math.max

/**
 * Kotlin port of ed22's edRules.js `state()` (the allowed-apps rule logic shared with /mobile and /me),
 * checked against the same rules/cases.json in RulesTest. Pure: the time, zone, child and usage are passed in.
 *
 * policy.allowedApps[i] = {package, until, days?, studentIds?, rules?: [{days?, studentIds?, start?, end?, minutesPerDay?}], extraUntil?}
 * With rules: allowed if extraUntil > now, or ANY rule matches the day, the child and the window and has minutes left
 * (top-level days/studentIds are then a copy for old clients and ignored). Tablet local time, start inclusive, end
 * exclusive, start > end = overnight (days = the day it starts), no start/end = the whole day. A limit is per child,
 * per rule, per window. Without rules: the legacy `until` (+ optional days / studentIds).
 *
 * Additions beyond edRules.js (Android request A2/A3):
 * - [strict] (policy.strict): (a) parent mode (no child): entries with rules allow only a future extraUntil;
 *   (b) entries without rules allow only while `until` is a number in the future; (c) an empty rules list allows nothing.
 * - `elsewhere: {<studentId>: {date: 'yyyy-MM-dd', minutes}}`: minutes that child used the app on other tablets
 *   today; added to the used minutes of every limited rule when `date` is today here. Ignored in parent mode.
 */
object Rules {
    /** [left] in minutes (Infinity = no limit), [until] = when this answer ends (ms) or null. */
    data class State(
        val allowed: Boolean,
        val why: String, // 'extra' | 'rule' | 'legacy' | 'rules' (no rule applies now) | 'none' (no entry)
        val left: Double = Double.POSITIVE_INFINITY,
        val until: Long? = null,
        val limit: Double? = null,
    )

    /** Minutes [studentId] (null = anyone) used [pkg] in [fromMs, toMs). */
    fun interface Used {
        fun minutes(pkg: String, studentId: String?, fromMs: Long, toMs: Long): Double
    }

    private val HHMM = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    fun minutes(hhmm: String?): Int? =
        HHMM.matchEntire(hhmm ?: "")?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }

    private fun str(o: JSONObject?, key: String): String? = (o?.opt(key) as? String)

    private fun arr(o: JSONObject?, key: String): List<Any?> {
        val a = o?.opt(key) as? JSONArray ?: return emptyList()
        return (0 until a.length()).map { a.opt(it) }
    }

    private fun kidOk(ids: List<Any?>, sid: String?) = ids.isEmpty() || sid.isNullOrEmpty() || ids.any { it == sid }

    private fun dayOk(days: List<Any?>, d: Int) = days.isEmpty() || days.any { it is Number && it.toDouble() == d.toDouble() }

    /** JS getDay(): 0 = Sunday. */
    private fun dayOf(t: ZonedDateTime) = t.dayOfWeek.value % 7

    /** Local midnight of [now]'s date + [dayOffset] days, + [min] minutes, as ms (like `new Date(y, m, d + off, 0, min)`). */
    private fun at(now: ZonedDateTime, dayOffset: Long, min: Int): Long =
        now.toLocalDate().plusDays(dayOffset).atStartOfDay().plusMinutes(min.toLong()).atZone(now.zone).toInstant().toEpochMilli()

    fun timed(r: JSONObject?): Boolean {
        val s = minutes(str(r, "start")) ?: return false
        val e = minutes(str(r, "end")) ?: return false
        return s != e
    }

    fun limitOf(r: JSONObject?): Double? {
        val n = when (val v = r?.opt("minutesPerDay")) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }
        return if (n != null && n > 0) n else null
    }

    /** The window of [r] that contains [now], as [fromMs, toMs), or null. "Any time" = the whole local day. */
    fun windowNow(r: JSONObject, now: ZonedDateTime): LongArray? {
        val d = dayOf(now)
        val t = now.hour * 60 + now.minute
        val days = arr(r, "days")
        if (!timed(r)) return if (dayOk(days, d)) longArrayOf(at(now, 0, 0), at(now, 1, 0)) else null
        val s = minutes(str(r, "start"))!!
        val e = minutes(str(r, "end"))!!
        if (s < e) return if (dayOk(days, d) && t >= s && t < e) longArrayOf(at(now, 0, s), at(now, 0, e)) else null
        if (dayOk(days, d) && t >= s) return longArrayOf(at(now, 0, s), at(now, 1, e))           // overnight, started today
        if (dayOk(days, (d + 6) % 7) && t < e) return longArrayOf(at(now, -1, s), at(now, 0, e)) // overnight, started yesterday
        return null
    }

    /** The entry's `elsewhere[sid].minutes` when its date is today (tablet time), else 0. */
    fun elsewhereMinutes(entry: JSONObject, now: ZonedDateTime, sid: String?): Double {
        if (sid.isNullOrEmpty()) return 0.0
        val e = entry.optJSONObject("elsewhere")?.optJSONObject(sid) ?: return 0.0
        if (str(e, "date") != now.toLocalDate().toString()) return 0.0
        return (e.opt("minutes") as? Number)?.toDouble()?.takeIf { it > 0 } ?: 0.0
    }

    private fun ruleState(pkg: String, r: JSONObject?, now: ZonedDateTime, sid: String?, used: Used?, elsewhere: Double): State? {
        if (r == null || !kidOk(arr(r, "studentIds"), sid)) return null
        val w = windowNow(r, now) ?: return null
        val limit = limitOf(r)
        val left = if (limit == null) Double.POSITIVE_INFINITY
        else max(0.0, limit - elsewhere - (used?.minutes(pkg, sid, w[0], now.toInstant().toEpochMilli()) ?: 0.0))
        return State(true, "rule", left, w[1], limit)
    }

    fun hasRules(e: JSONObject?) = arr(e, "rules").isNotEmpty()

    /** `policy.rev` in ms; missing = 0. */
    fun rev(p: JSONObject?): Long = (p?.opt("rev") as? Number)?.toLong() ?: 0L

    /**
     * The policy in force and its source: the [local] one (kiosk.policy.set) when its `rev` is higher than the
     * [doc]'s, else the doc's (ties and missing revs go to the doc). `locked` and `command` always come from the doc.
     */
    fun pickPolicy(doc: JSONObject?, local: JSONObject?): Pair<JSONObject, String> {
        val d = doc ?: JSONObject()
        if (local == null || rev(local) <= rev(d)) return d to "doc"
        val p = JSONObject(local.toString())
        p.remove("locked"); p.remove("command")
        d.opt("locked")?.let { p.put("locked", it) }
        d.opt("command")?.let { p.put("command", it) }
        return p to "local"
    }

    /** edRules.state(): the most generous matching rule wins. [strict]: see the class comment. */
    fun state(entry: JSONObject?, nowMs: Long, zone: ZoneId, sid: String?, used: Used?, strict: Boolean = false): State {
        if (entry == null) return State(false, "none")
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        if (strict && entry.opt("rules") is JSONArray && !hasRules(entry)) return State(false, "rules") // (c)
        if (hasRules(entry)) {
            val extra = entry.opt("extraUntil")
            if (extra is Number && extra.toDouble() > nowMs) {
                val x = extra.toLong()
                return State(true, "extra", ceil((x - nowMs) / 60_000.0), x, null)
            }
            if (strict && sid.isNullOrEmpty()) return State(false, "rules") // (a)
            val elsewhere = elsewhereMinutes(entry, now, sid)
            var best: State? = null
            val pkg = str(entry, "package") ?: ""
            val rules = entry.optJSONArray("rules")!!
            for (i in 0 until rules.length()) {
                val s = ruleState(pkg, rules.optJSONObject(i), now, sid, used, elsewhere) ?: continue
                val b = best
                if (s.left > 0 && (b == null || s.left > b.left || (s.left == b.left && s.until!! > b.until!!))) best = s
            }
            return best ?: State(false, "rules")
        }
        val u = entry.opt("until")
        val untilOk = (!strict && (u == null || u == JSONObject.NULL)) || (u is Number && u.toDouble() > nowMs) // (b)
        val ok = untilOk && dayOk(arr(entry, "days"), dayOf(now)) && kidOk(arr(entry, "studentIds"), sid)
        return if (ok) State(true, "legacy", until = (u as? Number)?.toLong()) else State(false, "legacy")
    }
}
