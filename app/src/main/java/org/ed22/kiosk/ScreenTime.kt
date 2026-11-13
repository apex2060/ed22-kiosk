package org.ed22.kiosk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.TreeMap

/**
 * Pure ledger of device-wide screen time per child (for [DeviceTime] limits and `status.deviceTime`).
 * JSON: {days: {"yyyy-MM-dd": {"<studentId>": seconds}}, mark: {sid, at}}. A mark = that child is being counted since `at`.
 * [tick] adds the time since the mark (split at local midnight) and sets a new mark when [counting] is a child.
 * A gap longer than [MAX_GAP_MS] (the app was not running, the clock jumped) is not counted.
 */
class ScreenTimeLedger(private val json: JSONObject = JSONObject()) {
    companion object {
        const val MAX_GAP_MS = 3 * 60_000L
        const val KEEP_DAYS = 7L
    }

    private fun days(): JSONObject = json.optJSONObject("days") ?: JSONObject().also { json.put("days", it) }

    private fun add(sid: String, from: Long, to: Long, zone: ZoneId) {
        var f = from
        while (f < to) {
            val date = Instant.ofEpochMilli(f).atZone(zone).toLocalDate()
            val midnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val t = minOf(to, midnight)
            val day = days().optJSONObject(date.toString()) ?: JSONObject().also { days().put(date.toString(), it) }
            day.put(sid, day.optDouble(sid, 0.0).let { if (it.isNaN()) 0.0 else it } + (t - f) / 1000.0)
            f = t
        }
    }

    /** Count up to [nowMs]; then count [counting] (a studentId, or null = nobody) from now. Returns true if seconds were added. */
    fun tick(nowMs: Long, zone: ZoneId, counting: String?): Boolean {
        var added = false
        json.optJSONObject("mark")?.let { m ->
            val sid = m.optString("sid")
            val at = m.optLong("at", 0L)
            val gap = nowMs - at
            if (sid.isNotEmpty() && at > 0 && gap > 0 && gap <= MAX_GAP_MS) {
                add(sid, at, nowMs, zone)
                added = true
            }
        }
        if (counting.isNullOrEmpty()) json.remove("mark")
        else json.put("mark", JSONObject().put("sid", counting).put("at", nowMs))
        prune(nowMs, zone)
        return added
    }

    /** Minutes [sid] used on this tablet on [nowMs]'s local date, the open mark included. */
    fun minutesToday(sid: String?, nowMs: Long, zone: ZoneId): Double {
        if (sid.isNullOrEmpty()) return 0.0
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        var s = days().optJSONObject(today.toString())?.optDouble(sid, 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        json.optJSONObject("mark")?.let { m ->
            val at = m.optLong("at", 0L)
            if (m.optString("sid") == sid && at > 0 && nowMs > at && nowMs - at <= MAX_GAP_MS) {
                val from = maxOf(at, today.atStartOfDay(zone).toInstant().toEpochMilli())
                s += (nowMs - from) / 1000.0
            }
        }
        return s / 60.0
    }

    private fun prune(nowMs: Long, zone: ZoneId) {
        val oldest = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().minusDays(KEEP_DAYS - 1).toString()
        val d = days()
        d.keys().asSequence().toList().filter { it < oldest }.forEach { d.remove(it) }
    }

    /** `status.deviceTime`: {date: {studentId: whole seconds}}, sorted (stable for the upload hash). */
    fun statusMap(): Map<String, Map<String, Long>> {
        val out = TreeMap<String, Map<String, Long>>()
        val d = days()
        d.keys().forEach { date ->
            val day = d.optJSONObject(date) ?: return@forEach
            val kids = TreeMap<String, Long>()
            day.keys().forEach { sid -> kids[sid] = Math.round(day.optDouble(sid, 0.0)) }
            if (kids.isNotEmpty()) out[date] = kids
        }
        return out
    }

    override fun toString(): String = json.toString()
}

/**
 * The tablet's screen-time counter: a child is counted while the screen is on, that child is active (not parent mode),
 * the kiosk is not paused by a parent, and no lock / rest cover is up. Kiosk pages and Android apps both count.
 * Ticked on every change (screen, child, cover, policy) and once a minute while counting.
 */
object ScreenTime {
    private const val PREFS = "kiosk_screen_time"
    private const val KEY = "ledger"
    private const val BEAT_MS = 60_000L
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun ledger(ctx: Context) = ScreenTimeLedger(prefs(ctx).getString(KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject())

    private val beat = object : Runnable {
        override fun run() {
            if (!::app.isInitialized) return
            tick(app)
            handler.postDelayed(this, BEAT_MS)
        }
    }

    fun start(ctx: Context) {
        app = ctx.applicationContext
        handler.removeCallbacks(beat)
        handler.post(beat)
    }

    /** Who is being counted right now (null = nobody). [covered] = the lock or rest cover is up. */
    fun countingNow(ctx: Context, covered: Boolean): String? {
        val sid = Prefs.activeStudentId(ctx) ?: return null
        if (covered || Prefs.isMaintenance(ctx)) return null
        val on = runCatching { ctx.getSystemService(PowerManager::class.java).isInteractive }.getOrDefault(true)
        return if (on) sid else null
    }

    @Synchronized
    fun tick(ctx: Context, covered: Boolean = DeviceSync.coverUp(ctx)) {
        val l = ledger(ctx)
        l.tick(System.currentTimeMillis(), ZoneId.systemDefault(), countingNow(ctx, covered))
        prefs(ctx).edit().putString(KEY, l.toString()).apply()
    }

    fun minutesToday(ctx: Context, sid: String?, nowMs: Long = System.currentTimeMillis()): Double =
        ledger(ctx).minutesToday(sid, nowMs, ZoneId.systemDefault())

    fun statusMap(ctx: Context): Map<String, Map<String, Long>> = ledger(ctx).statusMap()

    fun clear(ctx: Context) { prefs(ctx).edit().remove(KEY).apply() }
}
