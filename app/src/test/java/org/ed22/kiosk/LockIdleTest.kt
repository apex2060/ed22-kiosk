package org.ed22.kiosk

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock-cover idle timer, without a device. Times are milliseconds on an injected clock.
 * 22_000 is the idle limit. Nothing here turns the screen off unless the cover is up,
 * the screen is on, and no parent UI is in front.
 */
class LockIdleTest {
    @Test
    fun idleIsTwentyTwoSeconds() {
        assertEquals(22_000L, LockIdle.IDLE_MS)
    }

    @Test
    fun coverCountsDownThenRequestsARealSleep() {
        val idle = LockIdle()
        val shown = idle.coverShown(0)
        assertEquals(22_000L, shown.nextDelayMs)
        assertFalse(shown.turnScreenOff)
        assertEquals(22_000L, idle.deadline())

        val early = idle.alarm(21_999)
        assertFalse(early.turnScreenOff)
        assertEquals(1L, early.nextDelayMs)

        val due = idle.alarm(22_000)
        assertTrue(due.turnScreenOff)
        assertTrue(idle.sleepPending)
        assertTrue(idle.stayOnSuspended)
        assertFalse(idle.windowShouldStayOn(wanted = true))

        assertFalse(idle.alarm(22_000).turnScreenOff)

        val slept = idle.sleepResult(ok = true)
        assertFalse(slept.turnScreenOff)
        assertFalse(slept.restorePluggedStayOn)
        assertTrue(idle.asleep)
        assertTrue(idle.coverVisible)
        assertFalse(idle.sleepPending)
        assertFalse(idle.windowShouldStayOn(wanted = true))
        assertFalse(idle.alarm(50_000).turnScreenOff)
    }

    @Test
    fun touchResetsTheDeadlineAndDoesNotDismissTheCover() {
        val idle = LockIdle()
        idle.coverShown(0)
        val touch = idle.touch(21_000)
        assertFalse(touch.turnScreenOff)
        assertTrue(idle.coverVisible)
        assertEquals(43_000L, idle.deadline())
        assertEquals(22_000L, touch.nextDelayMs)

        assertFalse(idle.alarm(22_000).turnScreenOff)
        assertEquals(21_000L, idle.alarm(22_000).nextDelayMs)
        assertTrue(idle.alarm(43_000).turnScreenOff)
    }

    @Test
    fun showingTheCoverAgainDoesNotResetTheClock() {
        val idle = LockIdle()
        idle.coverShown(0)
        val again = idle.coverShown(10_000)
        assertEquals(12_000L, again.nextDelayMs)
        assertEquals(22_000L, idle.deadline())
        assertFalse(again.turnScreenOff)
        assertTrue(idle.alarm(22_000).turnScreenOff)
    }

    @Test
    fun unlockedScreenNeverSleepsOrChangesStayOn() {
        val idle = LockIdle()
        assertTrue(idle.windowShouldStayOn(wanted = true))
        assertFalse(idle.windowShouldStayOn(wanted = false))
        listOf(
            idle.touch(1_000),
            idle.alarm(22_000),
            idle.screenOff(),
            idle.screenOn(30_000),
            idle.dialogOpened(),
            idle.dialogClosed(30_000),
            idle.paused(),
            idle.resumed(30_000),
            idle.coverHidden(),
            idle.destroy(),
        ).forEach { effect ->
            assertFalse(effect.turnScreenOff)
            assertFalse(effect.restorePluggedStayOn)
        }
        assertFalse(idle.stayOnSuspended)
        assertNull(idle.deadline())
    }

    @Test
    fun wakeStartsAFreshCountdownAndRefreshesTheClock() {
        val idle = sleeping()
        assertTrue(idle.coverVisible)

        val off = idle.screenOff()
        assertFalse(off.turnScreenOff)
        assertFalse(off.restorePluggedStayOn)
        assertTrue(idle.stayOnSuspended)
        assertTrue(idle.coverVisible)
        assertTrue(idle.asleep)

        val on = idle.screenOn(30_000)
        assertFalse(on.turnScreenOff)
        assertTrue(on.restorePluggedStayOn)
        assertTrue(on.refreshClock)
        assertEquals(22_000L, on.nextDelayMs)
        assertEquals(52_000L, idle.deadline())
        assertFalse(idle.asleep)
        assertTrue(idle.windowShouldStayOn(wanted = false))

        val again = idle.screenOn(35_000)
        assertFalse(again.turnScreenOff)
        assertFalse(again.restorePluggedStayOn)
        assertFalse(again.refreshClock)
        assertEquals(52_000L, idle.deadline())
        assertFalse(idle.alarm(35_000).turnScreenOff)
    }

