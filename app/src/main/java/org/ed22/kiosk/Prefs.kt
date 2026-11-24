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

    private const val KEY_PAUSE_KIND = "pauseKind"
    private const val KEY_PAUSE_AT = "pauseAt"

    /**
     * A parent's pause from the PIN menu is in force ([ParentPause.active]): every reclaim / return path checks this.
     * Ended when the parent comes back ([ParentPause.onEntry]), by a reboot, or (Settings pauses) after 15 minutes.
     */
    fun isMaintenance(ctx: Context) = ParentPause.active(pause(ctx), System.currentTimeMillis())

    /** The stored pause, or null. A pause saved by a build before the kinds existed counts as Exit. */
    fun pause(ctx: Context): ParentPause.State? {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_MAINTENANCE, false)) return null
        val kind = runCatching { ParentPause.Kind.valueOf(p.getString(KEY_PAUSE_KIND, null) ?: "EXIT") }.getOrDefault(ParentPause.Kind.EXIT)
        return ParentPause.State(kind, p.getLong(KEY_PAUSE_AT, 0L))
    }

    /** Starts a pause (synchronously saved, so a process restart right after still sees it). */
    fun startPause(ctx: Context, kind: ParentPause.Kind) {
        prefs(ctx).edit().putBoolean(KEY_MAINTENANCE, true).putString(KEY_PAUSE_KIND, kind.name)
            .putLong(KEY_PAUSE_AT, System.currentTimeMillis()).commit()
    }

    fun endPause(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_MAINTENANCE, false).remove(KEY_PAUSE_KIND).remove(KEY_PAUSE_AT).commit()
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
