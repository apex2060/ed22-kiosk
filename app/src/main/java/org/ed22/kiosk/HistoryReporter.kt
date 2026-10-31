package org.ed22.kiosk

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * POSTs every top-level page visit to [KioskConfig.HISTORY_URL]. Visits made while offline are
 * kept in a small on-disk queue and sent once the network is back.
 */
object HistoryReporter {
    private const val TAG = "HistoryReporter"
    private const val MAX_QUEUE = 500
    private const val QUEUE_FILE = "history_queue.json"

    private val executor = Executors.newSingleThreadExecutor()
    private val queue = ArrayDeque<JSONObject>()
    private var loaded = false

    fun report(ctx: Context, url: String, title: String?, referrer: String?) {
        val app = ctx.applicationContext
        val visit = JSONObject()
            .put("url", url)
            .put("host", Uri.parse(url).host ?: "")
            .put("title", title ?: JSONObject.NULL)
            .put("referrer", referrer ?: JSONObject.NULL)
            .put("visitedAt", isoNow())
            .put("deviceId", Prefs.deviceId(app))
            .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("appVersion", BuildConfig.VERSION_NAME)
        executor.execute {
            load(app)
            queue.addLast(visit)
            while (queue.size > MAX_QUEUE) queue.removeFirst()
            drain(app)
        }
    }

    /** Retries anything still queued, e.g. when the network comes back. */
    fun flush(ctx: Context) {
        val app = ctx.applicationContext
        executor.execute {
            load(app)
            if (queue.isNotEmpty()) drain(app)
        }
    }

    private fun drain(app: Context) {
        while (queue.isNotEmpty()) {
            if (!send(app, queue.first())) break
            queue.removeFirst()
        }
        save(app)
    }

    /** Returns true when the visit is done with (delivered, or rejected in a way a retry will not fix). */
    private fun send(app: Context, visit: JSONObject): Boolean {
        val conn = try {
            URL(KioskConfig.HISTORY_URL).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return false
        }
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Kiosk-Device-Id", Prefs.deviceId(app))
            conn.setRequestProperty("User-Agent", "${WebSettings.getDefaultUserAgent(app)} ED22Kiosk/${BuildConfig.VERSION_NAME}")
            CookieManager.getInstance().getCookie(KioskConfig.HISTORY_URL)?.let {
                conn.setRequestProperty("Cookie", it)
            }
            conn.outputStream.use { it.write(visit.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            when {
                code in 200..299 -> true
                code == 401 || code == 403 || code == 408 || code == 429 -> false
                code in 400..499 -> {
                    Log.w(TAG, "History endpoint rejected visit with $code; dropping it")
                    true
                }
                else -> false
            }
        } catch (e: IOException) {
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun load(app: Context) {
        if (loaded) return
        loaded = true
        try {
            val file = File(app.filesDir, QUEUE_FILE)
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) queue.addLast(arr.getJSONObject(i))
        } catch (e: Exception) {
            Log.w(TAG, "Could not read history queue", e)
        }
    }

    private fun save(app: Context) {
        try {
            val file = File(app.filesDir, QUEUE_FILE)
            if (queue.isEmpty()) file.delete() else file.writeText(JSONArray(queue.toList()).toString())
        } catch (e: Exception) {
            Log.w(TAG, "Could not save history queue", e)
        }
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
}
