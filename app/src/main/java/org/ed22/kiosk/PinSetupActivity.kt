package org.ed22.kiosk

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** First-run PIN setup, also reused from the parent menu to change the PIN. */
class PinSetupActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_CHANGE = "change"

        /** In front (the kiosk's backstop leaves a parent changing the PIN alone). */
        var showing = false
            private set

        const val SHOT_TITLE = "Screenshots of the whole screen"
        const val SHOT_BODY = "Lets you see this tablet's screen from the device manager, including other apps. " +
            "Tap Turn on, then turn on Little Explorer and tap Allow."

        /** The first-run screen waiting in Settings; closed when the screenshot service brings the kiosk back instead. */
        private var waiting: java.lang.ref.WeakReference<PinSetupActivity>? = null

        /** MainActivity started (e.g. ShotService returned to it): the setup screen under it is done. */
        fun closeSetup() {
            val a = waiting?.get()?.takeIf { !it.isFinishing }
            waiting = null
            if (a == null) return
            KioskLog.add(a, "setup", "Setup: full-screen screenshots turned on")
            a.inSettings = false
            a.finish()
        }
    }

    /** The parent went to Accessibility settings from the screenshots step. */
    private var inSettings = false

    override fun onResume() {
        super.onResume()
        showing = true
        if (inSettings) {
            // Back from Settings (turned on or not): end the Settings pause and start the kiosk.
            inSettings = false
            waiting = null
            Prefs.endPause(this)
            val on = ShotService.enabledInSettings(this)
            KioskLog.add(this, "setup", if (on) "Setup: full-screen screenshots turned on" else "Setup: full-screen screenshots skipped (not turned on in Settings)")
            startKiosk()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("inSettings", inSettings)
    }

    private fun startKiosk() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /**
     * Android 11+ first run: offer to turn on full-screen screenshots (the accessibility service). Not shown when it is
     * already on, or when adb granted WRITE_SECURE_SETTINGS (then it is turned on silently). True = the step is showing.
     */
    private fun offerShotStep(): Boolean {
        if (!ScreenshotPlan.offerSetupStep(android.os.Build.VERSION.SDK_INT, changing, false)) return false
        runCatching { KioskPolicy.allowShotService(this) }
        val enabled = ShotService.enabledInSettings(this)
        val silent = !enabled && ShotService.enableSilently(this)
        if (silent) KioskLog.add(this, "setup", "Setup: full-screen screenshots turned on (adb permission)")
        if (enabled || silent) return false
        findViewById<TextView>(R.id.title).text = SHOT_TITLE
        findViewById<TextView>(R.id.subtitle).text = SHOT_BODY
        listOf(R.id.pin, R.id.confirm, R.id.error, R.id.save, R.id.owner_warning).forEach { findViewById<View>(it).visibility = View.GONE }
        currentFocus?.let { v ->
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
        }
        findViewById<View>(R.id.shot_step).visibility = View.VISIBLE
        // Android 13+ may block turning on an accessibility service for a sideloaded app ("Restricted setting").
        if (android.os.Build.VERSION.SDK_INT >= 33) findViewById<View>(R.id.shot_restricted).visibility = View.VISIBLE
        KioskLog.add(this, "setup", "Setup: full-screen screenshots step shown")
        findViewById<Button>(R.id.shot_skip).setOnClickListener {
            KioskLog.add(this, "setup", "Setup: full-screen screenshots skipped")
            startKiosk()
        }
        findViewById<Button>(R.id.shot_on).setOnClickListener { openShotSettings() }
        return true
    }

    /** [Turn on]: the same Settings page as the parent menu, under the same Settings pause, so nothing reclaims the tablet. */
    private fun openShotSettings() {
        Prefs.startPause(this, ParentPause.Kind.SETTINGS)
        // First run normally has no lock task yet; if one is running (a restart), Settings would be blocked.
        if (KioskPolicy.isLockTaskActive(this)) runCatching { stopLockTask() }
        ShotService.setReturnAfterSetup(this, true)
        val opened = runCatching {
            startActivity(ParentMenu.accessibilityIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!opened) {
            Prefs.endPause(this)
            ShotService.setReturnAfterSetup(this, false)
            Toast.makeText(this, "Could not open Settings.", Toast.LENGTH_SHORT).show()
            KioskLog.add(this, "setup", "Setup: full-screen screenshots skipped (Settings did not open)")
            startKiosk()
            return
        }
        inSettings = true
        waiting = java.lang.ref.WeakReference(this)
        KioskLog.add(this, "setup", "Setup: full-screen screenshots step: opened Accessibility settings")
    }

    override fun onPause() {
        showing = false
        super.onPause()
    }

    private val changing get() = intent.getBooleanExtra(EXTRA_CHANGE, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_setup)
        if (savedInstanceState?.getBoolean("inSettings") == true && PinStore.hasPin(this)) {
            // Rebuilt while the parent was in Settings: onResume finishes the step.
            inSettings = true
            return
        }

        val pin = findViewById<EditText>(R.id.pin)
        val confirm = findViewById<EditText>(R.id.confirm)
        EscapeGuard.hardenSecretField(pin)
        EscapeGuard.hardenSecretField(confirm)
        val error = findViewById<TextView>(R.id.error)
        if (changing) findViewById<TextView>(R.id.title).text = "Change parent PIN"
        if (!KioskPolicy.isDeviceOwner(this)) findViewById<View>(R.id.owner_warning).visibility = View.VISIBLE

        fun save() {
            val a = pin.text.toString()
            val b = confirm.text.toString()
            val problem = when {
                !PinStore.PIN_REGEX.matches(a) -> "The PIN must be 4 to 8 digits."
                a != b -> "The two PINs don't match."
                else -> null
            }
            if (problem != null) {
                error.text = problem
                error.visibility = View.VISIBLE
                return
            }
            PinStore.setPin(this, a)
            Toast.makeText(this, "Parent PIN saved", Toast.LENGTH_SHORT).show()
            if (changing) return finish()
            if (!offerShotStep()) startKiosk()
        }

        findViewById<Button>(R.id.save).setOnClickListener { save() }
        confirm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save()
                true
            } else {
                false
            }
        }
    }
}
