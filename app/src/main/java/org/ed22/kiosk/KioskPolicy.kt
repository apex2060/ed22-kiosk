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
        safely("lock task packages") {
            dpm.setLockTaskPackages(admin, (listOf(ctx.packageName) + LOCK_TASK_EXTRAS).toTypedArray())
        }
        if (Build.VERSION.SDK_INT >= 28) {
            // Keep the power menu so the device can still be restarted or turned off.
            safely("lock task features") {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
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
            val all = BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB or
                BatteryManager.BATTERY_PLUGGED_WIRELESS
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, all.toString())
        }
        AUTO_GRANT.forEach { grantPermission(ctx, it) }
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

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Policy step failed: $what", e)
        }
    }
}
