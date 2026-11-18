package org.ed22.kiosk

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Ring my device, natively (policy.command type 'ring' / 'ringStop', see DeviceSync.runCommand). Works while the device is
 * locked or resting and while the page is paused behind the lock cover: alarm-usage tones (AudioTrack, STREAM_ALARM at max),
 * vibration, a screen wake lock and a full-screen "Ringing... Tap to stop" overlay on the kiosk activity. Ends on ringStop,
 * on any touch ([onTouch]), or after [RingPlan.TOTAL_MS]; the alarm volume is put back. Main thread only.
 */
object RingDevice {
    private const val TAG = "RingDevice"
    private val handler = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var wake: PowerManager.WakeLock? = null
    private var overlay: View? = null
    private var prevAlarm: Int? = null
    private var ctx: Context? = null
    private val timeout = Runnable { stop("timeout") }

    @Volatile var active = false
        private set
    private var firstTouchGrace = 0L

    /** Starts (or restarts) the ring. Returns null when it rings, else why it could not (the `why` of status.remote). */
    fun start(c: Context): String? {
        stop("restart", restoreOnly = true)
        val app = c.applicationContext
        ctx = app
        val am = app.getSystemService(AudioManager::class.java) ?: return "no audio"
        // The volume first: a refused change (Do Not Disturb policy) is not fatal, the tones still play at the current level.
        try {
            prevAlarm = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "alarm volume not allowed (Do Not Disturb?)", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "alarm volume failed", e)
        }
        var ok = false
        var why = "no sound"
        try {
            val pcm = RingPlan.pcm()
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RingPlan.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(pcm, 0, pcm.size)
            t.setVolume(1f)
            t.play()
            track = t
            ok = true
        } catch (e: Exception) {
            Log.w(TAG, "tones failed", e)
            why = "no sound: ${e.javaClass.simpleName}"
        }
        wakeScreen(app)
        vibrate(app)
        active = true
        firstTouchGrace = System.currentTimeMillis() + 800  // the touch that woke it must not stop it at once
        showOverlay()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, RingPlan.TOTAL_MS + 300)
        if (!ok) { stop("no sound"); return why }
        return null
    }

    /** Stops tones, vibration, wake lock and overlay, and restores the alarm volume. Safe to call when not ringing. */
    fun stop(why: String = "stop", restoreOnly: Boolean = false) {
        handler.removeCallbacks(timeout)
        runCatching { track?.stop(); track?.release() }
        track = null
        runCatching { ctx?.getSystemService(Vibrator::class.java)?.cancel() }
        runCatching { if (wake?.isHeld == true) wake?.release() }
        wake = null
        runCatching { overlay?.let { (it.parent as? ViewGroup)?.removeView(it) } }
        overlay = null
        val was = prevAlarm
        prevAlarm = null
        if (was != null) {
            try {
                ctx?.getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_ALARM, was, 0)
            } catch (e: RuntimeException) {
                Log.w(TAG, "alarm volume not restored", e)
            }
        }
        if (active && !restoreOnly) Log.i(TAG, "Ring ended: $why")
        active = false
    }

    /** MainActivity.dispatchTouchEvent: a touch stops the ring (after a short grace). True = the ring was stopped. */
    fun onTouch(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!active || nowMs < firstTouchGrace) return false
        stop("tap")
        return true
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(c: Context) {
        try {
            val pm = c.getSystemService(PowerManager::class.java)
            wake = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "ed22kiosk:ring"
            ).apply { acquire(RingPlan.TOTAL_MS + 2_000) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "wake lock failed", e)
        }
        MainActivity.current?.let { a ->
            runCatching { a.setShowWhenLocked(true); a.setTurnScreenOn(true) }
            if (!MainActivity.inForeground) DeviceSync.openKiosk(c)
        } ?: DeviceSync.openKiosk(c)
    }

    private fun vibrate(c: Context) {
        runCatching {
            val v = c.getSystemService(Vibrator::class.java)
            if (v?.hasVibrator() == true) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400, 600), 0))
            }
        }
    }

    /** The kiosk activity came to the front while a ring is going: show the overlay there. */
    fun attach() { if (active && overlay == null) showOverlay() }

    private fun showOverlay() {
        val a = MainActivity.current ?: return
        runCatching {
            val tv = TextView(a).apply {
                text = "Ringing\u2026\nTap to stop"
                setTextColor(Color.WHITE); textSize = 34f; typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor("#D32F2F"))
                isClickable = true
            }
            a.addContentView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            overlay = tv
        }
    }
}
