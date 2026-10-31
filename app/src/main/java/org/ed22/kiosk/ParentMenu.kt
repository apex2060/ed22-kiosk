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
        val locked = PinStore.lockoutRemainingMs(activity)
        if (locked > 0) {
            toast(activity, "Too many wrong PINs. Try again in ${(locked + 999) / 1000} seconds.")
            return
        }
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Parent PIN"
            filters = arrayOf(InputFilter.LengthFilter(8))
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
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (PinStore.verify(activity, input.text.toString())) {
                    PinStore.Check.OK -> {
                        dialog.dismiss()
                        showMenu(activity)
                    }
                    PinStore.Check.WRONG -> {
                        input.text.clear()
                        input.error = "Wrong PIN"
                    }
                    PinStore.Check.LOCKED -> {
                        dialog.dismiss()
                        toast(activity, "Too many wrong PINs. Try again in a minute.")
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            input.removeCallbacks(timeout)
            activity.hideSystemUi()
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.postDelayed(timeout, PROMPT_TIMEOUT_MS)
    }

    private fun showMenu(activity: MainActivity) {
        val items = arrayOf(
            "Exit kiosk mode",
            "Open Wi-Fi settings",
            "Reload start page",
            "Change PIN",
            "Updates (${BuildConfig.VERSION_NAME})",
            "Remove kiosk lock (allow uninstall)",
        )
        AlertDialog.Builder(activity)
            .setTitle("Parent menu")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> exit(activity, null)
                    1 -> exit(activity, Intent(Settings.ACTION_WIFI_SETTINGS))
                    2 -> activity.goHome()
                    3 -> activity.startActivity(
                        Intent(activity, PinSetupActivity::class.java).putExtra(PinSetupActivity.EXTRA_CHANGE, true)
                    )
                    4 -> showUpdates(activity)
                    5 -> confirmRemove(activity)
                }
            }
            .setNegativeButton("Close", null)
            .setOnDismissListener { activity.hideSystemUi() }
            .show()
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
            .setOnDismissListener { activity.hideSystemUi() }
            .show()
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
                toast(activity, "Kiosk lock removed. You can now uninstall ED22 Kiosk.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exit(activity: MainActivity, then: Intent?) {
        KioskPolicy.release(activity)
        leave(activity, then)
        toast(activity, "Kiosk paused. Open ED22 Kiosk or restart the device to lock it again.")
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
