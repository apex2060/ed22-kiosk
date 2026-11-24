package org.ed22.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ed22.kiosk.ParentPause.Entry
import org.ed22.kiosk.ParentPause.Kind

/** A parent's pause from the PIN menu (Exit kiosk mode, Wi-Fi / Accessibility / App info settings) is respected. */
class ParentPauseTest {
    private val t0 = 1_795_490_000_000L
    private val exit = ParentPause.State(Kind.EXIT, t0)
    private val settings = ParentPause.State(Kind.SETTINGS, t0)

    private fun entry(s: ParentPause.State?, after: Long, home: Boolean = false, launcher: Boolean = false, other: Boolean = true, resume: Boolean = false) =
        ParentPause.onEntry(s, t0 + after, home, launcher, other, resume)

    @Test
    fun theBounceBack_androidRehomingRightAfterThePauseDoesNotEndIt() {
        // Build 154: finishing the kiosk's Home task made Android relaunch Little Explorer ~1 s later and onCreate ended the pause.
        assertEquals(Entry.STEP_ASIDE, entry(settings, 1_000, home = true))
        assertEquals(Entry.FORWARD_HOME, entry(exit, 1_000, home = true))
        assertEquals(Entry.KEEP, entry(exit, 1_000, home = true, other = false))
        assertEquals(Entry.KEEP, entry(settings, 800)) // explicit start / resume within the grace
        assertEquals(Entry.KEEP, entry(exit, 800, resume = true))
    }

    @Test
    fun exitHoldsUntilTheKioskIsReentered() {
        // Home after Exit goes to the device's own home app, however long after.
        assertEquals(Entry.FORWARD_HOME, entry(exit, 3 * 3_600_000L, home = true))
        // No other home app: Little Explorer stays on screen, unlocked.
        assertEquals(Entry.KEEP, entry(exit, 3 * 3_600_000L, home = true, other = false))
        // Opening Little Explorer (icon, or recents / an explicit start later) re-enters.
        assertEquals(Entry.END, entry(exit, 60_000, launcher = true))
        assertEquals(Entry.END, entry(exit, 60_000, resume = true))
        assertEquals(Entry.END, entry(exit, 60_000))
        // Exit never times out.
        assertTrue(ParentPause.active(exit, t0 + 30L * 24 * 3_600_000L))
    }

    @Test
    fun settingsPauseEndsWhenTheParentComesBackOrAfter15Minutes() {
        assertTrue(ParentPause.active(settings, t0 + ParentPause.SETTINGS_MAX_MS - 1))
        assertFalse(ParentPause.active(settings, t0 + ParentPause.SETTINGS_MAX_MS))
        // Back from Settings (resume), Home, the icon, or the accessibility service bringing Little Explorer back.
        assertEquals(Entry.END, entry(settings, 20_000, resume = true))
        assertEquals(Entry.END, entry(settings, 20_000, home = true))
        assertEquals(Entry.STEP_ASIDE, entry(settings, 9_000, home = true))
        assertEquals(Entry.END, entry(settings, 20_000, launcher = true))
        assertEquals(Entry.END, entry(settings, 20_000))
        // Expired: the next entry locks again.
        assertEquals(Entry.END, entry(settings, ParentPause.SETTINGS_MAX_MS + 1, home = true))
    }

    @Test
    fun noPauseMeansNormalKiosk() {
        assertFalse(ParentPause.active(null, t0))
        assertEquals(Entry.END, entry(null, 0, home = true))
        assertEquals(Entry.END, entry(null, 0, resume = true))
    }

    @Test
    fun whilePausedEveryReclaimPathStandsDown() {
        // shouldOwnScreen is false while Prefs.isMaintenance (= ParentPause.active): backstop, watchdog, foreign-screen check.
        val paused = Reclaim.Check(ownsScreen = false, screenOn = true, kioskInFront = false, busyOwnScreen = false,
            coverUp = true, openPkg = null, openAllowed = true)
        assertNull(Reclaim.reason(paused))
        val w = Recovery.Watch(maintenance = true, deviceOwner = true, hasPin = true, screenOn = true, activityAlive = false,
            inForeground = false, lockTaskActive = false, appOpen = false)
        assertEquals(Recovery.WatchAction.NONE, Recovery.watch(w, 5))
        assertFalse(EscapeGuard.shouldReclaim("com.android.settings", "org.ed22.kiosk", emptySet(), false, ownsScreen = false))
        // Child escapes are still reclaimed when not paused.
        assertTrue(EscapeGuard.shouldReclaim("com.android.settings", "org.ed22.kiosk", emptySet(), false, ownsScreen = true))
        assertEquals(Recovery.WatchAction.RELAUNCH, Recovery.watch(w.copy(maintenance = false), 0))
        assertTrue(Reclaim.reason(paused.copy(ownsScreen = true)) != null)
    }

    @Test
    fun toasts() {
        assertTrue(ParentPause.toast(Kind.EXIT).contains("restart"))
        assertTrue(ParentPause.toast(Kind.SETTINGS).contains("15 minutes"))
    }
}
