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
