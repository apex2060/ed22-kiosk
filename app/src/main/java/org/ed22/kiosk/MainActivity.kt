package org.ed22.kiosk

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    }

    lateinit var webView: WebView
        private set
    private lateinit var root: FrameLayout
    private lateinit var bridge: KioskBridge
    private lateinit var cornerTaps: CornerTapDetector
    private var hasFocus = false
    private val handler = Handler(Looper.getMainLooper())
    private var lockOverlay: View? = null
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
        KioskPolicy.startLockTask(this)
        webView.onResume()
        bridge.onResume()
        refreshAppsButton()
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
        inForeground = false
        if (::webView.isInitialized) {
            webView.onPause()
            bridge.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (current === this) current = null
        DeviceSync.removeListener(policyListener)
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
     * Full-screen "locked" cover while the device doc's policy.locked is true. It swallows touches, but
     * the parent-menu corner taps are seen first in [dispatchTouchEvent], so the PIN still works.
     */
    fun updateLockOverlay() {
        if (!::root.isInitialized) return
        val locked = DeviceSync.policy(this).optBoolean("locked", false)
        if (!locked) {
            lockOverlay?.let { root.removeView(it) }
            lockOverlay = null
            return
        }
        val overlay = lockOverlay ?: android.widget.TextView(this).apply {
            text = "This device is locked"
            textSize = 28f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xFF1F2A44.toInt())
            isClickable = true
            isFocusable = true
            setOnTouchListener { _, _ -> true }
        }.also {
            root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
            lockOverlay = it
        }
        overlay.bringToFront()
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
            if (view === webView) setPageHasAppsUi(false)
        }

        override fun onPageFinished(view: WebView, url: String) {
            bridge.onPageFinished(view, url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (!isReload) onVisit(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame || error.errorCode == ERROR_UNSUPPORTED_SCHEME) return
            view.loadUrl("file:///android_asset/offline.html#" + Uri.encode(request.url.toString()))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page's renderer crashed or was killed; rebuild the activity with a fresh WebView.
            root.removeView(view)
            view.destroy()
            webView = WebView(this@MainActivity)
            recreate()
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
