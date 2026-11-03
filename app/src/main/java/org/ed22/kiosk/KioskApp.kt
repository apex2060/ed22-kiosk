package org.ed22.kiosk

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat

class KioskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceSync.init(this)
        // The Back/Home tray overlay shows while no kiosk activity is started (another app is in front).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                started++
                KioskTray.hideOverlay(this@KioskApp)
            }
            override fun onActivityStopped(activity: Activity) {
                started = maxOf(0, started - 1)
                if (started == 0) KioskTray.showOverlay(this@KioskApp)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        // Screen off ends the current AppUsage session.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = AppUsage.end(context)
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
}
