package org.ed22.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the kiosk takes the screen back from another app, how it retries, and policy.locked revoking apps (Reclaim). */
class ReclaimTest {
    private val inApp = Reclaim.Check(
        ownsScreen = true, screenOn = true, kioskInFront = false, busyOwnScreen = false,
        coverUp = false, openPkg = "com.example.game", openAllowed = true,
    )

    @Test
    fun allowedAppIsLeftAlone() {
        assertNull(Reclaim.reason(inApp))
        assertNull(Reclaim.reason(inApp.copy(openPkg = null)))
    }

    @Test
    fun appThatRanOutIsClosed() {
        val why = Reclaim.reason(inApp.copy(openAllowed = false))
        assertEquals("com.example.game is no longer allowed", why)
    }

    @Test
    fun lockOrScreenTimeUpBringsTheCoverBackFromAnyApp() {
        assertTrue(Reclaim.reason(inApp.copy(coverUp = true))!!.contains("locked"))
        assertTrue(Reclaim.reason(inApp.copy(coverUp = true))!!.contains("was in com.example.game"))
        // Even when the kiosk did not open the app itself.
        assertNotNull(Reclaim.reason(inApp.copy(coverUp = true, openPkg = null)))
    }

    @Test
    fun nothingWhenTheKioskShouldNotAct() {
        val bad = inApp.copy(coverUp = true, openAllowed = false)
        assertNull(Reclaim.reason(bad.copy(kioskInFront = true)))
        assertNull(Reclaim.reason(bad.copy(screenOn = false)))
        assertNull(Reclaim.reason(bad.copy(ownsScreen = false))) // parent paused the kiosk, no PIN, not device owner
        assertNull(Reclaim.reason(bad.copy(busyOwnScreen = true))) // parent changing the PIN, a picker the page opened
    }

    @Test
    fun retriesGetStrongerThenStop() {
        assertEquals(Reclaim.Method.REORDER, Reclaim.method(0))
        assertEquals(Reclaim.Method.MOVE_TASK, Reclaim.method(1))
        assertEquals(Reclaim.Method.CLEAR_TOP, Reclaim.method(2))
        assertEquals(1_500L, Reclaim.retryDelay(0))
        assertNotNull(Reclaim.retryDelay(2))
        assertNull(Reclaim.retryDelay(3))
        assertTrue(Reclaim.TICK_MS <= 15_000L)
    }

    @Test
    fun lockedTakesEveryAppAway() {
        val states = mapOf(
            "a" to Rules.State(true, "legacy"),
            "b" to Rules.State(true, "rule", left = 10.0, until = 5L),
            "c" to Rules.State(false, "rules"),
        )
        assertEquals(states, Reclaim.lockedStates(false, states))
        val locked = Reclaim.lockedStates(true, states)
        assertEquals(setOf("a", "b", "c"), locked.keys)
        assertTrue(locked.values.all { !it.allowed && it.why == "locked" })
    }
}
