package org.ed22.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** openApp (round 7): RemoteCommands.decide, status.features, and the return to the kiosk when the time is up / [Done]. */
class RemoteCommandsTest {
    private val now = 1_793_000_000_000L
    private val store = "com.android.vending"
    private fun open(id: Any, pkg: String = store) = JSONObject().put("id", id).put("type", "openApp").put("package", pkg)
    private val allowStore: (String) -> Boolean = { it == store }

    @Test
    fun allowedPackageOpens() {
        val d = RemoteCommands.decide(open(now.toString()), null, now + 1_000, false, allowStore)
        assertTrue(d.run); assertTrue(d.handled)
    }

    @Test
    fun notAllowedIsIgnoredButHandled() {
        val d = RemoteCommands.decide(open(now.toString(), "com.other"), null, now, false, allowStore)
        assertFalse(d.run); assertTrue(d.handled)
        assertFalse(RemoteCommands.decide(open(now.toString(), ""), null, now, false) { true }.run)
    }

    @Test
    fun sameIdNeverTwice() {
        val cmd = open(now.toString())
        assertTrue(RemoteCommands.decide(cmd, null, now, false, allowStore).run)
        val again = RemoteCommands.decide(cmd, now.toString(), now, false, allowStore)
        assertFalse(again.run); assertFalse(again.handled)
        // Other types keep the same rule.
        val reload = JSONObject().put("id", "c1").put("type", "reload")
        assertTrue(RemoteCommands.decide(reload, null, now, true, allowStore).run)
        assertFalse(RemoteCommands.decide(reload, "c1", now, false, allowStore).run)
        assertFalse(RemoteCommands.decide(null, null, now, false, allowStore).run)
    }

    @Test
    fun oldOrNonNumericIdIsIgnoredButHandled() {
        val ok = RemoteCommands.decide(open((now - 119_000).toString()), null, now, false, allowStore)
        assertTrue(ok.run)
        val old = RemoteCommands.decide(open((now - 121_000).toString()), null, now, false, allowStore)
        assertFalse(old.run); assertTrue(old.handled)
        val text = RemoteCommands.decide(open("abc"), null, now, false, allowStore)
        assertFalse(text.run); assertTrue(text.handled)
        assertTrue(RemoteCommands.decide(open(now), null, now, false, allowStore).run) // a number id works too
    }

    @Test
    fun lockedIsIgnoredButHandled() {
        val d = RemoteCommands.decide(open(now.toString()), null, now, true, allowStore)
        assertFalse(d.run); assertTrue(d.handled)
    }

    @Test
    fun featuresIncludeOpenApp() {
        assertTrue("openApp" in RemoteCommands.FEATURES)
        assertTrue(RemoteCommands.FEATURES.containsAll(listOf("rules", "ruleLimits", "strict", "localPolicy", "elsewhere", "appsUsageByChild", "appsEvent")))
    }

    /** "Allow for a while" (until / extraUntil) ends -> not allowed, the alarm time is that end, and enforce() goes back. */
    @Test
    fun timeUpAndDoneReturnToKiosk() {
        val zone = ZoneId.of("America/Phoenix")
        val t0 = LocalDateTime.parse("2026-11-04T15:00").atZone(zone).toInstant().toEpochMilli()
        val end = t0 + 30 * 60_000
        val legacy = JSONObject().put("package", store).put("until", end)
        val tool = JSONObject("""{"package":"$store","rules":[{"studentIds":["ava"],"days":[6]}]}""").put("extraUntil", end)
        for (strict in listOf(false, true)) for (e in listOf(legacy, tool)) {
            val s = Rules.state(e, t0, zone, null, null, strict)
            assertTrue(s.allowed); assertEquals(end, s.until)                       // scheduleCheck arms the alarm at `end`
            assertFalse(Rules.state(e, end, zone, null, null, strict).allowed)      // at the alarm: no longer allowed
        }
        // enforce(): Play Store was open (AppUsage.openPkg set by launch()) and is gone -> back to the kiosk.
        assertEquals(setOf(store), RemoteCommands.noLongerAllowed(setOf(store, "epic"), setOf("epic"), store))
        // Allowed set already updated elsewhere, but the open app is not allowed -> still back.
        assertEquals(setOf(store), RemoteCommands.noLongerAllowed(setOf("epic"), setOf("epic"), store))
        // Still allowed -> stay.
        assertTrue(RemoteCommands.noLongerAllowed(setOf(store), setOf(store), store).isEmpty())
        assertTrue(RemoteCommands.noLongerAllowed(null, emptySet(), null).isEmpty())
    }
}
