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

class MainActivity : AppCompatActivity() {
    companion object {
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    lateinit var webView: WebView
        private set
    private lateinit var root: FrameLayout
    private lateinit var bridge: KioskBridge
    private lateinit var cornerTaps: CornerTapDetector
    private val handler = Handler(Looper.getMainLooper())

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
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

        cornerTaps = CornerTapDetector(KioskConfig.CORNER_SIZE_DP * resources.displayMetrics.density) {
            ParentMenu.prompt(this)
        }
        configureWebView()
        bridge = KioskBridge(this)
        bridge.install(webView)

        onBackPressedDispatcher.addCallback(this) {
            when {
                customView != null -> hideCustomView()
                webView.canGoBack() -> webView.goBack()
            }
        }

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(KioskConfig.START_URL)
        }
        HistoryReporter.flush(this)
        Updater.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        if (!::webView.isInitialized) return
        hideSystemUi()
        KioskPolicy.startLockTask(this)
        webView.onResume()
        bridge.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            webView.onPause()
            bridge.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
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
        if (hasFocus) hideSystemUi()
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

    fun goHome() {
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
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "$userAgentString ED22Kiosk/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = KioskWebViewClient()
        webView.webChromeClient = KioskChromeClient()
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
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
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
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            bridge.onNavigation(view, url)
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
