package org.ed22.kiosk

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

class KioskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceSync.init(this)
        // Screen off ends the current AppUsage session. If that app is still in front when the screen comes
        // back on, a "resumed" session keeps counting it for the allowed-apps limits.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                private var pausedPkg: String? = null
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) {
                        pausedPkg = AppUsage.openPkg(context) ?: pausedPkg
                        AppUsage.end(context)
                        return
                    }
                    val pkg = pausedPkg ?: return
                    pausedPkg = null
                    if (!MainActivity.inForeground && !Prefs.isMaintenance(context)) {
                        AppUsage.start(context, pkg, resumed = true)
                        DeviceSync.enforce(context)
                    }
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
}
