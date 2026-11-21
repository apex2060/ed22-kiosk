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
        // First, so anything that fails during start-up is logged too.
        CrashGuard.install(this)
        KioskLog.add(this, "appStart", "Little Explorer ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) started")
        KioskLog.recordPastExits(this)
        NetworkWatch.start(this)
        DeviceSync.init(this)
        KioskWatchdog.schedule(this)
        // Keeps the process out of Android's cached (frozen / killable) state while the child is in another app.
        ReturnService.start(this)
        // Screen off ends the current AppUsage session. If that app is still in front when the screen comes
        // back on, a "resumed" session keeps counting it for the allowed-apps limits.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    // Screen time counts only while the screen is on.
                    ScreenTime.tick(context)
                    if (intent.action == Intent.ACTION_SCREEN_OFF) {
                        // Kept in prefs: the process may be killed while the screen is off.
                        AppUsage.openPkg(context)?.let { AppUsage.setPausedPkg(context, it) }
                        AppUsage.end(context)
                        return
                    }
                    DeviceSync.enforce(context)
                    // Screen back on: make sure the kiosk is still the one in front.
                    KioskWatchdog.checkSoon(context, 2_000)
                    val pkg = AppUsage.takePausedPkg(context) ?: return
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
