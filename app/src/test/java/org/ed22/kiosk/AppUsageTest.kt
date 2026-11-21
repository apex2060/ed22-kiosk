package org.ed22.kiosk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Usage accuracy (Nov 20 2026): short launches left out of the report, lastMs in status.appUsageByChild; resumed time unchanged. */
class AppUsageTest {
    private val sid = "7w5zyEAVa13AivXernwO"
    private val day = { ms: Long -> if (ms < 1_000_000_000L) "2026-11-19" else "2026-11-20" }

    /** A stored piece as recordSession writes it: a short launch carries short + creditMs 0. */
    private fun piece(pkg: String, start: Long, end: Long, cont: Boolean = false, short: Boolean = false, who: String? = sid) =
        JSONObject().put("pkg", pkg).put("studentId", who ?: JSONObject.NULL).put("startMs", start).put("endMs", end).apply {
            if (cont) put("cont", true)
            if (short) put("short", true).put("creditMs", 0L)
        }

    @Suppress("UNCHECKED_CAST")
    private fun entry(m: Map<String, Any>, d: String, pkg: String, who: String = sid) =
        ((m[d] as Map<String, Any>?)?.get(who) as Map<String, Any>?)?.get(pkg) as Map<String, Any>?

    @Test fun minimums() {
        assertEquals(15_000L, AppUsage.MIN_MS)
        assertEquals(2_000L, AppUsage.RULES_MIN_MS)
        assertEquals(3_000L, AppUsage.BOUNCE_CHECK_MS)
    }

    @Test fun resumedTimeCountsInFullAsBefore() {
        val t = 1_700_000_000_000L
        val list = JSONArray()
            .put(piece("org.lds.ldssa", t, t + 600_000))                              // launched, 10 min
            .put(piece("org.lds.ldssa", t + 700_000, t + 3_400_000, cont = true))     // woken screen, 45 min: full, as before
            .put(piece("com.goodandbeautiful.lettertiles", t, t + 52_000, cont = true)) // woken, 52 s (Nov 19): full, as before
            .put(piece("com.goodandbeautiful.lettertiles", t + 60_000, t + 65_000, cont = true)) // woken 5 s: also as before (>= 2 s)
        val m = AppUsage.usageByChildFrom(list, "2026-11-14", day)
        val lib = entry(m, "2026-11-20", "org.lds.ldssa")!!
        assertEquals(3300L, lib["seconds"]); assertEquals(1L, lib["opens"]); assertEquals(t + 3_400_000, lib["lastMs"])
        val lt = entry(m, "2026-11-20", "com.goodandbeautiful.lettertiles")!!
        assertEquals(57L, lt["seconds"]); assertEquals(0L, lt["opens"]); assertEquals(t + 65_000, lt["lastMs"])
    }

    @Test fun shortLaunchLeftOutOfReport() {
        val t = 1_700_000_000_000L
        val m = AppUsage.usageByChildFrom(JSONArray()
            .put(piece("com.goodandbeautiful.homeschool", t, t + 10_000, short = true))   // a bounce: no entry
            .put(piece("me.greenlight", t, t + 14_000, short = true))
            .put(piece("me.greenlight", t + 20_000, t + 320_000)), "2026-11-14", day)  // real 5 min use
        assertNull(entry(m, "2026-11-20", "com.goodandbeautiful.homeschool"))
        val gl = entry(m, "2026-11-20", "me.greenlight")!!
        assertEquals(300L, gl["seconds"]); assertEquals(1L, gl["opens"]); assertEquals(t + 320_000, gl["lastMs"])
    }

    @Test fun olderBuildPiecesCountInFull() {
        val t = 1_700_000_000_000L
        val m = AppUsage.usageByChildFrom(JSONArray().put(piece("old.build", t, t + 120_000)).put(piece("old.build", t + 200_000, t + 205_000)), "2026-11-14", day)
        val e = entry(m, "2026-11-20", "old.build")!!
        assertEquals(125L, e["seconds"]); assertEquals(2L, e["opens"])
    }

    @Test fun lastMsIsNewestEndOfTheDay() {
        val t = 1_700_000_000_000L
        val m = AppUsage.usageByChildFrom(JSONArray().put(piece("a.b", t + 500_000, t + 560_000)).put(piece("a.b", t, t + 60_000)), "2026-11-14", day)
        assertEquals(t + 560_000, entry(m, "2026-11-20", "a.b")!!["lastMs"])
    }

    @Test fun oldDaysAndParentBucket() {
        val list = JSONArray().put(piece("a.b", 100L, 60_100L)).put(piece("a.b", 1_700_000_000_000L, 1_700_000_060_000L, who = null))
        val m = AppUsage.usageByChildFrom(list, "2026-11-20", day)
        assertFalse(m.containsKey("2026-11-19"))
        val p = entry(m, "2026-11-20", "a.b", "parent")!!
        assertEquals(60L, p["seconds"]); assertTrue(p.containsKey("lastMs"))
    }

    @Test fun featureListed() { assertTrue("usageLastUsed" in RemoteCommands.FEATURES) }
}
