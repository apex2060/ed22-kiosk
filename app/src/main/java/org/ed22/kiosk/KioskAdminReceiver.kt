package org.ed22.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/** Device admin component, made device owner by QR provisioning or `adb shell dpm set-device-owner`. */
class KioskAdminReceiver : DeviceAdminReceiver() {
    /** Provisioning finished (Android 8+; also sent after the Android 10+ QR flow): lock down and open the kiosk. */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        KioskPolicy.apply(context)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
