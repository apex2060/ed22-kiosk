package org.ed22.kiosk

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
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
