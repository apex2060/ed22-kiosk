package org.ed22.kiosk

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.ceil
import kotlin.math.max

/**
 * Device-wide screen time for the active child (Ryan, Nov 13 2026): the hours the child may use the tablet and the most
 * minutes per day, on top of the per-app rules ([Rules]). Kotlin twin of ed22 edDeviceTime.js, checked against the same
 * device-time-cases.json in DeviceTimeTest. Pure: the time, zone, usage and extra time are passed in.
 *
 * policy.deviceTime = {students: {<studentId>: {windows: [{days?, start, end}], limits: [{days?, minutes}]}},
 *                      elsewhere: {<studentId>: {date, minutes}}, extra: {<studentId>: untilMs}, warnMinutes}
 * - windows: allowed hours on those days (start inclusive, end exclusive, start < end). A day with no window = any time.
 *   A day whose only windows are broken is closed (never widened).
 * - limits: most minutes per local day on those days (the smallest matching wins). None = no limit.
 * - days: 0 = Sunday … 6 = Saturday; missing / empty = every day. Times are this tablet's own clock and time zone.
 * - extra: a parent's "30 more minutes" (also a PIN grant on the tablet, [localExtra]); the time still counts as used.
 * - Parent mode (no active child) is never limited.
 */
object DeviceTime {
    const val WARN_MINUTES = 5
    const val EXTRA_MS = 30 * 60_000L

    /** [left] minutes (Infinity = no limit); [until] = when this answer ends; [next] = when a blocked child may use it again. */
    data class State(
        val allowed: Boolean,
        val why: String, // 'parent' | 'open' | 'extra' | 'ok' | 'window' | 'limit'
        val left: Double = Double.POSITIVE_INFINITY,
        val until: Long? = null,
        val next: Long? = null,
        val limit: Double? = null,
        val used: Double = 0.0,
        val ends: Boolean = false,
        val warn: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject().put("allowed", allowed).put("why", why)
            .put("left", if (left.isFinite()) ceil(left).toInt() else JSONObject.NULL)
            .put("until", until ?: JSONObject.NULL).put("next", next ?: JSONObject.NULL)
            .put("limit", limit ?: JSONObject.NULL).put("used", Math.round(used).toInt()).put("warn", warn)
    }

    private class Win(val s: Int, val e: Int)

