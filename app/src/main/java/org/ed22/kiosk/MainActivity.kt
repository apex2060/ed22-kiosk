package org.ed22.kiosk

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.text.format.DateFormat
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.time.ZonedDateTime
import java.util.Locale

class MainActivity : AppCompatActivity() {
    companion object {
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        /** The live kiosk activity, if any (used by device-doc commands). */
        var current: MainActivity? = null
            private set
        var inForeground = false
            private set

        const val OFFLINE_URL = "file:///android_asset/offline.html"

        /** Renderer deaths (wall clock ms) in this process, for the rebuild backoff. */
        private var rendererDeaths: List<Long> = emptyList()
    }

    // --- offline recovery state (main WebView only) ---
    /** The offline page is showing. */
    private var showingOffline = false
    /** The last main-frame load failed. */
    private var lastLoadFailed = false
    /** A request failed while the current page was still loading, so its scripts or data may be missing. */
    private var brokenLoad = false
    private var pageLoading = false
    private var pageFinishedAt = 0L
    private var lastRecoveryAt = -Recovery.RELOAD_GAP_MS
    /** When onStop logged "left the screen" (elapsed ms), so onStart can log how long it was away. */
    private var stopLoggedAt = 0L
    private val networkBackListener: () -> Unit = { onNetworkBack() }

