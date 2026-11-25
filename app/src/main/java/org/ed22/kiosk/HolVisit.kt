package org.ed22.kiosk

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native House of Learning visit: honor the visiting family's kiosk.policy.set, and end the visit
 * (wipe the page session, drop the visitor policy, keep the house signed in) from kiosk.hol.end,
 * a safety-net AlarmManager timer, boot / app start, or the parent menu.
 *
 * Does not call [DeviceSync.signOut] and does not clear [HolDevice] prefs (`hol.on` and the group path stay).
 * Screenshots stay allowed on a personal device; they are skipped while House of Learning mode is on.
 * Parent pauses (#15) are respected: ending a visit never reclaims the screen while the parent is in Settings
 * or has exited kiosk mode.
 */
object HolVisit {
    private const val PREFS = "kiosk_hol"
    private const val KEY_VISITOR = "visitor"
    private const val KEY_ENDS = "endsAtMs"
    private const val KEY_WARN_MIN = "warnMin"
    private const val KEY_WARNED_FOR = "warnedFor"
    private const val KEY_NAME = "name"
    private const val KEY_STUDENT = "studentId"
    private const val KEY_LAST = "last"
    private const val KEY_CURRENT = "current"
    private const val KEY_LOAD_HOME = "loadHome"
    private const val CHANNEL = "hol"
    private const val NOTIFY_ID = 22_022
    private const val PI_WARN = 2201
    private const val PI_END = 2202

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var ending = false
    private val warnRunnable = Runnable { appOrNull()?.let { onWarn(it) } }
    private val endRunnable = Runnable { appOrNull()?.let { end(it, "visit ended") } }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun appOrNull(): Context? = MainActivity.current?.applicationContext

    /** A visit is stored and has not been ended yet (the family's local policy is what the tablet enforces). */
    fun visiting(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_VISITOR, false)

    fun storedVisit(ctx: Context): HolPlan.Visit? {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_VISITOR, false)) return null
        val ends = p.getLong(KEY_ENDS, 0L)
        if (ends <= 0L) return null
        return HolPlan.Visit(ends, p.getInt(KEY_WARN_MIN, HolPlan.DEFAULT_WARN_MIN))
    }

    data class ExplorerState(
        val visiting: Boolean,
        val name: String?,
        val studentId: String?,
        val current: Any?,
        val last: Map<String, Any?>?,
    )

    /** Name / current / student for the group explorer doc (no tool totals, no screenshot). */
    fun explorerState(ctx: Context): ExplorerState {
        val p = prefs(ctx)
        val current = p.getString(KEY_CURRENT, null)?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let { firebaseValue(it) }
        }
        return ExplorerState(visiting(ctx), p.getString(KEY_NAME, null), p.getString(KEY_STUDENT, null), current, lastMap(p))
    }

    /** App start / process start: end a visit whose time has already passed, before the page loads; otherwise arm the timer. */
    fun onStart(ctx: Context) {
        val c = ctx.applicationContext
        val v = storedVisit(c) ?: return
        if (HolPlan.shouldEnd(v, System.currentTimeMillis())) end(c, "visit ended")
        else arm(c)
    }

    /**
     * kiosk.hol.setVisit({endsAtMs, warnMin}): store the end, write status.hol (visitor), and arm the safety-net timer.
     * A later endsAtMs replaces the stored one (an extension). If the end has already passed, ends the visit now.
     */
    fun setVisit(ctx: Context, endsAtMs: Long, warnMin: Int?): JSONObject {
        if (endsAtMs <= 0L) throw IllegalArgumentException("endsAtMs is required")
        val c = ctx.applicationContext
        val visit = HolPlan.visit(endsAtMs, warnMin)
        if (HolPlan.shouldEnd(visit, System.currentTimeMillis())) {
            val p = prefs(c)
            p.edit().putBoolean(KEY_VISITOR, true).putLong(KEY_ENDS, visit.endsAtMs)
                .putInt(KEY_WARN_MIN, visit.warnMin).commit()
            end(c, "visit ended")
            return JSONObject().put("ended", true)
        }
        val prev = storedVisit(c)?.endsAtMs
        val p = prefs(c)
        val keepWarn = p.getLong(KEY_WARNED_FOR, 0L).takeIf { it == HolPlan.warnAt(visit.endsAtMs, visit.warnMin) } ?: 0L
        p.edit().putBoolean(KEY_VISITOR, true).putLong(KEY_ENDS, visit.endsAtMs)
            .putInt(KEY_WARN_MIN, visit.warnMin).putLong(KEY_WARNED_FOR, keepWarn).commit()
        writeHol(c, HolPlan.visitingStatus(visit.endsAtMs, p.getString(KEY_STUDENT, null), p.getString(KEY_NAME, null), lastMap(p)))
        // So a local policy already pushed (or about to be) wins over the house doc, regardless of rev.
        DeviceSync.applyPolicyNow()
        arm(c)
        if (HolPlan.isExtension(prev, visit.endsAtMs)) {
            KioskLog.add(c, HolPlan.LOG_KIND, "HOL visit extended")
        } else {
            KioskLog.add(c, HolPlan.LOG_KIND, "HOL visit armed")
            audit(c, HolDevicePlan.AUDIT_START)
        }
        return JSONObject().put("endsAtMs", visit.endsAtMs).put("warnMin", visit.warnMin)
    }

    /**
     * kiosk.hol.end({reason}): wipe the visitor's page session, drop their policy, keep the house signed in.
     * Safe to call more than once. Runs on the main thread.
     */
    fun end(ctx: Context, reason: String?) {
        val c = ctx.applicationContext
        val why = reason?.takeIf { it.isNotBlank() } ?: "ended"
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { end(c, why) }
            return
        }
        if (ending) return
        ending = true
        try {
            endNow(c, why)
        } finally {
            ending = false
        }
    }

    private fun endNow(ctx: Context, why: String) {
        val was = visiting(ctx)
        val endDetail = explorerState(ctx)
        disarmed(ctx)

        // 1. WebView state (IndexedDB / Firebase web session, cookies, cache, history), including popups.
        WebStorage.getInstance().deleteAllData()
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies(null)
        cookies.flush()
        MainActivity.current?.wipeWebViews()

        // 2. The page's kiosk.storage keys for this session.
        NativeStorage.clearKeys(ctx, HolPlan::clearStorageKey)

        // 3–4. Child and any local "30 more minutes" grant.
        // Keep docStudentId as last seen so a stale snapshot of the visitor cannot re-apply them.
        DeviceSync.setActiveChild(ctx, null)
        DeviceSync.clearLocalExtra(ctx)

        // 5. Close an open Android app and bring the kiosk forward, unless a parent pause is in force.
        AppUsage.end(ctx)
        AppUsage.takePausedPkg(ctx)
        if (!Prefs.isMaintenance(ctx) && !MainActivity.inForeground) DeviceSync.returnToKiosk(ctx, "HOL visit ended")

        // 6. Start page (Quick Connect). Do not sign the app out: the house link stays.
        prefs(ctx).edit().putBoolean(KEY_LOAD_HOME, true).commit()
        MainActivity.current?.goHome()

        // 8. House doc: cleared visit, no current child. Screenshots are left as they are.
        val at = System.currentTimeMillis()
        val ended = HolPlan.endedStatus(at, why)
        prefs(ctx).edit().putString(KEY_LAST, JSONObject(mapOf("at" to at, "why" to why)).toString())
            .remove(KEY_NAME).remove(KEY_STUDENT).remove(KEY_CURRENT).commit()
        writeEnded(ctx, ended)

        // 9. Drop the visitor's local policy so the house doc's policy is in force again.
        prefs(ctx).edit().putBoolean(KEY_VISITOR, false).remove(KEY_ENDS).remove(KEY_WARN_MIN).remove(KEY_WARNED_FOR).commit()
        DeviceSync.dropLocalPolicy(ctx)

        if (was) {
            KioskLog.add(ctx, HolPlan.LOG_KIND, HolPlan.endLog(why), important = true)
            audit(ctx, HolDevicePlan.AUDIT_END, endDetail)
        }
        DeviceSync.reportSoon()
    }

    /**
     * kiosk.hol.report({name, endsAtMs, activeStudentId, current, remote}): write the house doc as the house.
     * Does not copy tool-usage totals. Screenshots stay allowed (studentId on status.screenshot is unchanged).
     */
    fun report(ctx: Context, args: JSONObject): JSONObject {
        val c = ctx.applicationContext
        val p = prefs(c)
        val name = if (args.has("name")) args.optString("name").takeIf { it.isNotBlank() && !args.isNull("name") } else p.getString(KEY_NAME, null)
        val sid = if (args.has("activeStudentId")) {
            if (args.isNull("activeStudentId")) null
            else args.optString("activeStudentId").takeIf { it.isNotBlank() }
        } else p.getString(KEY_STUDENT, null)
        val ends = when {
            args.has("endsAtMs") && !args.isNull("endsAtMs") -> (args.opt("endsAtMs") as? Number)?.toLong() ?: p.getLong(KEY_ENDS, 0L)
            visiting(c) -> p.getLong(KEY_ENDS, 0L)
            else -> 0L
        }
        val ed = p.edit()
        if (args.has("name")) {
            if (name == null) ed.remove(KEY_NAME) else ed.putString(KEY_NAME, name)
        }
        if (args.has("activeStudentId")) {
            if (sid == null) ed.remove(KEY_STUDENT) else ed.putString(KEY_STUDENT, sid)
        }
        ed.commit()
        if (args.has("activeStudentId")) DeviceSync.setActiveChild(c, sid)
        if (args.has("current")) {
            if (args.isNull("current")) p.edit().remove(KEY_CURRENT).commit()
            else p.edit().putString(KEY_CURRENT, args.opt("current").toString()).commit()
        }
        val current = firebaseValue(args.opt("current")).takeIf { args.has("current") && !args.isNull("current") }
            ?: explorerState(c).current
        if (HolDevice.explorerRef(c) != null) {
            val fields = HashMap<String, Any?>()
            fields["visiting"] = visiting(c) || ends > 0L
            fields["name"] = name ?: ""
            if (args.has("activeStudentId") || sid != null) fields["activeStudentId"] = sid ?: FieldValue.delete()
            if (args.has("current") || current != null) fields["current"] = current ?: FieldValue.delete()
            fields["updatedOn"] = FieldValue.serverTimestamp()
            write(c, fields)
        } else {
            val hol = if (visiting(c) || ends > 0L) HolPlan.visitingStatus(ends, sid, name, lastMap(p))
            else HolPlan.restingStatus(false, lastMap(p)).toMutableMap().also {
                if (sid != null) it["studentId"] = sid
                if (name != null) it["name"] = name
            }
            val fields = HashMap<String, Any?>()
            putHolFields(fields, hol)
            if (args.has("activeStudentId")) {
                fields["activeStudentId"] = sid ?: FieldValue.delete()
            }
            if (args.has("current")) {
                fields["status.current"] = current ?: FieldValue.delete()
            }
            if (args.has("remote") && !args.isNull("remote")) {
                fields["status.remote"] = firebaseValue(args.opt("remote"))
            }
            write(c, fields)
        }
        return JSONObject().put("ok", true)
    }

    /** `status.hol` for [DeviceSync.reportStatus]. */
    fun status(ctx: Context): Map<String, Any> {
        val p = prefs(ctx)
        val last = lastMap(p)
        val holOn = DeviceSync.policy(ctx).optJSONObject("hol")?.optBoolean("on") == true
        val raw = if (visiting(ctx)) HolPlan.visitingStatus(
            p.getLong(KEY_ENDS, 0L), p.getString(KEY_STUDENT, null), p.getString(KEY_NAME, null), last
        ) else HolPlan.restingStatus(holOn, last)
        return reportable(raw)
    }

    /** MainActivity: after a visit ended before the activity existed, open the start page instead of restoring the visitor's WebView. */
    fun takeLoadHome(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_LOAD_HOME, false)) return false
        p.edit().remove(KEY_LOAD_HOME).commit()
        return true
    }

    /** Clears visit prefs without writing the house doc (the account is going away). */
    fun clearLocal(ctx: Context) {
        disarmed(ctx)
        prefs(ctx).edit().clear().commit()
    }

    fun onWarn(ctx: Context) {
        val c = ctx.applicationContext
        val v = storedVisit(c) ?: return
        val now = System.currentTimeMillis()
        if (!HolPlan.shouldWarn(v, now, prefs(c).getLong(KEY_WARNED_FOR, 0L).takeIf { it > 0L })) return
        prefs(c).edit().putLong(KEY_WARNED_FOR, HolPlan.warnAt(v.endsAtMs, v.warnMin)).commit()
        val text = HolPlan.warnText(v.warnMin)
        handler.post {
            Toast.makeText(c, text, Toast.LENGTH_LONG).show()
            if (!MainActivity.inForeground) notify(c, text)
        }
        arm(c) // the end alarm is still needed
    }

    private fun arm(ctx: Context) {
        handler.removeCallbacks(warnRunnable)
        handler.removeCallbacks(endRunnable)
        val v = storedVisit(ctx) ?: run { cancelAlarms(ctx); return }
        val now = System.currentTimeMillis()
        if (HolPlan.shouldEnd(v, now)) {
            handler.post { end(ctx, "visit ended") }
            return
        }
        val warnAt = HolPlan.warnAt(v.endsAtMs, v.warnMin)
        val already = prefs(ctx).getLong(KEY_WARNED_FOR, 0L) == warnAt
        if (!already && warnAt > now) {
            handler.postDelayed(warnRunnable, warnAt - now)
            schedule(ctx, PI_WARN, HolAlarmReceiver.ACTION_WARN, warnAt)
        } else {
            cancel(ctx, PI_WARN, HolAlarmReceiver.ACTION_WARN)
            if (!already && now >= warnAt) handler.post { onWarn(ctx) }
        }
        handler.postDelayed(endRunnable, (v.endsAtMs - now).coerceAtLeast(0L))
        schedule(ctx, PI_END, HolAlarmReceiver.ACTION_END, v.endsAtMs)
    }

    private fun disarmed(ctx: Context) {
        handler.removeCallbacks(warnRunnable)
        handler.removeCallbacks(endRunnable)
        cancelAlarms(ctx)
    }

    private fun cancelAlarms(ctx: Context) {
        cancel(ctx, PI_WARN, HolAlarmReceiver.ACTION_WARN)
        cancel(ctx, PI_END, HolAlarmReceiver.ACTION_END)
    }

    private fun schedule(ctx: Context, code: Int, action: String, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, code, action)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancel(ctx: Context, code: Int, action: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, code, action))
    }

    private fun pending(ctx: Context, code: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, HolAlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun notify(ctx: Context, text: String) {
        runCatching { KioskPolicy.grantPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "House of Learning", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Tapping it opens Little Explorer; if a parent pause is in force, MainActivity keeps the pause (#15).
        nm.notify(
            NOTIFY_ID,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("House of Learning")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        )
    }

    private fun lastMap(p: android.content.SharedPreferences): Map<String, Any?>? {
        val raw = p.getString(KEY_LAST, null) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return mapOf("at" to o.optLong("at"), "why" to o.optString("why"))
    }

    private fun writeHol(ctx: Context, hol: Map<String, Any?>) {
        if (HolDevice.explorerRef(ctx) != null) {
            val st = explorerState(ctx)
            write(ctx, mapOf(
                "visiting" to true,
                "name" to (st.name ?: ""),
                "activeStudentId" to (st.studentId ?: FieldValue.delete()),
                "current" to (st.current ?: FieldValue.delete()),
                "updatedOn" to FieldValue.serverTimestamp(),
            ))
        } else {
            val fields = HashMap<String, Any?>()
            putHolFields(fields, hol)
            write(ctx, fields)
        }
    }

    private fun writeEnded(ctx: Context, hol: Map<String, Any?>) {
        if (HolDevice.explorerRef(ctx) != null) {
            write(ctx, mapOf(
                "visiting" to false,
                "name" to "",
                "current" to FieldValue.delete(),
                "activeStudentId" to FieldValue.delete(),
                "last" to (hol["last"] ?: FieldValue.delete()),
                "updatedOn" to FieldValue.serverTimestamp(),
            ))
        } else {
            val fields = HashMap<String, Any?>()
            putHolFields(fields, hol)
            fields["activeStudentId"] = FieldValue.delete()
            fields["status.current"] = FieldValue.delete()
            write(ctx, fields)
        }
    }

    private fun audit(ctx: Context, type: String, st: ExplorerState = explorerState(ctx)) {
        if (HolDevice.explorerRef(ctx) == null) return
        HolDevice.writeAudit(ctx, type, HolDevicePlan.auditDetail(
            Prefs.deviceId(ctx), HolDevice.title(ctx), st.name, st.current,
        ))
    }

    /** Nested nulls become FieldValue.delete() on dotted paths so Firestore actually stores a cleared visit. */
    private fun putHolFields(fields: MutableMap<String, Any?>, hol: Map<String, Any?>) {
        for ((k, v) in hol) {
            if (v == null) fields["status.hol.$k"] = FieldValue.delete()
            else fields["status.hol.$k"] = if (k == "last") v else v
        }
    }

    private fun write(ctx: Context, fields: Map<String, Any?>) {
        if (fields.isEmpty()) return
        if (!DeviceSync.isSignedIn()) return
        val ref = DeviceSync.deviceRef(ctx) ?: return
        ref.update(fields)
            .addOnFailureListener { e -> android.util.Log.w("HolVisit", "status.hol update failed", e) }
    }

    private fun reportable(raw: Map<String, Any?>): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((k, v) in raw) {
            when (v) {
                null -> Unit
                is Map<*, *> -> out[k] = v.entries.associate { it.key.toString() to (it.value ?: "") }
                else -> out[k] = v
            }
        }
        return out
    }

    private fun firebaseValue(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> v.keys().asSequence().associateWith { firebaseValue(v.get(it)) }.filterValues { it != null }
        is JSONArray -> (0 until v.length()).mapNotNull { firebaseValue(v.opt(it)) }
        is Number, is Boolean, is String -> v
        else -> v.toString()
    }
}

/** Safety-net timer for [HolVisit] (warn, then end), independent of the rules-check alarm. */
class HolAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_WARN = "org.ed22.kiosk.HOL_WARN"
        const val ACTION_END = "org.ed22.kiosk.HOL_END"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_WARN -> HolVisit.onWarn(context)
            ACTION_END -> HolVisit.end(context, "visit ended")
        }
    }
}
