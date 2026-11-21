package org.ed22.kiosk

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keyboard "Manage passwords" escape: autofill off by policy, secret-field flags, and reclaiming foreign screens. */
class EscapeGuardTest {
    private val own = "org.ed22.kiosk"
    private val gms = "com.google.android.gms"

    private fun reclaim(top: String?, allowed: Set<String> = emptySet(), waiting: Boolean = false, owns: Boolean = true) =
        EscapeGuard.shouldReclaim(top, own, allowed, waiting, owns)

    @Test
    fun policyDisallowsAutofill() {
        assertTrue(KioskPolicy.RESTRICTIONS.contains("no_autofill"))
        // The earlier lockdown restrictions are still there.
        for (r in listOf("no_safe_boot", "no_factory_reset", "no_add_user", "no_physical_media")) {
            assertTrue(r, KioskPolicy.RESTRICTIONS.contains(r))
        }
    }

    @Test
    fun passwordManagerInsideTheKioskTaskIsClosed() {
        assertTrue(reclaim(gms))
        assertTrue(reclaim("com.android.chrome"))
        assertTrue(reclaim("com.google.android.googlequicksearchbox"))
    }

    @Test
    fun kioskOwnScreensAreLeftAlone() {
        assertFalse(reclaim(own))
        assertFalse(reclaim(null))
        assertFalse(reclaim(""))
    }

    @Test
    fun pickersAndPermissionPromptsAreLeftAlone() {
        for (p in EscapeGuard.IN_TASK_SYSTEM) assertFalse(p, reclaim(p))
        assertTrue(EscapeGuard.IN_TASK_SYSTEM.contains("com.google.android.documentsui"))
        assertTrue(EscapeGuard.IN_TASK_SYSTEM.contains("com.google.android.permissioncontroller"))
        assertFalse(EscapeGuard.IN_TASK_SYSTEM.contains(gms))
        // Whatever a file picker opens next (Photos, Drive) stays until the picker returns.
        assertFalse(reclaim("com.google.android.apps.photos", waiting = true))
        assertTrue(reclaim("com.google.android.apps.photos", waiting = false))
    }

    @Test
    fun allowedAppsAndParentPauseAreLeftAlone() {
        assertFalse(reclaim("com.duolingo", allowed = setOf("com.duolingo")))
        assertTrue(reclaim(gms, allowed = setOf("com.duolingo")))
        // Paused from the parent menu (or no PIN / not device owner): the kiosk does not own the screen.
        assertFalse(reclaim(gms, owns = false))
    }

    @Test
    fun secretFieldFlags() {
        assertEquals(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, EscapeGuard.SECRET_IME_OPTIONS and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertEquals(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, EscapeGuard.PIN_INPUT_TYPE)
    }
}
