package org.ed22.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlin.system.exitProcess

/**
 * Uncaught exceptions: logged with their stack (status.log / status.lastExit), then, while the kiosk should own the
 * screen, the app reopens itself a moment later instead of leaving the child on the system home screen.
 */
object CrashGuard {
    private const val TAG = "CrashGuard"
    private const val PREFS = "kiosk_log"
    private const val KEY_CRASHES = "crashTimes"
    private const val RESTART_REQUEST = 7301

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            var restart = false
            try {
                val stack = Log.getStackTraceString(e)
                val why = "crash on ${thread.name}: ${Recovery.describe(e)}"
                KioskLog.add(app, "crash", why, stack)
                KioskLog.exit(app, why)
                restart = shouldOwnScreen(app)
                if (restart) {
                    val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val now = System.currentTimeMillis()
                    val old = p.getString(KEY_CRASHES, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
                    val times = Recovery.recent(old, now, Recovery.CRASH_WINDOW_MS)
                    p.edit().putString(KEY_CRASHES, times.joinToString(",")).commit()
                    val delay = Recovery.crashRestartDelay(times.size)
                    scheduleRestart(app, delay)
                    KioskLog.add(app, "relaunch", "Reopening Little Explorer in ${delay / 1000}s after a crash (${times.size} in 2 min)")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Crash handler failed", t)
            }
            if (restart) {
                // Quietly end this process; the alarm reopens the kiosk (no "keeps stopping" box for the child).
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            } else {
                previous?.uncaughtException(thread, e)
            }
        }
    }

    /** Device owner, a parent PIN, and not paused from the parent menu. */
    fun shouldOwnScreen(ctx: Context): Boolean =
        !Prefs.isMaintenance(ctx) && PinStore.hasPin(ctx) && KioskPolicy.isDeviceOwner(ctx)

    fun scheduleRestart(ctx: Context, delayMs: Long) {
        val pi = PendingIntent.getActivity(
            ctx, RESTART_REQUEST,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + delayMs
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule the restart", e)
        }
    }
}

/**
 * Default-network watcher for the whole app: logs network lost / back (validated internet), lets the kiosk page
 * recover ([listeners]) and has [DeviceSync] reconnect and upload the log when the connection is back.
 */
object NetworkWatch {
    private val handler = Handler(Looper.getMainLooper())
    private var current: Network? = null
    private var validated = false
    private var since = SystemClock.elapsedRealtime()

    /** True when the default network has validated internet. */
    @Volatile var online = false
        private set

    /** Called on the main thread when [online] turns true. */
    val listeners = mutableSetOf<() -> Unit>()

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        online = runCatching {
            cm.getNetworkCapabilities(cm.activeNetwork)?.let(::hasInternet) == true
        }.getOrDefault(false)
        if (!online) KioskLog.add(app, "network", "No internet at app start")
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val ok = hasInternet(caps)
                    handler.post { current = network; validated = ok; set(app, ok, transport(caps)) }
                }

                override fun onLost(network: Network) {
                    handler.post { if (current == null || current == network) { current = null; validated = false; set(app, false, null) } }
                }
            })
        } catch (e: RuntimeException) {
            KioskLog.add(app, "network", "Network watcher unavailable: ${e.message}")
        }
    }

    private fun hasInternet(caps: NetworkCapabilities) =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private fun transport(caps: NetworkCapabilities) = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }

    private fun set(ctx: Context, now: Boolean, how: String?) {
        if (now == online) return
        online = now
        val secs = (SystemClock.elapsedRealtime() - since) / 1000
        since = SystemClock.elapsedRealtime()
        if (now) {
            KioskLog.add(ctx, "network", "Internet back ($how) after ${secs}s offline")
            listeners.toList().forEach { runCatching { it() }.onFailure { e -> Log.w("NetworkWatch", "listener", e) } }
            // Give DNS / Firestore a moment, then reconnect the device doc and upload status.log.
            handler.postDelayed({ runCatching { DeviceSync.onNetworkBack() } }, 3_000)
        } else {
            KioskLog.add(ctx, "network", "Internet lost after ${secs}s online")
        }
    }
}

/**
 * A light, in-process watchdog: a check about once a minute while the app runs (main-thread handler, so it never wakes
 * the device or starts the app by itself) and a check on screen on. While the kiosk should own the screen it reopens
 * Little Explorer if its activity is gone, or if it is not in front with lock task off (the system home screen) twice
 * in a row. See [Recovery.watch] for the exact rule. A dead process is covered by [CrashGuard] (crash) and by the
 * kiosk being the persistent Home app (any other exit).
 */
object KioskWatchdog {
    private const val INTERVAL_MS = 60_000L
    private val handler = Handler(Looper.getMainLooper())
    private var strikes = 0
    private lateinit var app: Context
    private val tick = object : Runnable {
        override fun run() {
            runCatching { check(app) }.onFailure { e -> Log.w("KioskWatchdog", "check failed", e) }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun schedule(ctx: Context) {
        app = ctx.applicationContext
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, INTERVAL_MS)
    }

    /** Runs a check after [delayMs]. */
    fun checkSoon(ctx: Context, delayMs: Long) {
        val c = ctx.applicationContext
        handler.postDelayed({ runCatching { check(c) } }, delayMs)
    }

    fun check(ctx: Context) {
        val app = ctx.applicationContext
        val w = Recovery.Watch(
            maintenance = Prefs.isMaintenance(app),
            deviceOwner = KioskPolicy.isDeviceOwner(app),
            hasPin = PinStore.hasPin(app),
            screenOn = app.getSystemService(PowerManager::class.java).isInteractive,
            activityAlive = MainActivity.current?.let { !it.isFinishing && !it.isDestroyed } == true,
            inForeground = MainActivity.inForeground,
            lockTaskActive = KioskPolicy.isLockTaskActive(app),
            appOpen = AppUsage.openPkg(app) != null,
        )
        when (Recovery.watch(w, strikes)) {
            Recovery.WatchAction.NONE -> strikes = 0
            Recovery.WatchAction.STRIKE -> strikes++
            Recovery.WatchAction.RELAUNCH -> {
                strikes = 0
                val why = if (!w.activityAlive) "kiosk was not running" else "kiosk was not in front and lock task was off (likely the home screen)"
                KioskLog.add(app, "leftKiosk", "Watchdog reopened Little Explorer: $why", important = true)
                KioskLog.exit(app, "left the kiosk: $why")
                DeviceSync.openKiosk(app)
            }
        }
    }
}
