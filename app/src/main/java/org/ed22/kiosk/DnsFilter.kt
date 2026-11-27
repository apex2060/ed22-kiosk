package org.ed22.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Web filter through Private DNS (ANDROID_REQUEST_dns.md). Applies `policy.dns` from the device doc (family `exp_` doc, or
 * the paired House of Learning explorer doc) with the device-owner Private DNS calls, falls back to opportunistic DNS
 * when the filter can't be reached (the tablet stays online), recovers by itself, and sends one alert per long outage.
 * The decisions are in [DnsPlan]; this object does the Android work. Everything runs on one worker thread (the set
 * call probes the host over the network).
 *
 * Its prefs file (`kiosk_dns`: the last policy.dns, the saved previous Private DNS mode and the state) is not cleared
 * by [DeviceSync.signOut] or [HolVisit.clearLocal], so a visit wipe never drops the filter and an offline boot
 * re-applies it. Only a device-doc snapshot changes the stored policy.dns.
 */
object DnsFilter {
    private const val TAG = "DnsFilter"
    private const val PREFS = "kiosk_dns"
    private const val KEY_DNS = "policyDns"
    private const val KEY_STATE = "state"
    private const val KEY_PREV_SAVED = "prevSaved"
    private const val KEY_PREV_MODE = "prevMode"
    private const val KEY_PREV_HOST = "prevHost"
    private const val KEY_CHROME = "chromeDohOff"
    private const val KEY_VPN = "vpnLocked"
    private const val LOG_KIND = "dns"
    /** Network-change retries of the host (each one is a probe) are at least this far apart. */
    private const val NET_RETRY_GAP_MS = 20_000L

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kiosk-dns").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    // Default network as last seen (worker thread only).
    private var network: Network? = null
    private var caps: NetworkCapabilities? = null
    private var link: LinkProperties? = null
    private var netKey: String? = null
    private var lastTry = 0L
    private var deadline: ScheduledFuture<*>? = null
    private var retry: ScheduledFuture<*>? = null
    private var alertCheck: ScheduledFuture<*>? = null

