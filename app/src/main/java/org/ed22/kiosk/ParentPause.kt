package org.ed22.kiosk

/**
 * A parent's intentional pause from the PIN menu (pure rules, JVM-tested; stored by [Prefs]).
 *
 * - [Kind.EXIT] "Exit kiosk mode": no reclaim of any kind until the kiosk is re-entered (Little Explorer opened from the
 *   launcher or recents, Home when no other home app exists, or a reboot). No time limit.
 * - [Kind.SETTINGS] "Open Wi-Fi settings", "Screenshots › Open Accessibility settings / App info", and the first-run
 *   screenshots step: no reclaim while the parent is in Settings; it ends when the parent comes back to Little Explorer
 *   (Back, Home, or the accessibility service bringing it back) or after [SETTINGS_MAX_MS], after which the normal
 *   watchdog reclaims the tablet (so a pause a parent forgot can't leave a child in Settings).
 *
 * Bug fixed here (build 154 and earlier): MainActivity.onCreate ended ANY pause. Exiting finished and removed the kiosk
 * task, which is the Home task, so Android immediately relaunched Home = Little Explorer; that new activity's onCreate
 * cleared the pause about a second later and every reclaim path (lock task, backstop, watchdog) came back.
 */
object ParentPause {
    enum class Kind { EXIT, SETTINGS }

    /** A Settings pause ends by itself after this long. */
    const val SETTINGS_MAX_MS = 15 * 60_000L

    /** A Home relaunch this soon after the pause started is Android re-homing, not the parent coming back. */
    const val REHOME_GRACE_MS = 5_000L

    data class State(val kind: Kind, val at: Long)

    /** In force now (a Settings pause expires; a pause dated in the future by a clock change counts from now). */
    fun active(s: State?, now: Long): Boolean = when {
        s == null -> false
        s.kind == Kind.EXIT -> true
        else -> now - s.at < SETTINGS_MAX_MS
    }

    /** What MainActivity does when it is created, gets a new intent, or resumes while a pause may be in force. */
    enum class Entry {
        /** Not paused, or the parent came back: end the pause and lock the kiosk again. */
        END,
        /** Stay paused and show Little Explorer unlocked (Exit with no other home app). */
        KEEP,
        /** Stay paused and get out of the way (Android re-homed right after a Settings pause started). */
        STEP_ASIDE,
        /** Stay paused and open the device's other home app (Home pressed after Exit). */
        FORWARD_HOME,
    }

    /**
     * [isHome] the intent is MAIN + HOME; [isLauncher] MAIN + LAUNCHER (the app icon); [otherHome] another home app
     * exists; [resumeOnly] no new intent (onResume: Back from Settings, recents).
     */
    fun onEntry(s: State?, now: Long, isHome: Boolean, isLauncher: Boolean, otherHome: Boolean, resumeOnly: Boolean = false): Entry {
        if (!active(s, now)) return Entry.END
        s!!
        if (isLauncher) return Entry.END
        val young = now - s.at in 0 until REHOME_GRACE_MS
        return when (s.kind) {
            Kind.EXIT -> when {
                isHome && otherHome -> Entry.FORWARD_HOME
                isHome -> Entry.KEEP // no other home app: Little Explorer is the home screen, still unlocked
                young -> Entry.KEEP  // our own exit just happened
                else -> Entry.END    // opened on purpose later (recents, a link): re-entered
            }
            Kind.SETTINGS -> if (young) {
                if (isHome) Entry.STEP_ASIDE else Entry.KEEP
            } else Entry.END
        }
    }

    /** The toast after starting a pause. */
    fun toast(kind: Kind): String = when (kind) {
        Kind.EXIT -> "Kiosk paused. Open Little Explorer or restart the device to lock it again."
        Kind.SETTINGS -> "Kiosk paused for Settings. Little Explorer locks again when you come back (or in 15 minutes)."
    }
}
