package org.ed22.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** childIds / childId (new names) with studentIds / studentId as the fallback. */
class ChildIdTest {
    private val zone = ZoneId.of("America/Phoenix")
    private val now = LocalDateTime.parse("2026-11-03T09:00").atZone(zone).toInstant().toEpochMilli() // Tuesday
    private fun ok(entry: String, sid: String?) = Rules.state(JSONObject(entry), now, zone, sid, null).allowed

    @Test
    fun childIdsOnRules() {
        val e = """{"package":"epic","rules":[{"childIds":["ava"]}]}"""
        assertTrue(ok(e, "ava")); assertFalse(ok(e, "ben"))
    }

    @Test
    fun childIdsOnLegacyEntry() {
        val e = """{"package":"old","until":null,"childIds":["ava"]}"""
        assertTrue(ok(e, "ava")); assertFalse(ok(e, "ben"))
    }

    @Test
    fun bothNamesPreferChildIds() {
        val rule = """{"package":"epic","rules":[{"childIds":["ava"],"studentIds":["ben"]}]}"""
        assertTrue(ok(rule, "ava")); assertFalse(ok(rule, "ben"))
        val legacy = """{"package":"old","until":null,"childIds":["ava"],"studentIds":["ben"]}"""
        assertTrue(ok(legacy, "ava")); assertFalse(ok(legacy, "ben"))
        // An empty childIds array is "everyone", even when studentIds lists someone.
        assertTrue(ok("""{"package":"epic","rules":[{"childIds":[],"studentIds":["ben"]}]}""", "ava"))
    }

    @Test
    fun studentIdsFallback() {
        val e = """{"package":"epic","rules":[{"studentIds":["ava"]}]}"""
        assertTrue(ok(e, "ava")); assertFalse(ok(e, "ben"))
        // childIds that isn't an array (null) -> studentIds.
        assertFalse(ok("""{"package":"epic","rules":[{"childIds":null,"studentIds":["ava"]}]}""", "ben"))
    }

    @Test
    fun elsewhereIsKeyedByIdValue() {
        val e = JSONObject("""{"package":"epic","rules":[{"childIds":["ava"],"minutesPerDay":30}],
            "elsewhere":{"ava":{"date":"2026-11-03","minutes":10}}}""")
        assertEquals(20.0, Rules.state(e, now, zone, "ava", Rules.Used { _, _, _, _ -> 0.0 }).left, 0.0)
    }

    @Test
    fun pickChildId() {
        assertEquals("a", Rules.pickChildId("a", "b"))                       // both: childId
        assertEquals("b", Rules.pickChildId(null, "b"))                      // fallback
        assertEquals("b", Rules.pickChildId(JSONObject.NULL, "b"))
        assertEquals("", Rules.pickChildId("", "b"))                         // "" (parent mode) still wins
        assertNull(Rules.pickChildId(null, null))
        assertNull(Rules.pickChildId(JSONObject.NULL, JSONObject.NULL))
        assertTrue("childId" in RemoteCommands.FEATURES)
    }
}
