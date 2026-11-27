package org.ed22.kiosk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Web filter (ANDROID_REQUEST_dns.md): picker (mirrors test/dnsFilter.test.js), host check, edges, fail open / recover, alert, status. */
class DnsPlanTest {
    private val sid = "fake-student-0001"
    private val sid2 = "fake-student-0002"
    private val zone = ZoneId.of("America/Phoenix")
    private val H = "onn--Tab-a1b2-p00001.dns.nextdns.io"
    private val K = "onn--Tab-a1b2-p00002.dns.nextdns.io"

    /** Nov 22 2026 = Sunday, so [dow] 0..6 = Sun..Sat (like the web test's `at`). */
    private fun at(dow: Int, hh: Int, mm: Int): ZonedDateTime = ZonedDateTime.of(2026, 11, 22 + dow, hh, mm, 0, 0, zone)

    private fun window(days: List<Int>, start: String, end: String, host: String, ids: List<String>? = null) = JSONObject()
        .put("days", JSONArray(days)).put("start", start).put("end", end).put("host", host)
        .apply { if (ids != null) put("studentIds", JSONArray(ids)) }

    /** The web test's fixture: D default, K for the child, SCHOOL weekdays 8-15, NIGHT 21:00-06:30 every day. */
    private val dns = JSONObject().put("on", true).put("host", "D").put("byStudent", JSONObject().put(sid, "K"))
        .put("windows", JSONArray()
            .put(window(listOf(1, 2, 3, 4, 5), "08:00", "15:00", "SCHOOL", listOf(sid)))
            .put(window(listOf(0, 1, 2, 3, 4, 5, 6), "21:00", "06:30", "NIGHT", listOf(sid))))
        .put("rev", 1764280000000L)

    @Test
    fun pickMirrorsTheWebSide() {
        assertEquals(DnsPlan.Pick("SCHOOL", "window"), DnsPlan.pick(dns, sid, at(1, 9, 0)))
        assertEquals(DnsPlan.Pick("D", "default"), DnsPlan.pick(dns, sid2, at(1, 9, 0)))          // other child → default
        assertEquals(DnsPlan.Pick("K", "student"), DnsPlan.pick(dns, sid, at(1, 16, 0)))         // child profile
        assertEquals("NIGHT", DnsPlan.pick(dns, sid, at(3, 22, 0)).host)                           // overnight, started today
        assertEquals("NIGHT", DnsPlan.pick(dns, sid, at(4, 6, 0)).host)                            // after midnight
        assertEquals("K", DnsPlan.pick(dns, sid, at(4, 6, 30)).host)                               // end exclusive
        assertEquals(DnsPlan.Pick(null, "off"), DnsPlan.pick(JSONObject().put("on", false), sid, at(1, 9, 0)))
        assertEquals(DnsPlan.Pick(null, "off"), DnsPlan.pick(null, sid, at(1, 9, 0)))
    }

    @Test
    fun parentModeAndWeekendUseTheDefault() {
        assertEquals(DnsPlan.Pick("D", "default"), DnsPlan.pick(dns, null, at(1, 9, 0)))   // parent mode: child windows skipped
        assertEquals(DnsPlan.Pick("D", "default"), DnsPlan.pick(dns, "", at(1, 9, 0)))
        assertEquals(DnsPlan.Pick("K", "student"), DnsPlan.pick(dns, sid, at(6, 9, 0)))    // Saturday: no school window
        assertEquals(DnsPlan.Pick("SCHOOL", "window"), DnsPlan.pick(dns, sid, at(1, 8, 0))) // start inclusive
        assertEquals("K", DnsPlan.pick(dns, sid, at(1, 15, 0)).host)                        // end exclusive
    }

    @Test
    fun overnightAfterMidnightBelongsToThePreviousDaysEntry() {
        val fri = JSONObject().put("on", true).put("host", "D")
            .put("windows", JSONArray().put(window(listOf(5), "22:00", "02:00", "LATE")))
        assertEquals("LATE", DnsPlan.pick(fri, null, at(5, 23, 0)).host)  // Friday night
        assertEquals("LATE", DnsPlan.pick(fri, null, at(6, 1, 59)).host)  // Saturday early = Friday's entry
        assertEquals("D", DnsPlan.pick(fri, null, at(6, 2, 0)).host)
        assertEquals("D", DnsPlan.pick(fri, null, at(5, 1, 0)).host)      // Friday early = Thursday's (none)
    }

