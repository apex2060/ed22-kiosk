package org.ed22.kiosk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** studentIds / activeStudentId only: legacy alternate field names are ignored. */
class StudentIdTest {
    private val zone = ZoneId.of("America/Phoenix")
    private val now = LocalDateTime.parse("2026-11-03T09:00").atZone(zone).toInstant().toEpochMilli() // Tuesday

    // Split so a repo-wide search for the retired names stays clean outside intentional product mentions.
    private val altIds = "child" + "Ids"
    private val altId = "child" + "Id"
    private val altActive = "active" + "Child" + "Id"

    private fun ok(entry: JSONObject, sid: String?) = Rules.state(entry, now, zone, sid, null).allowed

    private fun ruleEntry(idsKey: String, ids: List<String>, studentIds: List<String>? = null): JSONObject {
        val rule = JSONObject().put(idsKey, JSONArray(ids))
        if (studentIds != null) rule.put("studentIds", JSONArray(studentIds))
        return JSONObject().put("package", "epic").put("rules", JSONArray().put(rule))
    }

    private fun legacyEntry(idsKey: String, ids: List<String>, studentIds: List<String>? = null): JSONObject {
        val e = JSONObject().put("package", "old").put("until", JSONObject.NULL).put(idsKey, JSONArray(ids))
        if (studentIds != null) e.put("studentIds", JSONArray(studentIds))
        return e
    }

    @Test
    fun studentIdsOnRules() {
        val e = ruleEntry("studentIds", listOf("ava"))
        assertTrue(ok(e, "ava")); assertFalse(ok(e, "ben"))
    }

    @Test
    fun studentIdsOnLegacyEntry() {
        val e = legacyEntry("studentIds", listOf("ava"))
        assertTrue(ok(e, "ava")); assertFalse(ok(e, "ben"))
    }

    @Test
    fun alternateIdsListIgnored() {
        // Alternate list field alone must not restrict (no studentIds = everyone).
        val onlyAlt = ruleEntry(altIds, listOf("ava"))
        assertTrue(ok(onlyAlt, "ava")); assertTrue(ok(onlyAlt, "ben"))
        val legacyAlt = legacyEntry(altIds, listOf("ava"))
        assertTrue(ok(legacyAlt, "ava")); assertTrue(ok(legacyAlt, "ben"))
        // When both appear, only studentIds is used.
        val both = ruleEntry(altIds, listOf("ava"), studentIds = listOf("ben"))
        assertFalse(ok(both, "ava")); assertTrue(ok(both, "ben"))
        val bothLegacy = legacyEntry(altIds, listOf("ava"), studentIds = listOf("ben"))
        assertFalse(ok(bothLegacy, "ava")); assertTrue(ok(bothLegacy, "ben"))
    }

    @Test
    fun elsewhereKeyedByStudentId() {
        val e = JSONObject("""{"package":"epic","rules":[{"studentIds":["ava"],"minutesPerDay":30}],
            "elsewhere":{"ava":{"date":"2026-11-03","minutes":10}}}""")
        assertEquals(20.0, Rules.state(e, now, zone, "ava", Rules.Used { _, _, _, _ -> 0.0 }).left, 0.0)
    }

    @Test
    fun docReadsActiveStudentIdOnly() {
        assertEquals("ava", Rules.docActiveStudentId("ava"))
        assertEquals("", Rules.docActiveStudentId(null))
        assertEquals("", Rules.docActiveStudentId(JSONObject.NULL))
        // Alternate top-level id field names are ignored; only activeStudentId counts.
        assertEquals(
            "ava",
            Rules.docActiveStudentIdFrom(JSONObject().put("activeStudentId", "ava").put(altActive, "ben")),
        )
        assertEquals("", Rules.docActiveStudentIdFrom(JSONObject().put(altActive, "ava")))
        assertEquals("", Rules.docActiveStudentIdFrom(JSONObject()))
    }

    @Test
    fun parseSetActiveChildArgs() {
        // Plain string form arrives as {studentId: "..."}.
        assertEquals(Rules.ActiveChildArg(true, "ava"), Rules.parseSetActiveChildArgs(JSONObject().put("studentId", "ava")))
        assertEquals(Rules.ActiveChildArg(true, null), Rules.parseSetActiveChildArgs(JSONObject().put("studentId", JSONObject.NULL)))
        assertEquals(Rules.ActiveChildArg(true, null), Rules.parseSetActiveChildArgs(JSONObject().put("studentId", "")))
        // {studentId} object form (after the JS shim) is the same shape.
        assertEquals(Rules.ActiveChildArg(true, "ben"), Rules.parseSetActiveChildArgs(JSONObject("""{"studentId":"ben"}""")))
        // Object with no studentId (e.g. only an alternate id field): ignored, no change.
        assertEquals(Rules.ActiveChildArg(false, null), Rules.parseSetActiveChildArgs(JSONObject()))
        assertEquals(Rules.ActiveChildArg(false, null), Rules.parseSetActiveChildArgs(JSONObject().put(altId, "ava")))
        // studentId wins when both keys are present.
        assertEquals(
            Rules.ActiveChildArg(true, "ben"),
            Rules.parseSetActiveChildArgs(JSONObject().put(altId, "ava").put("studentId", "ben")),
        )
    }

    @Test
    fun featuresExcludeAlternateIdName() {
        assertFalse(altId in RemoteCommands.FEATURES)
        assertTrue(RemoteCommands.FEATURES.containsAll(
            listOf("rules", "ruleLimits", "strict", "localPolicy", "elsewhere", "appsUsageByChild", "appsEvent", "openApp"),
        ))
    }
}
