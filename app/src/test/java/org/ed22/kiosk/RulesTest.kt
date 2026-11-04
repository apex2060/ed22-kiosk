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

/** Android additions: policy.strict (A2), elsewhere minutes (A3) and local/doc policy precedence (A1). */
class RulesExtrasTest {
    private val zone = ZoneId.of("America/Phoenix")
    private fun t(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private fun j(s: String) = JSONObject(s)
    private val noUse = Rules.Used { _, _, _, _ -> 0.0 }
    private val now = t("2026-11-03T09:00") // Tuesday

    private val ruled = """{"package":"epic","until":null,"rules":[{"studentIds":["ava"]}]}"""

    @Test
    fun strictParentModeAllowsOnlyExtraTime() {
        assertTrue(Rules.state(j(ruled), now, zone, null, noUse).allowed)                   // not strict: as before
        assertEquals(false, Rules.state(j(ruled), now, zone, null, noUse, strict = true).allowed)
        assertTrue(Rules.state(j(ruled), now, zone, "ava", noUse, strict = true).allowed)
        val extra = j(ruled).put("extraUntil", t("2026-11-03T09:30"))
        val s = Rules.state(extra, now, zone, null, noUse, strict = true)
        assertTrue(s.allowed); assertEquals("extra", s.why)
    }

    @Test
    fun strictLegacyNeedsAFutureUntil() {
        val open = j("""{"package":"old","until":null}""")
        assertTrue(Rules.state(open, now, zone, "ava", noUse).allowed)
        assertEquals(false, Rules.state(open, now, zone, "ava", noUse, strict = true).allowed)
        assertEquals(false, Rules.state(j("""{"package":"old","until":null,"days":[2],"studentIds":["ava"]}"""), now, zone, "ava", noUse, strict = true).allowed)
        val forAWhile = j("""{"package":"com.android.vending"}""").put("until", t("2026-11-03T09:30"))
        assertTrue(Rules.state(forAWhile, now, zone, null, noUse, strict = true).allowed)
        assertEquals(false, Rules.state(forAWhile, t("2026-11-03T09:30"), zone, null, noUse, strict = true).allowed)
    }

    @Test
    fun strictEmptyRulesAllowNothing() {
        val empty = j("""{"package":"x","until":null,"rules":[]}""")
        assertTrue(Rules.state(empty, now, zone, "ava", noUse).allowed) // not strict: legacy path, as before
        assertEquals(false, Rules.state(empty, now, zone, "ava", noUse, strict = true).allowed)
        assertEquals(false, Rules.state(empty, now, zone, null, noUse, strict = true).allowed)
    }

    private fun limited(date: String) = j("""{"package":"epic","until":null,
        "rules":[{"studentIds":["ava","ben"],"minutesPerDay":30}],
        "elsewhere":{"ava":{"date":"$date","minutes":6}}}""")

    @Test
    fun elsewhereCountsTodayOnly() {
        val used4 = Rules.Used { _, _, _, _ -> 4.0 }
        assertEquals(20.0, Rules.state(limited("2026-11-03"), now, zone, "ava", used4).left, 0.0)      // 30 - 6 - 4
        assertEquals(26.0, Rules.state(limited("2026-11-02"), now, zone, "ava", used4).left, 0.0)      // other date: ignored
        assertEquals(26.0, Rules.state(limited("2026-11-03"), now, zone, "ben", used4).left, 0.0)      // other child
        assertEquals(26.0, Rules.state(limited("2026-11-03"), now, zone, null, used4).left, 0.0)       // parent mode: ignored
        val used24 = Rules.Used { _, _, _, _ -> 24.0 }
        assertEquals(false, Rules.state(limited("2026-11-03"), now, zone, "ava", used24).allowed)     // 6 + 24 = 30
    }

    @Test
    fun revPrecedence() {
        val doc = j("""{"rev":100,"allowedApps":[{"package":"doc"}],"locked":true,"command":{"id":"c1","type":"reload"}}""")
        val newerLocal = j("""{"rev":200,"allowedApps":[{"package":"local"}],"locked":false,"command":{"id":"x"}}""")
        val (p, src) = Rules.pickPolicy(doc, newerLocal)
        assertEquals("local", src)
        assertEquals("local", p.getJSONArray("allowedApps").getJSONObject(0).getString("package"))
        assertEquals(true, p.getBoolean("locked"))                                  // locked / command from the doc
        assertEquals("c1", p.getJSONObject("command").getString("id"))
        assertEquals("doc", Rules.pickPolicy(doc.put("rev", 300), newerLocal).second) // doc newer
        assertEquals("doc", Rules.pickPolicy(doc.put("rev", 200), newerLocal).second) // tie -> doc
        assertEquals("local", Rules.pickPolicy(j("""{"allowedApps":[]}"""), newerLocal).second) // missing doc rev = 0
        assertEquals("doc", Rules.pickPolicy(doc, j("""{"allowedApps":[]}""")).second)       // missing local rev = 0
        assertEquals("doc", Rules.pickPolicy(null, null).second)
    }
}
