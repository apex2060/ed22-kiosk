package org.ed22.kiosk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The shared ed22 rule cases (copied from ed22 rules/cases.json, see edRules.test.js). Times are local
 * "YYYY-MM-DDTHH:MM"; the few absolute ms values in the file (until / extraUntil) were generated in
 * America/Phoenix, so the cases run in that zone.
 */
class RulesTest {
    private val zone = ZoneId.of("America/Phoenix")
    private fun t(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun sharedCases() {
        val cases = JSONArray(javaClass.classLoader!!.getResource("cases.json")!!.readText())
        assertEquals(29, cases.length())
        val failures = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val sessions = c.getJSONArray("sessions")
            // Same as edRules.test.js: overlap of the child's sessions (any child when sid is null) with [from, to).
            val used = Rules.Used { pkg, sid, from, to ->
                (0 until sessions.length()).map { sessions.getJSONObject(it) }
                    .filter { it.getString("pkg") == pkg && (sid.isNullOrEmpty() || it.optString("studentId") == sid) }
                    .sumOf { maxOf(0L, minOf(to, t(it.getString("to"))) - maxOf(from, t(it.getString("from")))) / 60_000.0 }
            }
            val sid = if (c.isNull("studentId")) null else c.getString("studentId")
            val s = Rules.state(c.getJSONObject("entry"), t(c.getString("time")), zone, sid, used)
            val e = c.getJSONObject("expect")
            if (s.allowed != e.getBoolean("allowed")) failures += "$name: allowed ${s.allowed}"
            if (e.has("left")) {
                val want = e.get("left")
                val ok = if (want == "Infinity") s.left.isInfinite() else s.left == (want as Number).toDouble()
                if (!ok) failures += "$name: left ${s.left} != $want"
            }
            if (e.has("until") && s.until != t(e.getString("until"))) failures += "$name: until ${s.until}"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        println("RulesTest: ${cases.length()}/${cases.length()} shared cases passed")
    }

    @Test
    fun minutesParsing() {
        assertEquals(8 * 60, Rules.minutes("08:00"))
        assertEquals(null, Rules.minutes("8:00"))
        assertEquals(null, Rules.minutes("24:00"))
    }

    @Test
    fun noEntryIsNotAllowed() {
        assertEquals("none", Rules.state(null, 0, zone, null, null).why)
        assertTrue(Rules.state(JSONObject().put("package", "x").put("until", JSONObject.NULL), 0, zone, "a", null).allowed)
    }
}
