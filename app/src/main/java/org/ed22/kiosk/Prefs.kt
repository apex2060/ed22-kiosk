package org.ed22.kiosk

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

object Prefs {
    private const val FILE = "kiosk"
    private const val KEY_MAINTENANCE = "maintenance"
    private const val KEY_STUDENT = "studentId"
    private const val KEY_DOC_STUDENT = "docStudentId"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** True after a parent exits kiosk mode; cleared when the kiosk is reopened or the device reboots. */
    fun isMaintenance(ctx: Context) = prefs(ctx).getBoolean(KEY_MAINTENANCE, false)

    fun setMaintenance(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MAINTENANCE, value).apply()
    }

    /** The child using the tablet (null = parent mode), from kiosk.setActiveChild or the doc's activeStudentId. */
    fun activeStudentId(ctx: Context): String? = prefs(ctx).getString(KEY_STUDENT, null)

    fun setActiveStudentId(ctx: Context, studentId: String?) {
        prefs(ctx).edit().putString(KEY_STUDENT, studentId).commit()
    }

    /** The doc's activeStudentId last seen ("" = none, null = never seen), so only a change in the doc applies. */
    fun docStudentId(ctx: Context): String? = prefs(ctx).getString(KEY_DOC_STUDENT, null)

    fun setDocStudentId(ctx: Context, studentId: String?) {
        prefs(ctx).edit().putString(KEY_DOC_STUDENT, studentId ?: "").apply()
    }

    @SuppressLint("HardwareIds")
    fun deviceId(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
}
