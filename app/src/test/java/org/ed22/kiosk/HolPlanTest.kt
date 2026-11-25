package org.ed22.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** House of Learning visit: policy pick, storage keys, timer, status shape (studentId only). */
class HolPlanTest {
    private val t0 = 1_795_900_000_000L
    private fun j(s: String) = JSONObject(s)

    @Test
    fun pickPolicyCopiesHolFromTheDoc() {
        val doc = j("""{"rev":100,"allowedApps":[{"package":"doc"}],"locked":true,"command":{"id":"c1","type":"reload"},"hol":{"on":true,"warnMin":5}}""")
        val local = j("""{"rev":200,"allowedApps":[{"package":"local"}],"locked":false,"command":{"id":"x"},"hol":{"on":false}}""")
        val (p, src) = Rules.pickPolicy(doc, local)
        assertEquals("local", src)
        assertEquals("local", p.getJSONArray("allowedApps").getJSONObject(0).getString("package"))
        assertEquals(true, p.getBoolean("locked"))
        assertEquals("c1", p.getJSONObject("command").getString("id"))
        assertEquals(true, p.getJSONObject("hol").getBoolean("on"))
        assertEquals(5, p.getJSONObject("hol").getInt("warnMin"))
        assertFalse(p.has("hol") && p.getJSONObject("hol").optBoolean("on") == false && !doc.getJSONObject("hol").optBoolean("on"))
    }

    @Test
    fun duringAVisitTheFamilyLocalPolicyWinsRegardlessOfRev() {
        val house = j("""{"rev":300,"allowedApps":[{"package":"house"}],"deviceTime":{"dailyMinutes":10},"locked":true,"command":{"id":"c1","type":"holLogout"},"hol":{"on":true,"warnMin":5}}""")
        val family = j("""{"rev":50,"allowedApps":[{"package":"family"}],"deviceTime":{"dailyMinutes":45},"locked":false}""")
        val (p, src) = Rules.pickPolicy(house, family, visit = true)
        assertEquals("local", src)
        assertEquals("family", p.getJSONArray("allowedApps").getJSONObject(0).getString("package"))
        assertEquals(45, p.getJSONObject("deviceTime").getInt("dailyMinutes"))
        assertEquals(true, p.getBoolean("locked")) // house lock / command / hol still apply
        assertEquals("c1", p.getJSONObject("command").getString("id"))
        assertEquals("holLogout", p.getJSONObject("command").getString("type"))
        assertEquals(true, p.getJSONObject("hol").getBoolean("on"))
        // No visit: house doc wins (newer or tied rev).
        assertEquals("doc", Rules.pickPolicy(house, family, visit = false).second)
        assertEquals("house", Rules.pickPolicy(house, family, false).first.getJSONArray("allowedApps").getJSONObject(0).getString("package"))
        // Visit but no local policy yet: the house doc is used.
        assertEquals("doc", Rules.pickPolicy(house, null, visit = true).second)
        // After the visit, rev rules return.
        val after = Rules.pickPolicy(house, family, visit = false)
        assertEquals("doc", after.second)
    }

    @Test
    fun storageKeysForThePageSessionAreClearedDeviceKeysAreKept() {
        assertTrue(HolPlan.clearStorageKey("mobile.session"))
        assertTrue(HolPlan.clearStorageKey("mobile.auth"))
        assertTrue(HolPlan.clearStorageKey("holVisit"))
        assertTrue(HolPlan.clearStorageKey("HOL"))
        assertTrue(HolPlan.clearStorageKey("hol.token"))
        assertTrue(HolPlan.clearStorageKey("activeScene"))
        assertTrue(HolPlan.clearStorageKey("activeReserve"))
        assertTrue(HolPlan.clearStorageKey("ActiveScene"))
        assertFalse(HolPlan.clearStorageKey("deviceId"))
        assertFalse(HolPlan.clearStorageKey("device.pinHint"))
        assertFalse(HolPlan.clearStorageKey("kiosk.theme"))
    }

