package org.ed22.kiosk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * The shared screen-time cases (copied from ed22 device-time-limits rules/device-time-cases.json and
 * device-time-policies.json, see edDeviceTime.test.js): the tablet and the device manager answer the same way.
 * Times are local "YYYY-MM-DDTHH:MM" in America/Phoenix.
 */
class DeviceTimeTest {
    private val zone = ZoneId.of("America/Phoenix")
    private fun t(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private fun policy(all: JSONObject, name: String): JSONObject {
        val p = JSONObject(all.getJSONObject(name).toString())
        p.optJSONObject("extra")?.let { ex -> ex.keys().asSequence().toList().forEach { k -> ex.put(k, t(ex.getString(k))) } }
        return p
    }

    @Test
    fun sharedCases() {
        val cases = JSONArray(res("device-time-cases.json"))
        val pols = JSONObject(res("device-time-policies.json"))
        assertEquals(28, cases.length())
        val failures = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val sid = if (c.isNull("studentId")) null else c.getString("studentId")
            val local = if (c.has("localExtra") && !c.isNull("localExtra")) t(c.getString("localExtra")) else null
            val s = DeviceTime.forStudent(policy(pols, c.getString("dt")), t(c.getString("time")), zone, sid, c.getDouble("usedHere"), local)
            val e = c.getJSONObject("expect")
            val bad = mutableListOf<String>()
            if (s.allowed != e.getBoolean("allowed")) bad += "allowed ${s.allowed}"
            if (e.has("why") && s.why != e.getString("why")) bad += "why ${s.why}"
            if (e.has("left")) {
                val ok = if (e.get("left") == "Infinity") s.left == Double.POSITIVE_INFINITY else Math.abs(s.left - e.getDouble("left")) < 1e-9
                if (!ok) bad += "left ${s.left}"
            }
            if (e.has("until") && s.until != t(e.getString("until"))) bad += "until ${s.until}"
            if (e.has("next") && s.next != t(e.getString("next"))) bad += "next ${s.next}"
            if (e.has("ends") && s.ends != e.getBoolean("ends")) bad += "ends ${s.ends}"
            if (e.has("warn") && s.warn != e.getBoolean("warn")) bad += "warn ${s.warn}"
            if (bad.isNotEmpty()) failures += "${c.getString("name")}: ${bad.joinToString()}"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun limitedOnlyForChildrenWithSettingsOrExtra() {
        val dt = JSONObject("""{"students":{"kid1":{"limits":[{"minutes":60}]},"kid2":{}},"extra":{"kid3":1}}""")
        assertTrue(DeviceTime.limited(dt, "kid1"))
        assertFalse(DeviceTime.limited(dt, "kid2"))
        assertTrue(DeviceTime.limited(dt, "kid3"))
        assertFalse(DeviceTime.limited(dt, null))
        assertFalse(DeviceTime.limited(null, "kid1"))
    }

    @Test
    fun stateJsonForPages() {
        val s = DeviceTime.State(true, "ok", left = 4.2, until = 5L, ends = true, warn = true).toJson()
        assertEquals(5, s.getInt("left"))
        assertEquals(true, s.getBoolean("warn"))
        assertTrue(DeviceTime.State(true, "open").toJson().isNull("left"))
    }
}

class ScreenTimeLedgerTest {
    private val zone = ZoneId.of("America/Phoenix")
    private fun t(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun countsWhileMarkedAndSplitsAtMidnight() {
        val l = ScreenTimeLedger()
        l.tick(t("2026-11-13T23:58"), zone, "kid1")
        assertEquals(1.0, l.minutesToday("kid1", t("2026-11-13T23:59"), zone), 1e-9) // open mark counts
        l.tick(t("2026-11-14T00:01"), zone, "kid1")
        val m = l.statusMap()
        assertEquals(120L, m["2026-11-13"]!!["kid1"])
        assertEquals(60L, m["2026-11-14"]!!["kid1"])
        assertEquals(1.0, l.minutesToday("kid1", t("2026-11-14T00:01"), zone), 1e-9)
        assertEquals(0.0, l.minutesToday("kid2", t("2026-11-14T00:01"), zone), 1e-9)
    }

    @Test
    fun stopsCountingAndSkipsLongGaps() {
        val l = ScreenTimeLedger()
        l.tick(t("2026-11-13T10:00"), zone, "kid1")
        l.tick(t("2026-11-13T10:02"), zone, null) // cover up / screen off: 2 minutes counted, then nobody
        l.tick(t("2026-11-13T10:30"), zone, "kid1")
        l.tick(t("2026-11-13T11:30"), zone, "kid1") // app was not running for an hour: not counted
        assertEquals(2.0, l.minutesToday("kid1", t("2026-11-13T11:30"), zone), 1e-9)
    }

    @Test
    fun keepsSevenDaysAndRoundTripsJson() {
        val l = ScreenTimeLedger()
        l.tick(t("2026-11-01T10:00"), zone, "kid1"); l.tick(t("2026-11-01T10:01"), zone, null)
        l.tick(t("2026-11-13T10:00"), zone, "kid1"); l.tick(t("2026-11-13T10:01"), zone, "kid1")
        val again = ScreenTimeLedger(JSONObject(l.toString()))
        assertEquals(setOf("2026-11-13"), again.statusMap().keys)
        assertEquals(1.0, again.minutesToday("kid1", t("2026-11-13T10:01"), zone), 1e-9)
    }
}

class DeviceTimeLockTextTest {
    private val zone = ZoneId.of("America/Phoenix")
    private fun t(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private val now = t("2026-11-13T20:30") // a Friday

    @Test
    fun whenTextTodayTomorrowWeekday() {
        assertEquals("at 9:00 PM", LockScreen.whenText(t("2026-11-13T21:00"), now, zone, true, Locale.US))
        assertEquals("tomorrow at 8:00 AM", LockScreen.whenText(t("2026-11-14T08:00"), now, zone, true, Locale.US))
        assertEquals("on Monday at 7:00 AM", LockScreen.whenText(t("2026-11-16T07:00"), now, zone, true, Locale.US))
        assertEquals("tomorrow at 08:00", LockScreen.whenText(t("2026-11-14T08:00"), now, zone, false, Locale.US))
        assertEquals(null, LockScreen.whenText(null, now, zone, true, Locale.US))
        assertEquals("3:05 PM", LockScreen.clockWithAmPm(LocalTime.of(15, 5), true, Locale.US))
    }

    @Test
    fun coverTextLockedWindowLimit() {
        val next = t("2026-11-14T08:00")
        assertEquals(LockScreen.LOCKED_MESSAGE to null,
            LockScreen.coverText(true, DeviceTime.State(false, "window", next = next), now, zone, true, Locale.US))
        assertEquals("Little Explorer is resting" to "You can use it again tomorrow at 8:00 AM.",
            LockScreen.coverText(false, DeviceTime.State(false, "window", next = next), now, zone, true, Locale.US))
        assertEquals("That's all the screen time for today" to "See you tomorrow at 8:00 AM!",
            LockScreen.coverText(false, DeviceTime.State(false, "limit", next = next), now, zone, true, Locale.US))
        assertEquals("Little Explorer is resting" to "Ask a parent for more time.",
            LockScreen.coverText(false, DeviceTime.State(false, "window"), now, zone, true, Locale.US))
    }

    @Test
    fun warningText() {
        assertEquals("5 minutes of screen time left", LockScreen.warningText(5))
        assertEquals("1 minute of screen time left", LockScreen.warningText(1))
    }

    @Test
    fun featureAdvertised() {
        assertTrue("deviceTime" in RemoteCommands.FEATURES)
    }
}
