package org.ed22.kiosk

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline recovery, the watchdog rule, and the device event log ring (Recovery). */
class RecoveryTest {
    @Test
    fun ringKeepsTheNewest200() {
        var list = JSONArray()
        for (i in 1..250) list = Recovery.append(list, i.toLong(), "k", "event $i")
        assertEquals(Recovery.LOG_MAX, list.length())
        assertEquals("event 51", list.getJSONObject(0).getString("msg"))
        assertEquals("event 250", list.getJSONObject(199).getString("msg"))
        // Survives a save / load.
        assertEquals(200, Recovery.parse(list.toString()).length())
        assertEquals(0, Recovery.parse("not json").length())
        assertEquals(0, Recovery.parse(null).length())
    }

    @Test
    fun repeatsAreFoldedIntoOneEntry() {
        var list = JSONArray()
        list = Recovery.append(list, 1, "loadFailed", "-2 net::ERR_INTERNET_DISCONNECTED https://us.ed22.org/mobile")
        list = Recovery.append(list, 11, "loadFailed", "-2 net::ERR_INTERNET_DISCONNECTED https://us.ed22.org/mobile")
        list = Recovery.append(list, 21, "loadFailed", "-2 net::ERR_INTERNET_DISCONNECTED https://us.ed22.org/mobile")
        assertEquals(1, list.length())
        assertEquals(3, list.getJSONObject(0).getInt("n"))
        assertEquals(1L, list.getJSONObject(0).getLong("at"))
        assertEquals(21L, list.getJSONObject(0).getLong("lastAt"))
        list = Recovery.append(list, 30, "network", "Internet back")
        list = Recovery.append(list, 31, "loadFailed", "-2 net::ERR_INTERNET_DISCONNECTED https://us.ed22.org/mobile")
        assertEquals(3, list.length())
        // Crashes (with a stack) are never folded.
        list = Recovery.append(list, 40, "crash", "boom", "stack")
        list = Recovery.append(list, 41, "crash", "boom", "stack")
        assertEquals(5, list.length())
    }

    @Test
    fun uploadIsTheNewest50WithShortDetails() {
        var list = JSONArray()
        for (i in 1..80) list = Recovery.append(list, i.toLong(), "k", "e$i")
        list = Recovery.append(list, 99, "crash", "x".repeat(1000), "s".repeat(5000))
        val up = Recovery.forUpload(list)
        assertEquals(Recovery.UPLOAD_MAX, up.size)
        assertEquals("e32", up.first()["msg"])
        val last = up.last()
        assertEquals("crash", last["kind"])
        assertEquals(Recovery.MSG_MAX, (last["msg"] as String).length)
        assertEquals(Recovery.UPLOAD_DETAIL_MAX, (last["detail"] as String).length)
        assertEquals(99L, last["at"])
    }

    @Test
    fun lastExitAndDescribe() {
        val e = Recovery.lastExit(5, "page renderer crashed")
        assertEquals(5L, e.getLong("at")); assertEquals("page renderer crashed", e.getString("why"))
        val d = Recovery.describe(RuntimeException("outer", IllegalStateException("inner")))
        assertTrue(d, d.startsWith("java.lang.RuntimeException: outer"))
        assertTrue(d, d.contains("IllegalStateException: inner"))
        assertEquals("java.lang.NullPointerException", Recovery.describe(NullPointerException()))
    }

    @Test
    fun crashAndRendererLoopsBackOff() {
        val now = 1_000_000L
        assertEquals(listOf(now), Recovery.recent(emptyList(), now, 120_000))
        val three = Recovery.recent(listOf(now - 200_000, now - 60_000, now - 1_000), now, 120_000)
        assertEquals(3, three.size) // the old one dropped, this one added
        assertEquals(Recovery.CRASH_RESTART_MS, Recovery.crashRestartDelay(2))
        assertEquals(Recovery.CRASH_BACKOFF_MS, Recovery.crashRestartDelay(3))
        assertEquals(0L, Recovery.rendererRebuildDelay(1))
        assertEquals(Recovery.RENDERER_BACKOFF_MS, Recovery.rendererRebuildDelay(3))
    }

