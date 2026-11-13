package org.ed22.kiosk

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Text and broadcast contract for the native lock cover.
 *
 * The clock is the device's local time (the caller passes a local [LocalTime]) as `h:mm` in
 * 12-hour mode and `HH:mm` in 24-hour mode. The date is a line like "Saturday, November 7".
 * Battery text comes from the JSON [DeviceInfo.battery] already returns.
 */
object LockScreen {
    const val LOCKED_MESSAGE = "This device is locked"
    /** Screen time (policy.deviceTime): outside the allowed hours / the daily maximum is used up. */
    const val REST_WINDOW = "Little Explorer is resting"
    const val REST_LIMIT = "That's all the screen time for today"
    const val WARNING_SHOW_MS = 15_000L
    const val CLOCK_TEXT_SP = 64f

    /** Navy fill used by the original lock cover. */
    val backgroundColor: Int = 0xFF1F2A44.toInt()

    const val ACTION_TIME_TICK = "android.intent.action.TIME_TICK"
    const val ACTION_TIME_CHANGED = "android.intent.action.TIME_SET"
    const val ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED"
    const val ACTION_DATE_CHANGED = "android.intent.action.DATE_CHANGED"
    const val ACTION_BATTERY_CHANGED = "android.intent.action.BATTERY_CHANGED"

    /** Time, clock setting, zone, and date. The date line changes with the last of these. */
    val timeActions: List<String> = listOf(
        ACTION_TIME_TICK,
        ACTION_TIME_CHANGED,
        ACTION_TIMEZONE_CHANGED,
        ACTION_DATE_CHANGED,
    )

    fun broadcastActions(): List<String> = timeActions + ACTION_BATTERY_CHANGED

    fun isTimeAction(action: String?) = action != null && action in timeActions

    fun isBatteryAction(action: String?) = action == ACTION_BATTERY_CHANGED

    fun clock(time: LocalTime, hour12: Boolean, locale: Locale = Locale.getDefault()): String {
        val pattern = if (hour12) "h:mm" else "HH:mm"
        return DateTimeFormatter.ofPattern(pattern, locale).format(time)
    }

    /** "3:00 PM" (12-hour) or "15:00" (24-hour). */
    fun clockWithAmPm(time: LocalTime, hour12: Boolean, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern(if (hour12) "h:mm a" else "HH:mm", locale).format(time)

    /** "at 3:00 PM" today, "tomorrow at 8:00 AM", else "on Monday at 7:00 AM"; null when nothing is scheduled. */
    fun whenText(next: Long?, nowMs: Long, zone: ZoneId, hour12: Boolean, locale: Locale = Locale.getDefault()): String? {
        next ?: return null
        val n = Instant.ofEpochMilli(next).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val clock = clockWithAmPm(n.toLocalTime(), hour12, locale)
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, n.toLocalDate())
        return when {
            days <= 0L -> "at $clock"
            days == 1L -> "tomorrow at $clock"
            else -> "on ${DateTimeFormatter.ofPattern("EEEE", locale).format(n)} at $clock"
        }
    }

    /** The cover's message and second line: locked wins; then screen time (window / limit) with when it opens again. */
    fun coverText(locked: Boolean, time: DeviceTime.State?, nowMs: Long, zone: ZoneId, hour12: Boolean, locale: Locale = Locale.getDefault()): Pair<String, String?> {
        if (locked || time == null || time.allowed) return LOCKED_MESSAGE to null
        val w = whenText(time.next, nowMs, zone, hour12, locale)
        return if (time.why == "limit") REST_LIMIT to (w?.let { "See you $it!" } ?: "Ask a parent for more time.")
        else REST_WINDOW to (w?.let { "You can use it again $it." } ?: "Ask a parent for more time.")
    }

    /** "5 minutes of screen time left" / "1 minute of screen time left". */
    fun warningText(minutesLeft: Int): String =
        if (minutesLeft <= 1) "1 minute of screen time left" else "$minutesLeft minutes of screen time left"

    fun date(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("EEEE, MMMM d", locale).format(date)

    /**
     * Percentage, and a charging bolt when [battery] reports `charging` or `full`
     * (both are set by [DeviceInfo.battery]).
     */
    fun batteryLine(battery: JSONObject): String {
        val level = battery.optInt("level", 0).coerceIn(0, 100)
        val bolt = battery.optBoolean("charging") || battery.optBoolean("full")
        return if (bolt) "$level% \u26A1" else "$level%"
    }
}

/**
 * Registers the lock-screen time and battery broadcasts while the overlay is showing.
 * [onShown] registers at most once; [onHidden] unregisters only after a successful registration.
 * A new instance (the activity was recreated) starts unregistered.
 */
class LockScreenListeners(
    private val register: () -> Unit,
    private val unregister: () -> Unit,
) {
    var registered: Boolean = false
        private set

    fun onShown() {
        if (registered) return
        register()
        registered = true
    }

    fun onHidden() {
        if (!registered) return
        unregister()
        registered = false
    }
}