    @Test
    fun studentIdsFilterAndEveryoneWindows() {
        val d = JSONObject().put("on", true).put("host", "D").put("byStudent", JSONObject().put(sid, "K"))
            .put("windows", JSONArray()
                .put(window(listOf(1), "08:00", "09:00", "ONLY2", listOf(sid2)))
                .put(window(listOf(1), "08:00", "10:00", "ALL", listOf())))
        assertEquals("ONLY2", DnsPlan.pick(d, sid2, at(1, 8, 30)).host)
        assertEquals("ALL", DnsPlan.pick(d, sid, at(1, 8, 30)).host)   // first matching window wins, empty ids = everyone
        assertEquals("ALL", DnsPlan.pick(d, null, at(1, 9, 30)).host)
        assertEquals("K", DnsPlan.pick(d, sid, at(1, 10, 0)).host)
    }

    @Test
    fun houseTabletsNeverUseByStudent() {
        val house = JSONObject().put("on", true).put("host", "HOUSE").put("byStudent", JSONObject().put(sid, "K"))
            .put("windows", JSONArray().put(window(listOf(1, 2, 3, 4, 5), "08:00", "15:00", "HOUSE-LIMITED")))
        assertEquals(DnsPlan.Pick("HOUSE", "default"), DnsPlan.pick(house, sid, at(1, 16, 0), hol = true))
        assertEquals(DnsPlan.Pick("HOUSE-LIMITED", "window"), DnsPlan.pick(house, null, at(1, 9, 0), hol = true))
        assertEquals("K", DnsPlan.pick(house, sid, at(1, 16, 0), hol = false).host)
    }

    @Test
    fun badWindowsNeverMatch() {
        val d = JSONObject().put("on", true).put("host", "D").put("windows", JSONArray()
            .put(window(listOf(1), "8am", "15:00", "X"))
            .put(window(listOf(1), "08:00", "08:00", "EMPTY"))
            .put("not a window"))
        assertEquals("D", DnsPlan.pick(d, null, at(1, 9, 0)).host)
        assertEquals("D", DnsPlan.pick(d, null, at(1, 8, 0)).host)
        assertEquals(8 * 60, DnsPlan.minutes("08:00"))
        assertNull(DnsPlan.minutes("8am"))
        assertNull(DnsPlan.minutes(800))
    }

    @Test
    fun hostCheck() {
        assertTrue(DnsPlan.hostOk(H))
        assertTrue(DnsPlan.hostOk("HOL--1-1234-abc123.dns.nextdns.io"))
        assertFalse(DnsPlan.hostOk("evil.example.com"))
        assertFalse(DnsPlan.hostOk("dns.google"))
        assertFalse(DnsPlan.hostOk("x.dns.nextdns.io.evil.com"))
        assertFalse(DnsPlan.hostOk("bad_host.dns.nextdns.io"))
        assertFalse(DnsPlan.hostOk("a".repeat(64) + ".dns.nextdns.io"))
        assertFalse(DnsPlan.hostOk(("a".repeat(60) + ".").repeat(4) + "dns.nextdns.io")) // > 253
        assertFalse(DnsPlan.hostOk(""))
        assertFalse(DnsPlan.hostOk(null))
    }

    @Test
    fun nextEdgeIsTheSoonestStartOrEnd() {
        val now = at(1, 7, 0)
        assertEquals(at(1, 8, 0).toInstant().toEpochMilli(), DnsPlan.nextEdge(dns, now))
        assertEquals(at(1, 15, 0).toInstant().toEpochMilli(), DnsPlan.nextEdge(dns, at(1, 8, 0)))  // exactly on a start → the next one
        assertEquals(at(1, 21, 0).toInstant().toEpochMilli(), DnsPlan.nextEdge(dns, at(1, 16, 0)))
        assertEquals(at(2, 6, 30).toInstant().toEpochMilli(), DnsPlan.nextEdge(dns, at(1, 22, 0)))  // tomorrow
        assertNull(DnsPlan.nextEdge(JSONObject().put("on", true).put("host", H), now))
        assertNull(DnsPlan.nextEdge(JSONObject().put("on", false).put("windows", dns.getJSONArray("windows")), now))
    }

