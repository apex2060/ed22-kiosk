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
            "Exit kiosk mode" to { exit(activity, null) },
            "Wi-Fi" to { LockWifiDialog.showParentControls(activity) },
            "Open Wi-Fi settings" to { exit(activity, Intent(Settings.ACTION_WIFI_SETTINGS)) },
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
            builder.setPositiveButton("Sign out") { _, _ ->
                DeviceSync.signOut()
                toast(activity, "Signed out")
            }
        }
        builder.create().present(activity)
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

    private fun exit(activity: MainActivity, then: Intent?) {
        KioskPolicy.release(activity)
        leave(activity, then)
        toast(activity, "Kiosk paused. Open Little Explorer or restart the device to lock it again.")
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

    private fun systemLauncher(activity: MainActivity): Intent? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val other = activity.packageManager
            .queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName != activity.packageName }
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
