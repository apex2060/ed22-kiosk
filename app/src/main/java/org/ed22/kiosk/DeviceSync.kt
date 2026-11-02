package org.ed22.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Links the device to its Firestore doc `share/library/myItems/<deviceDocId>` (created by /mobile).
 * Signs in with a custom token from the page, reports `status` (at start, on sign-in and every 15 min),
 * and listens to `policy`, which is cached in prefs so it still applies offline and after a reboot.
 * Runs in the Application, so it works while /mobile isn't loaded. All callbacks run on the main thread.
 */
object DeviceSync {
    private const val TAG = "DeviceSync"
    private const val PREFS = "kiosk_account"
    private const val KEY_DOC = "deviceDocId"
    private const val KEY_POLICY = "policy"
    private const val KEY_COMMAND = "lastCommandId"
    private const val STATUS_WORK = "kiosk-status-periodic"
    private const val RETRY_MS = 5 * 60_000L

    private lateinit var app: Context
    private val handler = Handler(Looper.getMainLooper())
    private var registration: ListenerRegistration? = null
    private val listeners = mutableSetOf<(JSONObject) -> Unit>()
    private var lastAllowed: Set<String>? = null

    /** Shown in the parent menu. */
    var listenerStatus = "Not signed in"
        private set
    var enforcementNote = ""
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val auth get() = FirebaseAuth.getInstance()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        enforce(app)
        val wm = WorkManager.getInstance(app)
        wm.enqueueUniquePeriodicWork(
            STATUS_WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<StatusWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
        if (isSignedIn()) {
            startListener()
            reportStatus(app)
        }
    }

    fun uid(): String? = auth.currentUser?.uid
    fun deviceDocId(ctx: Context = app): String? = prefs(ctx).getString(KEY_DOC, null)
    fun isSignedIn() = auth.currentUser != null && deviceDocId() != null

    fun policy(ctx: Context = app): JSONObject =
        prefs(ctx).getString(KEY_POLICY, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    fun addListener(fn: (JSONObject) -> Unit) { listeners += fn }
    fun removeListener(fn: (JSONObject) -> Unit) { listeners -= fn }

    fun authInfo(): JSONObject = JSONObject()
        .put("signedIn", auth.currentUser != null)
        .put("uid", uid() ?: JSONObject.NULL)
        .put("deviceDocId", deviceDocId() ?: JSONObject.NULL)

    fun setAuth(token: String, docId: String?, done: (Result<JSONObject>) -> Unit) {
        auth.signInWithCustomToken(token)
            .addOnSuccessListener {
                if (!docId.isNullOrBlank()) prefs(app).edit().putString(KEY_DOC, docId).apply()
                startListener()
                reportStatus(app)
                done(Result.success(JSONObject().put("uid", uid()).put("deviceDocId", deviceDocId() ?: JSONObject.NULL)))
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "signInWithCustomToken failed", e)
                done(Result.failure(e))
            }
    }

    fun signOut() {
        stopListener()
        auth.signOut()
        prefs(app).edit().remove(KEY_DOC).remove(KEY_POLICY).apply()
        listenerStatus = "Not signed in"
        onPolicy(JSONObject())
    }

    private fun doc(docId: String): DocumentReference =
        FirebaseFirestore.getInstance().collection("share").document("library").collection("myItems").document(docId)

    private fun stopListener() {
        handler.removeCallbacks(retry)
        registration?.remove()
        registration = null
    }

    private val retry = Runnable { if (isSignedIn()) startListener() }

    private fun startListener() {
        stopListener()
        val docId = deviceDocId() ?: return
        listenerStatus = "Connecting…"
        registration = doc(docId).addSnapshotListener { snap, error ->
            if (error != null) {
                Log.w(TAG, "Device doc listener failed: ${error.code}", error)
                listenerStatus = "Error: ${error.code} ${error.message ?: ""}".trim()
                registration = null
                handler.postDelayed(retry, RETRY_MS)
                return@addSnapshotListener
            }
            if (snap == null || !snap.exists()) {
                listenerStatus = "Device doc not found"
                return@addSnapshotListener
            }
            listenerStatus = "Listening (updated ${DateFormat.getTimeInstance().format(Date())}" +
                (if (snap.metadata.isFromCache) ", from cache)" else ")")
            val policy = (toJson(snap.get("policy")) as? JSONObject) ?: JSONObject()
            prefs(app).edit().putString(KEY_POLICY, policy.toString()).apply()
            onPolicy(policy)
        }
    }

    private fun onPolicy(policy: JSONObject) {
        enforce(app)
        runCommand(policy.optJSONObject("command"))
        listeners.toList().forEach { runCatching { it(policy) }.onFailure { e -> Log.w(TAG, "policy listener", e) } }
    }

    // --- commands ---

    private fun runCommand(cmd: JSONObject?) {
        val id = cmd?.opt("id")?.takeUnless { it == JSONObject.NULL }?.toString() ?: return
        val p = prefs(app)
        if (p.getString(KEY_COMMAND, null) == id) return
        // Saved synchronously first so a reboot command can never loop.
        p.edit().putString(KEY_COMMAND, id).commit()
        val activity = MainActivity.current
        Log.i(TAG, "Running command $id: ${cmd.optString("type")}")
        when (cmd.optString("type")) {
            "reload" -> activity?.webView?.reload() ?: openKiosk(app)
            "goHome" -> activity?.goHome() ?: openKiosk(app)
            "reboot" -> if (!KioskPolicy.reboot(app)) Log.w(TAG, "reboot needs device owner")
            else -> Log.w(TAG, "Unknown command type: ${cmd.optString("type")}")
        }
    }