    /** Main thread: the dns status changed (kiosk.on('dns')). */
    val listeners = mutableSetOf<(JSONObject) -> Unit>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        try {
            app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) { exec.execute { onNetwork(n, c, null) } }
                override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) { exec.execute { onNetwork(n, null, lp) } }
                override fun onLost(n: Network) { exec.execute { if (network == null || network == n) onNetwork(null, null, null) } }
            })
        } catch (e: RuntimeException) {
            Log.w(TAG, "network callback unavailable", e)
        }
        // Boot / app start (also offline: the last policy.dns is kept in this file).
        refresh("start")
    }

    private fun ready() = ::app.isInitialized

    // --- inputs ---

    /** A device-doc snapshot's `policy.dns` (null = absent → clear). Called on every snapshot; only a change applies. */
    fun onDocPolicy(ctx: Context, dns: JSONObject?) {
        val c = ctx.applicationContext
        val text = dns?.toString() ?: ""
        if (prefs(c).getString(KEY_DNS, null) == text) return
        prefs(c).edit().putString(KEY_DNS, text).commit()
        refresh("policy.dns rev ${DnsPlan.rev(dns)}")
    }

    /** The last policy.dns from the device doc (survives sign-out and the visit wipe). */
    fun storedDns(ctx: Context): JSONObject? =
        prefs(ctx).getString(KEY_DNS, null)?.takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** policy.dns.on (the browser / VPN limits follow this, not whether the filter is reachable). */
    fun on(ctx: Context): Boolean = DnsPlan.active(storedDns(ctx)) != null

    fun blocksApp(ctx: Context, studentId: String?, pkg: String): Boolean =
        pkg in DnsPlan.DOH_BROWSERS && DnsPlan.blocksApp(on(ctx), studentId, pkg)

    fun state(ctx: Context): DnsPlan.State =
        DnsPlan.State.fromJson(prefs(ctx).getString(KEY_STATE, null)?.let { runCatching { JSONObject(it) }.getOrNull() })

    /** In fallback: show [DnsPlan.PAUSED_TEXT] in parent mode and the Parent Menu. */
    fun paused(ctx: Context): Boolean = state(ctx).mode == DnsPlan.MODE_FALLBACK

    /** Re-pick and apply (child change, window edge, screen on, time change, the 15-minute report). Any thread. */
    fun refresh(why: String, retryHost: Boolean = false) {
        if (!ready()) return
        exec.execute { runCatching { step(why, retryHost) }.onFailure { e -> Log.w(TAG, "refresh ($why) failed", e) } }
    }

    /** The 15-minute status report: re-check, and send a pending alert (a failed POST is retried here). */
    fun tick() = refresh("report")

    /** `status.dns` / the house doc's `dns` as of now. */
    fun statusMap(ctx: Context): Map<String, Any?> {
        val s = state(ctx)
        return DnsPlan.status(s, s.mode == DnsPlan.MODE_HOST && privateDnsOk(s.host), System.currentTimeMillis())
    }

    fun statusJson(ctx: Context): JSONObject {
        val o = JSONObject()
        statusMap(ctx).forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        val paused = paused(ctx)
        return o.put("paused", paused).put("text", if (paused) DnsPlan.PAUSED_TEXT else JSONObject.NULL)
    }

    // --- worker thread ---

    private fun save(s: DnsPlan.State) {
        prefs(app).edit().putString(KEY_STATE, s.toJson().toString()).commit()
    }

    private fun step(why: String, retryHost: Boolean) {
        val dns = storedDns(app)
        val now = System.currentTimeMillis()
        val hol = HolDevice.on(app)
        val sid = Prefs.activeStudentId(app)
        val pick = DnsPlan.pick(dns, if (hol) null else sid, now, ZoneId.systemDefault(), hol)
        val before = state(app)
        val retryNow = retryHost || DnsPlan.retryDue(before, lastTry, now)
        val s = handle(DnsPlan.onPick(before, dns, pick, Build.VERSION.SDK_INT, now, retryNow), why)
        // Right after an apply, judge the network as it is now (a later callback updates it).
        if (s.mode == DnsPlan.MODE_HOST) handle(DnsPlan.onHealth(s, health(s.host), now), "network")
        limits(dns, sid)
        schedule(dns)
        maybeAlert(dns)
    }

    /** Saves the step's state, runs its action (and the result of the set call), reports when asked. Returns the final state. */
    private fun handle(step: DnsPlan.Step, why: String): DnsPlan.State {
        var st = step.state
        var report = step.report
        val before = state(app)
        save(st)
        when (step.action) {
            DnsPlan.Action.NONE -> Unit
            DnsPlan.Action.APPLY_HOST -> {
                if (before.mode == DnsPlan.MODE_FALLBACK) lastTry = System.currentTimeMillis()
                val err = applyHost(st.host)
                val next = DnsPlan.onApplied(st, err, System.currentTimeMillis())
                if (next.action == DnsPlan.Action.FALLBACK) fallbackNow()
                st = next.state
                report = report || next.report
                save(st)
                if (err != null) Log.w(TAG, "set ${st.host}: $err")
            }
            DnsPlan.Action.FALLBACK -> fallbackNow()
            DnsPlan.Action.RESTORE -> restore()
        }
        if (st.mode != before.mode) {
            KioskLog.add(app, LOG_KIND, when (st.mode) {
                DnsPlan.MODE_HOST -> "Web filter on (${st.why})" + (if (before.mode == DnsPlan.MODE_FALLBACK) " — filter reachable again" else "")
                DnsPlan.MODE_FALLBACK -> "Web filter paused: ${st.err} (stayed online)"
                DnsPlan.MODE_OFF -> "Web filter off"
                else -> "Web filter not supported on Android ${Build.VERSION.RELEASE}"
            } + " [$why]", important = st.mode == DnsPlan.MODE_FALLBACK)
        }
        if (report) changed(st, before)
        return st
    }

    private fun isOwner() = runCatching { KioskPolicy.isDeviceOwner(app) }.getOrDefault(false)

    /** null = in force (PRIVATE_DNS_SET_NO_ERROR, or already set), else DnsPlan.ERR_*. */
    private fun applyHost(host: String): String? {
        if (Build.VERSION.SDK_INT < DnsPlan.MIN_SDK) return DnsPlan.ERR_FAILURE_SETTING
        if (!isOwner()) return DnsPlan.ERR_FAILURE_SETTING
        val dpm = dpm(app)
        val admin = KioskPolicy.admin(app)
        savePrevious(dpm)
        val already = runCatching {
            dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME &&
                dpm.getGlobalPrivateDnsHost(admin).equals(host, ignoreCase = true)
        }.getOrDefault(false)
        val err = if (already) null else try {
            when (dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, host)) {
                DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR -> null
                DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING -> DnsPlan.ERR_HOST_NOT_SERVING
                else -> DnsPlan.ERR_FAILURE_SETTING
            }
        } catch (e: Exception) {
            Log.w(TAG, "setGlobalPrivateDnsModeSpecifiedHost failed", e)
            DnsPlan.ERR_FAILURE_SETTING
        }
        lockPrivateDns(true)
        return err
    }

    private fun fallbackNow() {
        if (Build.VERSION.SDK_INT < DnsPlan.MIN_SDK || !isOwner()) return
        try {
            val r = dpm(app).setGlobalPrivateDnsModeOpportunistic(KioskPolicy.admin(app))
            if (r != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) Log.w(TAG, "opportunistic: $r")
        } catch (e: Exception) {
            Log.w(TAG, "setGlobalPrivateDnsModeOpportunistic failed", e)
        }
        lockPrivateDns(true)
    }

    /** Before the first apply: the Private DNS mode / host in force, to restore when policy.dns is turned off. */
    private fun savePrevious(dpm: DevicePolicyManager) {
        val p = prefs(app)
        if (p.getBoolean(KEY_PREV_SAVED, false)) return
        val admin = KioskPolicy.admin(app)
        val mode = runCatching { dpm.getGlobalPrivateDnsMode(admin) }.getOrDefault(DevicePolicyManager.PRIVATE_DNS_MODE_UNKNOWN)
        val host = runCatching { dpm.getGlobalPrivateDnsHost(admin) }.getOrNull()
        p.edit().putBoolean(KEY_PREV_SAVED, true).putInt(KEY_PREV_MODE, mode).putString(KEY_PREV_HOST, host).commit()
    }

    /**
     * policy.dns off: back to the saved mode. A previous provider hostname is set again; anything else (off, unknown,
     * opportunistic) becomes opportunistic, Android's default (a device owner has no call that turns Private DNS off).
     */
    private fun restore() {
        val p = prefs(app)
        if (Build.VERSION.SDK_INT >= DnsPlan.MIN_SDK && isOwner()) {
            val dpm = dpm(app)
            val admin = KioskPolicy.admin(app)
            val mode = p.getInt(KEY_PREV_MODE, DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC)
            val host = p.getString(KEY_PREV_HOST, null)
            try {
                val r = if (mode == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME && !host.isNullOrBlank())
                    dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, host) else DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                if (r != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) dpm.setGlobalPrivateDnsModeOpportunistic(admin)
            } catch (e: Exception) {
                Log.w(TAG, "restore Private DNS failed", e)
            }
            lockPrivateDns(false)
        }
        p.edit().remove(KEY_PREV_SAVED).remove(KEY_PREV_MODE).remove(KEY_PREV_HOST).commit()
    }

    private fun lockPrivateDns(on: Boolean) {
        if (Build.VERSION.SDK_INT < DnsPlan.MIN_SDK || !isOwner()) return
        try {
            if (on) dpm(app).addUserRestriction(KioskPolicy.admin(app), UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            else dpm(app).clearUserRestriction(KioskPolicy.admin(app), UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        } catch (e: Exception) {
            Log.w(TAG, "DISALLOW_CONFIG_PRIVATE_DNS ($on) failed", e)
        }
    }

    /** §4: Chrome without its own DoH while policy.dns.on; DISALLOW_CONFIG_VPN in child mode while policy.dns.on. */
    private fun limits(dns: JSONObject?, sid: String?) {
        if (!isOwner()) return
        val on = DnsPlan.active(dns) != null && Build.VERSION.SDK_INT >= DnsPlan.MIN_SDK
        val p = prefs(app)
        val dpm = dpm(app)
        val admin = KioskPolicy.admin(app)
        val chromeSet = p.getBoolean(KEY_CHROME, false)
        if (on != chromeSet) try {
            val b = Bundle(dpm.getApplicationRestrictions(admin, DnsPlan.CHROME))
            if (on) b.putString(DnsPlan.CHROME_DOH_KEY, DnsPlan.CHROME_DOH_OFF) else b.remove(DnsPlan.CHROME_DOH_KEY)
            dpm.setApplicationRestrictions(admin, DnsPlan.CHROME, b)
            p.edit().putBoolean(KEY_CHROME, on).commit()
        } catch (e: Exception) {
            Log.w(TAG, "Chrome DnsOverHttpsMode failed", e)
        }
        val wantVpn = DnsPlan.wantsVpnLock(on, sid)
        val vpnByUs = p.getBoolean(KEY_VPN, false)
        try {
            val already = dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_CONFIG_VPN, false)
            if (wantVpn && !already) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
                p.edit().putBoolean(KEY_VPN, true).commit()
            } else if (!wantVpn && vpnByUs) {
                // Only lifted when this filter set it.
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
                p.edit().putBoolean(KEY_VPN, false).commit()
            }
        } catch (e: Exception) {
            Log.w(TAG, "DISALLOW_CONFIG_VPN failed", e)
        }
    }

    // --- network ---

    private fun onNetwork(n: Network?, c: NetworkCapabilities?, lp: LinkProperties?) {
        if (n == null) { network = null; caps = null; link = null }
        else {
            if (n != network) { caps = null; link = null }
            network = n
            if (c != null) caps = c
            if (lp != null) link = lp
        }
        val key = network?.let { "$it|${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}|" +
            "${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)}" }
        val changed = key != netKey
        netKey = key
        val s = state(app)
        when {
            s.mode == DnsPlan.MODE_HOST -> {
                val after = handle(DnsPlan.onHealth(s, health(s.host), System.currentTimeMillis()), "network")
                if (after.mode != s.mode || after.failSince != s.failSince) schedule(storedDns(app))
            }
            // Fallback: every network change is a chance the filter is back (a captive portal signed in, new Wi-Fi).
            s.mode == DnsPlan.MODE_FALLBACK && changed && network != null &&
                System.currentTimeMillis() - lastTry >= NET_RETRY_GAP_MS -> step("network change", true)
        }
    }

    private fun health(host: String): DnsPlan.Health {
        if (network == null) return DnsPlan.Health.NO_NETWORK
        val c = caps ?: return DnsPlan.Health.NO_NETWORK
        if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) return DnsPlan.Health.CAPTIVE
        return if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && dnsOn(link, host)) DnsPlan.Health.OK
        else DnsPlan.Health.FAILING
    }

    private fun dnsOn(lp: LinkProperties?, host: String): Boolean =
        lp != null && lp.isPrivateDnsActive && lp.privateDnsServerName.equals(host, ignoreCase = true)

    /** `ok`: Private DNS active with [host] on the default network (read fresh, any thread). */
    private fun privateDnsOk(host: String): Boolean = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && dnsOn(cm.getLinkProperties(n), host)
    }.getOrDefault(false)

    // --- timers ---

    /** The 60-second failure deadline, the 5-minute retry and alert check (worker), and the next window edge (exact alarm). */
    private fun schedule(dns: JSONObject?) {
        val s = state(app)
        val now = System.currentTimeMillis()
        deadline?.cancel(false); deadline = null
        DnsPlan.failDeadline(s)?.let { at ->
            deadline = exec.schedule({ runCatching { step("60 s without the filter", false) } }, maxOf(0L, at - now) + 500, TimeUnit.MILLISECONDS)
        }
        retry?.cancel(false); retry = null
        alertCheck?.cancel(false); alertCheck = null
        if (s.mode == DnsPlan.MODE_FALLBACK) {
            val nextTry = maxOf(now, lastTry + DnsPlan.RETRY_MS)
            retry = exec.schedule({ runCatching { step("5-minute retry", true) } }, nextTry - now + 500, TimeUnit.MILLISECONDS)
            if (!s.alertSent) {
                val at = s.since + DnsPlan.ALERT_AFTER_MS
                if (at > now) alertCheck = exec.schedule({ runCatching { maybeAlert(storedDns(app)) } }, at - now + 500, TimeUnit.MILLISECONDS)
            }
        }
        val edge = DnsPlan.nextEdge(dns, Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(app, 7401, Intent(app, DnsAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        if (edge != null) {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, edge + 500, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, edge + 500, pi)
        }
    }

    // --- report / alert ---

    private fun changed(s: DnsPlan.State, before: DnsPlan.State) {
        runCatching { DeviceSync.writeDnsStatus(app, statusMap(app)) }.onFailure { e -> Log.w(TAG, "dns status write failed", e) }
        val json = statusJson(app)
        val nowPaused = s.mode == DnsPlan.MODE_FALLBACK && before.mode != DnsPlan.MODE_FALLBACK
        main.post {
            listeners.toList().forEach { runCatching { it(json) } }
            // Parent mode only: nothing on a child's screen.
            if (nowPaused && Prefs.activeStudentId(app) == null && MainActivity.inForeground) {
                Toast.makeText(app, DnsPlan.PAUSED_TEXT, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun maybeAlert(dns: JSONObject?) {
        val s = state(app)
        val now = System.currentTimeMillis()
        if (!DnsPlan.shouldAlert(s, DnsPlan.notify(dns), now)) return
        save(s.copy(alertTriedAt = now))
        val ok = runCatching { postAlert(s) }.onFailure { e -> Log.w(TAG, "alert failed: ${e.javaClass.simpleName}") }.getOrDefault(false)
        val cur = state(app)
        // Only for the same episode (it may have recovered while the POST ran).
        if (ok && cur.mode == DnsPlan.MODE_FALLBACK && cur.since == s.since) {
            save(cur.copy(alertSent = true))
            KioskLog.add(app, LOG_KIND, "Told the parent the web filter is paused (${s.err})")
        }
    }

    /** POST {action:'alert', jwt, device, mode, err}. The ID token is never logged or stored. */
    private fun postAlert(s: DnsPlan.State): Boolean {
        val user = FirebaseAuth.getInstance().currentUser ?: return false
        val device = if (HolDevice.on(app)) {
            val serviceId = HolDevice.serviceId(app) ?: return false
            val id = HolDevice.path(app)?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return false
            DnsPlan.holDevice(serviceId, id)
        } else {
            DnsPlan.familyDevice(DeviceSync.deviceDocId(app)?.takeIf { it.startsWith("exp_") } ?: return false)
        }
        val jwt = Tasks.await(user.getIdToken(false), 30, TimeUnit.SECONDS).token ?: return false
        val body = DnsPlan.alertBody(jwt, device, s).toString().toByteArray(Charsets.UTF_8)
        val conn = URL(DnsPlan.ALERT_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.useCaches = false
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            runCatching { (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } }
            if (code !in 200..299) Log.w(TAG, "alert HTTP $code")
            return code in 200..299
        } finally {
            conn.disconnect()
        }
    }
}

/** The next policy.dns window edge (see DnsFilter.schedule). */
class DnsAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DnsFilter.refresh("window edge")
    }
}
