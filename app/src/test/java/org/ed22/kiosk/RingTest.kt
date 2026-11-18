package org.ed22.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ring my device (native): command decisions, status.features, the tone plan and the status.remote shape. */
class RingTest {
    private val now = 1_793_000_000_000L
    private fun cmd(type: String, id: Any = now.toString()) = JSONObject().put("id", id).put("type", type)
    private val none: (String) -> Boolean = { false }

    @Test
    fun ringRunsEvenWhenLocked() {
        for (t in listOf("ring", "ringStop")) {
            val d = RemoteCommands.decide(cmd(t), null, now + 1_000, true, none)
            assertTrue(t, d.run); assertTrue(t, d.handled)
        }
        // openApp and openTool still do not run while locked.
        assertFalse(RemoteCommands.decide(cmd("openTool"), null, now, true, none).run)
    }

    @Test
    fun ringMustBeFreshAndNew() {
        val old = RemoteCommands.decide(cmd("ring"), null, now + 3 * 60_000, false, none)
        assertFalse(old.run); assertTrue(old.handled)
        assertTrue(RemoteCommands.decide(cmd("ring"), null, now + 119_000, false, none).run)
        val again = RemoteCommands.decide(cmd("ring"), now.toString(), now, false, none)
        assertFalse(again.run); assertFalse(again.handled)
        val bad = RemoteCommands.decide(cmd("ring", "abc"), null, now, false, none)
        assertFalse(bad.run); assertTrue(bad.handled)
    }

    @Test
    fun featureIsListed() {
        assertTrue("ringNative" in RemoteCommands.FEATURES)
    }

    @Test
    fun planIsThreeRisingBeepsPerCycleWithinTheTotal() {
        val p = RingPlan.plan()
        assertEquals(listOf(660, 880, 1320), p.take(3).map { it.hz })
        assertEquals(listOf(0, 220, 440), p.take(3).map { it.atMs })
        assertEquals(1_100, p[3].atMs)
        assertTrue(p.all { it.atMs < RingPlan.TOTAL_MS })
        assertEquals(42, p.size)  // 14 cycles of 3 beeps (the last starts at 14.3 s)
        assertEquals(listOf(RingPlan.Beep(0, 160, 660)), RingPlan.plan(100))
    }

    @Test
    fun pcmCoversTheTotalAndIsLoudButFaded() {
        val pcm = RingPlan.pcm(2_000)
        assertEquals(2 * RingPlan.SAMPLE_RATE, pcm.size)
        assertTrue(pcm.maxOf { it.toInt() } > 25_000)
        assertEquals(0, pcm[0].toInt())
        assertEquals(0, pcm[pcm.size - 1].toInt())  // the gap after the last beep is silence
    }

    @Test
    fun remoteStatusHasTheSameShapeAsThePage() {
        val m = RingPlan.remote("1793000000000", false, "no sound", 5L)
        assertEquals(setOf("id", "ok", "why", "at"), m.keys)
        assertEquals(false, m["ok"]); assertEquals("no sound", m["why"])
    }
}
