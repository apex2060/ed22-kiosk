package org.ed22.kiosk

/**
 * Idle sleep for the native lock cover.
 *
 * While the cover is on screen and nobody is touching it, the screen turns off after
 * [IDLE_MS]. That is a real sleep (`DevicePolicyManager.lockNow` from the device owner),
 * not a black view. The power button wakes the device; this app does not enable tap-to-wake.
 * Hardware that already wakes on tap delivers the same screen-on event and returns to this cover.
 *
 * The unlocked kiosk is untouched: the timer runs only while the cover is up, and the
 * "stay on while plugged in" setting is cleared only for that sleep, then put back on wake,
 * unlock, or teardown.
 */
class LockIdle(
    private val idleMs: Long = IDLE_MS,
) {
    var coverVisible: Boolean = false
        private set
    var screenOn: Boolean = true
        private set
    var asleep: Boolean = false
        private set
    var sleepPending: Boolean = false
        private set
    var parentDialogs: Int = 0
        private set
    var held: Boolean = false
        private set
    var stayOnSuspended: Boolean = false
        private set
    var sleepSupported: Boolean = true
        private set

    private var deadline: Long? = null

    /** What the activity should do after a transition. Delays are relative to the `now` just passed in. */
    data class Effect(
        val turnScreenOff: Boolean = false,
        val restorePluggedStayOn: Boolean = false,
        val refreshClock: Boolean = false,
        val nextDelayMs: Long? = null,
    )

    fun deadline(): Long? = deadline

    /**
     * Keep the window awake while the lock clock is actually showing, so the system timeout
     * cannot blank it early. Drop that hold while asleep or about to sleep, or the screen
     * turns straight back on. Unlocked, the page's own preference stands.
     */
    fun windowShouldStayOn(wanted: Boolean): Boolean = when {
        !coverVisible -> wanted
        sleepPending || asleep -> false
        else -> true
    }

    /** Initial screen state, before the cover is shown. Ignored once the cover is up. */
    fun noteScreen(interactive: Boolean) {
        if (coverVisible) return
        screenOn = interactive
        asleep = !interactive
    }

    fun coverShown(now: Long): Effect {
        val first = !coverVisible
        coverVisible = true
        if (!first) return Effect(nextDelayMs = remaining(now))
        sleepSupported = true
        return arm(now)
    }

    fun coverHidden(): Effect {
        val restore = stayOnSuspended
        coverVisible = false
        asleep = false
        sleepPending = false
        held = false
        deadline = null
        stayOnSuspended = false
        return Effect(restorePluggedStayOn = restore)
    }

    fun destroy(): Effect {
        parentDialogs = 0
        return coverHidden()
    }

    fun touch(now: Long): Effect {
        if (!coverVisible || !screenOn || asleep) return Effect(nextDelayMs = remaining(now))
        if (sleepPending) return cancelPendingAndArm(now)
        if (parentDialogs > 0 || held) return Effect()
        return arm(now)
    }

    fun screenOff(): Effect {
        screenOn = false
        asleep = true
        sleepPending = false
        deadline = null
        return Effect()
    }

    fun screenOn(now: Long): Effect {
        val waking = !screenOn || asleep
        screenOn = true
        if (!waking) return Effect(nextDelayMs = remaining(now))
        asleep = false
        val restore = stayOnSuspended
        stayOnSuspended = false
        if (!coverVisible || parentDialogs > 0 || held) {
            return Effect(restorePluggedStayOn = restore, refreshClock = coverVisible)
        }
        return arm(now).copy(restorePluggedStayOn = restore, refreshClock = true)
    }

    fun dialogOpened(): Effect {
        parentDialogs++
        if (sleepPending) return cancelPending()
        deadline = null
        return Effect()
    }

    fun dialogClosed(now: Long): Effect {
        if (parentDialogs > 0) parentDialogs--
        return arm(now)
    }

    /** Our activity is not in front (parent PIN screen, or the screen is still reported on). */
    fun paused(): Effect {
        if (!coverVisible || !screenOn || asleep) return Effect()
        held = true
        if (sleepPending) return cancelPending()
        deadline = null
        return Effect()
    }

    fun resumed(now: Long): Effect {
        if (!held) return Effect(nextDelayMs = remaining(now), refreshClock = coverVisible && screenOn)
        held = false
        return arm(now).copy(refreshClock = coverVisible)
    }

    fun alarm(now: Long): Effect {
        val due = deadline ?: return Effect()
        if (now < due) return Effect(nextDelayMs = due - now)
        deadline = null
        if (!canCount()) return Effect()
        sleepPending = true
        stayOnSuspended = true
        return Effect(turnScreenOff = true)
    }

    fun sleepResult(ok: Boolean): Effect {
        // A screen-off during lockNow, or a cancel, already settled this request.
        if (!sleepPending) return Effect()
        sleepPending = false
        if (ok || !screenOn) {
            asleep = true
            screenOn = false
            deadline = null
            return Effect()
        }
        sleepSupported = false
        asleep = false
        stayOnSuspended = false
        return Effect(restorePluggedStayOn = true)
    }

    private fun cancelPendingAndArm(now: Long): Effect {
        val cancelled = cancelPending()
        return arm(now).copy(restorePluggedStayOn = cancelled.restorePluggedStayOn)
    }

    private fun cancelPending(): Effect {
        sleepPending = false
        val restore = stayOnSuspended
        stayOnSuspended = false
        return Effect(restorePluggedStayOn = restore)
    }

    private fun arm(now: Long): Effect {
        if (!canCount()) {
            deadline = null
            return Effect()
        }
        deadline = now + idleMs
        return Effect(nextDelayMs = idleMs)
    }

    private fun canCount(): Boolean =
        coverVisible && screenOn && !asleep && !sleepPending &&
            parentDialogs == 0 && !held && sleepSupported

    private fun remaining(now: Long): Long? {
        val due = deadline ?: return null
        return (due - now).coerceAtLeast(0)
    }

    companion object {
        const val IDLE_MS = 22_000L

        const val ACTION_SCREEN_OFF = "android.intent.action.SCREEN_OFF"
        const val ACTION_SCREEN_ON = "android.intent.action.SCREEN_ON"

        fun screenActions(): List<String> = listOf(ACTION_SCREEN_OFF, ACTION_SCREEN_ON)

        fun isScreenOff(action: String?) = action == ACTION_SCREEN_OFF

        fun isScreenOn(action: String?) = action == ACTION_SCREEN_ON

        /** Time, battery, and screen on/off. Registered together for the life of the cover. */
        fun coverBroadcastActions(): List<String> = LockScreen.broadcastActions() + screenActions()
    }
}
