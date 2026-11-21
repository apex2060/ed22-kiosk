package org.ed22.kiosk

/**
 * Pure rules for sending the child back to Little Explorer from another app (no Android calls, so they run in JVM tests).
 *
 * Why this exists: while the child is in an allowed app, the kiosk process is in the background. Android then treats
 * it as a cached app: it can be frozen (no Firestore snapshots, no main-thread timers) or killed for memory, so a
 * parent's lock / "open tool" or a rule that ran out was only seen minutes later, or after the child came back by
 * themselves. [ReturnService] keeps the process in the foreground-service state and runs [reason] every [TICK_MS];
 * [DeviceSync.returnToKiosk] brings the kiosk to the front and checks it worked, trying again with [RETRY_DELAYS_MS].
 */
object Reclaim {
    /** How often the backstop looks while the screen is on and another app is in front. */
    const val TICK_MS = 10_000L

    /** After each try, how long to wait before checking the kiosk is in front (and trying again a stronger way). */
    val RETRY_DELAYS_MS = longArrayOf(1_500L, 4_000L, 10_000L)

    /**
     * [ownsScreen] = device owner, parent PIN set and not paused by a parent; [busyOwnScreen] = one of the kiosk's own
     * screens or prompts is in front (PIN setup, a file picker or permission prompt the page asked for);
     * [coverUp] = policy.locked or the child's screen time is up; [openPkg] = the app the kiosk opened, if any,
     * and [openAllowed] whether it is still allowed now.
     */
    data class Check(
        val ownsScreen: Boolean,
        val screenOn: Boolean,
        val kioskInFront: Boolean,
        val busyOwnScreen: Boolean,
        val coverUp: Boolean,
        val openPkg: String?,
        val openAllowed: Boolean,
    )

    /** Why the kiosk must come back to the front now, or null to leave things alone. */
    fun reason(c: Check): String? = when {
        !c.ownsScreen || !c.screenOn || c.kioskInFront || c.busyOwnScreen -> null
        c.coverUp -> "the device is locked or screen time is up" + (c.openPkg?.let { " (was in $it)" } ?: "")
        c.openPkg != null && !c.openAllowed -> "${c.openPkg} is no longer allowed"
        else -> null
    }

    /** Delay before checking try number [attempt] (0 = the first), or null when there are no tries left. */
    fun retryDelay(attempt: Int): Long? = RETRY_DELAYS_MS.getOrNull(attempt)

    /** How try number [attempt] brings the kiosk forward; later tries use stronger means. */
    enum class Method { REORDER, MOVE_TASK, CLEAR_TOP }

    fun method(attempt: Int): Method = when {
        attempt <= 0 -> Method.REORDER
        attempt == 1 -> Method.MOVE_TASK
        else -> Method.CLEAR_TOP
    }

    /**
     * policy.locked takes every allowed app away (like screen time running out): lock task drops their tasks and
     * the child is sent back to the cover. Returns [states] unchanged when not locked.
     */
    fun <K> lockedStates(locked: Boolean, states: Map<K, Rules.State>): Map<K, Rules.State> =
        if (!locked) states else states.mapValues { Rules.State(false, "locked") }
}