    @Test
    fun parsing() {
        assertNull(DnsPlan.active(null))
        assertNull(DnsPlan.active(JSONObject().put("on", false).put("host", H)))
        assertNull(DnsPlan.active(JSONObject().put("host", H)))
        assertEquals(1764280000000L, DnsPlan.rev(dns))
        assertEquals(0L, DnsPlan.rev(JSONObject()))
        assertTrue(DnsPlan.notify(JSONObject().put("on", true)))
        assertFalse(DnsPlan.notify(JSONObject().put("on", true).put("notify", false)))
    }

    // --- state machine ---

    private val on = JSONObject().put("on", true).put("host", H).put("rev", 5L)
    private val t0 = 1_800_000_000_000L
    private fun pickH(why: String = "default") = DnsPlan.Pick(H, why)

    private fun applied(now: Long = t0): DnsPlan.State {
        val s = DnsPlan.onPick(DnsPlan.State(), on, pickH(), 34, now)
        assertEquals(DnsPlan.Action.APPLY_HOST, s.action)
        assertTrue(s.report)
        return DnsPlan.onApplied(s.state, null, now).state
    }

    @Test
    fun applyFromOff() {
        val s = applied()
        assertEquals(DnsPlan.MODE_HOST, s.mode)
        assertEquals(H, s.host)
        assertEquals(5L, s.rev)
        assertEquals(t0, s.since)
        assertNull(s.err)
        // Same pick again: re-applied (DnsFilter skips the set call when already in force), nothing to report.
        val again = DnsPlan.onPick(s, on, pickH(), 34, t0 + 1000)
        assertEquals(DnsPlan.Action.APPLY_HOST, again.action)
        assertFalse(again.report)
        assertEquals(t0, again.state.since)
    }

    @Test
    fun childOrWindowChangeSwitchesHost() {
        val s = applied()
        val k = DnsPlan.onPick(s, on, DnsPlan.Pick(K, "student"), 34, t0 + 5000)
        assertEquals(DnsPlan.Action.APPLY_HOST, k.action)
        assertEquals(K, k.state.host)
        assertEquals("student", k.state.why)
        assertEquals(t0 + 5000, k.state.since)
        assertTrue(k.report)
    }

    @Test
    fun badHostIsIgnoredAndReported() {
        val fresh = DnsPlan.onPick(DnsPlan.State(), on, DnsPlan.Pick("evil.example.com", "default"), 34, t0)
        assertEquals(DnsPlan.Action.NONE, fresh.action)
        assertEquals(DnsPlan.MODE_OFF, fresh.state.mode)
        assertEquals(DnsPlan.ERR_BAD_HOST, fresh.state.err)
        assertTrue(fresh.report)
        // With a good host in force, it stays in force.
        val s = applied()
        val bad = DnsPlan.onPick(s, on, DnsPlan.Pick("evil.example.com", "default"), 34, t0 + 1)
        assertEquals(DnsPlan.Action.NONE, bad.action)
        assertEquals(DnsPlan.MODE_HOST, bad.state.mode)
        assertEquals(H, bad.state.host)
        assertEquals(DnsPlan.ERR_BAD_HOST, bad.state.err)
        // Fixed: the note clears without restarting the mode.
        val fixed = DnsPlan.onPick(bad.state, on, pickH(), 34, t0 + 2)
        assertNull(fixed.state.err)
        assertEquals(t0, fixed.state.since)
    }

    @Test
    fun belowAndroid10IsUnsupported() {
        val s = DnsPlan.onPick(DnsPlan.State(), on, pickH(), 28, t0)
        assertEquals(DnsPlan.Action.NONE, s.action)
        assertEquals(DnsPlan.MODE_UNSUPPORTED, s.state.mode)
        assertTrue(s.report)
        assertFalse(DnsPlan.onPick(s.state, on, pickH(), 28, t0 + 1).report)
    }

