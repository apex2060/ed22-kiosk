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
    const val MAX_FAILURES = 5
    private const val KEY_LOCKOUTS = "lockouts"
    const val LOCKOUT_MS = 60_000L
    const val MAX_LOCKOUT_MS = 15 * 60_000L

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
            .putInt(KEY_LOCKOUTS, 0)
            .putLong(KEY_LOCKED_UNTIL, 0)
            .commit()
    }

    fun lockoutRemainingMs(ctx: Context): Long =
        (prefs(ctx).getLong(KEY_LOCKED_UNTIL, 0) - System.currentTimeMillis()).coerceAtLeast(0)

    /**
     * Checks a typed PIN. Never throws: an empty entry, a malformed one, a missing/corrupt stored hash or a crypto
     * error is WRONG (fail closed). Before 2026-11-20 an empty entry crashed here (PBKDF2 "password empty"), the
     * crash restart dropped lock task for 2 s (60 s after three crashes) and the child got out of the kiosk.
     * Wrong entries count toward a lockout that grows with each lockout ([lockoutMs]); only OK resets it.
     */
    fun verify(ctx: Context, pin: String): Check {
        val now = System.currentTimeMillis()
        if (lockoutRemainingMs(ctx) > 0) return Check.LOCKED
        // Nothing typed: stay on the prompt, no hash and no strike (a stray tap is not a guess).
        if (pin.isEmpty()) return Check.WRONG
        val p = prefs(ctx)
        val salt = p.getString(KEY_SALT, null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
        val expected = p.getString(KEY_HASH, null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
        val ok = salt != null && expected != null && matches(pin, salt, expected)
        val s = step(ok, Attempts(p.getInt(KEY_FAILURES, 0), p.getInt(KEY_LOCKOUTS, 0)), now)
        val editor = p.edit().putInt(KEY_FAILURES, s.state.failures).putInt(KEY_LOCKOUTS, s.state.lockouts)
        if (s.lockedUntil > 0) editor.putLong(KEY_LOCKED_UNTIL, s.lockedUntil)
        editor.commit()
        return s.check
    }

    data class Attempts(val failures: Int, val lockouts: Int)
    data class Step(val check: Check, val state: Attempts, val lockedUntil: Long)

    /** Pure: the next attempt state. [MAX_FAILURES] wrong in a row lock the prompt for [lockoutMs] of the lockouts so far. */
    fun step(ok: Boolean, state: Attempts, now: Long): Step {
        if (ok) return Step(Check.OK, Attempts(0, 0), 0)
        val failures = state.failures + 1
        if (failures >= MAX_FAILURES) {
            return Step(Check.LOCKED, Attempts(0, state.lockouts + 1), now + lockoutMs(state.lockouts))
        }
        return Step(Check.WRONG, Attempts(failures, state.lockouts), 0)
    }

    /** 1 min, then 2, 4, 8, capped at 15 min, for the 1st, 2nd, 3rd... lockout since the last correct PIN. */
    fun lockoutMs(previousLockouts: Int): Long =
        (LOCKOUT_MS shl previousLockouts.coerceIn(0, 4)).coerceAtMost(MAX_LOCKOUT_MS)

    /** Pure and exception-free: [pin] is a well-formed PIN whose hash equals [expected]. */
    fun matches(pin: String, salt: ByteArray, expected: ByteArray): Boolean {
        if (!PIN_REGEX.matches(pin)) return false
        val h = try {
            hash(pin, salt)
        } catch (e: Exception) {
            return false
        }
        return MessageDigest.isEqual(h, expected)
    }

    /** For tests and [setPin]: the stored hash of [pin]. */
    internal fun hashFor(pin: String, salt: ByteArray): ByteArray = hash(pin, salt)

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}
