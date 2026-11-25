package org.ed22.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** policy.command screenshot: RemoteCommands.decide, features, ScreenshotPlan (scope, size steps, subdoc and status shape). */
class ScreenshotTest {
    private val now = 1_793_000_000_000L
    private val kiosk = "org.ed22.kiosk"
    private val yt = ScreenshotPlan.AppRef("com.google.android.apps.youtube.kids", "YouTube Kids")
    private fun shot(id: Any) = JSONObject().put("id", id).put("type", "screenshot")

    @Test
    fun runsOncePerIdWhileFreshEvenWhenLocked() {
        val d = RemoteCommands.decide(shot(now.toString()), null, now + 1_000, locked = false) { false }
        assertTrue(d.run); assertTrue(d.handled)
        val locked = RemoteCommands.decide(shot(now.toString()), null, now + 1_000, locked = true) { false }
        assertTrue(locked.run); assertTrue(locked.handled)
        val again = RemoteCommands.decide(shot(now.toString()), now.toString(), now, false) { true }
        assertFalse(again.run); assertFalse(again.handled)
    }

    @Test
    fun oldOrNonNumericIdIsDropped() {
        assertTrue(RemoteCommands.decide(shot((now - 119_000).toString()), null, now, true) { true }.run)
        val old = RemoteCommands.decide(shot((now - 121_000).toString()), null, now, false) { true }
        assertFalse(old.run); assertTrue(old.handled)
        val text = RemoteCommands.decide(shot("abc"), null, now, false) { true }
        assertFalse(text.run); assertTrue(text.handled)
    }

    @Test
    fun otherLockRulesUnchanged() {
        val open = JSONObject().put("id", now.toString()).put("type", "openApp").put("package", "p")
        assertFalse(RemoteCommands.decide(open, null, now, true) { true }.run)
        assertTrue("screenshot" in RemoteCommands.TIMED_TYPES)
        assertTrue("ring" in RemoteCommands.RING_TYPES && "ringStop" in RemoteCommands.RING_TYPES)
    }

    @Test
    fun featuresListScreenshotAndFullOnlyWhenReady() {
        assertTrue("screenshot" in RemoteCommands.FEATURES)
        assertFalse("screenshotFull" in RemoteCommands.FEATURES)
        assertTrue("screenshot" in RemoteCommands.features(false))
        assertFalse("screenshotFull" in RemoteCommands.features(false))
        assertTrue("screenshotFull" in RemoteCommands.features(true))
        assertEquals(RemoteCommands.FEATURES, RemoteCommands.features(false))
        assertTrue(RemoteCommands.features(true).containsAll(RemoteCommands.FEATURES))
    }

    @Test
    fun screenOffFailsWithoutCapture() {
        for (sdk in listOf(26, 29, 30, 34)) for (ready in listOf(false, true)) {
            val p = ScreenshotPlan.plan(sdk, ready, interactive = false, kioskInFront = true, front = null, kioskPkg = kiosk)
            assertFalse(p.ok); assertEquals("The screen is off", p.why)
        }
    }

    @Test
    fun fullScreenOnAndroid11WithService() {
        val other = ScreenshotPlan.plan(30, true, true, kioskInFront = false, front = yt, kioskPkg = kiosk)
        assertTrue(other.ok); assertEquals("screen", other.scope); assertEquals(yt, other.app)
        val own = ScreenshotPlan.plan(34, true, true, kioskInFront = true, front = null, kioskPkg = kiosk)
        assertEquals("screen", own.scope); assertEquals(ScreenshotPlan.AppRef(kiosk, "Little Explorer"), own.app)
        // scope 'screen' always names an app.
        val unknown = ScreenshotPlan.plan(34, true, true, kioskInFront = false, front = null, kioskPkg = kiosk)
        assertEquals("screen", unknown.scope); assertEquals("Another screen", unknown.app!!.label)
    }

