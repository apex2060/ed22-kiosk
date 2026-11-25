package org.ed22.kiosk

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Parent-menu House of Learning *device* identity: a local `hol.on` flag plus an optional
 * `hol-device-<deviceId>` session on the group's explorer doc.
 *
 * This prefs file is **not** cleared by [DeviceSync.signOut] or [HolVisit.clearLocal], so the toggle
 * and registration path survive the end of a visit and unlinking a personal account.
 */
object HolDevice {
    private const val TAG = "HolDevice"
    private const val PREFS = "kiosk_hol_device"
    private const val KEY_ON = "on"
    private const val KEY_SERVICE = "serviceId"
    private const val KEY_PATH = "path"
    private const val KEY_WARN = "warnMin"
    private const val KEY_TITLE = "title"

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-hol-pair").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun on(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, false)
    fun serviceId(ctx: Context): String? = prefs(ctx).getString(KEY_SERVICE, null)
    fun path(ctx: Context): String? = prefs(ctx).getString(KEY_PATH, null)
    fun title(ctx: Context): String? = prefs(ctx).getString(KEY_TITLE, null)
    fun warnMin(ctx: Context): Int = prefs(ctx).getInt(KEY_WARN, HolDevicePlan.DEFAULT_WARN_MIN)

    /** Paired and signed in as the tablet's own hol-device identity (not a visiting family). */
    fun registered(ctx: Context): Boolean =
        on(ctx) && !path(ctx).isNullOrBlank() && HolDevicePlan.isHolDeviceUid(DeviceSync.uid())

    /** Needs a pairing code: HOL is on, but the tablet is not signed in to the group doc. */
    fun needsPairing(ctx: Context): Boolean = on(ctx) && !registered(ctx)

    fun policyHol(ctx: Context): JSONObject =
        HolDevicePlan.policyHol(on(ctx), serviceId(ctx), warnMin(ctx))

    /**
     * Turn the device-local toggle. On: signs out any personal/family session and leaves hol.on set.
     * Off: signs out the hol-device session, stops the group-doc listener, clears registration, hol.on = false.
     */
    fun setOn(ctx: Context, value: Boolean) {
        val c = ctx.applicationContext
        if (value) {
            prefs(c).edit().putBoolean(KEY_ON, true).commit()
            DeviceSync.signOut()
            KioskLog.add(c, HolPlan.LOG_KIND, "House of Learning device on")
        } else {
            prefs(c).edit().putBoolean(KEY_ON, false).remove(KEY_SERVICE).remove(KEY_PATH).remove(KEY_TITLE).commit()
            DeviceSync.signOut()
            KioskLog.add(c, HolPlan.LOG_KIND, "House of Learning device off")
        }
        DeviceSync.applyPolicyNow()
    }

    /**
     * Redeem a house pairing code (the PIN was already checked to open the parent menu; it is never sent).
     * [done] is called on the main thread. The custom token is not persisted or logged.
     */
    fun pair(ctx: Context, rawCode: String, done: (Result<Unit>) -> Unit) {
        val c = ctx.applicationContext
        val code = HolDevicePlan.sanitizeCode(rawCode)
        if (!HolDevicePlan.codeReady(code)) {
            done(Result.failure(IllegalArgumentException("Enter the 8-character code from the house Devices tab.")))
            return
        }
        val deviceId = Prefs.deviceId(c)
        val title = (title(c) ?: Build.MODEL).ifBlank { "Little Explorer tablet" }.take(80)
        io.execute {
            val result = runCatching { redeem(code, deviceId, title) }
            main.post {
                result.fold(
                    onSuccess = { body -> finishPair(c, body, done) },
                    onFailure = { e ->
                        Log.w(TAG, "pair redeem failed: ${e.javaClass.simpleName}")
                        done(Result.failure(e))
                    },
                )
            }
        }
    }

    private fun finishPair(ctx: Context, body: JSONObject, done: (Result<Unit>) -> Unit) {
        val token = body.optString("token")
        val path = body.optString("path")
        val serviceId = body.optString("serviceId")
        val title = body.optString("title").ifBlank { Build.MODEL }
        if (token.isBlank() || !HolDevicePlan.isExplorerPath(path) || serviceId.isBlank()) {
            done(Result.failure(IllegalStateException("The house did not accept this tablet.")))
            return
        }
        prefs(ctx).edit().putBoolean(KEY_ON, true).putString(KEY_SERVICE, serviceId)
            .putString(KEY_PATH, path).putString(KEY_TITLE, title).commit()
        DeviceSync.setAuth(token, path) { r ->
            r.fold(
                onSuccess = {
                    KioskLog.add(ctx, HolPlan.LOG_KIND, "House of Learning tablet registered")
                    done(Result.success(Unit))
                },
                onFailure = { e ->
                    Log.w(TAG, "hol-device sign-in failed: ${e.javaClass.simpleName}")
                    done(Result.failure(IllegalStateException("Could not sign this tablet in to the house.")))
                },
            )
        }
    }

    /** POST pair.redeem. Never logs the code or the token. */
    private fun redeem(code: String, deviceId: String, title: String): JSONObject {
        val req = JSONObject().put("action", "pair.redeem").put("code", code).put("deviceId", deviceId).put("title", title)
        val conn = URL(HolDevicePlan.PAIR_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.useCaches = false
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(req.toString().toByteArray(Charsets.UTF_8)) }
            val text = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val body = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
            if (conn.responseCode !in 200..299) {
                val why = body.optString("error").takeIf { it.isNotBlank() }
                    ?: if (conn.responseCode == 410) "That pairing code has expired. Ask the house for a new one."
                    else "Could not register this tablet."
                throw IllegalStateException(why)
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    fun explorerRef(ctx: Context): DocumentReference? {
        val path = path(ctx) ?: return null
        if (!HolDevicePlan.isExplorerPath(path)) return null
        return FirebaseFirestore.getInstance().document(path)
    }

    /** Visit start/end for the group's Activity log. No screenshot, studentId only in [detail] if present. */
    fun writeAudit(ctx: Context, type: String, detail: Map<String, Any?>) {
        val serviceId = serviceId(ctx) ?: return
        val uid = DeviceSync.uid() ?: return
        val clean = detail.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
        val entry = hashMapOf<String, Any>(
            "type" to type,
            "createdBy" to uid,
            "by" to uid,
            "createdOn" to FieldValue.serverTimestamp(),
            "detail" to clean,
        )
        FirebaseFirestore.getInstance().collection(HolDevicePlan.auditCollection(serviceId))
            .add(entry)
            .addOnFailureListener { e -> Log.w(TAG, "explorerAudit write failed", e) }
    }
}
