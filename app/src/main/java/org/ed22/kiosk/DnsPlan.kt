package org.ed22.kiosk

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Web filter through Private DNS (ANDROID_REQUEST_dns.md). Pure: parsing `policy.dns`, the host picker, host validation,
 * the next window edge, the fail-open / recover state machine and the `dns` status map. No Android calls, so it runs in
 * JVM tests ([DnsFilter] does the DevicePolicyManager / ConnectivityManager work).
 *
 * policy.dns = {on, slug, host, byStudent: {studentId: host}, windows: [{days, start, end, host, studentIds?}], onFail, notify, rev}
 * written only by the Ed22 cloud (`/project/mobile/cloud/dnsFilter`). The tablet only reads it. studentId only.
 */
object DnsPlan {
    /** Every filter host is a NextDNS DoT hostname. */
    const val SUFFIX = ".dns.nextdns.io"
    private val HOST_RE = Regex("^[A-Za-z0-9-]{1,63}(\\.[A-Za-z0-9-]{1,63})+$")

    /** Strict Private DNS failing this long falls back to opportunistic (immediately on a captive portal). */
    const val FAIL_OPEN_MS = 60_000L
    /** While in fallback, the filter host is tried again this often (and on every network change). */
    const val RETRY_MS = 5 * 60_000L
    /** A fallback episode this long sends one alert to the parent / house (when notify). */
    const val ALERT_AFTER_MS = 5 * 60_000L
    /** A failed alert POST is tried again with the next 15-minute report. */
    const val ALERT_RETRY_MS = 15 * 60_000L

    /** Listed in status.features (family) / the house doc's features. */
    const val FEATURE = "dns"

    const val ALERT_URL = "https://us.ed22.org/project/mobile/cloud/dnsFilter"

    /** Shown in parent mode and the Parent Menu while in fallback. */
    const val PAUSED_TEXT = "Web filter paused — no connection to the filter"

    /** Chrome (85+) managed config: no DNS-over-HTTPS, so Chrome uses the system resolver (Private DNS). */
    const val CHROME = "com.android.chrome"
    const val CHROME_DOH_KEY = "DnsOverHttpsMode"
    const val CHROME_DOH_OFF = "off"

    /** Browsers with their own DoH: never allowed in child mode while policy.dns.on (not even "Allow for a while"). */
    val DOH_BROWSERS = setOf(
        "org.mozilla.firefox",
        "com.brave.browser",
        "com.opera.browser",
        "com.sec.android.app.sbrowser",
        "com.duckduckgo.mobile.android",
    )

    /** Rules.State.why for an app blocked by the web filter. */
    const val WHY_BLOCKED = "dnsFilter"

    // status `mode` / `why` / `err` values (exact strings the web side reads)
    const val MODE_HOST = "host"
    const val MODE_FALLBACK = "fallback"
    const val MODE_OFF = "off"
    const val MODE_UNSUPPORTED = "unsupported"
    const val ERR_HOST_NOT_SERVING = "HOST_NOT_SERVING"
    const val ERR_FAILURE_SETTING = "FAILURE_SETTING"
    const val ERR_UNREACHABLE = "UNREACHABLE"
    const val ERR_CAPTIVE_PORTAL = "CAPTIVE_PORTAL"
    const val ERR_BAD_HOST = "BAD_HOST"

    /** Android 10 (API 29) added the device-owner Private DNS calls. */
    const val MIN_SDK = 29

    fun hostOk(h: String?): Boolean =
        h != null && h.length <= 253 && h.endsWith(SUFFIX) && HOST_RE.matches(h)

    /** policy.dns when it is an object with `on: true`, else null (absent / off → clear). */
    fun active(dns: JSONObject?): JSONObject? = dns?.takeIf { it.optBoolean("on", false) }

    fun rev(dns: JSONObject?): Long = (dns?.opt("rev") as? Number)?.toLong() ?: 0L

    fun notify(dns: JSONObject?): Boolean = dns?.optBoolean("notify", true) ?: false

    private val HHMM = Regex("^(\\d{1,2}):(\\d{2})$")

    /** "HH:MM" → minutes after midnight, or null (an unparseable window never matches, like NaN in the web pick). */
    fun minutes(s: Any?): Int? {
        val m = HHMM.matchEntire((s as? String)?.trim() ?: return null) ?: return null
        val h = m.groupValues[1].toInt()
        val mm = m.groupValues[2].toInt()
        if (h > 24 || mm > 59) return null
        return h * 60 + mm
    }