    @Test
    fun offRestoresThePreviousMode() {
        val s = applied()
        val off = DnsPlan.onPick(s, JSONObject().put("on", false).put("rev", 6L), DnsPlan.Pick(null, "off"), 34, t0 + 10)
        assertEquals(DnsPlan.Action.RESTORE, off.action)
        assertEquals(DnsPlan.MODE_OFF, off.state.mode)
        assertEquals("", off.state.host)
        assertEquals(6L, off.state.rev)
        assertTrue(off.report)
        // Already off: nothing.
        assertEquals(DnsPlan.Action.NONE, DnsPlan.onPick(off.state, null, DnsPlan.Pick(null, "off"), 34, t0 + 20).action)
        // Off from fallback restores too.
        val fb = DnsPlan.onHealth(applied(), DnsPlan.Health.CAPTIVE, t0).state
        assertEquals(DnsPlan.Action.RESTORE, DnsPlan.onPick(fb, null, DnsPlan.Pick(null, "off"), 34, t0 + 1).action)
        // Unsupported → off: nothing to restore.
        val un = DnsPlan.onPick(DnsPlan.State(), on, pickH(), 28, t0).state
        assertEquals(DnsPlan.Action.NONE, DnsPlan.onPick(un, null, DnsPlan.Pick(null, "off"), 28, t0 + 1).action)
    }

    @Test
    fun failsOpenAfter60Seconds() {
        val s = applied()
        val f1 = DnsPlan.onHealth(s, DnsPlan.Health.FAILING, t0 + 1_000)
        assertEquals(DnsPlan.Action.NONE, f1.action)
        assertEquals(t0 + 1_000, f1.state.failSince)
        assertEquals(t0 + 61_000, DnsPlan.failDeadline(f1.state))
        val f2 = DnsPlan.onHealth(f1.state, DnsPlan.Health.FAILING, t0 + 60_999)
        assertEquals(DnsPlan.MODE_HOST, f2.state.mode)
        val f3 = DnsPlan.onHealth(f2.state, DnsPlan.Health.FAILING, t0 + 61_000)
        assertEquals(DnsPlan.Action.FALLBACK, f3.action)
        assertEquals(DnsPlan.MODE_FALLBACK, f3.state.mode)
        assertEquals(DnsPlan.ERR_UNREACHABLE, f3.state.err)
        assertEquals(t0 + 61_000, f3.state.since)
        assertEquals(H, f3.state.host) // the host it goes back to
        assertTrue(f3.report)
        assertNull(DnsPlan.failDeadline(f3.state))
    }

    @Test
    fun shortBlipsDoNotFallBack() {
        val s = applied()
        val f = DnsPlan.onHealth(s, DnsPlan.Health.FAILING, t0 + 1_000).state
        val ok = DnsPlan.onHealth(f, DnsPlan.Health.OK, t0 + 30_000).state
        assertNull(ok.failSince)
        assertEquals(DnsPlan.MODE_HOST, DnsPlan.onHealth(ok, DnsPlan.Health.FAILING, t0 + 70_000).state.mode) // clock restarted
        // No network at all (airplane mode) is not a filter failure.
        val nn = DnsPlan.onHealth(f, DnsPlan.Health.NO_NETWORK, t0 + 30_000).state
        assertNull(nn.failSince)
        assertEquals(DnsPlan.MODE_HOST, DnsPlan.onHealth(nn, DnsPlan.Health.NO_NETWORK, t0 + 10 * 60_000).state.mode)
    }

    @Test
    fun captivePortalFallsBackImmediately() {
        val c = DnsPlan.onHealth(applied(), DnsPlan.Health.CAPTIVE, t0 + 5)
        assertEquals(DnsPlan.Action.FALLBACK, c.action)
        assertEquals(DnsPlan.ERR_CAPTIVE_PORTAL, c.state.err)
        assertEquals(t0 + 5, c.state.since)
    }

    @Test
    fun setErrorsFallBackAfter60Seconds() {
        val s = DnsPlan.onPick(DnsPlan.State(), on, pickH(), 34, t0).state
        val e1 = DnsPlan.onApplied(s, DnsPlan.ERR_HOST_NOT_SERVING, t0)
        assertEquals(DnsPlan.MODE_HOST, e1.state.mode)
        assertEquals(t0, e1.state.failSince)
        val e2 = DnsPlan.onApplied(e1.state, DnsPlan.ERR_HOST_NOT_SERVING, t0 + 60_000)
        assertEquals(DnsPlan.Action.FALLBACK, e2.action)
        assertEquals(DnsPlan.ERR_HOST_NOT_SERVING, e2.state.err)
        val f = DnsPlan.onApplied(DnsPlan.onPick(DnsPlan.State(), on, pickH(), 34, t0).state, DnsPlan.ERR_FAILURE_SETTING, t0)
        assertEquals(DnsPlan.ERR_FAILURE_SETTING,
            DnsPlan.onApplied(f.state, DnsPlan.ERR_FAILURE_SETTING, t0 + 61_000).state.err)
        // A no-op success (already set) does not reset a running failure clock.
        val failing = DnsPlan.onHealth(applied(), DnsPlan.Health.FAILING, t0 + 1).state
        assertEquals(t0 + 1, DnsPlan.onApplied(failing, null, t0 + 2).state.failSince)
    }

