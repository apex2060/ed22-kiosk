package org.ed22.kiosk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Keeps Little Explorer able to answer while the child is in another app: a quiet foreground service (type
 * systemExempted, allowed for a device owner) so Android does not freeze or kill the kiosk process in the background,
 * and a backstop check every [Reclaim.TICK_MS] while the screen is on that sends the child back when the device is
 * locked, screen time is up, or the open app is no longer allowed (see [DeviceSync.backstop]).
 */
class ReturnService : Service() {
    companion object {
        private const val TAG = "ReturnService"
        private const val CHANNEL = "kiosk_guard"
        private const val NOTIFICATION_ID = 4101

        /** Starts the service when this app is the device owner. Safe to call often. */
        fun start(ctx: Context) {
            val app = ctx.applicationContext
            if (!KioskPolicy.isDeviceOwner(app)) return
            try {
                ContextCompat.startForegroundService(app, Intent(app, ReturnService::class.java))
            } catch (e: Exception) {
                // Android 12+ may refuse a background start; the next start (activity, boot, alarm) tries again.
                Log.w(TAG, "Could not start the return service", e)
                KioskLog.add(app, "returnService", "Could not start the background guard: ${e.javaClass.simpleName}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            runCatching { DeviceSync.backstop(this@ReturnService) }.onFailure { e -> Log.w(TAG, "backstop failed", e) }
            handler.postDelayed(this, Reclaim.TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Little Explorer", NotificationManager.IMPORTANCE_MIN).apply {
                        setShowBadge(false)
                        description = "Keeps Little Explorer in charge while an allowed app is open"
                    }
                )
            }
            val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
                .setSmallIcon(R.drawable.ic_stat_kiosk)
                .setContentTitle("Little Explorer is on")
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            KioskLog.add(this, "returnService", "Background guard could not run in the foreground: ${e.javaClass.simpleName}: ${e.message}")
            // Still run the backstop while the process lives.
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, Reclaim.TICK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }
}
