package org.ed22.kiosk

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** The PIN prompt and parent menu opened by tapping the bottom-left corner. */
object ParentMenu {
    private const val PROMPT_TIMEOUT_MS = 30_000L

    fun prompt(activity: MainActivity) {
        when (parentPromptKind(PinStore.hasPin(activity), PinStore.lockoutRemainingMs(activity))) {
            ParentPinResult.Missing -> {
                toast(activity, "No parent PIN is set.")
                return
            }
            ParentPinResult.Lockout -> {
                val locked = PinStore.lockoutRemainingMs(activity)
                toast(activity, "Too many wrong PINs. Try again in ${(locked + 999) / 1000} seconds.")
                return
            }
            else -> Unit
        }
        val input = EditText(activity).apply {
            inputType = EscapeGuard.PIN_INPUT_TYPE
            hint = "Parent PIN"
            filters = arrayOf(InputFilter.LengthFilter(8))
            EscapeGuard.hardenSecretField(this)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Parent menu")
            .setView(padded(activity, input))
            .setPositiveButton("Unlock", null)
            .setNegativeButton("Cancel", null)
            .create()
        val timeout = Runnable { if (dialog.isShowing) dialog.dismiss() }
        dialog.setOnShowListener {
            input.requestFocus()
            val unlock = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            unlock.setOnClickListener {
                val typed = input.text.toString()
                // Fail closed: anything but a verified PIN keeps the prompt (and the kiosk) where it is.
                val check = runCatching { PinStore.verify(activity, typed) }.getOrDefault(PinStore.Check.WRONG)
                when (pinPromptOutcome(typed, check)) {
                    PinPromptOutcome.Granted -> {
                        dialog.dismiss()
                        showMenu(activity)
                    }
                    PinPromptOutcome.Empty -> input.error = "Enter the parent PIN"
                    PinPromptOutcome.Wrong -> {
                        input.text.clear()
                        input.error = "Wrong PIN"
                    }
                    PinPromptOutcome.Locked -> {
                        input.text.clear()
                        val secs = (PinStore.lockoutRemainingMs(activity) + 999) / 1000
                        dialog.dismiss()
                        toast(activity, "Too many wrong PINs. Try again in $secs seconds.")
                    }
                }
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.present(activity) { input.removeCallbacks(timeout) }
        input.postDelayed(timeout, PROMPT_TIMEOUT_MS)
    }

    private fun showMenu(activity: MainActivity) {
        // Screen time first when the active child has any (the parent is often here because the rest cover is up).
        val sid = Prefs.activeStudentId(activity)
        val time = if (DeviceTime.limited(DeviceSync.policy(activity).optJSONObject("deviceTime"), sid)) DeviceSync.timeState(activity) else null
        val items = listOfNotNull<Pair<String, () -> Unit>>(
            time?.let { (if (it.allowed) "Screen time: 30 more minutes" else "Screen time is up: give 30 more minutes") to { giveMoreTime(activity) } },
            "Exit kiosk mode" to { exit(activity, null, ParentPause.Kind.EXIT) },
            HolVisit.visiting(activity).takeIf { it }?.let {
                "House of Learning: end visit now" to {
                    HolVisit.end(activity, "parent menu")
                    toast(activity, "Visit ended.")
                }
            },
            "House of Learning device: ${if (HolDevice.on(activity)) "On" else "Off"}" to { toggleHol(activity) },
            HolDevice.needsPairing(activity).takeIf { it }?.let {
                "Register this tablet" to { promptPair(activity) }
            },
            "Wi-Fi" to { LockWifiDialog.showParentControls(activity) },
            "Open Wi-Fi settings" to { exit(activity, Intent(Settings.ACTION_WIFI_SETTINGS), ParentPause.Kind.SETTINGS) },
            "Reload start page" to { activity.goHome() },
            "Change PIN" to {
                activity.startActivity(Intent(activity, PinSetupActivity::class.java).putExtra(PinSetupActivity.EXTRA_CHANGE, true))
            },
            ScreenLock.isSecure(activity).takeIf { it }?.let { "Remove phone screen lock" to { removeScreenLock(activity) } },
            "Updates (${BuildConfig.VERSION_NAME})" to { showUpdates(activity) },
            "Account" to { showAccount(activity) },
            "Remove kiosk lock (allow uninstall)" to { confirmRemove(activity) },
        )
        AlertDialog.Builder(activity)
            .setTitle("Parent menu")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Close", null)
            .create()
            .present(activity)
    }

    /** Clears a phone PIN/pattern/password set behind the kiosk (only possible once the reset token is active). */
    private fun removeScreenLock(activity: MainActivity) {
        val r = ScreenLock.clear(activity, "parent menu")
        toast(activity, if (r.ok) "Phone screen lock removed." else "Could not remove the screen lock: ${r.why}")
        DeviceSync.reportStatus(activity)
    }

    /**
     * Local device flag only. On signs out any personal account and leaves this tablet ready to
     * register with a house pairing code. Off leaves the house list until a new code is entered.
     * [HolDevice] prefs survive [DeviceSync.signOut] and visit end.
     */
    private fun toggleHol(activity: MainActivity) {
        if (HolDevice.on(activity)) {
            confirm(activity, "Turn off House of Learning?",
                "This tablet will leave the house list until you register it again.") {
                if (HolVisit.visiting(activity)) HolVisit.end(activity, "parent menu")
                HolDevice.setOn(activity, false)
                toast(activity, "House of Learning is off.")
            }
        } else {
            confirm(activity, "Turn this tablet into a House of Learning device?",
                "Any personal account on this tablet will be signed out. Visitors can use Quick Connect. " +
                    "The house will see the child's name and what they have open, not screenshots.") {
                if (HolVisit.visiting(activity)) HolVisit.end(activity, "parent menu")
                HolDevice.setOn(activity, true)
                toast(activity, "House of Learning is on.")
                promptPair(activity)
            }
        }
    }

    private var pairInFlight = false

    private fun promptPair(activity: MainActivity) {
        if (!HolDevice.on(activity)) return
        if (HolDevice.registered(activity)) {
            toast(activity, "This tablet is already registered.")
            return
        }
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            hint = "8-character code"
            filters = arrayOf(InputFilter.LengthFilter(HolDevicePlan.CODE_LEN))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Register this tablet")
            .setMessage("Enter the 8-character code from the house Devices tab.")
            .setView(padded(activity, input))
            .setPositiveButton("Register", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val go = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            go.setOnClickListener {
                val code = HolDevicePlan.sanitizeCode(input.text.toString())
                if (!HolDevicePlan.codeReady(code)) {
                    input.error = "Enter the 8-character code"
                    return@setOnClickListener
                }
                if (pairInFlight) return@setOnClickListener
                pairInFlight = true
                go.isEnabled = false
                HolDevice.pair(activity, code) { r ->
                    pairInFlight = false
                    if (activity.isFinishing || activity.isDestroyed) return@pair
                    r.fold(
                        onSuccess = {
                            dialog.dismiss()
                            toast(activity, "This tablet is registered to the house.")
                        },
                        onFailure = { e ->
                            go.isEnabled = true
                            input.error = e.message?.takeIf { it.isNotBlank() } ?: "Could not register this tablet."
                        },
                    )
                }
            }
        }
        dialog.present(activity)
    }

    /** Keeps hol.on and the group path. Ends a visit first so a signed-out device is not mid-session. */
    private fun signOutAccount(activity: MainActivity) {
        if (HolVisit.visiting(activity)) HolVisit.end(activity, "parent menu")
        DeviceSync.signOut()
        toast(activity, if (HolDevice.on(activity)) "Signed out. House of Learning stays on." else "Signed out.")
    }

    /** The same 30 minutes as "+30 min" in the device manager, granted here with the PIN (works offline). */
    private fun giveMoreTime(activity: MainActivity) {
        val until = DeviceSync.grantLocalExtra(activity) ?: return toast(activity, "No child is using this tablet.")
        activity.updateLockOverlay()
        val clock = LockScreen.clockWithAmPm(java.time.Instant.ofEpochMilli(until).atZone(java.time.ZoneId.systemDefault()).toLocalTime(),
            !android.text.format.DateFormat.is24HourFormat(activity))
        toast(activity, "30 more minutes, until $clock.")
    }

    private fun showUpdates(activity: MainActivity) {
        AlertDialog.Builder(activity)
            .setTitle("Updates")
            .setMessage(Updater.statusText(activity))
            .setPositiveButton("Check for updates now") { _, _ ->
                Updater.checkNow(activity)
                toast(activity, "Checking for updates. Reopen this screen in a minute to see the result.")
            }
            .setNegativeButton("Close", null)
            .create()
            .present(activity)
    }

    private fun showAccount(activity: MainActivity) {
        val note = DeviceSync.enforcementNote
        val message = "Signed in: ${DeviceSync.uid() ?: "no"}\n" +
            "Device doc: ${DeviceSync.deviceDocId(activity) ?: "—"}\n" +
            "Listener: ${DeviceSync.listenerStatus}\n" +
            "Allowed apps now: ${DeviceSync.allowedPackages(activity).joinToString().ifEmpty { "none" }}" +
            (if (note.isNotEmpty()) "\n$note" else "")
        val builder = AlertDialog.Builder(activity)
            .setTitle("Account")
            .setMessage(message)
            .setNegativeButton("Close", null)
        if (DeviceSync.uid() != null) {
            builder.setPositiveButton("Sign out") { _, _ -> signOutAccount(activity) }
        }
        builder.create().present(activity)
    }

    private fun confirm(activity: MainActivity, title: String, message: String, then: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> then() }
            .setNegativeButton("Cancel", null)
            .create()
            .present(activity)
    }

    private fun confirmRemove(activity: MainActivity) {
        AlertDialog.Builder(activity)
            .setTitle("Remove kiosk lock?")
            .setMessage(
                "This removes the app's device-owner status so it can be uninstalled. " +
                    "To lock the device again you will need to factory reset it and repeat the install steps."
            )
            .setPositiveButton("Remove") { _, _ ->
                KioskPolicy.removeDeviceOwner(activity)
                leave(activity, null)
                toast(activity, "Kiosk lock removed. You can now uninstall Little Explorer.")
            }
            .setNegativeButton("Cancel", null)
            .create()
            .present(activity)
    }

    /**
     * Pauses the kiosk ([ParentPause]) and opens [then] (Exit: the device's other home app) on top. The kiosk activity is
     * NOT finished: removing the kiosk's Home task made Android relaunch Home = Little Explorer at once, and that
     * relaunch ended the pause (the bounce back, build 154 and earlier).
     */
    private fun exit(activity: MainActivity, then: Intent?, kind: ParentPause.Kind) {
        KioskPolicy.release(activity, kind)
        KioskLog.add(activity, "parentPause", "Parent PIN: ${if (kind == ParentPause.Kind.EXIT) "exit kiosk mode" else "settings"} pause" +
            (then?.action?.let { " ($it)" } ?: ""))
        val next = then ?: systemLauncher(activity)
        val opened = next != null && runCatching { activity.startActivity(next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!opened && kind == ParentPause.Kind.SETTINGS) {
            // Nothing opened: don't leave the kiosk unlocked.
            Prefs.endPause(activity)
            KioskPolicy.apply(activity)
            KioskPolicy.startLockTask(activity)
            toast(activity, "Could not open Settings.")
            return
        }
        toast(activity, ParentPause.toast(kind))
    }

    /** Closes the kiosk and opens [then], or the device's normal home screen. */
    private fun leave(activity: MainActivity, then: Intent?) {
        val next = then ?: systemLauncher(activity)
        activity.finishAndRemoveTask()
        if (next != null) {
            try {
                activity.startActivity(next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                // Nothing to open; the system will show its own home screen.
            }
        }
    }

    /** The device's other home app (not Little Explorer), or null. */
    fun systemLauncher(activity: android.app.Activity): Intent? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val other = activity.packageManager
            .queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName != activity.packageName }
            // Skip Settings' FallbackHome (priority -1000, only for the locked-at-boot state): it closes at once,
            // which put the tablet straight back in Little Explorer.
            .filter { it.priority >= 0 }
            .maxByOrNull { it.priority }
            ?: return null
        return home.setClassName(other.activityInfo.packageName, other.activityInfo.name)
    }

    /** Shows a parent dialog and holds the lock-cover sleep timer until it closes. */
    private fun AlertDialog.present(activity: MainActivity, onDismiss: (() -> Unit)? = null) {
        activity.parentDialogShown()
        setOnDismissListener {
            try {
                onDismiss?.invoke()
            } finally {
                activity.parentDialogHidden()
                activity.hideSystemUi()
            }
        }
        try {
            show()
        } catch (e: Exception) {
            activity.parentDialogHidden()
            throw e
        }
    }

    private fun padded(activity: MainActivity, input: EditText): FrameLayout {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        return FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
    }

    private fun toast(activity: MainActivity, text: String) =
        Toast.makeText(activity.applicationContext, text, Toast.LENGTH_LONG).show()
}