    private fun ints(a: JSONArray?): Set<Int> =
        if (a == null) emptySet() else (0 until a.length()).mapNotNull { (a.opt(it) as? Number)?.toInt() }.toSet()

    private fun strs(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it) as? String }

    /** [host] null = off. [why]: 'default' | 'student' | 'window' | 'off'. */
    data class Pick(val host: String?, val why: String)

    /**
     * The web side's `cloud/dnsFilter.js pick`: the first window whose day (0 = Sunday), local time [start, end) and
     * studentIds (empty = everyone) match; an overnight window (end < start) belongs to the day it starts. Else
     * byStudent[studentId], else host. [hol]: House of Learning tablets never use byStudent (house host / windows only).
     */
    fun pick(dns: JSONObject?, studentId: String?, now: ZonedDateTime, hol: Boolean = false): Pick {
        val d = active(dns) ?: return Pick(null, "off")
        val sid = studentId?.takeIf { it.isNotEmpty() }
        val day = now.dayOfWeek.value % 7
        val m = now.hour * 60 + now.minute
        val windows = d.optJSONArray("windows")
        if (windows != null) for (i in 0 until windows.length()) {
            val w = windows.optJSONObject(i) ?: continue
            val ids = strs(w.optJSONArray("studentIds"))
            if (ids.isNotEmpty() && (sid == null || sid !in ids)) continue
            val a = minutes(w.opt("start")) ?: continue
            val b = minutes(w.opt("end")) ?: continue
            val days = ints(w.optJSONArray("days"))
            val today = day in days
            val yday = ((day + 6) % 7) in days
            val hit = if (a <= b) today && m >= a && m < b else (today && m >= a) || (yday && m < b)
            if (hit) return Pick(w.opt("host") as? String, "window")
        }
        if (!hol && sid != null) (d.optJSONObject("byStudent")?.opt(sid) as? String)?.let { return Pick(it, "student") }
        return Pick(d.opt("host") as? String, "default")
    }

    fun pick(dns: JSONObject?, studentId: String?, nowMs: Long, zone: ZoneId, hol: Boolean = false): Pick =
        pick(dns, studentId, Instant.ofEpochMilli(nowMs).atZone(zone), hol)

    /**
     * The next window start or end after [now] (within a day), as ms, or null when there are no windows. Days and
     * children are not checked: at that moment the pick is simply computed again.
     */
    fun nextEdge(dns: JSONObject?, now: ZonedDateTime): Long? {
        val windows = active(dns)?.optJSONArray("windows") ?: return null
        val zone = now.zone
        return (0 until windows.length()).flatMap { i ->
            val w = windows.optJSONObject(i) ?: return@flatMap emptyList()
            listOfNotNull(minutes(w.opt("start")), minutes(w.opt("end")))
        }.map { min ->
            val today = now.toLocalDate().atStartOfDay(zone).plusMinutes(min.toLong())
            (if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atStartOfDay(zone).plusMinutes(min.toLong()))
                .toInstant().toEpochMilli()
        }.minOrNull()
    }

    // --- fail open / recover ---

    /** What the default network says about the filter right now. */
    enum class Health {
        /** Validated and Private DNS is active with our host. */
        OK,
        /** Strict mode is set but the network is not validated / Private DNS is not active with our host. */
        FAILING,
        /** The network has a captive portal (strict Private DNS would block the sign-in page). */
        CAPTIVE,
        /** No default network (airplane mode, out of range): nothing to judge, the clock is not started. */
        NO_NETWORK,
    }

    /**
     * Persisted state. [mode]: host / fallback / off / unsupported. [host], [why], [rev]: the pick in force.
     * [since]: when [mode] started. [failSince]: first failure seen while in host mode (null = healthy).
     * [alertSent]: this fallback episode's alert was accepted. [alertTriedAt]: last alert attempt (ms, 0 = none).
     */
    data class State(
        val mode: String = MODE_OFF,
        val host: String = "",
        val why: String = "default",
        val rev: Long = 0L,
        val err: String? = null,
        val since: Long = 0L,
        val failSince: Long? = null,
        val alertSent: Boolean = false,
        val alertTriedAt: Long = 0L,
    ) {
        fun toJson(): JSONObject = JSONObject().put("mode", mode).put("host", host).put("why", why).put("rev", rev)
            .put("err", err ?: JSONObject.NULL).put("since", since).put("failSince", failSince ?: JSONObject.NULL)
            .put("alertSent", alertSent).put("alertTriedAt", alertTriedAt)

        companion object {
            fun fromJson(o: JSONObject?): State {
                if (o == null) return State()
                return State(
                    mode = o.optString("mode", MODE_OFF),
                    host = o.optString("host", ""),
                    why = o.optString("why", "default"),
                    rev = o.optLong("rev", 0L),
                    err = (o.opt("err") as? String),
                    since = o.optLong("since", 0L),
                    failSince = (o.opt("failSince") as? Number)?.toLong(),
                    alertSent = o.optBoolean("alertSent", false),
                    alertTriedAt = o.optLong("alertTriedAt", 0L),
                )
            }
        }
    }

    /** What [DnsFilter] must do after a [decide] step. */
    enum class Action {
        NONE,
        /** setGlobalPrivateDnsModeSpecifiedHost(host) (+ DISALLOW_CONFIG_PRIVATE_DNS). */
        APPLY_HOST,
        /** setGlobalPrivateDnsModeOpportunistic (keep DISALLOW_CONFIG_PRIVATE_DNS). */
        FALLBACK,
        /** Restore the saved previous mode and lift DISALLOW_CONFIG_PRIVATE_DNS. */
        RESTORE,
    }

    data class Step(val state: State, val action: Action, val report: Boolean)

    /**
     * Policy / pick / time input: [pick] is the wanted host for now ([Pick.host] null = off), [sdk] the Android level.
     * Decides whether to apply, clear or (in fallback) keep waiting for the next retry.
     */
    fun onPick(s: State, dns: JSONObject?, pick: Pick, sdk: Int, now: Long, retryDue: Boolean = false): Step {
        val rev = rev(dns)
        if (pick.host == null) {
            if (s.mode == MODE_OFF) return Step(s.copy(rev = rev, err = null), Action.NONE, false)
            val restore = s.mode == MODE_HOST || s.mode == MODE_FALLBACK
            return Step(State(mode = MODE_OFF, rev = rev, since = now), if (restore) Action.RESTORE else Action.NONE, true)
        }
        if (sdk < MIN_SDK) {
            val changed = s.mode != MODE_UNSUPPORTED || s.rev != rev
            return Step(State(mode = MODE_UNSUPPORTED, host = pick.host, why = pick.why, rev = rev,
                since = if (s.mode == MODE_UNSUPPORTED) s.since else now), Action.NONE, changed)
        }
        if (!hostOk(pick.host)) {
            // Ignored: whatever is in force stays; the web side sees BAD_HOST.
            val changed = s.err != ERR_BAD_HOST
            return Step(s.copy(err = ERR_BAD_HOST, rev = rev), Action.NONE, changed)
        }
        val same = pick.host == s.host
        if (s.mode == MODE_FALLBACK && same) {
            // Waiting for the filter: only the 5-minute retry / a network change tries the host again.
            val st = s.copy(why = pick.why, rev = rev, err = if (s.err == ERR_BAD_HOST) ERR_UNREACHABLE else s.err)
            return Step(st, if (retryDue) Action.APPLY_HOST else Action.NONE, st != s)
        }
        if (s.mode == MODE_HOST && same) {
            // Already in force (DnsFilter skips the set call when the system already has it); a BAD_HOST note is cleared.
            val st = s.copy(why = pick.why, rev = rev, err = if (s.err == ERR_BAD_HOST) null else s.err)
            return Step(st, Action.APPLY_HOST, st != s)
        }
        // A new host (rev / child / window change) or coming from off / unsupported / BAD_HOST: apply now.
        return Step(State(mode = MODE_HOST, host = pick.host, why = pick.why, rev = rev, since = now), Action.APPLY_HOST, true)
    }

    /** The result of the set call: null = PRIVATE_DNS_SET_NO_ERROR, else ERR_HOST_NOT_SERVING / ERR_FAILURE_SETTING. */
    fun onApplied(s: State, err: String?, now: Long): Step {
        if (err == null) {
            if (s.mode == MODE_FALLBACK) {
                // Recovered: the host answered the probe. Episode over.
                return Step(State(mode = MODE_HOST, host = s.host, why = s.why, rev = s.rev, since = now), Action.NONE, true)
            }
            return Step(s, Action.NONE, false)
        }
        if (s.mode == MODE_FALLBACK) return Step(s, Action.NONE, false) // still down; next retry in 5 minutes
        // Strict mode was not set (the host did not answer). Counts as failure; after 60 s it falls back.
        val failSince = s.failSince ?: now
        val st = s.copy(failSince = failSince)
        return if (now - failSince >= FAIL_OPEN_MS) fallback(st, err, now) else Step(st, Action.NONE, false)
    }

    /** A default-network observation (capabilities / link properties changed, or a periodic check). */
    fun onHealth(s: State, h: Health, now: Long): Step {
        if (s.mode != MODE_HOST) return Step(s, Action.NONE, false)
        return when (h) {
            Health.OK -> Step(s.copy(failSince = null, err = null), Action.NONE, s.err != null)
            Health.NO_NETWORK -> Step(s.copy(failSince = null), Action.NONE, false)
            Health.CAPTIVE -> fallback(s, ERR_CAPTIVE_PORTAL, now)
            Health.FAILING -> {
                val failSince = s.failSince ?: now
                if (now - failSince >= FAIL_OPEN_MS) fallback(s, ERR_UNREACHABLE, now)
                else Step(s.copy(failSince = failSince), Action.NONE, false)
            }
        }
    }

    private fun fallback(s: State, err: String, now: Long): Step = Step(
        s.copy(mode = MODE_FALLBACK, err = err, since = now, failSince = null, alertSent = false, alertTriedAt = 0L),
        Action.FALLBACK, true,
    )

    /** When the 60-second failure clock runs out (to schedule a re-check), or null. */
    fun failDeadline(s: State): Long? = if (s.mode == MODE_HOST) s.failSince?.plus(FAIL_OPEN_MS) else null

    /** In fallback: time for the 5-minute retry of the host. */
    fun retryDue(s: State, lastTry: Long, now: Long): Boolean = s.mode == MODE_FALLBACK && now - lastTry >= RETRY_MS

    /**
     * One alert per fallback episode lasting ≥ 5 minutes (when notify): first at the 5-minute mark, then (if the POST
     * failed) at most once per 15 minutes with the report, until one is accepted or the episode ends.
     */
    fun shouldAlert(s: State, notify: Boolean, now: Long): Boolean =
        notify && s.mode == MODE_FALLBACK && !s.alertSent && now - s.since >= ALERT_AFTER_MS &&
            (s.alertTriedAt == 0L || now - s.alertTriedAt >= ALERT_RETRY_MS)

    /** POST body for `{action:'alert'}` ([jwt] is the tablet's own Firebase ID token; never logged). */
    fun alertBody(jwt: String, device: JSONObject, s: State): JSONObject = JSONObject()
        .put("action", "alert").put("jwt", jwt).put("device", device).put("mode", MODE_FALLBACK)
        .put("err", s.err ?: ERR_UNREACHABLE)

    fun familyDevice(docId: String): JSONObject = JSONObject().put("kind", "family").put("id", docId)

    fun holDevice(serviceId: String, deviceId: String): JSONObject =
        JSONObject().put("kind", "hol").put("serviceId", serviceId).put("id", deviceId)

    /** `status.dns` (family) / top-level `dns` (house). [ok]: Private DNS active with this host on the default network. */
    fun status(s: State, ok: Boolean, now: Long): Map<String, Any?> = mapOf(
        "mode" to s.mode,
        "host" to s.host,
        "why" to s.why,
        "rev" to s.rev,
        "ok" to (ok && s.mode == MODE_HOST),
        "err" to s.err,
        "since" to s.since,
        "at" to now,
    )

    /** Firefox & co. are blocked for a child while policy.dns.on (parent mode is not limited). */
    fun blocksApp(dnsOn: Boolean, studentId: String?, pkg: String): Boolean =
        dnsOn && !studentId.isNullOrEmpty() && pkg in DOH_BROWSERS

    /** DISALLOW_CONFIG_VPN is wanted: child mode while policy.dns.on. */
    fun wantsVpnLock(dnsOn: Boolean, studentId: String?): Boolean = dnsOn && !studentId.isNullOrEmpty()
}
