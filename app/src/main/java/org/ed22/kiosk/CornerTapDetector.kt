package org.ed22.kiosk

import android.os.SystemClock

/** Fires [onTriggered] after the required number of taps land in the bottom-left corner within the time window. */
class CornerTapDetector(
    private val sizePx: Float,
    private val onTriggered: () -> Unit,
) {
    private val taps = ArrayDeque<Long>()

    fun onDown(x: Float, y: Float, viewHeight: Int) {
        if (x > sizePx || y < viewHeight - sizePx) {
            taps.clear()
            return
        }
        val now = SystemClock.uptimeMillis()
        taps.addLast(now)
        while (taps.isNotEmpty() && now - taps.first() > KioskConfig.CORNER_TAP_WINDOW_MS) taps.removeFirst()
        if (taps.size >= KioskConfig.CORNER_TAPS) {
            taps.clear()
            onTriggered()
        }
    }
}