    lateinit var webView: WebView
        private set
    private lateinit var root: FrameLayout
    private lateinit var bridge: KioskBridge
    private lateinit var cornerTaps: CornerTapDetector
    private var hasFocus = false
    private val handler = Handler(Looper.getMainLooper())
    private var lockOverlay: View? = null
    private var lockClock: android.widget.TextView? = null
    private var lockDate: android.widget.TextView? = null
    private var lockBattery: android.widget.TextView? = null
    private var lockWifi: android.widget.TextView? = null
    private var lockWifiCallback: ConnectivityManager.NetworkCallback? = null
    private val lockScreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (lockOverlay == null || isDestroyed) return
            when {
                // Screen off leaves the cover and its clock/battery/Wi-Fi receivers in place.
                LockIdle.isScreenOff(intent?.action) -> applyLockIdle(lockIdle.screenOff())
                LockIdle.isScreenOn(intent?.action) -> applyLockIdle(lockIdle.screenOn(SystemClock.uptimeMillis()))
                LockScreen.isBatteryAction(intent?.action) -> refreshLockBattery()
                LockScreen.isTimeAction(intent?.action) -> refreshLockClock()
                LockWifi.isWifiAction(intent?.action) -> refreshLockWifi()
            }
        }
    }
    private val lockIdle = LockIdle()
    private var keepScreenOnWanted = true
    private var stayOnToRestore: String? = null
    private val lockIdleRunnable = Runnable {
        if (isDestroyed || lockOverlay == null) return@Runnable
        applyLockIdle(lockIdle.alarm(SystemClock.uptimeMillis()))
    }
    private val lockSleepRunnable = Runnable {
        if (isDestroyed || !lockIdle.sleepPending) return@Runnable
        android.util.Log.i("MainActivity", "Lock cover idle: turning the screen off")
        val ok = KioskPolicy.sleepScreen(this)
        if (!ok) android.util.Log.w("MainActivity", "Lock cover idle: this device cannot turn the screen off")
        applyLockIdle(lockIdle.sleepResult(ok))
    }
    private val lockListeners = LockScreenListeners(
        register = {
            val filter = IntentFilter()
            LockWifi.overlayBroadcasts().forEach { filter.addAction(it) }
            ContextCompat.registerReceiver(this, lockScreenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refreshLockWifiOnMain()
                override fun onLost(network: Network) = refreshLockWifiOnMain()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refreshLockWifiOnMain()
            }
            try {
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, callback)
                lockWifiCallback = callback
            } catch (e: RuntimeException) {
                android.util.Log.w("MainActivity", "Wi-Fi status listener not available")
            }
        },
        unregister = {
            try {
                unregisterReceiver(lockScreenReceiver)
            } catch (e: IllegalArgumentException) {
                // Already unregistered (unlock raced with destruction).
            }
            lockWifiCallback?.let { callback ->
                try {
                    getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
                } catch (e: IllegalArgumentException) {
                    // Already unregistered.
                }
                lockWifiCallback = null
            }
        },
    )
    private val policyListener: (org.json.JSONObject) -> Unit = { updateLockOverlay(); refreshAppsButton() }

    private var appsButton: android.widget.TextView? = null

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var popup: WebView? = null
    private var popupFrame: FrameLayout? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var lastVisit: String? = null

    private val permissionQueue = ArrayDeque<Pair<Array<String>, (Map<String, Boolean>) -> Unit>>()
    private var permissionInFlight = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val (perms, callback) = permissionQueue.removeFirst()
            permissionInFlight = false
            callback(perms.associateWith { p -> isGranted(p) })
            nextPermissionRequest()
        }

    private val fileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
            fileCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!PinStore.hasPin(this)) {
            startActivity(Intent(this, PinSetupActivity::class.java))
            finish()
            return
        }
        Prefs.setMaintenance(this, false)
        KioskPolicy.apply(this)

        root = FrameLayout(this)
        webView = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        current = this
        DeviceSync.addListener(policyListener)
        updateLockOverlay()
        // Native Apps button removed (2026-11-02): /mobile shows its own apps icon. addAppsButton() kept unused.

        cornerTaps = CornerTapDetector(KioskConfig.CORNER_SIZE_DP * resources.displayMetrics.density) {
            ParentMenu.prompt(this)
        }
        configureWebView()
        bridge = KioskBridge(this)
        bridge.install(webView)

        onBackPressedDispatcher.addCallback(this) {
            when {
                customView != null -> hideCustomView()
                popup != null -> popup?.let { if (it.canGoBack()) it.goBack() else closePopup() }
                webView.canGoBack() -> webView.goBack()
            }
        }

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(KioskConfig.START_URL)
        }
        HistoryReporter.flush(this)
        Updater.schedule(this)
        NetworkWatch.listeners += networkBackListener
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // System Home while the kiosk is already in front resets it to the start page. Home pressed in
        // another app only brings the kiosk back (it is paused/unfocused then, and the intent carries
        // FLAG_ACTIVITY_BROUGHT_TO_FRONT), so the child continues where they left off.
        val isHome = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)
        val alreadyInFront = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ||
            (hasFocus && (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0)
        if (isHome && alreadyInFront && ::webView.isInitialized) goHome()
    }

    override fun onResume() {
        super.onResume()
        AppUsage.end(this)
        if (!::webView.isInitialized) return
        inForeground = true
        hideSystemUi()
        // Timers on the offline page may have been throttled while we were in the background.
        if (showingOffline && NetworkWatch.online) onNetworkBack()
        KioskPolicy.startLockTask(this)
        webView.onResume()
        bridge.onResume()
        refreshAppsButton()
        if (lockOverlay != null) {
            val now = SystemClock.uptimeMillis()
            if (isScreenInteractive() && (!lockIdle.screenOn || lockIdle.asleep)) {
                applyLockIdle(lockIdle.screenOn(now))
            }
            if (lockIdle.held) applyLockIdle(lockIdle.resumed(now))
            else {
                refreshLockClock()
                refreshLockBattery()
            }
        }
    }

    // --- allowed apps launcher ---

    private data class LaunchableApp(val pkg: String, val label: String, val until: Long?)

    /** Installed, launchable apps the policy allows right now (same rules as DeviceSync.launch). */
    private fun allowedLaunchable(): List<LaunchableApp> {
        val list = DeviceSync.appList(this, withIcons = false)
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val pkg = o.optString("package")
            if (!o.optBoolean("allowed") || packageManager.getLaunchIntentForPackage(pkg) == null) return@mapNotNull null
            LaunchableApp(pkg, o.optString("label"), (o.opt("until") as? Number)?.toLong())
        }
    }

    private fun addAppsButton() {
        val d = resources.displayMetrics.density
        val btn = android.widget.TextView(this).apply {
            text = "\u25A6  Apps"
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 100 * d
                setColor(0x99000000.toInt())
            }
            elevation = 6 * d
            visibility = View.GONE
            setOnClickListener { showAppsDialog() }
        }
        // Kept clear of the 80 dp parent-menu corner and the popup close button (top-right).
        root.addView(btn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.START).apply {
            leftMargin = (16 * d).toInt(); bottomMargin = (96 * d).toInt()
        })
        appsButton = btn
        refreshAppsButton()
    }

    /** True once the current main-WebView page called kiosk.apps.list() (it draws its own apps UI). */
    private var pageHasAppsUi = false

    fun setPageHasAppsUi(value: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { setPageHasAppsUi(value) }; return }
        if (pageHasAppsUi == value) return
        pageHasAppsUi = value
        refreshAppsButton()
    }

    fun refreshAppsButton() {
        if (!::root.isInitialized) return
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { refreshAppsButton() }; return }
        val btn = appsButton ?: return
        btn.visibility = if (!pageHasAppsUi && runCatching { allowedLaunchable() }.getOrDefault(emptyList()).isNotEmpty()) View.VISIBLE else View.GONE
        btn.bringToFront()
    }

    private fun showAppsDialog() {
        val apps = allowedLaunchable()
        refreshAppsButton()
        if (apps.isEmpty()) return
        val d = resources.displayMetrics.density
        val fmt = android.text.format.DateFormat.getTimeFormat(this)
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val list = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        }
        apps.forEach { app ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
                isClickable = true
                setOnClickListener {
                    dialog?.dismiss()
                    runCatching { DeviceSync.launch(this@MainActivity, app.pkg) }
                        .onFailure { e -> android.widget.Toast.makeText(this@MainActivity, e.message, android.widget.Toast.LENGTH_SHORT).show(); refreshAppsButton() }
                }
            }
            val icon = android.widget.ImageView(this).apply {
                runCatching { setImageDrawable(packageManager.getApplicationIcon(app.pkg)) }
            }
            row.addView(icon, android.widget.LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()))
            val label = android.widget.TextView(this).apply {
                text = app.label + (app.until?.let { "\nuntil " + fmt.format(java.util.Date(it)) } ?: "")
                textSize = 18f
                setPadding((16 * d).toInt(), 0, 0, 0)
            }
            row.addView(label, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row)
        }
        dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Apps")
            .setView(android.widget.ScrollView(this).apply { addView(list) })
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener { hideSystemUi() }
        dialog.show()
    }

    override fun onPause() {
        // Another screen (parent PIN, settings) is in front. Don't sleep behind it.
        // lockNow also pauses us while the screen is still reported interactive; that pending
        // sleep must not be cancelled, or the screen would stay on.
        if (lockOverlay != null && isScreenInteractive() && !lockIdle.sleepPending) {
            applyLockIdle(lockIdle.paused())
        }
        inForeground = false
        if (::webView.isInitialized) {
            webView.onPause()
            bridge.onPause()
        }
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        if (stopLoggedAt != 0L) {
            KioskLog.add(this, "kioskBack", "Little Explorer is back in front after ${(SystemClock.elapsedRealtime() - stopLoggedAt) / 1000}s")
            stopLoggedAt = 0L
        }
    }

    override fun onStop() {
        super.onStop()
        // Left the screen while the kiosk should own it: screen on, no allowed app opened, not paused by a parent,
        // and lock task off. That is the "dropped to the home screen" case (pickers and prompts keep lock task on).
        if (!::webView.isInitialized || isChangingConfigurations || !isScreenInteractive()) return
        if (!CrashGuard.shouldOwnScreen(this) || AppUsage.openPkg(this) != null || KioskPolicy.isLockTaskActive(this)) return
        stopLoggedAt = SystemClock.elapsedRealtime()
        KioskLog.add(this, "kioskStopped", "Little Explorer left the screen with lock task off and no allowed app open" +
            (if (isFinishing) " (finishing)" else ""), important = true)
        KioskWatchdog.checkSoon(this, 5_000)
    }

    @Suppress("DEPRECATION") // the levels still arrive on every Android version we support
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_RUNNING_CRITICAL || level == TRIM_MEMORY_COMPLETE) {
            KioskLog.add(this, "lowMemory", "Android is low on memory (trim level $level, ${if (inForeground) "in front" else "in background"})")
        }
    }

    override fun onDestroy() {
        NetworkWatch.listeners -= networkBackListener
        // recreate() (renderer rebuild) and rotation set isChangingConfigurations; a parent's Exit sets maintenance first.
        if (::webView.isInitialized && !isChangingConfigurations && CrashGuard.shouldOwnScreen(this)) {
            val why = "kiosk activity destroyed" + (if (isFinishing) " (finishing)" else " (by Android)")
            KioskLog.add(this, "kioskDestroyed", why, important = true)
            KioskLog.exit(this, why)
            KioskWatchdog.checkSoon(applicationContext, 3_000)
        }
        if (current === this) current = null
        DeviceSync.removeListener(policyListener)
        removeLockOverlay()
        closePopup()
        if (::webView.isInitialized) {
            bridge.destroy()
            root.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        this.hasFocus = hasFocus
        if (hasFocus) hideSystemUi()
        if (::bridge.isInitialized) bridge.onFocusChanged(hasFocus)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Watch for the parent gesture without stealing the touch from the page.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ::cornerTaps.isInitialized) {
            cornerTaps.onDown(ev.x, ev.y, window.decorView.height)
        }
        if (lockOverlay != null) applyLockIdle(lockIdle.touch(SystemClock.uptimeMillis()))
        return super.dispatchTouchEvent(ev)
    }

    fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Full-screen lock cover while the device doc's policy.locked is true: local time, date, battery,
     * then "This device is locked", on the same navy background. It swallows touches, but the
     * parent-menu corner taps are seen first in [dispatchTouchEvent], so the PIN still works.
     * Time and battery broadcasts stay registered for the life of the cover, including while the
     * screen is off, and the clock is refreshed when the screen comes back.
     * After 5 seconds with no touches the screen actually turns off. The power button wakes it
     * back to this cover. Tap-to-wake is not turned on by the app. The Wi-Fi row opens a status
     * box. Reconnect, when the radio is not on a network, pauses that timer until it finishes.
     * Parent Menu asks for the existing PIN.
     */
    fun updateLockOverlay() {
        if (!::root.isInitialized) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { if (!isDestroyed) updateLockOverlay() }
            return
        }
        if (isDestroyed || !DeviceSync.policy(this).optBoolean("locked", false)) {
            removeLockOverlay()
            return
        }
        showLockOverlay()
    }

    private fun showLockOverlay() {
        val first = lockOverlay == null
        if (first) {
            lockIdle.noteScreen(isScreenInteractive())
            val d = resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            val clock = android.widget.TextView(this).apply {
                textSize = LockScreen.CLOCK_TEXT_SP
                setTextColor(android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
                includeFontPadding = false
                typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            }
            val date = android.widget.TextView(this).apply {
                textSize = 20f
                setTextColor(android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
            }
            val battery = android.widget.TextView(this).apply {
                textSize = 20f
                setTextColor(android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
            }
            val message = android.widget.TextView(this).apply {
                text = LockScreen.LOCKED_MESSAGE
                textSize = 28f
                setTextColor(android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
            }
            val wifi = android.widget.TextView(this).apply {
                textSize = 18f
                setTextColor(android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
                isClickable = true
                isFocusable = true
                val pad = dp(14)
                setPadding(pad, dp(8), pad, dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 100f * d
                    setColor(0x33FFFFFF)
                }
                contentDescription = "Wi-Fi"
                setOnClickListener { LockWifiDialog.show(this@MainActivity) }
            }
            val column = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                addView(clock, android.widget.LinearLayout.LayoutParams(MATCH, WRAP))
                addView(date, android.widget.LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
                addView(battery, android.widget.LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
                addView(wifi, android.widget.LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(16) })
                addView(message, android.widget.LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(28) })
            }
            val overlay = android.widget.FrameLayout(this).apply {
                setBackgroundColor(LockScreen.backgroundColor)
                isClickable = true
                isFocusable = true
                // Swallow touches so the page underneath cannot be used. Corner taps are counted
                // earlier, in dispatchTouchEvent, and are not affected by this.
                setOnTouchListener { _, _ -> true }
                addView(column, FrameLayout.LayoutParams(MATCH, WRAP, android.view.Gravity.CENTER).apply {
                    val side = dp(24)
                    leftMargin = side
                    rightMargin = side
                })
            }
            lockClock = clock
            lockDate = date
            lockBattery = battery
            lockWifi = wifi
            lockOverlay = overlay
            root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        refreshLockClock()
        refreshLockBattery()
        refreshLockWifi()
        lockListeners.onShown()
        lockOverlay?.bringToFront()
        applyLockIdle(lockIdle.coverShown(SystemClock.uptimeMillis()))
    }

    private fun removeLockOverlay() {
        LockWifiDialog.dismiss()
        applyLockIdle(lockIdle.coverHidden())
        try {
            lockListeners.onHidden()
        } catch (e: Exception) {
            // Stay registered so a later removal can retry. Still take the cover down.
            android.util.Log.w("MainActivity", "lock screen unregister", e)
        }
        val overlay = lockOverlay ?: return
        lockOverlay = null
        lockClock = null
        lockDate = null
        lockBattery = null
        lockWifi = null
        if (::root.isInitialized && overlay.parent === root) root.removeView(overlay)
        syncKeepScreenOn()
    }

    /** Device-local clock and date. 12-hour devices use h:mm; 24-hour devices use HH:mm. */
    private fun refreshLockClock() {
        val now = ZonedDateTime.now()
        val locale = Locale.getDefault()
        lockClock?.text = LockScreen.clock(now.toLocalTime(), !DateFormat.is24HourFormat(this), locale)
        lockDate?.text = LockScreen.date(now.toLocalDate(), locale)
    }

    private fun refreshLockBattery() {
        val line = LockScreen.batteryLine(DeviceInfo.battery(this))
        if (lockBattery?.text?.toString() != line) lockBattery?.text = line
    }

    private fun refreshLockWifiOnMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { refreshLockWifiOnMain() }
            return
        }
        if (isDestroyed || lockOverlay == null) return
        refreshLockWifi()
    }

    private fun refreshLockWifi() {
        val label = indicatorOf(WifiControl.snapshot(this)).label()
        if (lockWifi?.text?.toString() != label) lockWifi?.text = label
    }

    /** Page preference from kiosk.setKeepScreenOn. The lock cover overrides it only while that cover is up. */
    fun setKeepScreenOnWanted(on: Boolean): Boolean {
        keepScreenOnWanted = on
        syncKeepScreenOn()
        return on
    }

    fun parentDialogShown() {
        if (isDestroyed) return
        applyLockIdle(lockIdle.dialogOpened())
    }

    fun parentDialogHidden() {
        if (isDestroyed) return
        applyLockIdle(lockIdle.dialogClosed(SystemClock.uptimeMillis()))
    }

    private fun isScreenInteractive(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive

    private fun applyLockIdle(effect: LockIdle.Effect) {
        if (effect.turnScreenOff && stayOnToRestore == null) {
            stayOnToRestore = KioskPolicy.suspendStayOnWhilePluggedIn(this)
        }
        if (effect.restorePluggedStayOn) restoreStayOn()
        if (effect.refreshClock && lockOverlay != null) {
            refreshLockClock()
            refreshLockBattery()
            refreshLockWifi()
        }
        syncKeepScreenOn()
        handler.removeCallbacks(lockIdleRunnable)
        if (!effect.turnScreenOff) handler.removeCallbacks(lockSleepRunnable)
        if (effect.turnScreenOff && !isDestroyed) handler.post(lockSleepRunnable)
        val delay = effect.nextDelayMs
        if (delay != null && !isDestroyed && lockOverlay != null) handler.postDelayed(lockIdleRunnable, delay)
    }

    private fun syncKeepScreenOn() {
        if (isDestroyed) return
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (lockIdle.windowShouldStayOn(keepScreenOnWanted)) window.addFlags(flag) else window.clearFlags(flag)
    }

    private fun restoreStayOn() {
        val value = stayOnToRestore ?: return
        stayOnToRestore = null
        KioskPolicy.restoreStayOnWhilePluggedIn(this, value)
    }

    /** The internet is back ([NetworkWatch]): retry the offline page's target, or reload a page that loaded broken. */
    private fun onNetworkBack() {
        if (isDestroyed || !::webView.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        when (Recovery.onNetworkBack(showingOffline, lastLoadFailed, brokenLoad, now - lastRecoveryAt)) {
            Recovery.PageAction.NONE -> Unit
            Recovery.PageAction.RETRY_TARGET -> {
                lastRecoveryAt = now
                val target = Recovery.offlineTarget(webView.url, KioskConfig.START_URL)
                KioskLog.add(this, "pageRecover", "Internet back: leaving the offline page for $target")
                webView.loadUrl(target)
            }
            Recovery.PageAction.RELOAD -> {
                lastRecoveryAt = now
                KioskLog.add(this, "pageRecover", "Internet back: reloading ${webView.url} (it ${if (lastLoadFailed) "failed" else "loaded with errors"} while offline)")
                webView.reload()
            }
        }
    }

    fun goHome() {
        if (customView != null) hideCustomView()
        closePopup()
        webView.loadUrl(KioskConfig.START_URL)
    }

    fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Makes sure the app holds [permissions]. As device owner they are granted silently; otherwise
     * Android's own permission prompt is shown. [callback] gets the final state of each permission.
     */
    fun requestAndroidPermissions(permissions: Array<String>, callback: (Map<String, Boolean>) -> Unit) {
        permissions.filterNot(::isGranted).forEach { KioskPolicy.grantPermission(this, it) }
        if (permissions.all(::isGranted)) {
            callback(permissions.associateWith { true })
            return
        }
        permissionQueue.addLast(permissions to callback)
        nextPermissionRequest()
    }

    private fun nextPermissionRequest() {
        if (permissionInFlight) return
        val next = permissionQueue.firstOrNull() ?: return
        permissionInFlight = true
        permissionLauncher.launch(next.first)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        applySettings(webView)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = KioskWebViewClient()
        webView.webChromeClient = KioskChromeClient()
    }

    /** Settings shared by the main WebView and popup windows. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun applySettings(view: WebView) {
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            // Popups (e.g. Firebase signInWithPopup) open in an in-app overlay; see onCreateWindow.
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Google refuses OAuth sign-in in WebViews it can detect ("disallowed_useragent"), so drop the
            // WebView markers ("; wv" and "Version/4.0") from the user agent.
            userAgentString = WebSettings.getDefaultUserAgent(this@MainActivity)
                .replace("; wv)", ")")
                .replace(Regex(" Version/[\\d.]+"), "") + " ED22Kiosk/${BuildConfig.VERSION_NAME}"
        }
        // Also stop sending the "X-Requested-With: org.ed22.kiosk" header, which reveals the app.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(view.settings, emptySet())
        }
    }

    /** Same rules for the main page and popups: web links stay in the app, everything else is blocked. */
    private fun blockNavigation(view: WebView, uri: Uri): Boolean = when (uri.scheme?.lowercase()) {
        "http", "https", "about", "data", "blob", "javascript" -> false
        "intent" -> {
            // Open the web fallback of an app link instead of leaving the kiosk.
            val fallback = try {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
            } catch (e: Exception) {
                null
            }
            if (fallback != null && fallback.startsWith("http")) view.loadUrl(fallback)
            true
        }
        // tel:, mailto:, market:, file: and other links would leave the kiosk or read local files.
        else -> true
    }

    /**
     * Shows a page's window.open() popup full-screen over the kiosk. It is a real child window (so
     * window.opener and postMessage work, which Firebase popup sign-in needs), with no kiosk bridge,
     * no device permissions and no file picker. It closes on window.close(), Back, or the Close button.
     */
    private fun openPopup(resultMsg: android.os.Message): Boolean {
        closePopup()
        val child = WebView(this)
        applySettings(child)
        CookieManager.getInstance().setAcceptThirdPartyCookies(child, true)
        child.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                blockNavigation(view, request.url)

            // All WebViews share one renderer. If any of them returns false here, Android kills the whole app,
            // so the popup handles it too (the main WebView rebuilds itself).
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                KioskLog.add(this@MainActivity, "rendererGone", "Popup renderer gone (crashed=${detail.didCrash()})")
                handler.post { if (popup === view) closePopup() }
                return true
            }
        }
        child.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) {
                handler.post { if (popup === window) closePopup() }
            }
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
                callback.invoke(origin, false, false)
        }
        val frame = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.WHITE) }
        frame.addView(child, FrameLayout.LayoutParams(MATCH, MATCH))
        val close = android.widget.TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            setOnClickListener { closePopup() }
        }
        frame.addView(close, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.END))
        root.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))
        lockOverlay?.bringToFront()
        popup = child
        popupFrame = frame
        (resultMsg.obj as WebView.WebViewTransport).webView = child
        resultMsg.sendToTarget()
        return true
    }

    private fun closePopup() {
        val child = popup ?: return
        popup = null
        popupFrame?.let { root.removeView(it) }
        popupFrame = null
        child.stopLoading()
        child.destroy()
        hideSystemUi()
    }

    private fun onVisit(url: String) {
        if (url == lastVisit || !(url.startsWith("https://") || url.startsWith("http://"))) return
        val referrer = lastVisit
        lastVisit = url
        // Give the page a moment to set its title before reporting.
        handler.postDelayed({
            if (isDestroyed) return@postDelayed
            val title = if (webView.url == url) webView.title else null
            HistoryReporter.report(this, url, title, referrer)
        }, 1500)
    }

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        lockOverlay?.bringToFront()
        webView.visibility = View.GONE
    }

    private fun hideCustomView() {
        customView?.let { root.removeView(it) }
        customView = null
        webView.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    private inner class KioskWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            blockNavigation(view, request.url)

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            bridge.onNavigation(view, url)
            if (view === webView) {
                setPageHasAppsUi(false)
                showingOffline = url.startsWith(OFFLINE_URL)
                if (!showingOffline) {
                    lastLoadFailed = false
                    brokenLoad = false
                    pageLoading = true
                }
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            bridge.onPageFinished(view, url)
            if (view === webView && !url.startsWith(OFFLINE_URL)) {
                pageLoading = false
                pageFinishedAt = SystemClock.elapsedRealtime()
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (!isReload) onVisit(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (error.errorCode == ERROR_UNSUPPORTED_SCHEME || request.url.toString().startsWith(OFFLINE_URL)) return
            if (!request.isForMainFrame) {
                if (showingOffline) return
                // A script, style or data request failed while the page was still starting: it may be half-built.
                // Reloaded once the internet is back (onNetworkBack). Later failures (a running page) are left alone.
                if (view === webView && !NetworkWatch.online &&
                    (pageLoading || SystemClock.elapsedRealtime() - pageFinishedAt < 5_000)
                ) brokenLoad = true
                return
            }
            if (view === webView) lastLoadFailed = true
            KioskLog.add(this@MainActivity, "loadFailed",
                "Page failed to load: ${error.errorCode} ${error.description} ${request.url}" +
                    (if (NetworkWatch.online) "" else " (no internet)"))
            view.loadUrl(OFFLINE_URL + "#" + Uri.encode(request.url.toString()))
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode >= 500) {
                KioskLog.add(this@MainActivity, "httpError", "Server error ${response.statusCode} for ${request.url}")
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page's renderer crashed or was killed (often for memory while an Android app is in front).
            // Returning true keeps the app alive; the activity is rebuilt with a fresh WebView on the start page.
            val why = if (detail.didCrash()) "page renderer crashed"
                else "page renderer was stopped by Android (priority ${detail.rendererPriorityAtExit()})"
            KioskLog.add(this@MainActivity, "rendererGone", "$why on ${view.url}", important = true)
            if (view !== webView) {
                // Already replaced (a second callback for the same death).
                runCatching { (view.parent as? ViewGroup)?.removeView(view); view.destroy() }
                return true
            }
            KioskLog.exit(this@MainActivity, why)
            val now = System.currentTimeMillis()
            rendererDeaths = Recovery.recent(rendererDeaths, now, Recovery.RENDERER_WINDOW_MS)
            val delay = Recovery.rendererRebuildDelay(rendererDeaths.size)
            root.removeView(view)
            view.destroy()
            webView = WebView(this@MainActivity)
            if (delay == 0L) {
                recreate()
            } else {
                KioskLog.add(this@MainActivity, "rendererGone", "${rendererDeaths.size} renderer deaths in 2 min; rebuilding in ${delay / 1000}s")
                handler.postDelayed({ if (!isDestroyed && !isFinishing) recreate() }, delay)
            }
            return true
        }
    }

    private inner class KioskChromeClient : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            if (!KioskConfig.isTrusted(request.origin)) {
                request.deny()
                return
            }
            val needed = buildList {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources) add(Manifest.permission.CAMERA)
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources) add(Manifest.permission.RECORD_AUDIO)
            }
            requestAndroidPermissions(needed.toTypedArray()) { granted ->
                val allowed = request.resources.filter { r ->
                    when (r) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> granted[Manifest.permission.CAMERA] == true
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> granted[Manifest.permission.RECORD_AUDIO] == true
                        PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID, PermissionRequest.RESOURCE_MIDI_SYSEX -> true
                        else -> false
                    }
                }
                if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            if (!KioskConfig.isTrusted(Uri.parse(origin))) {
                callback.invoke(origin, false, false)
                return
            }
            requestAndroidPermissions(LOCATION_PERMISSIONS) { granted ->
                callback.invoke(origin, granted.values.any { it }, false)
            }
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message) =
            openPopup(resultMsg)

        override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) = showCustomView(view, callback)

        override fun onHideCustomView() = hideCustomView()

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: WebChromeClient.FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            return try {
                fileLauncher.launch(fileChooserParams.createIntent())
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }
    }
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
