package org.ed22.kiosk

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import kotlin.math.abs

/**
 * Hold-to-refresh: with the page scrolled to the top, swipe down at least [KioskConfig.REFRESH_PULL_DP]
 * and hold for [KioskConfig.REFRESH_HOLD_MS]; [ring] fills meanwhile and the page reloads when it is full.
 * Lifting the finger, a second finger, or moving back up cancels. Touches are only watched, never
 * consumed, so scrolling and pages that use touch keep working.
 */
class HoldToRefresh(
    private val webView: WebView,
    private val ring: HoldRing,
    private val onRefresh: () -> Unit,
) : View.OnTouchListener {
    private val pullPx = KioskConfig.REFRESH_PULL_DP * webView.resources.displayMetrics.density
    private var startX = 0f
    private var startY = 0f
    private var armed = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.x
                startY = e.y
                armed = !webView.canScrollVertically(-1)
            }
            MotionEvent.ACTION_MOVE -> if (armed) {
                val dy = e.y - startY
                val downward = dy > 2 * abs(e.x - startX)
                when {
                    webView.canScrollVertically(-1) -> cancel()
                    !ring.isRunning && dy >= pullPx && downward -> ring.start(KioskConfig.REFRESH_HOLD_MS) {
                        armed = false
                        onRefresh()
                    }
                    ring.isRunning && (dy < pullPx / 2 || !downward) -> ring.stop()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancel()
        }
        return false
    }

    private fun cancel() {
        armed = false
        ring.stop()
    }
}