    @Test
    fun fallbackRetriesEvery5MinutesAndRecovers() {
        val fb = DnsPlan.onHealth(applied(), DnsPlan.Health.CAPTIVE, t0).state
        // Health is not judged in fallback (the system is on opportunistic DNS).
        assertEquals(fb, DnsPlan.onHealth(fb, DnsPlan.Health.OK, t0 + 1).state)
        // A plain refresh does not probe; the retry (5 minutes / network change) does.
        assertEquals(DnsPlan.Action.NONE, DnsPlan.onPick(fb, on, pickH(), 34, t0 + 1000).action)
        assertFalse(DnsPlan.retryDue(fb, t0, t0 + 299_999))
        assertTrue(DnsPlan.retryDue(fb, t0, t0 + 300_000))
        assertFalse(DnsPlan.retryDue(applied(), t0, t0 + 300_000))
        val r = DnsPlan.onPick(fb, on, pickH(), 34, t0 + 300_000, retryDue = true)
        assertEquals(DnsPlan.Action.APPLY_HOST, r.action)
        // Still down: stay in fallback, same episode.
        val still = DnsPlan.onApplied(r.state, DnsPlan.ERR_HOST_NOT_SERVING, t0 + 300_000)
        assertEquals(DnsPlan.MODE_FALLBACK, still.state.mode)
        assertEquals(t0, still.state.since)
        assertFalse(still.report)
        // Back: host mode, reported, episode over.
        val back = DnsPlan.onApplied(r.state, null, t0 + 600_000)
        assertEquals(DnsPlan.MODE_HOST, back.state.mode)
        assertNull(back.state.err)
        assertEquals(t0 + 600_000, back.state.since)
        assertTrue(back.report)
        // A policy change while in fallback applies the new host at once.
        val newHost = DnsPlan.onPick(fb, on, DnsPlan.Pick(K, "student"), 34, t0 + 10)
        assertEquals(DnsPlan.Action.APPLY_HOST, newHost.action)
        assertEquals(DnsPlan.MODE_HOST, newHost.state.mode)
    }

    @Test
    fun oneAlertPerEpisodeAfter5Minutes() {
        val fb = DnsPlan.onHealth(applied(), DnsPlan.Health.FAILING, t0).state.let { DnsPlan.onHealth(it, DnsPlan.Health.FAILING, t0 + 60_000).state }
        val since = fb.since
        assertFalse(DnsPlan.shouldAlert(fb, true, since + 2 * 60_000))   // a 2-minute blip: none
        assertTrue(DnsPlan.shouldAlert(fb, true, since + 5 * 60_000))
        assertFalse(DnsPlan.shouldAlert(fb, false, since + 5 * 60_000))  // notify:false
        // POST failed: tried again with a 15-minute report, not sooner.
        val tried = fb.copy(alertTriedAt = since + 5 * 60_000)
        assertFalse(DnsPlan.shouldAlert(tried, true, since + 10 * 60_000))
        assertTrue(DnsPlan.shouldAlert(tried, true, since + 20 * 60_000))
        // Accepted: never again this episode.
        assertFalse(DnsPlan.shouldAlert(tried.copy(alertSent = true), true, since + 60 * 60_000))
        // Recovered, then down again: a new episode can alert again (the cloud caps it at 1 per 6 h).
        val back = DnsPlan.onApplied(tried.copy(alertSent = true), null, since + 30 * 60_000).state
        assertFalse(DnsPlan.shouldAlert(back, true, since + 31 * 60_000))
        val again = DnsPlan.onHealth(back, DnsPlan.Health.CAPTIVE, since + 40 * 60_000).state
        assertFalse(again.alertSent)
        assertEquals(0L, again.alertTriedAt)
        assertTrue(DnsPlan.shouldAlert(again, true, since + 45 * 60_000))
    }