    @Test
    fun fallbackIsTheKioskWindowOnly() {
        // Service off (Android 11+) or Android 8-10: own window when in front.
        for (sdk in listOf(26, 29, 30)) {
            val p = ScreenshotPlan.plan(sdk, fullReady = sdk >= 30 && false, interactive = true, kioskInFront = true, front = yt, kioskPkg = kiosk)
            assertEquals("app", p.scope); assertEquals("Little Explorer", p.app!!.label)
        }
        // The service "ready" flag is ignored below Android 11.
        assertEquals("app", ScreenshotPlan.plan(29, true, true, true, null, kiosk).scope)
        // Another app in front: no capture, no bringing the kiosk forward, a readable why.
        val off11 = ScreenshotPlan.plan(31, false, true, kioskInFront = false, front = yt, kioskPkg = kiosk)
        assertFalse(off11.ok)
        assertEquals("YouTube Kids is open. Turn on Little Explorer in Android Settings › Accessibility to see other apps", off11.why)
        val old = ScreenshotPlan.plan(29, false, true, kioskInFront = false, front = yt, kioskPkg = kiosk)
        assertEquals("YouTube Kids is open; this Android version can only show Little Explorer", old.why)
        assertTrue(ScreenshotPlan.plan(29, false, true, false, null, kiosk).why.startsWith("Another app is open"))
    }

    @Test
    fun scaledToLongestSide1280NeverUp() {
        assertEquals(800 to 1280, ScreenshotPlan.scaled(1600, 2560))
        assertEquals(1280 to 800, ScreenshotPlan.scaled(2560, 1600))
        assertEquals(1280 to 576, ScreenshotPlan.scaled(1920, 864))
        assertEquals(720 to 1280, ScreenshotPlan.scaled(720, 1280))
        assertEquals(600 to 400, ScreenshotPlan.scaled(600, 400))
        assertEquals(0 to 0, ScreenshotPlan.scaled(0, 100))
        val (w, h) = ScreenshotPlan.scaled(1080, 2400)
        assertEquals(1280, maxOf(w, h)); assertEquals(576, w)
    }

    @Test
    fun theDataUrlFitsTheFirestoreDoc() {
        assertEquals(700_000, ScreenshotPlan.MAX_DATA_CHARS)
        val jpeg = ByteArray(1000) { (it * 7).toByte() }
        val data = ScreenshotPlan.dataUrl(jpeg)
        assertTrue(data.startsWith("data:image/jpeg;base64,"))
        assertEquals(ScreenshotPlan.dataLength(jpeg.size), data.length)
        assertTrue(java.util.Base64.getDecoder().decode(data.removePrefix(ScreenshotPlan.DATA_PREFIX)).contentEquals(jpeg))
        for (n in listOf(0, 1, 2, 3, 4, 524_000, 524_270)) assertEquals(ScreenshotPlan.dataUrl(ByteArray(n)).length, ScreenshotPlan.dataLength(n))
        // About 512 KB of JPEG is the most that fits.
        val maxBytes = (0..700_000).last { ScreenshotPlan.fits(it) }
        assertTrue(maxBytes in 520_000..530_000)
        assertTrue(ScreenshotPlan.dataLength(maxBytes) <= 700_000)
        assertFalse(ScreenshotPlan.fits(maxBytes + 3))
    }

    @Test
    fun stepsDownQualityThenSizeUntilItFits() {
        assertEquals(listOf(1280 to 70, 1280 to 60, 1280 to 50, 1280 to 40, 1024 to 50, 800 to 50, 640 to 50),
            ScreenshotPlan.STEPS.map { it.maxSide to it.quality })
        // A typical screen fits at the first step.
        val tried = mutableListOf<ScreenshotPlan.Step>()
        val first = ScreenshotPlan.firstFit({ tried += it; 240_000 }, { it })!!
        assertEquals(ScreenshotPlan.Step(1280, 70), first.first); assertEquals(1, tried.size)
        // A busy, oversized screen (simulated sizes): steps down until it fits at 1024 px.
        val sizes = mapOf(ScreenshotPlan.Step(1280, 70) to 900_000, ScreenshotPlan.Step(1280, 60) to 780_000,
            ScreenshotPlan.Step(1280, 50) to 690_000, ScreenshotPlan.Step(1280, 40) to 600_000, ScreenshotPlan.Step(1024, 50) to 450_000)
        tried.clear()
        val fit = ScreenshotPlan.firstFit({ tried += it; sizes.getValue(it) }, { it })!!
        assertEquals(ScreenshotPlan.Step(1024, 50), fit.first); assertEquals(450_000, fit.second); assertEquals(5, tried.size)
        // Still too large at 640 px: fail ("The screenshot is too large").
        tried.clear()
        assertNull(ScreenshotPlan.firstFit({ tried += it; 2_000_000 }, { it }))
        assertEquals(ScreenshotPlan.STEPS, tried)
        assertEquals("The screenshot is too large", ScreenshotPlan.WHY_TOO_LARGE)
        assertEquals(640 to 360, ScreenshotPlan.scaled(1920, 1080, 640))
    }