    private fun openKiosk(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the kiosk", e)
        }
    }

    // --- allowed apps ---

    /** `until` in epoch ms, or null for no end time. */
    private fun allowedEntries(policy: JSONObject): Map<String, Long?> {
        val list = policy.optJSONArray("allowedApps") ?: return emptyMap()
        val out = LinkedHashMap<String, Long?>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val pkg = o.optString("package").takeIf { it.isNotBlank() } ?: continue
            out[pkg] = (o.opt("until") as? Number)?.toLong()
        }
        return out
    }

    fun allowedPackages(ctx: Context, now: Long = System.currentTimeMillis()): Set<String> =
        allowedEntries(policy(ctx)).filter { (_, until) -> until == null || until > now }.keys

    fun isAllowed(ctx: Context, pkg: String) = pkg in allowedPackages(ctx)

    /** Recomputes lock task packages and the next expiry alarm. Safe to call any time. */
    fun enforce(ctx: Context) {
        val now = System.currentTimeMillis()
        val entries = allowedEntries(policy(ctx))
        val allowed = entries.filter { (_, until) -> until == null || until > now }.keys
        scheduleExpiry(ctx, entries.values.filterNotNull().filter { it > now }.minOrNull())
        if (!KioskPolicy.isDeviceOwner(ctx)) {
            enforcementNote = if (entries.isEmpty()) "" else "Allowed apps not enforced: app is not device owner"
            lastAllowed = allowed
            return
        }
        enforcementNote = ""
        KioskPolicy.setLockTaskPackages(ctx, allowed)
        val removed = lastAllowed?.minus(allowed).orEmpty()
        lastAllowed = allowed
        if (removed.isNotEmpty() && !MainActivity.inForeground && !Prefs.isMaintenance(ctx)) {
            Log.i(TAG, "No longer allowed: $removed; returning to the kiosk")
            openKiosk(ctx)
        }
    }

    private fun scheduleExpiry(ctx: Context, at: Long?) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, PolicyAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
        if (at == null) return
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at + 500, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at + 500, pi)
        }
    }

    /** Launchable apps other than this one, with their policy state. */
    fun appList(ctx: Context): JSONArray {
        val entries = allowedEntries(policy(ctx))
        val allowed = allowedPackages(ctx)
        val out = JSONArray()
        installedApps(ctx).forEach { (pkg, label) ->
            out.put(
                JSONObject().put("package", pkg).put("label", label).put("allowed", pkg in allowed)
                    .put("until", entries[pkg] ?: JSONObject.NULL)
            )
        }
        return out
    }

    fun installedApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    fun launch(ctx: Context, pkg: String) {
        if (!isAllowed(ctx, pkg)) throw IllegalStateException("$pkg is not allowed right now")
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw IllegalArgumentException("$pkg has no launcher activity")
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // --- status ---

    fun statusMap(ctx: Context): Map<String, Any?> {
        val battery = DeviceInfo.battery(ctx)
        return mapOf(
            "lastSeen" to FieldValue.serverTimestamp(),
            "version" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE,
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "androidVersion" to Build.VERSION.RELEASE,
            "battery" to battery.optInt("level", 0) / 100.0,
            "charging" to battery.optBoolean("charging"),
            "deviceId" to Prefs.deviceId(ctx),
            "deviceOwner" to KioskPolicy.isDeviceOwner(ctx),
            "policyNote" to enforcementNote,
            "installedApps" to installedApps(ctx).map { (pkg, label) -> mapOf("package" to pkg, "label" to label) },
        )
    }

    /** Writes `status` with update(), so no other field of the doc is touched. Returns null when signed out. */
    fun reportStatus(ctx: Context) =
        if (auth.currentUser == null) null
        else deviceDocId(ctx)?.let { id ->
            doc(id).update("status", statusMap(ctx))
                .addOnFailureListener { e -> Log.w(TAG, "Status update failed", e) }
        }

    private fun toJson(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Timestamp -> v.toDate().time
        is Date -> v.time
        is Map<*, *> -> JSONObject().apply { v.forEach { (k, x) -> put(k.toString(), toJson(x)) } }
        is List<*> -> JSONArray().apply { v.forEach { put(toJson(it)) } }
        is Number, is Boolean, is String -> v
        else -> v.toString()
    }
}

class StatusWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = try {
        DeviceSync.reportStatus(applicationContext)?.let { Tasks.await(it, 60, TimeUnit.SECONDS) }
        Result.success()
    } catch (e: java.util.concurrent.TimeoutException) {
        Result.success() // Queued by Firestore; it will be sent when online.
    } catch (e: Exception) {
        Log.w("StatusWorker", "Status update failed", e)
        Result.retry()
    }
}

/** Fires at the soonest allowedApps `until`. */
class PolicyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = DeviceSync.enforce(context)
}
