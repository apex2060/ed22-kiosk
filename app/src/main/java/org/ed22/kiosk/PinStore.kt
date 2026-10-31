package org.ed22.kiosk

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Stores the parent PIN as a salted PBKDF2 hash, with a lockout after repeated wrong guesses. */
object PinStore {
    enum class Check { OK, WRONG, LOCKED }

    private const val FILE = "kiosk_pin"
    private const val KEY_HASH = "hash"
    private const val KEY_SALT = "salt"
    private const val KEY_FAILURES = "failures"
    private const val KEY_LOCKED_UNTIL = "locked_until"
    private const val ITERATIONS = 120_000
    private const val MAX_FAILURES = 5
    private const val LOCKOUT_MS = 60_000L

    val PIN_REGEX = Regex("^\\d{4,8}$")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hasPin(ctx: Context) = prefs(ctx).contains(KEY_HASH)

    fun setPin(ctx: Context, pin: String) {
        require(PIN_REGEX.matches(pin)) { "PIN must be 4 to 8 digits" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(ctx).edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .putInt(KEY_FAILURES, 0)
            .putLong(KEY_LOCKED_UNTIL, 0)
            .commit()
    }

    fun lockoutRemainingMs(ctx: Context): Long =
        (prefs(ctx).getLong(KEY_LOCKED_UNTIL, 0) - System.currentTimeMillis()).coerceAtLeast(0)

    fun verify(ctx: Context, pin: String): Check {
        if (lockoutRemainingMs(ctx) > 0) return Check.LOCKED
        val p = prefs(ctx)
        val salt = Base64.decode(p.getString(KEY_SALT, null) ?: return Check.WRONG, Base64.NO_WRAP)
        val expected = Base64.decode(p.getString(KEY_HASH, null) ?: return Check.WRONG, Base64.NO_WRAP)
        if (MessageDigest.isEqual(hash(pin, salt), expected)) {
            p.edit().putInt(KEY_FAILURES, 0).apply()
            return Check.OK
        }
        val failures = p.getInt(KEY_FAILURES, 0) + 1
        val editor = p.edit().putInt(KEY_FAILURES, failures)
        if (failures >= MAX_FAILURES) {
            editor.putLong(KEY_LOCKED_UNTIL, System.currentTimeMillis() + LOCKOUT_MS).putInt(KEY_FAILURES, 0)
            editor.apply()
            return Check.LOCKED
        }
        editor.apply()
        return Check.WRONG
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