    @Test
    fun powerButtonWhileAwakeDoesNotSleepAgainUntilTheNextWake() {
        val idle = LockIdle()
        idle.coverShown(0)
        idle.screenOff()
        assertFalse(idle.alarm(22_000).turnScreenOff)
        assertNull(idle.deadline())
        val on = idle.screenOn(40_000)
        assertEquals(22_000L, on.nextDelayMs)
        assertTrue(on.refreshClock)
        assertFalse(on.turnScreenOff)
    }

    @Test
    fun startingWhileTheScreenIsAlreadyOffDoesNotSleepImmediately() {
        val idle = LockIdle()
        idle.noteScreen(interactive = false)
        val shown = idle.coverShown(0)
        assertFalse(shown.turnScreenOff)
        assertNull(shown.nextDelayMs)
        assertTrue(idle.coverVisible)
        val on = idle.screenOn(5_000)
        assertFalse(on.turnScreenOff)
        assertTrue(on.refreshClock)
        assertEquals(22_000L, on.nextDelayMs)
    }

    @Test
    fun parentDialogBlocksSleepAndClosingItStartsANewCountdown() {
        val idle = LockIdle()
        idle.coverShown(0)
        val opened = idle.dialogOpened()
        assertFalse(opened.turnScreenOff)
        assertNull(idle.deadline())
        assertFalse(idle.alarm(22_000).turnScreenOff)
        assertFalse(idle.touch(20_000).turnScreenOff)
        assertNull(idle.deadline())

        val closed = idle.dialogClosed(25_000)
        assertEquals(22_000L, closed.nextDelayMs)
        assertEquals(47_000L, idle.deadline())
        assertFalse(idle.alarm(46_999).turnScreenOff)
        assertTrue(idle.alarm(47_000).turnScreenOff)
    }

    @Test
    fun dialogCancelsAPendingSleepAndRestoresStayOn() {
        val idle = LockIdle()
        idle.coverShown(0)
        assertTrue(idle.alarm(22_000).turnScreenOff)
        val opened = idle.dialogOpened()
        assertFalse(idle.sleepPending)
        assertTrue(opened.restorePluggedStayOn)
        assertFalse(opened.turnScreenOff)
        assertFalse(idle.stayOnSuspended)
        assertFalse(idle.sleepResult(ok = true).turnScreenOff)
        assertFalse(idle.asleep)
    }

    @Test
    fun touchCancelsAPendingSleepWithoutLeavingTheCover() {
        val idle = LockIdle()
        idle.coverShown(0)
        idle.alarm(22_000)
        val touch = idle.touch(22_100)
        assertFalse(idle.sleepPending)
        assertTrue(touch.restorePluggedStayOn)
        assertFalse(touch.turnScreenOff)
        assertTrue(idle.coverVisible)
        assertEquals(44_100L, idle.deadline())
        assertTrue(idle.windowShouldStayOn(wanted = true))
    }

    @Test
    fun parentActivityHoldsTheTimerUntilWeAreInFrontAgain() {
        val idle = LockIdle()
        idle.coverShown(0)
        val paused = idle.paused()
        assertFalse(paused.turnScreenOff)
        assertTrue(idle.held)
        assertNull(idle.deadline())
        assertFalse(idle.alarm(22_000).turnScreenOff)

        idle.screenOff()
        val on = idle.screenOn(30_000)
        assertFalse(on.turnScreenOff)
        assertNull(on.nextDelayMs)
        assertTrue(idle.held)

        val resumed = idle.resumed(31_000)
        assertFalse(resumed.turnScreenOff)
        assertTrue(resumed.refreshClock)
        assertEquals(22_000L, resumed.nextDelayMs)
        assertFalse(idle.held)
    }

    @Test
    fun resumeWhileAlreadyCountingDoesNotResetTheDeadline() {
        val idle = LockIdle()
        idle.coverShown(0)
        val resumed = idle.resumed(5_000)
        assertEquals(17_000L, resumed.nextDelayMs)
        assertEquals(22_000L, idle.deadline())
        assertTrue(resumed.refreshClock)
        assertFalse(resumed.turnScreenOff)
    }

    @Test
    fun pauseCancelsAPendingSleep() {
        val idle = LockIdle()
        idle.coverShown(0)
        idle.alarm(22_000)
        val paused = idle.paused()
        assertFalse(idle.sleepPending)
        assertTrue(idle.held)
        assertTrue(paused.restorePluggedStayOn)
        assertFalse(idle.alarm(22_000).turnScreenOff)
        val resumed = idle.resumed(23_000)
        assertFalse(resumed.turnScreenOff)
        assertEquals(22_000L, resumed.nextDelayMs)
    }

