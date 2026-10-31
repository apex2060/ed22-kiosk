package org.ed22.kiosk

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

object Prefs {
    private const val FILE = "kiosk"
    private const val KEY_MAINTENANCE = "maintenance"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** True after a parent exits kiosk mode; cleared when the kiosk is reopened or the device reboots. */
    fun isMaintenance(ctx: Context) = prefs(ctx).getBoolean(KEY_MAINTENANCE, false)

    fun setMaintenance(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MAINTENANCE, value).apply()
    }

    @SuppressLint("HardwareIds")
    fun deviceId(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
}