    private fun list(o: JSONObject?, key: String): List<JSONObject> {
        val a = o?.opt(key) as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.opt(it) as? JSONObject }
    }

    private fun dayOk(o: JSONObject, d: Int): Boolean {
        val a = o.opt("days") as? JSONArray ?: return true
        if (a.length() == 0) return true
        return (0 until a.length()).any { (a.opt(it) as? Number)?.toDouble() == d.toDouble() }
    }

    private fun dayOf(t: ZonedDateTime) = t.dayOfWeek.value % 7

    private fun at(now: ZonedDateTime, dayOffset: Long, min: Int): Long =
        now.toLocalDate().plusDays(dayOffset).atStartOfDay().plusMinutes(min.toLong()).atZone(now.zone).toInstant().toEpochMilli()

    private fun windowsOn(cfg: JSONObject?, d: Int): List<Win> = list(cfg, "windows").filter { dayOk(it, d) }
        .mapNotNull { w ->
            val s = Rules.minutes(w.opt("start") as? String) ?: return@mapNotNull null
            val e = Rules.minutes(w.opt("end") as? String) ?: return@mapNotNull null
            if (s < e) Win(s, e) else null
        }.sortedBy { it.s }

    private fun hasWindowsOn(cfg: JSONObject?, d: Int) = list(cfg, "windows").any { dayOk(it, d) }

    private fun limitOn(cfg: JSONObject?, d: Int): Double? = list(cfg, "limits").filter { dayOk(it, d) }
        .mapNotNull { (it.opt("minutes") as? Number)?.toDouble()?.takeIf { m -> m.isFinite() && m >= 0 } }.minOrNull()

    fun has(cfg: JSONObject?) = list(cfg, "windows").isNotEmpty() || list(cfg, "limits").isNotEmpty()

    private fun nextOpen(cfg: JSONObject?, now: ZonedDateTime, todayOk: Boolean): Long? {
        val ms = now.toInstant().toEpochMilli()
        if (todayOk) {
            windowsOn(cfg, dayOf(now)).map { at(now, 0, it.s) }.filter { it > ms }.minOrNull()?.let { return it }
        }
        for (off in 1..7) {
            val d = (dayOf(now) + off) % 7
            if (limitOn(cfg, d) == 0.0) continue
            val ws = windowsOn(cfg, d)
            if (ws.isNotEmpty()) return at(now, off.toLong(), ws[0].s)
            if (!hasWindowsOn(cfg, d)) return at(now, off.toLong(), 0)
        }
        return null
    }

    private fun core(cfg: JSONObject?, nowMs: Long, zone: ZoneId, used: Double, extraUntil: Long?): State {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val u = max(0.0, used)
        if (extraUntil != null && extraUntil > nowMs) {
            return State(true, "extra", ceil((extraUntil - nowMs) / 60_000.0), extraUntil, used = u)
        }
        if (!has(cfg)) return State(true, "open", used = u)
        val d = dayOf(now)
        val lim = limitOn(cfg, d)
        val ws = windowsOn(cfg, d)
        val closedDay = ws.isEmpty() && hasWindowsOn(cfg, d)
        if (lim != null && u >= lim) return State(false, "limit", 0.0, null, nextOpen(cfg, now, false), lim, u)
        val win = ws.firstOrNull { at(now, 0, it.s) <= nowMs && nowMs < at(now, 0, it.e) }
        val left = if (lim == null) Double.POSITIVE_INFINITY else lim - u
        if (closedDay || (ws.isNotEmpty() && win == null)) {
            return State(false, "window", left, null, nextOpen(cfg, now, !closedDay), lim, u)
        }
        val end = if (win != null) at(now, 0, win.e) else at(now, 1, 0)
        val until = if (left.isInfinite()) end else minOf(end, nowMs + ceil(left * 60_000).toLong())
        return State(true, "ok", left, until, null, lim, u)
    }

    /** [core] plus [State.ends] (the device really closes at `until`) and [State.warn] (within [warnMinutes] of that). */
    fun state(cfg: JSONObject?, nowMs: Long, zone: ZoneId, used: Double, extraUntil: Long?, warnMinutes: Int = WARN_MINUTES): State {
        val s = core(cfg, nowMs, zone, used, extraUntil)
        val until = s.until ?: return s
        if (!s.allowed) return s
        val then = until + 1000
        val sameDay = Instant.ofEpochMilli(then).atZone(zone).toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val usedThen = if (sameDay) s.used + (until - nowMs) / 60_000.0 else 0.0
        val ends = !core(cfg, then, zone, usedThen, null).allowed
        val w = if (warnMinutes > 0) warnMinutes else WARN_MINUTES
        return s.copy(ends = ends, warn = ends && until - nowMs <= w * 60_000L)
    }

    /** The state for [sid] from the device's policy.deviceTime; [usedHere] = minutes on this tablet today. */
    fun forStudent(dt: JSONObject?, nowMs: Long, zone: ZoneId, sid: String?, usedHere: Double, localExtra: Long?): State {
        if (sid.isNullOrEmpty()) return State(true, "parent")
        val cfg = dt?.optJSONObject("students")?.optJSONObject(sid)
        val el = dt?.optJSONObject("elsewhere")?.optJSONObject(sid)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()
        val away = if (el != null && el.optString("date") == today) max(0.0, (el.opt("minutes") as? Number)?.toDouble() ?: 0.0) else 0.0
        val fromPolicy = (dt?.optJSONObject("extra")?.opt(sid) as? Number)?.toLong() ?: 0L
        val extra = max(fromPolicy, localExtra ?: 0L).takeIf { it > 0 }
        val warn = (dt?.opt("warnMinutes") as? Number)?.toInt() ?: WARN_MINUTES
        return state(cfg, nowMs, zone, usedHere + away, extra, warn)
    }

    /** True when [sid] has settings or extra time on this device (otherwise nothing to show or count against). */
    fun limited(dt: JSONObject?, sid: String?): Boolean =
        !sid.isNullOrEmpty() && (has(dt?.optJSONObject("students")?.optJSONObject(sid)) || dt?.optJSONObject("extra")?.has(sid) == true)
}
