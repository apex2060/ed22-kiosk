package org.ed22.kiosk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

/**
 * A ring that fills while something is held. [start] shows it and calls `onDone` once it is full;
 * [stop] cancels and hides it. Timed with the clock, not an animator, so it still works when
 * system animations are turned off.
 */
class HoldRing(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4 * d; color = 0x55FFFFFF
    }
    private val arc = Paint(track).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val oval = RectF()
    private var progress = 0f
    private var startedAt = 0L
    private var durationMs = 1L
    private var onDone: (() -> Unit)? = null

    val isRunning get() = onDone != null

    private val tick = object : Runnable {
        override fun run() {
            val done = onDone ?: return
            progress = ((SystemClock.uptimeMillis() - startedAt).toFloat() / durationMs).coerceAtMost(1f)
            invalidate()
            if (progress >= 1f) {
                stop()
                done()
            } else {
                postOnAnimation(this)
            }
        }
    }

    init {
        visibility = INVISIBLE
    }

    fun start(ms: Long, onDone: () -> Unit) {
        stop()
        startedAt = SystemClock.uptimeMillis()
        durationMs = ms
        this.onDone = onDone
        visibility = VISIBLE
        postOnAnimation(tick)
    }

    fun stop() {
        onDone = null
        removeCallbacks(tick)
        progress = 0f
        visibility = INVISIBLE
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height) / 2f
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, size, bg)
        val r = size - track.strokeWidth * 1.5f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawCircle(cx, cy, r, track)
        canvas.drawArc(oval, -90f, 360f * progress, false, arc)
    }
}