    @Test
    fun alertBodyShape() {
        val fb = DnsPlan.onHealth(applied(), DnsPlan.Health.CAPTIVE, t0).state
        val fam = DnsPlan.alertBody("jwt-x", DnsPlan.familyDevice("exp_parentA_18ced952901c68c1"), fb)
        assertEquals("alert", fam.getString("action"))
        assertEquals("jwt-x", fam.getString("jwt"))
        assertEquals("fallback", fam.getString("mode"))
        assertEquals("CAPTIVE_PORTAL", fam.getString("err"))
        assertEquals("family", fam.getJSONObject("device").getString("kind"))
        assertEquals("exp_parentA_18ced952901c68c1", fam.getJSONObject("device").getString("id"))
        val hol = DnsPlan.holDevice("S98UaXvYQPw0YXalK7MD", "18ced952901c68c1")
        assertEquals(setOf("kind", "serviceId", "id"), hol.keys().asSequence().toSet())
        assertEquals("hol", hol.getString("kind"))
        assertEquals("https://us.ed22.org/project/mobile/cloud/dnsFilter", DnsPlan.ALERT_URL)
    }

    @Test
    fun statusFieldNames() {
        val s = applied()
        val st = DnsPlan.status(s, ok = true, now = t0 + 9)
        assertEquals(listOf("mode", "host", "why", "rev", "ok", "err", "since", "at"), st.keys.toList())
        assertEquals("host", st["mode"])
        assertEquals(H, st["host"])
        assertEquals("default", st["why"])
        assertEquals(5L, st["rev"])
        assertEquals(true, st["ok"])
        assertNull(st["err"])
        assertEquals(t0, st["since"])
        assertEquals(t0 + 9, st["at"])
        val fb = DnsPlan.onHealth(s, DnsPlan.Health.CAPTIVE, t0 + 1).state
        val fst = DnsPlan.status(fb, ok = true, now = t0 + 2)
        assertEquals("fallback", fst["mode"])
        assertEquals(false, fst["ok"])
        assertEquals("CAPTIVE_PORTAL", fst["err"])
        assertEquals("off", DnsPlan.status(DnsPlan.State(), ok = false, now = 1)["mode"])
        assertTrue(DnsPlan.FEATURE in RemoteCommands.FEATURES)
        assertEquals("dns", DnsPlan.FEATURE)
    }

    @Test
    fun stateSurvivesARestart() {
        val fb = DnsPlan.onHealth(applied(), DnsPlan.Health.FAILING, t0).state.copy(alertTriedAt = 7L)
        assertEquals(fb, DnsPlan.State.fromJson(JSONObject(fb.toJson().toString())))
        assertEquals(DnsPlan.State(), DnsPlan.State.fromJson(null))
    }

    @Test
    fun dohBrowsersAndVpnInChildModeOnly() {
        for (pkg in listOf("org.mozilla.firefox", "com.brave.browser", "com.opera.browser", "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android")) {
            assertTrue(pkg, DnsPlan.blocksApp(true, sid, pkg))
            assertFalse(pkg, DnsPlan.blocksApp(true, null, pkg))  // parent mode
            assertFalse(pkg, DnsPlan.blocksApp(false, sid, pkg))  // filter off
        }
        assertFalse(DnsPlan.blocksApp(true, sid, "com.android.chrome")) // Chrome: DoH turned off by managed config instead
        assertTrue(DnsPlan.wantsVpnLock(true, sid))
        assertFalse(DnsPlan.wantsVpnLock(true, ""))
        assertFalse(DnsPlan.wantsVpnLock(false, sid))
    }

    @Test
    fun dnsAlwaysComesFromTheDocNotKioskPolicySet() {
        val doc = JSONObject().put("rev", 1L).put("dns", on)
        val visitor = JSONObject().put("rev", 9L).put("dns", JSONObject().put("on", false)).put("allowedApps", JSONArray())
        val (p, src) = Rules.pickPolicy(doc, visitor, visit = true)
        assertEquals("local", src)
        assertTrue(p.getJSONObject("dns").getBoolean("on"))
        assertEquals(H, p.getJSONObject("dns").getString("host"))
        val (p2, _) = Rules.pickPolicy(JSONObject().put("rev", 1L), visitor)
        assertFalse(p2.has("dns"))
    }
}
