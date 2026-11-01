package org.ed22.kiosk

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Native side of `window.kiosk`. Pages post `{id, method, args}` messages through the `KioskNative`
 * object, which WebView only injects into pages whose origin is in [KioskConfig.BRIDGE_ORIGIN_RULES].
 * Replies are `{id, result}` or `{id, error}`; pushed events are `{event, data}`.
 */
class KioskBridge(private val activity: MainActivity) : WebViewCompat.WebMessageListener {
    companion object {
        private const val TAG = "KioskBridge"
        private const val OBJECT_NAME = "KioskNative"

        private val PERMISSION_NAMES: Map<String, List<String>> = buildMap {
            put("camera", listOf(Manifest.permission.CAMERA))
            put("microphone", listOf(Manifest.permission.RECORD_AUDIO))
            put("location", MainActivity.LOCATION_PERMISSIONS.toList())
            put("phone", listOf(Manifest.permission.READ_PHONE_STATE))
            if (Build.VERSION.SDK_INT >= 29) put("activity", listOf(Manifest.permission.ACTIVITY_RECOGNITION))
        }

        private val VOLUME_STREAMS = mapOf(
            "music" to AudioManager.STREAM_MUSIC,
            "ring" to AudioManager.STREAM_RING,
            "alarm" to AudioManager.STREAM_ALARM,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "system" to AudioManager.STREAM_SYSTEM,
            "call" to AudioManager.STREAM_VOICE_CALL,
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val sensors = SensorHub(activity)
    private var mainFrame: JavaScriptReplyProxy? = null
    private var fallbackShim: String? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    /** Utterances waiting to be spoken, in order; [speaking] is the one TextToSpeech is saying now. */
    private val speechQueue = ArrayDeque<Pair<String, String>>()
    private var speaking: String? = null
    private var nextUtterance = 1
    private val nfc = NfcReader(activity) { tag -> handler.post { emit("nfc", tag) } }
    private var lastBattery: String? = null
    private var lastNetwork: String? = null

    private inner class Call(val id: Int, val proxy: JavaScriptReplyProxy) {
        fun ok(result: Any? = true) =
            send(proxy, JSONObject().put("id", id).put("result", result ?: JSONObject.NULL))

        fun fail(message: String) = send(proxy, JSONObject().put("id", id).put("error", message))
    }

    fun install(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.e(TAG, "This WebView is too old for the kiosk bridge; update Android System WebView.")
            return
        }
        WebViewCompat.addWebMessageListener(webView, OBJECT_NAME, KioskConfig.BRIDGE_ORIGIN_RULES, this)
        val shim = activity.assets.open("kiosk.js").bufferedReader().use { it.readText() }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, shim, KioskConfig.BRIDGE_ORIGIN_RULES)
        } else {
            fallbackShim = shim
        }
        ContextCompat.registerReceiver(
            activity, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        activity.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        ContextCompat.registerReceiver(
            activity, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun onNavigation(webView: WebView, url: String) {
        mainFrame = null
        sensors.clearAll()
        injectFallback(webView, url)
    }

    fun onPageFinished(webView: WebView, url: String) = injectFallback(webView, url)

    fun onResume() {
        sensors.resume()
        nfc.onResume()
    }

    fun onPause() {
        sensors.pause()
        nfc.onPause()
    }

    fun onFocusChanged(focused: Boolean) = emit("focus", JSONObject().put("focused", focused))

    fun destroy() {
        sensors.clearAll()
        try {
            activity.unregisterReceiver(batteryReceiver)
            activity.unregisterReceiver(screenReceiver)
            activity.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            // Never registered.
        }
        tts?.shutdown()
        tts = null
    }

    private fun injectFallback(webView: WebView, url: String) {
        val shim = fallbackShim ?: return
        if (KioskConfig.isTrusted(Uri.parse(url))) webView.evaluateJavascript(shim, null)
    }

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        val msg = try {
            JSONObject(message.data ?: return)
        } catch (e: JSONException) {
            return
        }
        if (isMainFrame) mainFrame = replyProxy
        val call = Call(msg.optInt("id", -1), replyProxy)
        val args = msg.optJSONObject("args") ?: JSONObject()
        try {
            handle(msg.optString("method"), args, call)
        } catch (e: Exception) {
            Log.w(TAG, "kiosk.${msg.optString("method")} failed", e)
            call.fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun handle(method: String, args: JSONObject, call: Call) {
        when (method) {
            "hello" -> call.ok(
                JSONObject()
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("deviceId", Prefs.deviceId(activity))
            )
            "getBattery" -> call.ok(DeviceInfo.battery(activity))
            "getNetwork" -> call.ok(DeviceInfo.network(activity))
            "getWifi" -> call.ok(DeviceInfo.wifi(activity))
            "getDevice" -> call.ok(DeviceInfo.device(activity))
            "getStorage" -> call.ok(DeviceInfo.storage())
            "getMemory" -> call.ok(DeviceInfo.memory(activity))
            "getLocation" -> getLocation(args, call)
            "getPermissions" -> call.ok(permissionStatus())
            "requestPermissions" -> requestPermissions(args, call)
            "vibrate" -> call.ok(vibrate(args))
            "setBrightness" -> call.ok(setBrightness(args.optDouble("level", -1.0)))
            "getVolume" -> call.ok(volumes())
            "setVolume" -> call.ok(setVolume(args))
            "setKeepScreenOn" -> call.ok(setKeepScreenOn(args.optBoolean("on", true)))
            "setTorch" -> call.ok(setTorch(args.optBoolean("on", true)))
            "setOrientation" -> call.ok(setOrientation(args.optString("orientation", "auto")))
            "toast" -> {
                Toast.makeText(activity, args.optString("text"), Toast.LENGTH_LONG).show()
                call.ok()
            }
            "speak" -> call.ok(speak(args.optString("text")))
            "stopSpeaking" -> call.ok(stopSpeaking())
            "cancelSpeak" -> call.ok(cancelSpeak(args.optString("id")))
            "nfcStatus" -> call.ok(nfc.status())
            "nfcEnable" -> call.ok(nfc.enable())
            "nfcDisable" -> call.ok(nfc.disable())
            "storageGet" -> call.ok(NativeStorage.get(activity, args.optString("key")) ?: JSONObject.NULL)
            "storageSet" -> {
                if (!args.has("value") || args.isNull("value")) throw IllegalArgumentException("value is required")
                NativeStorage.set(activity, args.optString("key"), args.get("value").toString())
                call.ok()
            }
            "storageRemove" -> {
                NativeStorage.remove(activity, args.optString("key"))
                call.ok()
            }
            "storageKeys" -> call.ok(NativeStorage.keys(activity))
            "getScreen" -> call.ok(screen())
            "isInFocus" -> call.ok(activity.hasWindowFocus())
            "reload" -> {
                call.ok()
                activity.webView.reload()
            }
            "goHome" -> {
                call.ok()
                activity.goHome()
            }
            "reboot" -> call.ok(KioskPolicy.reboot(activity))
            "sensorList" -> call.ok(sensors.list())
            "sensorWatch" -> sensorWatch(args, call)
            "sensorClear" -> {
                sensors.clear(args.optInt("watchId"))
                call.ok()
            }
            else -> call.fail("Unknown kiosk method: $method")
        }
    }

    // --- events ---

    private fun send(proxy: JavaScriptReplyProxy, payload: JSONObject) {
        try {
            proxy.postMessage(payload.toString())
        } catch (e: Exception) {
            // The page that asked has gone away.
        }
    }

    private fun emit(event: String, data: Any) {
        val proxy = mainFrame ?: return
        send(proxy, JSONObject().put("event", event).put("data", data))
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val battery = DeviceInfo.battery(activity)
            val key = "${battery.opt("level")}|${battery.opt("charging")}|${battery.opt("plugged")}"
            if (key == lastBattery) return
            lastBattery = key
            emit("battery", battery)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            emit("screen", JSONObject().put("on", intent.action == Intent.ACTION_SCREEN_ON))
        }
    }

    private fun screen(): JSONObject {
        val brightness = activity.window.attributes.screenBrightness
        return JSONObject()
            .put("on", activity.getSystemService(android.os.PowerManager::class.java).isInteractive)
            .put("brightness", if (brightness < 0) -1.0 else brightness.toDouble())
            .put(
                "orientation",
                if (activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
            )
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            HistoryReporter.flush(activity)
            changed()
        }

        override fun onLost(network: Network) = changed()

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()

        private fun changed() {
            handler.post {
                val net = DeviceInfo.network(activity)
                val key = net.toString()
                if (key != lastNetwork) {
                    lastNetwork = key
                    emit("network", net)
                }
            }
        }
    }

    // --- permissions and location ---

    private fun permissionStatus(): JSONObject = JSONObject().apply {
        PERMISSION_NAMES.forEach { (name, perms) -> put(name, perms.any(activity::isGranted)) }
    }

    private fun requestPermissions(args: JSONObject, call: Call) {
        val names = args.optJSONArray("permissions") ?: JSONArray()
        val wanted = (0 until names.length()).flatMap { i ->
            val name = names.optString(i)
            PERMISSION_NAMES[name] ?: throw IllegalArgumentException("Unknown permission: $name")
        }
        activity.requestAndroidPermissions(wanted.toTypedArray()) { call.ok(permissionStatus()) }
    }

    @SuppressLint("MissingPermission")
    private fun getLocation(args: JSONObject, call: Call) {
        activity.requestAndroidPermissions(MainActivity.LOCATION_PERMISSIONS) { granted ->
            if (granted.values.none { it }) {
                call.fail("Location permission denied")
                return@requestAndroidPermissions
            }
            val lm = activity.getSystemService(LocationManager::class.java)
            val gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val net = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            val provider = when {
                gps && (args.optBoolean("highAccuracy") || !net) -> LocationManager.GPS_PROVIDER
                net -> LocationManager.NETWORK_PROVIDER
                else -> {
                    call.fail("Location is turned off on this device")
                    return@requestAndroidPermissions
                }
            }
            LocationManagerCompat.getCurrentLocation(
                lm, provider, null as android.os.CancellationSignal?, ContextCompat.getMainExecutor(activity)
            ) { location ->
                val loc = location ?: lm.getLastKnownLocation(provider)
                if (loc == null) call.fail("Location unavailable") else call.ok(locationJson(loc))
            }
        }
    }

    private fun locationJson(loc: Location) = JSONObject()
        .put("latitude", loc.latitude)
        .put("longitude", loc.longitude)
        .put("accuracyM", loc.accuracy.toDouble())
        .put("altitudeM", if (loc.hasAltitude()) loc.altitude else JSONObject.NULL)
        .put("speedMps", if (loc.hasSpeed()) loc.speed.toDouble() else JSONObject.NULL)
        .put("bearing", if (loc.hasBearing()) loc.bearing.toDouble() else JSONObject.NULL)
        .put("provider", loc.provider ?: JSONObject.NULL)
        .put("timestamp", loc.time)

    // --- sensors ---

    private fun sensorWatch(args: JSONObject, call: Call) {
        val type = args.optString("type")
        val rateMs = args.optLong("rateMs", 100)
        val start = {
            val id = sensors.watch(type, rateMs) { reading ->
                send(call.proxy, JSONObject().put("event", "sensor").put("data", reading))
            }
            call.ok(id)
        }
        if (type.startsWith("step_") && Build.VERSION.SDK_INT >= 29) {
            activity.requestAndroidPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)) {
                try {
                    start()
                } catch (e: Exception) {
                    call.fail(e.message ?: "Sensor unavailable")
                }
            }
        } else {
            start()
        }
    }