    @Test
    fun subdocShapeOneDocPerDevice() {
        val jpeg = ByteArray(300) { 1 }
        val data = ScreenshotPlan.dataUrl(jpeg)
        val ts = Any()
        val d = ScreenshotPlan.subdoc("cmd1", 5L, 576, 1280, "screen", yt, "stu1", jpeg, data, ts)
        assertEquals(setOf("id", "at", "w", "h", "scope", "app", "studentId", "mime", "bytes", "data", "updatedOn"), d.keys)
        assertEquals("cmd1", d["id"]); assertEquals("image/jpeg", d["mime"]); assertEquals(300, d["bytes"])
        assertEquals(data, d["data"]); assertTrue(d["updatedOn"] === ts)
        assertEquals(mapOf("package" to yt.pkg, "label" to "YouTube Kids"), d["app"])
        assertFalse(d.keys.any { it == "child" + "Id" })
        val parent = ScreenshotPlan.subdoc("c", 1L, 1, 1, "app", null, " ", jpeg, data, ts)
        assertNull(parent["studentId"]); assertNull(parent["app"])
        // Every capture set()s the same doc, so only the latest exists.
        assertEquals("items", ScreenshotPlan.ITEMS); assertEquals("screenshot", ScreenshotPlan.SUBDOC_ID)
        assertEquals("items/screenshot", ScreenshotPlan.DOC_REF)
        assertEquals(30_000L, ScreenshotPlan.WRITE_TIMEOUT_MS)
        assertEquals("The tablet is offline", ScreenshotPlan.WHY_OFFLINE)
        assertEquals("Could not send the screenshot", ScreenshotPlan.WHY_SEND)
        assertEquals("Could not send the screenshot (slow network)", ScreenshotPlan.WHY_SLOW)
    }

    @Test
    fun watchdogStandsDownWhilePinSetupIsInFront() {
        // Changing the PIN (or first-run setup) must not be covered by the kiosk watchdog.
        val w = Recovery.Watch(maintenance = false, deviceOwner = true, hasPin = true, screenOn = true, activityAlive = false,
            inForeground = false, lockTaskActive = true, appOpen = false, pinSetupInFront = true)
        assertEquals(Recovery.WatchAction.NONE, Recovery.watch(w, 0))
        assertEquals(Recovery.WatchAction.RELAUNCH, Recovery.watch(w.copy(pinSetupInFront = false), 0))
    }

    @Test
    fun noCloudinaryLeftInTheApp() {
        val src = java.io.File("src/main").walkTopDown().filter { it.isFile }.toList()
        assertTrue(src.size > 20)
        val hits = src.filter { it.readText().contains("cloudinary", ignoreCase = true) }.map { it.name }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun statusShapeStudentIdOnly() {
        val s = ScreenshotPlan.status("123", 5L, 800, 1280, "screen", yt, "stu1")
        // No url: /me reads the picture from the items/screenshot subdoc.
        assertEquals(setOf("id", "at", "w", "h", "scope", "app", "studentId", "doc"), s.keys)
        assertEquals("items/screenshot", s["doc"])
        assertEquals("123", s["id"]); assertEquals(800, s["w"]); assertEquals("screen", s["scope"])
        assertEquals(mapOf("package" to yt.pkg, "label" to "YouTube Kids"), s["app"])
        assertEquals("stu1", s["studentId"])
        val parent = ScreenshotPlan.status("1", 5L, 1, 1, "app", null, "")
        assertNull(parent["studentId"]); assertNull(parent["app"])
        assertFalse(s.keys.any { it == "child" + "Id" })
        val r = RingPlan.remote("123", false, "The screen is off", 9L)
        assertEquals(mapOf("id" to "123", "ok" to false, "why" to "The screen is off", "at" to 9L), r)
    }

    @Test
    fun logLines() {
        assertEquals("Screenshot for the parent (screen, 240 KB)", ScreenshotPlan.logLine("screen", "", 245_760))
        assertEquals("Screenshot for the parent (app, 1 KB)", ScreenshotPlan.logLine("app", "", 700))
        assertEquals("Screenshot failed: The tablet is offline", ScreenshotPlan.logLine(null, "The tablet is offline"))
    }
}
