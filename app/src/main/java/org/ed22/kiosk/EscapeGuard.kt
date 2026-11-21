package org.ed22.kiosk

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText

/**
 * Keyboard/autofill escape hatch (2026-11-20). A password field (the parent PIN dialog, a login page in the
 * WebView) made the keyboard offer saved passwords, and its "Manage passwords" entry opened Google Password
 * Manager. Autofill starts that screen with the kiosk activity's startIntentSenderForResult, so it opens inside
 * the kiosk's own lock task, which lock task mode allows even though Google Play services is not allowlisted,
 * and from there a web page could be reached.
 *
 * Defenses: [KioskPolicy] sets DISALLOW_AUTOFILL, password fields and the WebViews opt out of autofill, and
 * [shouldReclaim] decides when a foreign screen on top of the kiosk's task is closed again.
 */
object EscapeGuard {
    /**
     * System screens the kiosk itself opens in its task and waits on: the file picker, the app chooser
     * ("android"), and runtime permission prompts.
     */
    val IN_TASK_SYSTEM: Set<String> = setOf(
        "android",
        "com.google.android.documentsui",
        "com.android.documentsui",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
    )

    /**
     * Android Settings, Google settings and Google Play services (Password Manager, Google account screens): never
     * kept open over the kiosk, never in the lock task allowlist and never opened as an allowed app, even when a
     * parent lists one, because from there a child can set a phone screen lock or change accounts (2026-11-20).
     * The parent menu's own Wi-Fi panel is inside the kiosk and is not affected; "Open Wi-Fi settings" pauses the
     * kiosk first.
     */
    val ALWAYS_BLOCKED: Set<String> = setOf(
        "com.android.settings",
        "com.google.android.settings",
        "com.android.settings.intelligence",
        "com.google.android.settings.intelligence",
        "com.google.android.gms",
        "com.google.android.apps.wellbeing",
        "com.samsung.android.app.settings.bixby",
        "com.android.credentialmanager",
    )

    /** [allowed] without the [ALWAYS_BLOCKED] packages (what lock task and launch() may use). */
    fun lockTaskSafe(allowed: Collection<String>): List<String> = allowed.filter { it !in ALWAYS_BLOCKED }

    fun isBlocked(pkg: String?): Boolean = pkg != null && pkg in ALWAYS_BLOCKED

    /**
     * True when the activity on top of the kiosk's own task belongs to another app that the kiosk did not open
     * and is not waiting on, so the kiosk should close it and come back to the front.
     * [waitingOnResult]: a file chooser or permission request the kiosk started is still open; whatever the
     * picker opens next (Photos, Drive) is left alone until it returns.
     */
    fun shouldReclaim(
        topPackage: String?,
        ownPackage: String,
        allowedApps: Collection<String>,
        waitingOnResult: Boolean,
        ownsScreen: Boolean,
    ): Boolean {
        if (!ownsScreen) return false
        if (topPackage.isNullOrEmpty() || topPackage == ownPackage) return false
        // Settings and Play services are closed even while a picker is open or if a parent allowed them.
        if (topPackage in ALWAYS_BLOCKED) return true
        if (waitingOnResult) return false
        if (topPackage in IN_TASK_SYSTEM) return false
        return topPackage !in allowedApps
    }

    /** IME flags for secret fields: no personalized learning, so the keyboard keeps nothing it can suggest later. */
    const val SECRET_IME_OPTIONS = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI

    /** Numeric PIN input type (digits, masked). */
    const val PIN_INPUT_TYPE = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD

    /** A PIN or password field: never autofilled, never saved, no personalized keyboard suggestions. */
    fun hardenSecretField(field: EditText) {
        field.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        field.imeOptions = field.imeOptions or SECRET_IME_OPTIONS
    }

    /** Pages in the kiosk (and its popups) get no autofill, saved form data or saved passwords. */
    @Suppress("DEPRECATION")
    fun hardenWebView(view: WebView) {
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        view.settings.saveFormData = false
        view.settings.savePassword = false
    }
}
