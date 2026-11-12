package org.ed22.kiosk

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure pieces of the offline / exit recovery and the device event log (no Android calls, so they run in JVM tests).
 * The Android side is in [KioskLog], [KioskWatchdog], [NetworkWatch] and MainActivity.
 */
object Recovery {
    /** Entries kept on the device (persisted). */
    const val LOG_MAX = 200
    /** Entries uploaded as status.log (newest last). */
    const val UPLOAD_MAX = 50
    const val MSG_MAX = 300
    const val DETAIL_MAX = 3000
    const val UPLOAD_DETAIL_MAX = 1500

    /** Renderer crashes within [RENDERER_WINDOW_MS] before the rebuild is delayed by [RENDERER_BACKOFF_MS]. */
    const val RENDERER_LIMIT = 3
    const val RENDERER_WINDOW_MS = 2 * 60_000L
    const val RENDERER_BACKOFF_MS = 15_000L

    /** App crashes within [CRASH_WINDOW_MS] before the restart waits [CRASH_BACKOFF_MS] instead of [CRASH_RESTART_MS]. */
    const val CRASH_LIMIT = 3
    const val CRASH_WINDOW_MS = 2 * 60_000L
    const val CRASH_RESTART_MS = 2_000L
    const val CRASH_BACKOFF_MS = 60_000L

    /** At most one automatic page recovery per this many ms. */
    const val RELOAD_GAP_MS = 10_000L

    /** Watchdog checks in a row that must see "escaped" before it reopens the kiosk. */
    const val WATCHDOG_STRIKES = 2

    // --- log ring ---

    /**
     * Adds an entry `{at, kind, msg, detail?}` to [list] (oldest first) and trims it to [max]. A repeat of the newest
     * entry (same kind and msg) is folded into it: `n` counts repeats and `lastAt` is the newest time, so a retry loop
     * while offline doesn't push everything else out.
     */
    fun append(list: JSONArray, at: Long, kind: String, msg: String, detail: String? = null, max: Int = LOG_MAX): JSONArray {
        val m = msg.take(MSG_MAX)
        val last = if (list.length() > 0) list.optJSONObject(list.length() - 1) else null
        if (last != null && detail == null && last.optString("kind") == kind && last.optString("msg") == m) {
            last.put("n", last.optInt("n", 1) + 1).put("lastAt", at)
            return list
        }
        val e = JSONObject().put("at", at).put("kind", kind).put("msg", m)
        if (!detail.isNullOrBlank()) e.put("detail", detail.take(DETAIL_MAX))
        list.put(e)
        if (list.length() <= max) return list
        val out = JSONArray()
        for (i in list.length() - max until list.length()) out.put(list.get(i))
        return out
    }

    /** Parses the stored ring; anything unreadable starts a new one. */
    fun parse(text: String?): JSONArray = text?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()

    /** status.log: the newest [max] entries as Firestore-friendly maps, with long details cut to [UPLOAD_DETAIL_MAX]. */
    fun forUpload(list: JSONArray, max: Int = UPLOAD_MAX): List<Map<String, Any>> {
        val from = maxOf(0, list.length() - max)
        return (from until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val m = LinkedHashMap<String, Any>()
            m["at"] = o.optLong("at")
            m["kind"] = o.optString("kind")
            m["msg"] = o.optString("msg")
            if (o.has("detail")) m["detail"] = o.optString("detail").take(UPLOAD_DETAIL_MAX)
            if (o.has("n")) m["n"] = o.optInt("n")
            if (o.has("lastAt")) m["lastAt"] = o.optLong("lastAt")
            m
        }
    }

    /** `{"at": .., "why": ..}` for status.lastExit. */
    fun lastExit(at: Long, why: String): JSONObject = JSONObject().put("at", at).put("why", why.take(MSG_MAX))

