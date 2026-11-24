package org.ed22.kiosk

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/**
 * Full-screen remote screenshots (Android 11+): an accessibility service whose only ability is takeScreenshot
 * (no events, no window content; see res/xml/shot_service.xml). A parent turns it on once in Android Settings ›
 * Accessibility › Little Explorer (parent menu › "Screenshots: turn on full screen"). While it is connected,
 * status.features lists 'screenshotFull'. Capturing shows nothing on the tablet.
 */
class ShotService : AccessibilityService() {
    companion object {
        private const val TAG = "ShotService"
        private const val PREFS = "kiosk_shot"
        /** Set by the parent menu before it opens Settings: bring the kiosk back when the service connects. */
        private const val KEY_RETURN = "returnAfterSetup"

        @Volatile
        private var instance: ShotService? = null

        /** Connected and able to capture the whole screen. */
        val ready: Boolean get() = Build.VERSION.SDK_INT >= ScreenshotPlan.FULL_SCREEN_SDK && instance != null

        fun component(ctx: Context) = ComponentName(ctx, ShotService::class.java)

        /** Our service is in Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES (it may still be connecting). */
        fun enabledInSettings(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = component(ctx)
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /**
         * Only when WRITE_SECURE_SETTINGS was granted once over adb (`pm grant org.ed22.kiosk android.permission.WRITE_SECURE_SETTINGS`):
         * turns the service on without the Settings screen. A device owner cannot grant itself that permission. False = not possible.
         */
        fun enableSilently(ctx: Context): Boolean {
            if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return false
            return runCatching {
                val cr = ctx.contentResolver
                val me = component(ctx).flattenToString()
                val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                if (cur.split(':').none { it.equals(me, true) || ComponentName.unflattenFromString(it) == component(ctx) }) {
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (cur.isBlank()) me else "$cur:$me")
                }
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                true
            }.onFailure { Log.w(TAG, "enableSilently failed", it) }.getOrDefault(false)
        }

        fun setReturnAfterSetup(ctx: Context, value: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RETURN, value).apply()
        }

        /**
         * One screenshot of the default display. [done] gets a software ARGB_8888 bitmap or an error text, on [executor].
         * False when the service is not connected.
         */
        @RequiresApi(30)
        fun capture(executor: Executor, done: (Bitmap?, String?) -> Unit): Boolean {
            val s = instance ?: return false
            return try {
                s.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            if (soft == null) done(null, "Could not read the screenshot") else done(soft, null)
                        } catch (e: Exception) {
                            done(null, "Could not read the screenshot (${e.javaClass.simpleName})")
                        } finally {
                            buffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) = done(null, failure(errorCode))
                })
                true
            } catch (e: Exception) {
                Log.w(TAG, "takeScreenshot failed", e)
                executor.execute { done(null, "Screenshot failed (${e.javaClass.simpleName})") }
                true
            }
        }

        @RequiresApi(30)
        private fun failure(code: Int) = when (code) {
            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Too soon after the last screenshot; try again"
            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "Little Explorer's Accessibility access is off"
            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "No screen to capture"
            else -> "Screenshot failed (error $code)"
        }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        KioskLog.add(this, "screenshot", "Full-screen screenshots on (Accessibility)")
        main.post { runCatching { DeviceSync.reportStatus(applicationContext) } }
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean(KEY_RETURN, false)) {
            // The parent turned it on from the parent menu: back to the kiosk (onCreate ends the pause and relocks).
            p.edit().putBoolean(KEY_RETURN, false).apply()
            main.postDelayed({ DeviceSync.openKiosk(applicationContext) }, 800)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnected()
        super.onDestroy()
    }

    private fun disconnected() {
        if (instance !== this) return
        instance = null
        KioskLog.add(this, "screenshot", "Full-screen screenshots off (Accessibility)")
        val c = applicationContext
        main.post { runCatching { DeviceSync.reportStatus(c) } }
    }
}
