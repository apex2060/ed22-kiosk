package org.ed22.kiosk

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import java.security.SecureRandom

/**
 * Phone screen lock (PIN / pattern / password) set behind the kiosk (2026-11-20: children reached Settings through
 * Google Password Manager and set their own PIN).
 *
 * What Android allows a device owner (Android 8-14):
 * - There is no user restriction that stops a screen lock from being set; [KioskPolicy] keeps Settings out of reach
 *   instead (EscapeGuard.ALWAYS_BLOCKED). setKeyguardDisabled(true) does nothing once a secure lock exists.
 * - setResetPasswordToken + resetPasswordWithToken can clear a lock, but only with a token that is ACTIVE. A token set
 *   while the phone has no lock is active at once; one set while a lock exists becomes active only after that lock is
 *   entered once (the next unlock). So a PIN set before this build cannot be cleared remotely until someone unlocks
 *   the phone with it once.
 * - [onPasswordChanged] (DeviceAdminReceiver) clears a new lock right away while the kiosk owns the screen and the
 *   token is active. A parent who wants a phone lock pauses the kiosk first (no auto-clear while paused).
 *
 * The token is random, stays in this app's private storage and is never logged or reported.
 */
object ScreenLock {
    private const val TAG = "ScreenLock"
    private const val PREFS = "screen_lock"
    private const val KEY_TOKEN = "resetToken"
    private const val KEY_LAST_CHANGE = "lastChange"
    private const val KEY_LAST_CLEARED = "lastCleared"
    const val TOKEN_BYTES = 32

    data class Result(val ok: Boolean, val why: String)

    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSecure(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure }.getOrDefault(false)

    fun tokenActive(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !KioskPolicy.isDeviceOwner(ctx)) return false
        return runCatching { dpm(ctx).isResetPasswordTokenActive(KioskPolicy.admin(ctx)) }.getOrDefault(false)
    }

    private fun storedToken(ctx: Context): ByteArray? =
        prefs(ctx).getString(KEY_TOKEN, null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }

    /**
     * Makes sure a reset token is registered. A new token is only registered when none is stored, or when the stored
     * one is inactive and the phone has no lock (then the new one is active at once). An inactive token on a locked
     * phone is kept: it becomes active after the next unlock, and replacing it would restart that wait.
     */
    fun ensureToken(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26 || !KioskPolicy.isDeviceOwner(ctx)) return
        val stored = storedToken(ctx)
        if (!needsNewToken(stored != null, tokenActive(ctx), isSecure(ctx))) return
        val token = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        if (dpm(ctx).setResetPasswordToken(KioskPolicy.admin(ctx), token)) {
            prefs(ctx).edit().putString(KEY_TOKEN, Base64.encodeToString(token, Base64.NO_WRAP)).commit()
            KioskLog.add(ctx, "screenLock", "Screen-lock reset token registered (active=${tokenActive(ctx)})")
        }
    }

    /** Pure rule for [ensureToken]. */
    fun needsNewToken(haveStored: Boolean, active: Boolean, secure: Boolean): Boolean = when {
        active -> false
        !haveStored -> true
        else -> !secure
    }

    /** Removes the phone screen lock with the reset token and turns the keyguard off again. */
    fun clear(ctx: Context, why: String): Result {
        if (Build.VERSION.SDK_INT < 26) return Result(false, "needs Android 8 or newer")
        if (!KioskPolicy.isDeviceOwner(ctx)) return Result(false, "not device owner")
        if (!isSecure(ctx)) return Result(true, "no screen lock set")
        val token = storedToken(ctx) ?: return Result(false, "no reset token yet")
        if (!tokenActive(ctx)) return Result(false, "reset token not active yet: unlock the phone once with its current PIN, then try again")
        return try {
            val ok = dpm(ctx).resetPasswordWithToken(KioskPolicy.admin(ctx), null, token, 0)
            if (ok) {
                runCatching { dpm(ctx).setKeyguardDisabled(KioskPolicy.admin(ctx), true) }
                prefs(ctx).edit().putLong(KEY_LAST_CLEARED, System.currentTimeMillis()).putBoolean(KEY_WAS_SECURE, false).apply()
                KioskLog.add(ctx, "screenLock", "Phone screen lock removed ($why)", important = true)
                Result(true, "")
            } else {
                Result(false, "Android refused the reset")
            }
        } catch (e: Exception) {
            Log.w(TAG, "resetPasswordWithToken failed", e)
            Result(false, e.javaClass.simpleName)
        }
    }

    /** DeviceAdminReceiver.onPasswordChanged: log it and, while the kiosk owns the screen, clear it again. */
    fun onPasswordChanged(ctx: Context) {
        val now = System.currentTimeMillis()
        val secure = isSecure(ctx)
        prefs(ctx).edit().putLong(KEY_LAST_CHANGE, now).putBoolean(KEY_WAS_SECURE, secure).apply()
        val owns = CrashGuard.shouldOwnScreen(ctx)
        KioskLog.add(ctx, "screenLock", "Phone screen lock changed (secure=$secure, kiosk active=$owns)", important = true)
        if (shouldAutoClear(secure, owns)) {
            val r = clear(ctx, "set while the kiosk was active")
            if (!r.ok) KioskLog.add(ctx, "screenLock", "Could not remove it: ${r.why}", important = true)
        }
        runCatching { DeviceSync.reportStatus(ctx) }
    }

    private const val KEY_WAS_SECURE = "wasSecure"

    /**
     * Fallback for phones that don't deliver onPasswordChanged to this admin: called when the kiosk comes to the front.
     * A lock that appeared since the last check is handled like [onPasswordChanged].
     */
    fun checkChanged(ctx: Context) {
        val secure = isSecure(ctx)
        val was = prefs(ctx).getBoolean(KEY_WAS_SECURE, false)
        prefs(ctx).edit().putBoolean(KEY_WAS_SECURE, secure).apply()
        if (secure && !was) onPasswordChanged(ctx)
    }

    /** Pure rule: a new lock is undone only while the kiosk owns the screen (not when a parent paused it). */
    fun shouldAutoClear(secure: Boolean, kioskOwnsScreen: Boolean): Boolean = secure && kioskOwnsScreen

    /** status.screenLock (no token material). */
    fun status(ctx: Context): Map<String, Any> = mapOf(
        "secure" to isSecure(ctx),
        "tokenActive" to tokenActive(ctx),
        "lastChange" to prefs(ctx).getLong(KEY_LAST_CHANGE, 0L),
        "lastCleared" to prefs(ctx).getLong(KEY_LAST_CLEARED, 0L),
    )
}
