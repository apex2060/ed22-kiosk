package org.ed22.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings kept out of the kiosk, account/credential restrictions, and the screen-lock reset rules. */
class SettingsLockTest {
    private val own = "org.ed22.kiosk"

    @Test
    fun settingsAndPlayServicesAreAlwaysClosed() {
        for (p in listOf("com.android.settings", "com.google.android.gms", "com.google.android.settings.intelligence")) {
            assertTrue(p, EscapeGuard.shouldReclaim(p, own, emptySet(), waitingOnResult = false, ownsScreen = true))
            // Even while a file picker is open, and even if a parent listed it as an allowed app.
            assertTrue(p, EscapeGuard.shouldReclaim(p, own, setOf(p), waitingOnResult = true, ownsScreen = true))
            // Not while a parent paused the kiosk ("Open Wi-Fi settings" pauses first).
            assertFalse(p, EscapeGuard.shouldReclaim(p, own, emptySet(), waitingOnResult = false, ownsScreen = false))
        }
    }

    @Test
    fun settingsNeverGetLockTask() {
        val allowed = listOf("com.duolingo", "com.android.settings", "com.google.android.gms")
        assertEquals(listOf("com.duolingo"), EscapeGuard.lockTaskSafe(allowed))
        assertTrue(EscapeGuard.isBlocked("com.android.settings"))
        assertFalse(EscapeGuard.isBlocked("com.duolingo"))
        assertFalse(EscapeGuard.isBlocked(null))
        // The file picker is still allowed in the kiosk's task.
        assertFalse(EscapeGuard.isBlocked("com.google.android.documentsui"))
    }

    @Test
    fun accountAndCredentialRestrictions() {
        assertTrue(KioskPolicy.RESTRICTIONS.contains("no_config_credentials"))
        assertTrue(KioskPolicy.RESTRICTIONS.contains("no_modify_accounts"))
        assertTrue(KioskPolicy.RESTRICTIONS.contains("no_autofill"))
        assertFalse("location stays configurable", KioskPolicy.RESTRICTIONS.contains("no_config_location"))
    }

    @Test
    fun resetTokenRules() {
        assertFalse(ScreenLock.needsNewToken(haveStored = true, active = true, secure = true))
        assertFalse(ScreenLock.needsNewToken(haveStored = false, active = true, secure = false))
        assertTrue(ScreenLock.needsNewToken(haveStored = false, active = false, secure = false))
        assertTrue(ScreenLock.needsNewToken(haveStored = false, active = false, secure = true))
        // An inactive token on a locked phone is kept so it activates on the next unlock.
        assertFalse(ScreenLock.needsNewToken(haveStored = true, active = false, secure = true))
        assertTrue(ScreenLock.needsNewToken(haveStored = true, active = false, secure = false))
        assertTrue(ScreenLock.TOKEN_BYTES >= 32)
    }

    @Test
    fun autoClearOnlyWhileKioskActive() {
        assertTrue(ScreenLock.shouldAutoClear(secure = true, kioskOwnsScreen = true))
        assertFalse(ScreenLock.shouldAutoClear(secure = true, kioskOwnsScreen = false))
        assertFalse(ScreenLock.shouldAutoClear(secure = false, kioskOwnsScreen = true))
    }

    @Test
    fun screenLockFeatureListed() {
        assertTrue(RemoteCommands.FEATURES.contains("screenLock"))
        // clearScreenLock is run once per id like reboot (not a timed command).
        assertFalse(RemoteCommands.TIMED_TYPES.contains("clearScreenLock"))
    }
}