    @Test
    fun warningAndEndTimes() {
        val v = HolPlan.visit(t0 + 7 * 60_000, 5)
        assertEquals(5, v.warnMin)
        assertEquals(t0 + 2 * 60_000, HolPlan.warnAt(v.endsAtMs, v.warnMin))
        assertFalse(HolPlan.shouldWarn(v, t0 + 60_000, null))
        assertTrue(HolPlan.shouldWarn(v, t0 + 2 * 60_000, null))
        assertFalse(HolPlan.shouldWarn(v, t0 + 2 * 60_000, HolPlan.warnAt(v.endsAtMs, v.warnMin)))
        assertFalse(HolPlan.shouldWarn(v, t0 + 7 * 60_000, null)) // already ending
        assertFalse(HolPlan.shouldEnd(v, t0 + 7 * 60_000 - 1))
        assertTrue(HolPlan.shouldEnd(v, t0 + 7 * 60_000))
        assertTrue(HolPlan.shouldEnd(v, t0 + 8 * 60_000))
        assertFalse(HolPlan.shouldEnd(null, t0))
        // Default warn, and a later endsAtMs is an extension.
        assertEquals(HolPlan.DEFAULT_WARN_MIN, HolPlan.visit(t0, null).warnMin)
        assertTrue(HolPlan.isExtension(t0, t0 + 10 * 60_000))
        assertFalse(HolPlan.isExtension(t0, t0))
        assertFalse(HolPlan.isExtension(null, t0))
        assertEquals("This visit ends in 5 minutes.", HolPlan.warnText(5))
        assertEquals("This visit ends in 1 minute.", HolPlan.warnText(1))
        assertEquals("HOL visit ended: visit ended", HolPlan.endLog("visit ended"))
        assertEquals("HOL visit ended: parent menu", HolPlan.endLog("parent menu"))
    }

    @Test
    fun statusShapeUsesStudentIdOnly() {
        val v = HolPlan.visitingStatus(t0, "fake-student-0001", "Ava", null)
        assertEquals(setOf("on", "visitor", "studentId", "name", "endsAtMs", "last"), v.keys)
        assertEquals(true, v["on"]); assertEquals(true, v["visitor"])
        assertEquals("fake-student-0001", v["studentId"]); assertEquals("Ava", v["name"])
        assertEquals(t0, v["endsAtMs"]); assertNull(v["last"])
        assertFalse(v.keys.any { it == "child" + "Id" })
        val ended = HolPlan.endedStatus(t0 + 1, "visit ended")
        assertEquals(true, ended["on"]); assertEquals(false, ended["visitor"])
        assertNull(ended["studentId"]); assertNull(ended["name"]); assertNull(ended["endsAtMs"])
        assertEquals(mapOf("at" to t0 + 1, "why" to "visit ended"), ended["last"])
        assertFalse(ended.keys.any { it == "child" + "Id" })
        val rest = HolPlan.restingStatus(true, ended["last"] as Map<String, Any?>)
        assertEquals(true, rest["on"]); assertEquals(false, rest["visitor"])
        val off = HolPlan.restingStatus(false, null)
        assertEquals(false, off["on"]); assertNull(off["last"])
        // No tool-usage totals on the house item (status.hol is display + visit only).
        assertFalse(v.containsKey("tools") || v.containsKey("usage") || v.containsKey("appUsage"))
    }

    @Test
    fun featureIsListedAndHolLogoutRunsOnceWhileFreshEvenWhenLocked() {
        assertTrue(HolPlan.FEATURE in RemoteCommands.FEATURES)
        assertTrue("hol" in RemoteCommands.features(false))
        // Timed so a stale command after reboot cannot end a new visit; also allowed while locked.
        assertTrue("holLogout" in RemoteCommands.TIMED_TYPES)
        assertTrue("holLogout" in RemoteCommands.RING_TYPES)
        val fresh = RemoteCommands.decide(JSONObject().put("id", t0.toString()).put("type", "holLogout"), null, t0 + 1_000, locked = true) { false }
        assertTrue(fresh.run && fresh.handled)
        val again = RemoteCommands.decide(JSONObject().put("id", t0.toString()).put("type", "holLogout"), t0.toString(), t0 + 2_000, false) { true }
        assertFalse(again.run)
        val stale = RemoteCommands.decide(JSONObject().put("id", "x").put("type", "holLogout"), null, t0, false) { true }
        assertFalse(stale.run); assertTrue(stale.handled)
    }
}