    // --- device controls ---

    private fun vibrate(args: JSONObject): Boolean {
        val vibrator = activity.getSystemService(Vibrator::class.java) ?: return false
        if (!vibrator.hasVibrator()) return false
        val pattern = args.optJSONArray("pattern")
        val effect = if (pattern != null && pattern.length() > 0) {
            // Same format as navigator.vibrate: on, off, on, ... in milliseconds.
            val timings = LongArray(pattern.length() + 1) { i -> if (i == 0) 0 else pattern.optLong(i - 1) }
            VibrationEffect.createWaveform(timings, -1)
        } else {
            VibrationEffect.createOneShot(args.optLong("ms", 200).coerceIn(1, 10_000), VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
        return true
    }

    private fun setBrightness(level: Double): Boolean {
        val attrs = activity.window.attributes
        attrs.screenBrightness = if (level < 0) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            level.coerceIn(0.01, 1.0).toFloat()
        }
        activity.window.attributes = attrs
        return true
    }

    private fun volumes(): JSONObject {
        val am = activity.getSystemService(AudioManager::class.java)
        return JSONObject().apply {
            VOLUME_STREAMS.forEach { (name, stream) ->
                val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
                put(name, am.getStreamVolume(stream).toDouble() / max)
            }
        }
    }

    private fun setVolume(args: JSONObject): JSONObject {
        val name = args.optString("stream", "music")
        val stream = VOLUME_STREAMS[name] ?: throw IllegalArgumentException("Unknown volume stream: $name")
        val am = activity.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(stream)
        am.setStreamVolume(stream, (args.optDouble("level", 0.5).coerceIn(0.0, 1.0) * max).roundToInt(), 0)
        return volumes()
    }

    private fun setKeepScreenOn(on: Boolean): Boolean {
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (on) activity.window.addFlags(flag) else activity.window.clearFlags(flag)
        return on
    }

    private fun setTorch(on: Boolean): Boolean {
        val cm = activity.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw IllegalStateException("This device has no flashlight")
        cm.setTorchMode(id, on)
        return on
    }

    private fun setOrientation(orientation: String): String {
        activity.requestedOrientation = when (orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "auto" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> throw IllegalArgumentException("Orientation must be portrait, landscape or auto")
        }
        return orientation
    }

    /** Queues [text] and returns its utterance id; progress is pushed as 'speech' events. */
    private fun speak(text: String): String {
        if (text.isBlank()) throw IllegalArgumentException("Nothing to say")
        val id = "u${nextUtterance++}"
        speechQueue.addLast(id to text)
        if (tts == null) {
            tts = TextToSpeech(activity) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) speakNext() else failAllSpeech()
            }.apply { setOnUtteranceProgressListener(speechListener) }
        } else if (ttsReady) {
            speakNext()
        }
        return id
    }

    private fun speakNext() {
        if (speaking != null || !ttsReady) return
        val (id, text) = speechQueue.removeFirstOrNull() ?: return
        speaking = id
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) speechEnded(id, "error")
    }

    private fun speechEnded(id: String, state: String) {
        if (speaking != id) return // Already reported (e.g. cancelled).
        speaking = null
        emitSpeech(id, state)
        speakNext()
    }

    private fun failAllSpeech() {
        while (speechQueue.isNotEmpty()) emitSpeech(speechQueue.removeFirst().first, "error")
    }

    /** Cancels one utterance, queued or playing. Returns false if it already finished. */
    private fun cancelSpeak(id: String): Boolean {
        if (speaking == id) {
            tts?.stop()
            speechEnded(id, "stopped")
            return true
        }
        val queued = speechQueue.firstOrNull { it.first == id } ?: return false
        speechQueue.remove(queued)
        emitSpeech(id, "stopped")
        return true
    }

    private fun stopSpeaking(): Boolean {
        while (speechQueue.isNotEmpty()) emitSpeech(speechQueue.removeFirst().first, "stopped")
        speaking?.let { id ->
            tts?.stop()
            speechEnded(id, "stopped")
        }
        return true
    }

    private fun emitSpeech(id: String, state: String) =
        emit("speech", JSONObject().put("id", id).put("state", state))

    private val speechListener = object : UtteranceProgressListener() {
        override fun onStart(id: String) {
            handler.post { if (speaking == id) emitSpeech(id, "start") }
        }

        override fun onDone(id: String) {
            handler.post { speechEnded(id, "done") }
        }

        override fun onStop(id: String, interrupted: Boolean) {
            handler.post { speechEnded(id, "stopped") }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(id: String) {
            handler.post { speechEnded(id, "error") }
        }

        override fun onError(id: String, errorCode: Int) {
            handler.post { speechEnded(id, "error") }
        }
    }
}
