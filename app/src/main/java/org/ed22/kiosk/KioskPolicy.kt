package org.ed22.kiosk

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/** Device-owner lockdown: lock task mode, home screen, status bar, restrictions and permission grants. */
object KioskPolicy {
    private const val TAG = "KioskPolicy"

    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
    )

    val AUTO_GRANT = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
    }

    /** Apps the kiosk may hand off to without leaving lock task mode (the file picker for uploads). */
    private val LOCK_TASK_EXTRAS = listOf(
        "com.google.android.documentsui",
        "com.android.documentsui",
    )

    fun admin(ctx: Context) = ComponentName(ctx, KioskAdminReceiver::class.java)

    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    fun isDeviceOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    fun isLockTaskActive(ctx: Context) =
        ctx.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    /** Applies every device-owner policy. Safe to call repeatedly. */
    fun apply(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        setLockTaskPackages(ctx, DeviceSync.allowedPackages(ctx))
        if (Build.VERSION.SDK_INT >= 28) {
            // Keep the power menu so the device can still be restarted or turned off, and show the
            // system Home button (it returns to the kiosk, which is the home app; in the kiosk it resets
            // to the start page). Back works in lock task mode without a feature flag.
            safely("lock task features") {
                dpm.setLockTaskFeatures(
                    admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                )
            }
        }
        safely("home activity") {
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, home, ComponentName(ctx, MainActivity::class.java))
        }
        safely("keyguard") { dpm.setKeyguardDisabled(admin, true) }
        safely("status bar") { dpm.setStatusBarDisabled(admin, true) }
        RESTRICTIONS.forEach { r -> safely(r) { dpm.addUserRestriction(admin, r) } }
        safely("stay on while plugged in") {
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, stayOnWhilePluggedInValue())
        }
        // Automatic date, time and time zone, so the allowed-apps rules can't be dodged by changing the clock.
        if (Build.VERSION.SDK_INT >= 30) {
            safely("auto time") { dpm.setAutoTimeEnabled(admin, true) }
            safely("auto time zone") { dpm.setAutoTimeZoneEnabled(admin, true) }
        } else {
            @Suppress("DEPRECATION")
            safely("auto time") { dpm.setAutoTimeRequired(admin, true) }
        }
        AUTO_GRANT.forEach { grantPermission(ctx, it) }
    }

    /** Lock task allowlist: this app, [LOCK_TASK_EXTRAS] and the apps the device doc currently allows. */
    fun setLockTaskPackages(ctx: Context, allowed: Collection<String>) {
        if (!isDeviceOwner(ctx)) return
        safely("lock task packages") {
            val packages = (listOf(ctx.packageName) + LOCK_TASK_EXTRAS + allowed).distinct()
            dpm(ctx).setLockTaskPackages(admin(ctx), packages.toTypedArray())
        }
    }

    fun startLockTask(activity: Activity) {
        if (!isDeviceOwner(activity) || Prefs.isMaintenance(activity) || isLockTaskActive(activity)) return
        safely("start lock task") { activity.startLockTask() }
    }

    /** Lets a parent out: stops lock task and undoes the lockdown until the kiosk is reopened or the device reboots. */
    fun release(activity: Activity) {
        Prefs.setMaintenance(activity, true)
        if (isLockTaskActive(activity)) safely("stop lock task") { activity.stopLockTask() }
        if (!isDeviceOwner(activity)) return
        val dpm = dpm(activity)
        val admin = admin(activity)
        safely("home activity") { dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName) }
        safely("status bar") { dpm.setStatusBarDisabled(admin, false) }
        safely("keyguard") { dpm.setKeyguardDisabled(admin, false) }
        RESTRICTIONS.forEach { r -> safely(r) { dpm.clearUserRestriction(admin, r) } }
    }

    /** Fully removes device-owner status so the app can be uninstalled normally. */
    fun removeDeviceOwner(activity: Activity) {
        release(activity)
        if (isDeviceOwner(activity)) {
            safely("clear device owner") { dpm(activity).clearDeviceOwnerApp(activity.packageName) }
        }
    }

    fun grantPermission(ctx: Context, permission: String): Boolean {
        if (!isDeviceOwner(ctx)) return false
        return try {
            dpm(ctx).setPermissionGrantState(
                admin(ctx), ctx.packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not grant $permission", e)
            false
        }
    }

    fun reboot(ctx: Context): Boolean {
        if (!isDeviceOwner(ctx)) return false
        dpm(ctx).reboot(admin(ctx))
        return true
    }

    /** The value [apply] writes for [Settings.Global.STAY_ON_WHILE_PLUGGED_IN] (AC, USB, and wireless). */
    fun stayOnWhilePluggedInValue(): String {
        val all = BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB or
            BatteryManager.BATTERY_PLUGGED_WIRELESS
        return all.toString()
    }

    /**
     * Clears stay-on-while-plugged so [sleepScreen] is not woken again by the charger.
     * Returns the previous value for [restoreStayOnWhilePluggedIn], or null if nothing was changed.
     * The unlocked kiosk calls apply() / restore, so this is not left at 0.
     */
    fun suspendStayOnWhilePluggedIn(ctx: Context): String? {
        if (!isDeviceOwner(ctx)) return null
        val key = Settings.Global.STAY_ON_WHILE_PLUGGED_IN
        val previous = Settings.Global.getString(ctx.contentResolver, key)
        val restore = if (previous.isNullOrEmpty()) stayOnWhilePluggedInValue() else previous
        if (restore == "0") return null
        return try {
            dpm(ctx).setGlobalSetting(admin(ctx), key, "0")
            restore
        } catch (e: Exception) {
            Log.w(TAG, "Could not suspend stay-on-while-plugged", e)
            null
        }
    }

    fun restoreStayOnWhilePluggedIn(ctx: Context, value: String) {
        if (!isDeviceOwner(ctx) || value.isEmpty()) return
        try {
            dpm(ctx).setGlobalSetting(admin(ctx), Settings.Global.STAY_ON_WHILE_PLUGGED_IN, value)
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore stay-on-while-plugged", e)
        }
    }

    /**
     * Turns the screen off. Device owner only; [android.app.admin.DeviceAdminReceiver] already
     * requests force-lock. No lock flags are passed, and the keyguard is not changed, so a wake
     * does not skip the kiosk lock cover or ask for a credential the kiosk disabled.
     * `PowerManager.goToSleep` is not used: that permission is not granted to a device-owner app.
     * Returns false when this process cannot sleep the screen (there is no fake black cover).
     */
    fun sleepScreen(ctx: Context): Boolean {
        if (!isDeviceOwner(ctx)) return false
        return try {
            dpm(ctx).lockNow()
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "lockNow was not allowed", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "lockNow failed", e)
            false
        }
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Policy step failed: $what", e)
        }
    }
}