    @Test
    fun unlockAndDestroyRestoreStayOnOnlyIfThisSleepChangedIt() {
        val counting = LockIdle()
        counting.coverShown(0)
        val hidden = counting.coverHidden()
        assertFalse(hidden.restorePluggedStayOn)
        assertFalse(hidden.turnScreenOff)
        assertFalse(counting.coverVisible)
        assertFalse(counting.alarm(22_000).turnScreenOff)

        val sleeping = LockIdle()
        sleeping.coverShown(0)
        sleeping.alarm(22_000)
        val destroyed = sleeping.destroy()
        assertTrue(destroyed.restorePluggedStayOn)
        assertFalse(sleeping.sleepPending)
        assertFalse(sleeping.stayOnSuspended)
        assertEquals(0, sleeping.parentDialogs)
        assertFalse(sleeping.alarm(90_000).turnScreenOff)
        assertTrue(sleeping.windowShouldStayOn(wanted = true))
        assertFalse(sleeping.windowShouldStayOn(wanted = false))
    }

    @Test
    fun failedSleepDoesNotLoopOrPretendTheScreenIsOff() {
        val idle = LockIdle()
        idle.coverShown(0)
        idle.alarm(22_000)
        val failed = idle.sleepResult(ok = false)
        assertTrue(failed.restorePluggedStayOn)
        assertFalse(failed.turnScreenOff)
        assertFalse(idle.asleep)
        assertFalse(idle.sleepPending)
        assertTrue(idle.coverVisible)
        assertTrue(idle.windowShouldStayOn(wanted = false))
        assertFalse(idle.alarm(50_000).turnScreenOff)
        assertFalse(idle.touch(50_000).turnScreenOff)

        idle.coverHidden()
        idle.coverShown(60_000)
        assertTrue(idle.alarm(82_000).turnScreenOff)
    }

    @Test
    fun screenOnWhileADialogIsOpenDoesNotStartTheTimer() {
        val idle = sleeping()
        idle.dialogOpened()
        val on = idle.screenOn(10_000)
        assertFalse(on.turnScreenOff)
        assertNull(on.nextDelayMs)
        assertTrue(on.refreshClock)
        assertEquals(1, idle.parentDialogs)
    }

    @Test
    fun extraDialogCloseDoesNotGoNegativeOrSleepWhenUnlocked() {
        val idle = LockIdle()
        val closed = idle.dialogClosed(0)
        assertEquals(0, idle.parentDialogs)
        assertFalse(closed.turnScreenOff)
        assertNull(closed.nextDelayMs)
    }

    @Test
    fun recreatedInstanceStartsUnregistered() {
        val first = LockIdle()
        first.coverShown(0)
        first.alarm(22_000)
        first.sleepResult(ok = true)
        val second = LockIdle()
        assertFalse(second.coverVisible)
        assertFalse(second.asleep)
        assertFalse(second.sleepPending)
        val shown = second.coverShown(100_000)
        assertEquals(22_000L, shown.nextDelayMs)
        assertFalse(shown.turnScreenOff)
        first.destroy()
        assertFalse(first.alarm(200_000).turnScreenOff)
    }

    @Test
    fun screenBroadcastsMatchThePlatformAndStayWithTheCover() {
        assertEquals(Intent.ACTION_SCREEN_OFF, LockIdle.ACTION_SCREEN_OFF)
        assertEquals(Intent.ACTION_SCREEN_ON, LockIdle.ACTION_SCREEN_ON)
        assertEquals(
            LockScreen.broadcastActions() + listOf(LockIdle.ACTION_SCREEN_OFF, LockIdle.ACTION_SCREEN_ON),
            LockIdle.coverBroadcastActions(),
        )
        assertTrue(LockIdle.isScreenOff(LockIdle.ACTION_SCREEN_OFF))
        assertTrue(LockIdle.isScreenOn(LockIdle.ACTION_SCREEN_ON))
        assertFalse(LockIdle.isScreenOff(LockIdle.ACTION_SCREEN_ON))
        assertFalse(LockIdle.isScreenOn(null))
    }

    private fun sleeping(): LockIdle {
        val idle = LockIdle()
        idle.coverShown(0)
        assertTrue(idle.alarm(22_000).turnScreenOff)
        assertFalse(idle.sleepResult(ok = true).restorePluggedStayOn)
        return idle
    }
}
