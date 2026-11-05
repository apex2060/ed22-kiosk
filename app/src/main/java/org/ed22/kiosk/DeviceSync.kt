package org.ed22.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
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
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

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
    /** The policy pushed by the page with kiosk.policy.set (see [setLocalPolicy]). */
    private const val KEY_LOCAL_POLICY = "localPolicy"
    private const val KEY_COMMAND = "lastCommandId"
    /** Hash of the installedApps last written to the device doc (packages + versionCodes + labels). */
    private const val KEY_APPS_HASH = "installedAppsHash"
    /** Hash of the appUsage last written to the device doc. */
    private const val KEY_USAGE_HASH = "appUsageHash"
    private const val KEY_CHILD_USAGE_HASH = "appUsageByChildHash"
    const val ICON_PX_LIST = 96
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

    private fun stored(ctx: Context, key: String): JSONObject? =
        prefs(ctx).getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun inForce(ctx: Context) = Rules.pickPolicy(stored(ctx, KEY_POLICY), stored(ctx, KEY_LOCAL_POLICY))

    /** The policy in force: the doc's, or the page-pushed one when its `rev` is higher (see [Rules.pickPolicy]). */
    fun policy(ctx: Context = app): JSONObject = inForce(ctx).first

    /** 'doc' or 'local'. */
    fun policySource(ctx: Context = app): String = inForce(ctx).second

    /** The applied policy's `rev` (0 = none). */
    fun policyRev(ctx: Context = app): Long = Rules.rev(policy(ctx))

    /** kiosk.policy.set: saves [p] (minus locked / command) as the local policy and applies it if it is newer. */
    fun setLocalPolicy(ctx: Context, p: JSONObject): JSONObject {
        if (!isSignedIn()) throw IllegalStateException("Not signed in")
        val local = JSONObject(p.toString()).apply { remove("locked"); remove("command") }
        prefs(ctx).edit().putString(KEY_LOCAL_POLICY, local.toString()).commit()
        applyPolicy()
        return JSONObject().put("rev", policyRev(ctx)).put("source", policySource(ctx))
    }

    /** `{policy, rev, source}` for kiosk.policy.get(). */
    fun policyInfo(ctx: Context = app): JSONObject = JSONObject()
        .put("policy", policy(ctx)).put("rev", policyRev(ctx)).put("source", policySource(ctx))

    /** Called with the allowed package list whenever the allowed set changes (kiosk.on('apps')). */
    val appsListeners = mutableSetOf<(Set<String>) -> Unit>()

    fun addListener(fn: (JSONObject) -> Unit) { listeners += fn }
    fun removeListener(fn: (JSONObject) -> Unit) { listeners -= fn }

    fun authInfo(): JSONObject = JSONObject()
        .put("signedIn", auth.currentUser != null)
        .put("uid", uid() ?: JSONObject.NULL)
        .put("deviceDocId", deviceDocId() ?: JSONObject.NULL)

    fun setAuth(token: String, docId: String?, done: (Result<JSONObject>) -> Unit) {
        auth.signInWithCustomToken(token)
            .addOnSuccessListener {
                if (!docId.isNullOrBlank() && docId != deviceDocId()) {
                    prefs(app).edit().putString(KEY_DOC, docId).remove(KEY_APPS_HASH).remove(KEY_USAGE_HASH).remove(KEY_CHILD_USAGE_HASH).apply()
                }
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
        prefs(app).edit().remove(KEY_DOC).remove(KEY_POLICY).remove(KEY_LOCAL_POLICY).remove(KEY_APPS_HASH).remove(KEY_USAGE_HASH).remove(KEY_CHILD_USAGE_HASH).apply()
        listenerStatus = "Not signed in"
        applyPolicy()
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
            // /mobile's activeStudentId: applied when it changes in the doc; kiosk.setActiveChild overrides it locally.
            val docSid = Rules.docActiveStudentId(snap.get("activeStudentId"))
            if (docSid != Prefs.docStudentId(app)) {
                Prefs.setDocStudentId(app, docSid)
                setActiveChild(app, docSid.ifEmpty { null })
            }
            // Policy first, so an openApp in the same snapshot sees the new allowedApps (the other commands don't read it).
            applyPolicy()
            runCommand(policy.optJSONObject("command"))
        }
    }

    private var reportedPolicy: String? = null

    /** Applies the policy in force (rules, lock task, alarm), tells listeners, and reports a new rev / source. */
    private fun applyPolicy() {
        enforce(app)
        val policy = policy(app)
        listeners.toList().forEach { runCatching { it(policy) }.onFailure { e -> Log.w(TAG, "policy listener", e) } }
        val key = "${policyRev(app)}|${policySource(app)}"
        if (key != reportedPolicy) {
            reportedPolicy = key
            reportStatus(app)
        }
    }

    // --- commands ---

    /** Runs policy.command once per id (see [RemoteCommands.decide]). Call after [applyPolicy]. */
    private fun runCommand(cmd: JSONObject?) {
        val p = prefs(app)
        val d = RemoteCommands.decide(
            cmd, p.getString(KEY_COMMAND, null), System.currentTimeMillis(),
            locked = policy(app).optBoolean("locked", false), isAllowed = { isAllowed(app, it) },
        )
        // Saved synchronously first so a reboot command can never loop.
        if (d.handled) p.edit().putString(KEY_COMMAND, RemoteCommands.id(cmd)).commit()
        if (!d.run) {
            if (d.handled) Log.w(TAG, "Dropped command ${RemoteCommands.id(cmd)} (${cmd?.optString("type")}): ${d.reason}")
            return
        }
        cmd!!
        val activity = MainActivity.current
        Log.i(TAG, "Running command ${RemoteCommands.id(cmd)}: ${cmd.optString("type")}")
        when (cmd.optString("type")) {
            "reload" -> activity?.webView?.reload() ?: openKiosk(app)
            "goHome" -> activity?.goHome() ?: openKiosk(app)
            "reboot" -> if (!KioskPolicy.reboot(app)) Log.w(TAG, "reboot needs device owner")
            "openApp" -> runCatching { launch(app, cmd.optString("package")) }
                .onFailure { e -> Log.w(TAG, "openApp ${cmd.optString("package")} failed: ${e.message}") }
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

    /** policy.allowedApps entries by package (the last one wins). */
    private fun allowedEntries(policy: JSONObject): Map<String, JSONObject> {
        val list = policy.optJSONArray("allowedApps") ?: return emptyMap()
        val out = LinkedHashMap<String, JSONObject>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val pkg = o.optString("package").takeIf { it.isNotBlank() } ?: continue
            out[pkg] = o
        }
        return out
    }

    /** [Rules] state of every allowedApps entry for the active child, in the tablet's time zone. */
    fun appStates(ctx: Context, now: Long = System.currentTimeMillis()): Map<String, Rules.State> {
        val sid = Prefs.activeStudentId(ctx)
        val zone = ZoneId.systemDefault()
        val used = Rules.Used { pkg, who, from, to -> AppUsage.usedMinutes(ctx, pkg, who, from, to) }
        val policy = policy(ctx)
        val strict = policy.optBoolean("strict", false)
        return allowedEntries(policy).mapValues { (_, e) -> Rules.state(e, now, zone, sid, used, strict) }
    }

    fun allowedPackages(ctx: Context, now: Long = System.currentTimeMillis()): Set<String> =
        appStates(ctx, now).filterValues { it.allowed }.keys

    fun isAllowed(ctx: Context, pkg: String) = appStates(ctx)[pkg]?.allowed == true

    /** The child using the tablet (null = parent mode). Splits an open app session and re-checks the rules. */
    fun setActiveChild(ctx: Context, studentId: String?) {
        val sid = studentId?.takeIf { it.isNotBlank() }
        if (sid == Prefs.activeStudentId(ctx)) return
        Log.i(TAG, "Active child: ${sid ?: "parent"}")
        AppUsage.switchChild(ctx) { Prefs.setActiveStudentId(ctx, sid) }
        enforce(ctx)
    }

    /**
     * Recomputes lock task packages and the next check alarm, and sends the child back to the kiosk
     * when an app it opened (or any app that was allowed) is no longer allowed. Safe to call any time.
     */
    fun enforce(ctx: Context) {
        val now = System.currentTimeMillis()
        val states = appStates(ctx, now)
        val allowed = states.filterValues { it.allowed }.keys
        scheduleCheck(ctx, now, states)
        if (allowed != lastAllowed) appsListeners.toList().forEach { runCatching { it(allowed) } }
        if (!KioskPolicy.isDeviceOwner(ctx)) {
            enforcementNote = if (states.isEmpty()) "" else "Allowed apps not enforced: app is not device owner"
            lastAllowed = allowed
            return
        }
        enforcementNote = ""
        KioskPolicy.setLockTaskPackages(ctx, allowed)
        val gone = RemoteCommands.noLongerAllowed(lastAllowed, allowed, AppUsage.openPkg(ctx))
        lastAllowed = allowed
        if (gone.isNotEmpty() && !MainActivity.inForeground && !Prefs.isMaintenance(ctx)) {
            Log.i(TAG, "No longer allowed: $gone; returning to the kiosk")
            openKiosk(ctx)
        }
    }

    /**
     * ONE alarm at the next moment the answer can change: the soonest `until` / window end / extraUntil of an
     * allowed app and, while an app opened from the kiosk is in front, its minutes running out and the next
     * local midnight (new day, new budgets). A minute before that app runs out, it fires early for a warning.
     */
    private fun scheduleCheck(ctx: Context, now: Long, states: Map<String, Rules.State>) {
        val times = states.values.filter { it.allowed }.mapNotNull { it.until }.toMutableList()
        var kickAt: Long? = null
        val openPkg = AppUsage.openPkg(ctx)
        val open = openPkg?.let { states[it] }?.takeIf { it.allowed }
        if (openPkg != null) {
            times += Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        if (open != null) {
            val runOut = if (open.left.isFinite()) now + (open.left * 60_000).toLong() else null
            kickAt = listOfNotNull(open.until, runOut).minOrNull()
            if (kickAt != null) times += kickAt
        }
        times += nextWindowStart(ctx, now) ?: Long.MAX_VALUE // so an opening window updates lock task and kiosk.on('apps')
        val at = times.filter { it > now && it != Long.MAX_VALUE }.minOrNull()
        val warn = at != null && at == kickAt && at - now > 90_000
        scheduleAlarm(ctx, if (warn) at!! - 60_000 else at, warn)
    }

    /** The next start time (within a day) of any timed rule in the policy; days and children are not checked. */
    private fun nextWindowStart(ctx: Context, now: Long): Long? {
        val zone = ZoneId.systemDefault()
        val t = Instant.ofEpochMilli(now).atZone(zone)
        return allowedEntries(policy(ctx)).values.flatMap { e ->
            val rules = e.optJSONArray("rules") ?: return@flatMap emptyList()
            (0 until rules.length()).mapNotNull { rules.optJSONObject(it)?.takeIf(Rules::timed)?.optString("start") }
        }.mapNotNull { Rules.minutes(it) }.map { m ->
            val today = t.toLocalDate().atStartOfDay(zone).plusMinutes(m.toLong())
            (if (today.isAfter(t)) today else today.plusDays(1)).toInstant().toEpochMilli()
        }.minOrNull()
    }

    private fun scheduleAlarm(ctx: Context, at: Long?, warn: Boolean) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, PolicyAlarmReceiver::class.java).putExtra(PolicyAlarmReceiver.EXTRA_WARN, warn),
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

    /**
     * Launchable apps other than this one, with their policy state for the active child (`allowed`, the entry's
     * `until`, `left` minutes or null = no limit, `windowUntil` = when the current answer ends, `why` from [Rules])
     * and (unless [withIcons] is false) `icon`: a 96 px PNG data URL, or null.
     */
    fun appList(ctx: Context, withIcons: Boolean = true): JSONArray {
        val entries = allowedEntries(policy(ctx))
        val states = appStates(ctx)
        val out = JSONArray()
        launcherApps(ctx).forEach { app ->
            val st = states[app.pkg]?.takeIf { it.allowed }
            val o = JSONObject().put("package", app.pkg).put("label", app.label).put("allowed", st != null)
                .put("until", (entries[app.pkg]?.opt("until") as? Number)?.toLong() ?: JSONObject.NULL)
                .put("left", st?.left?.takeIf { it.isFinite() }?.let { ceil(it).toInt() } ?: JSONObject.NULL)
                .put("windowUntil", st?.until ?: JSONObject.NULL)
                .put("why", states[app.pkg]?.why ?: "none")
            if (withIcons) o.put("icon", iconDataUrl(ctx, app, ICON_PX_LIST) ?: JSONObject.NULL)
            out.put(o)
        }
        return out
    }

    fun installedApps(ctx: Context): List<Pair<String, String>> = launcherApps(ctx).map { it.pkg to it.label }

    class LauncherApp(val pkg: String, val label: String, val versionCode: Long, val info: ResolveInfo)

    private fun launcherApps(ctx: Context): List<LauncherApp> {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val vc = runCatching { PackageInfoCompat.getLongVersionCode(pm.getPackageInfo(pkg, 0)) }.getOrDefault(-1L)
                LauncherApp(pkg, ri.loadLabel(pm).toString(), vc, ri)
            }
            .sortedBy { it.label.lowercase() }
    }

    // --- icons ---

    /** "pkg:versionCode:px" -> data URL, or "" when rendering failed. */
    private val iconCache = ConcurrentHashMap<String, String>()

    /** The app's launcher icon (adaptive icons included) as a [px]x[px] PNG data URL; null on failure. */
    fun iconDataUrl(ctx: Context, app: LauncherApp, px: Int): String? {
        val key = "${app.pkg}:${app.versionCode}:$px"
        iconCache[key]?.let { return it.ifEmpty { null } }
        val url = try {
            val drawable = app.info.loadIcon(ctx.packageManager)
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, px, px)
            drawable.draw(Canvas(bmp))
            val bytes = ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            bmp.recycle()
            "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Throwable) {
            Log.w(TAG, "Icon for ${app.pkg} failed", e)
            null
        }
        // Drop entries for older versions of this package at this size.
        iconCache.keys.removeAll { it.startsWith("${app.pkg}:") && it.endsWith(":$px") && it != key }
        iconCache[key] = url ?: ""
        return url
    }

    fun launch(ctx: Context, pkg: String) {
        if (!isAllowed(ctx, pkg)) throw IllegalStateException("$pkg is not allowed right now")
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw IllegalArgumentException("$pkg has no launcher activity")
        // Lock task packages follow the rules as of now (a window may have just opened).
        KioskPolicy.setLockTaskPackages(ctx, allowedPackages(ctx))
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        AppUsage.start(ctx, pkg)
        enforce(ctx) // arms the alarm for this app's window / minutes
    }

    // --- status ---

    /** Status fields other than installedApps and appUsage (always written). */
    fun statusMap(ctx: Context): Map<String, Any> {
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
            "timeZone" to ZoneId.systemDefault().id,
            "features" to RemoteCommands.FEATURES,
            "policyRev" to policyRev(ctx),
            "policySource" to policySource(ctx),
        )
    }

    private fun appsHash(apps: List<LauncherApp>): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("v2-noicon\n".toByteArray())  // changed format: forces one rewrite on upgrade
        apps.sortedBy { it.pkg }.forEach { md.update("${it.pkg}\u0000${it.versionCode}\u0000${it.label}\n".toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Writes `status.*` with update() and dotted paths, so no other field of the doc (or of `status`) is
     * touched. `status.installedApps` and `status.appUsage` are only included when they changed
     * since the last successful write. Returns null when signed out.
     */
    fun reportStatus(ctx: Context): com.google.android.gms.tasks.Task<Void>? {
        if (auth.currentUser == null) return null
        val id = deviceDocId(ctx) ?: return null
        val fields = HashMap<String, Any>()
        statusMap(ctx).forEach { (k, v) -> fields["status.$k"] = v }
        val apps = launcherApps(ctx)
        val hash = appsHash(apps)
        val p = prefs(ctx)
        val sendApps = p.getString(KEY_APPS_HASH, null) != hash
        if (sendApps) {
            fields["status.installedApps"] = apps.map { app -> mapOf("package" to app.pkg, "label" to app.label) }
            Log.i(TAG, "Writing status.installedApps: ${apps.size} apps")
        }
        val usage = AppUsage.usageMap(ctx)
        val usageHash = sha256(usage.toString())
        val sendUsage = p.getString(KEY_USAGE_HASH, null) != usageHash
        if (sendUsage) fields["status.appUsage"] = usage
        val byChild = AppUsage.usageByChildMap(ctx)
        val byChildHash = sha256(byChild.toString())
        val sendByChild = p.getString(KEY_CHILD_USAGE_HASH, null) != byChildHash
        if (sendByChild) fields["status.appUsageByChild"] = byChild
        return doc(id).update(fields)
            .addOnSuccessListener {
                // Only remember the hash once the server has it, and only for the doc it was written to.
                if (deviceDocId(ctx) != id) return@addOnSuccessListener
                if (sendApps) p.edit().putString(KEY_APPS_HASH, hash).apply()
                if (sendUsage) p.edit().putString(KEY_USAGE_HASH, usageHash).apply()
                if (sendByChild) p.edit().putString(KEY_CHILD_USAGE_HASH, byChildHash).apply()
            }
            .addOnFailureListener { e -> Log.w(TAG, "Status update failed", e) }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

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

/** The rules check alarm (see DeviceSync.scheduleCheck), and time / time-zone changes. */
class PolicyAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_WARN = "warn"
    }

    override fun onReceive(context: Context, intent: Intent) {
        DeviceSync.enforce(context)
        MainActivity.current?.refreshAppsButton()
        if (intent.action == null && intent.getBooleanExtra(EXTRA_WARN, false) &&
            AppUsage.openPkg(context) != null && !MainActivity.inForeground
        ) {
            android.widget.Toast.makeText(context, "1 minute left", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