    @Test
    fun networkBackRecoversOnlyBrokenPages() {
        val long = 60_000L
        assertEquals(Recovery.PageAction.RETRY_TARGET, Recovery.onNetworkBack(true, true, false, long))
        assertEquals(Recovery.PageAction.RELOAD, Recovery.onNetworkBack(false, true, false, long))
        assertEquals(Recovery.PageAction.RELOAD, Recovery.onNetworkBack(false, false, true, long))
        // A page that loaded fine is never reloaded just because Wi-Fi blinked.
        assertEquals(Recovery.PageAction.NONE, Recovery.onNetworkBack(false, false, false, long))
        // At most one recovery per 10 s.
        assertEquals(Recovery.PageAction.NONE, Recovery.onNetworkBack(true, true, true, 3_000))
        assertEquals(Recovery.PageAction.RETRY_TARGET, Recovery.onNetworkBack(true, false, false, Recovery.RELOAD_GAP_MS))
    }

    @Test
    fun offlineTargetIsTheHashOrTheStartPage() {
        val start = "https://us.ed22.org/mobile"
        assertEquals("https://us.ed22.org/me?x=1&y=2",
            Recovery.offlineTarget("file:///android_asset/offline.html#https%3A%2F%2Fus.ed22.org%2Fme%3Fx%3D1%26y%3D2", start))
        assertEquals(start, Recovery.offlineTarget("file:///android_asset/offline.html", start))
        assertEquals(start, Recovery.offlineTarget("file:///android_asset/offline.html#javascript%3Aalert(1)", start))
        assertEquals(start, Recovery.offlineTarget(null, start))
    }

    private val owning = Recovery.Watch(
        maintenance = false, deviceOwner = true, hasPin = true, screenOn = true,
        activityAlive = true, inForeground = false, lockTaskActive = false, appOpen = false,
    )

    @Test
    fun watchdogReopensWhenTheKioskWasLeft() {
        assertEquals(Recovery.WatchAction.STRIKE, Recovery.watch(owning, 0))
        assertEquals(Recovery.WatchAction.RELAUNCH, Recovery.watch(owning, 1))
        assertEquals(Recovery.WatchAction.RELAUNCH, Recovery.watch(owning.copy(activityAlive = false), 0))
    }

    @Test
    fun watchdogLeavesEverythingElseAlone() {
        val none = Recovery.WatchAction.NONE
        assertEquals(none, Recovery.watch(owning.copy(inForeground = true), 5))
        assertEquals(none, Recovery.watch(owning.copy(lockTaskActive = true), 5))     // picker, prompt, PIN change
        assertEquals(none, Recovery.watch(owning.copy(appOpen = true), 5))            // an allowed Android app
        assertEquals(none, Recovery.watch(owning.copy(appOpen = true, activityAlive = false), 5))
        assertEquals(none, Recovery.watch(owning.copy(maintenance = true, activityAlive = false), 5)) // parent exited
        assertEquals(none, Recovery.watch(owning.copy(deviceOwner = false, activityAlive = false), 5))
        assertEquals(none, Recovery.watch(owning.copy(hasPin = false, activityAlive = false), 5))   // first-run PIN setup
        assertEquals(none, Recovery.watch(owning.copy(screenOn = false), 5))
    }

    @Test
    fun exitReasons() {
        assertEquals("low memory", Recovery.exitReasonName(3))
        assertEquals("crash", Recovery.exitReasonName(4))
        assertEquals("ANR", Recovery.exitReasonName(6))
        assertEquals("reason 99", Recovery.exitReasonName(99))
        assertTrue(Recovery.isExpectedExit(16)) // package updated (self-update)
        assertFalse(Recovery.isExpectedExit(3))
        assertFalse(Recovery.isExpectedExit(4))
    }
}