    /** Throwable -> one line for msg. */
    fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.last()
        val head = "${e.javaClass.name}: ${e.message ?: ""}".trim().trimEnd(':')
        return if (root === e) head else "$head (cause ${root.javaClass.simpleName}: ${root.message ?: ""})".replace(": )", ")")
    }

    // --- crash / renderer loops ---

    /** Keeps the times in [window] ms before [now], plus [now]. */
    fun recent(times: List<Long>, now: Long, window: Long): List<Long> = times.filter { now - it in 0 until window } + now

    /** Delay before reopening after a crash, given the crash times (this one included) in the window. */
    fun crashRestartDelay(recentCrashes: Int): Long = if (recentCrashes >= CRASH_LIMIT) CRASH_BACKOFF_MS else CRASH_RESTART_MS

    /** Delay before rebuilding the WebView after its renderer died, given the deaths (this one included) in the window. */
    fun rendererRebuildDelay(recentDeaths: Int): Long = if (recentDeaths >= RENDERER_LIMIT) RENDERER_BACKOFF_MS else 0L

    // --- page recovery when the network comes back ---

    /**
     * What to do when a validated network appears. [showingOffline] = the offline page is up; [lastLoadFailed] = the last
     * main-frame load failed; [brokenLoad] = a request failed while the current page was
     * still loading (so its scripts or data may be missing); [msSinceRecovery] = time since the last automatic recovery.
     */
    enum class PageAction { NONE, RETRY_TARGET, RELOAD }

    fun onNetworkBack(showingOffline: Boolean, lastLoadFailed: Boolean, brokenLoad: Boolean, msSinceRecovery: Long): PageAction = when {
        msSinceRecovery in 0 until RELOAD_GAP_MS -> PageAction.NONE
        showingOffline -> PageAction.RETRY_TARGET
        lastLoadFailed || brokenLoad -> PageAction.RELOAD
        else -> PageAction.NONE
    }

    /** The page the offline screen should retry (its #hash), or the start page. */
    fun offlineTarget(offlineUrl: String?, startUrl: String): String {
        val hash = offlineUrl?.substringAfter('#', "")?.takeIf { it.isNotBlank() } ?: return startUrl
        val decoded = runCatching { java.net.URLDecoder.decode(hash, "UTF-8") }.getOrDefault(hash)
        return if (decoded.startsWith("https://") || decoded.startsWith("http://")) decoded else startUrl
    }

    // --- watchdog ---

    data class Watch(
        val maintenance: Boolean,
        val deviceOwner: Boolean,
        val hasPin: Boolean,
        val screenOn: Boolean,
        /** MainActivity exists in this process. */
        val activityAlive: Boolean,
        /** MainActivity is resumed. */
        val inForeground: Boolean,
        val lockTaskActive: Boolean,
        /** An allowed Android app opened from the kiosk (AppUsage.openPkg). */
        val appOpen: Boolean,
    )

    enum class WatchAction { NONE, STRIKE, RELAUNCH }

    /**
     * Only acts when the kiosk should own the screen: device owner, PIN set, not paused by a parent, screen on, and no
     * allowed app open. Then: no kiosk activity at all -> relaunch. Kiosk not in front AND lock task off -> the child is
     * outside the kiosk (normally the system home screen): a strike, and [WATCHDOG_STRIKES] in a row relaunch. Lock task
     * still on (file picker, permission prompt, PIN change) is never touched.
     */
    fun watch(w: Watch, strikes: Int): WatchAction = when {
        w.maintenance || !w.deviceOwner || !w.hasPin || !w.screenOn || w.appOpen -> WatchAction.NONE
        !w.activityAlive -> WatchAction.RELAUNCH
        w.inForeground || w.lockTaskActive -> WatchAction.NONE
        strikes + 1 >= WATCHDOG_STRIKES -> WatchAction.RELAUNCH
        else -> WatchAction.STRIKE
    }

    // --- historical exit reasons (ApplicationExitInfo, Android 11+) ---

    /** ApplicationExitInfo.REASON_* -> name. */
    fun exitReasonName(reason: Int): String = when (reason) {
        0 -> "unknown"; 1 -> "exit self"; 2 -> "signaled"; 3 -> "low memory"; 4 -> "crash"; 5 -> "native crash"
        6 -> "ANR"; 7 -> "initialization failure"; 8 -> "permission change"; 9 -> "excessive resource usage"
        10 -> "user requested"; 11 -> "user stopped"; 12 -> "dependency died"; 13 -> "other"; 14 -> "freezer"
        15 -> "package state change"; 16 -> "package updated"
        else -> "reason $reason"
    }

    /** Expected exits (an update, a parent or the system stopping the app on purpose, or our own exit after a crash that is already logged): logged, not lastExit. */
    fun isExpectedExit(reason: Int): Boolean = reason in setOf(1, 8, 10, 11, 15, 16)
}
