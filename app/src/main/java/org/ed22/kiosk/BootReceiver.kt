package org.ed22.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // A reboot always puts the device back into kiosk mode.
            Intent.ACTION_BOOT_COMPLETED -> Prefs.setMaintenance(context, false)
            // After a self-update, reopen the kiosk unless a parent had paused it.
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Updater.setStatus(context, "Updated to ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                if (Prefs.isMaintenance(context)) return
            }
            else -> return
        }
        if (KioskPolicy.isDeviceOwner(context)) {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
