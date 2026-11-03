package org.ed22.kiosk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.roundToInt

/**
 * The Back / Home tray along the bottom of the screen. MainActivity draws it inside the kiosk
 * ([TrayView] in its layout). While another app is in front (no kiosk activity started), KioskApp
 * shows the same tray as a "display over other apps" overlay, if that is allowed.
 */
object KioskTray {
    private const val TAG = "KioskTray"
    const val HEIGHT_DP = 48
    const val HOME_HOLD_MS = 2000L

    private var overlay: View? = null
    /** The grant is tried (and its failure logged) once per process. */
    private var triedGrant = false

    fun heightPx(ctx: Context) = (HEIGHT_DP * ctx.resources.displayMetrics.density).roundToInt()

    /** Kiosk mode as KioskPolicy defines it: device owner and not released through the parent menu. */
    fun isKioskMode(ctx: Context) = KioskPolicy.isDeviceOwner(ctx) && !Prefs.isMaintenance(ctx)

    fun overlayAllowed(ctx: Context) = Settings.canDrawOverlays(ctx)

    /** Shows the overlay tray over other apps (kiosk mode only). Back, Home and Home-hold all return to the kiosk. */
    fun showOverlay(ctx: Context) {
        val app = ctx.applicationContext
        if (overlay != null || !isKioskMode(app)) return
        if (!overlayAllowed(app)) {
            if (triedGrant) return
            triedGrant = true
            if (!KioskPolicy.grantOverlay(app)) {
                Log.w(TAG, "\"Display over other apps\" is not granted (device-owner grant did not apply), so the " +
                    "tray only shows inside the kiosk. Grant it with: adb shell appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
                return
            }
        }
        val view = TrayView(
            app,
            onBack = { DeviceSync.openKiosk(app) },
            onHome = { DeviceSync.openKiosk(app) },
            onHomeHold = { DeviceSync.openKiosk(app, goHome = true) },
        )
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, heightPx(app),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        try {
            app.getSystemService(WindowManager::class.java).addView(view, lp)
            overlay = view
        } catch (e: Exception) {
            Log.w(TAG, "Could not add the tray overlay", e)
        }
    }

    fun hideOverlay(ctx: Context) {
        val view = overlay ?: return
        overlay = null
        runCatching { ctx.applicationContext.getSystemService(WindowManager::class.java).removeViewImmediate(view) }
    }
}

/** Slim dark bar: Back on the left, Home in the center (tap, or hold [KioskTray.HOME_HOLD_MS] while a ring fills). */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TrayView(
    ctx: Context,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onHomeHold: () -> Unit,
) : FrameLayout(ctx) {
    init {
        val d = resources.displayMetrics.density
        setBackgroundColor(0xB3000000.toInt())
        isClickable = true // touches on the bar never fall through to the page

        val back = icon(R.drawable.ic_tray_back, "Back").apply { setOnClickListener { onBack() } }
        addView(back, LayoutParams((96 * d).toInt(), LayoutParams.MATCH_PARENT, Gravity.START))

        val home = FrameLayout(ctx)
        val ring = HoldRing(ctx, withBackground = false)
        home.addView(ring, LayoutParams((44 * d).toInt(), (44 * d).toInt(), Gravity.CENTER))
        home.addView(icon(R.drawable.ic_tray_home, "Home"), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        home.setOnTouchListener { v, e ->
            val inside = e.x >= 0 && e.y >= 0 && e.x <= v.width && e.y <= v.height
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> ring.start(KioskTray.HOME_HOLD_MS, onHomeHold)
                MotionEvent.ACTION_MOVE -> if (!inside) ring.stop()
                MotionEvent.ACTION_UP -> if (ring.isRunning) {
                    ring.stop()
                    if (inside) onHome()
                }
                MotionEvent.ACTION_CANCEL -> ring.stop()
            }
            true
        }
        addView(home, LayoutParams((120 * d).toInt(), LayoutParams.MATCH_PARENT, Gravity.CENTER))
    }

    private fun icon(res: Int, label: String) = ImageView(context).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = label
    }
}
