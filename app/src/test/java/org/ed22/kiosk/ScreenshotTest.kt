package org.ed22.kiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** policy.command screenshot: RemoteCommands.decide, features, ScreenshotPlan (scope, size, upload form, status shape). */
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
    fun uploadFormAndUrl() {
        assertEquals("https://api.cloudinary.com/v1_1/homeschoolexpress/image/upload", ScreenshotPlan.uploadUrl(KioskConfig.CLOUDINARY_CLOUD))
        assertEquals("userDocs", KioskConfig.CLOUDINARY_PRESET)
        val f = ScreenshotPlan.formFields("userDocs", "uid1", "exp_uid1_dev1").toMap()
        assertEquals("userDocs", f["upload_preset"])
        assertEquals("user/uid1/explorerScreens/exp_uid1_dev1", f["folder"])
        assertEquals("explorerScreenshot,exp_uid1_dev1", f["tags"])
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())
        val body = ScreenshotPlan.multipart("B", ScreenshotPlan.formFields("userDocs", "u", "d"), "s.jpg", jpeg)
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("--B\r\nContent-Disposition: form-data; name=\"upload_preset\"\r\n\r\nuserDocs\r\n"))
        assertTrue(text.contains("name=\"folder\"\r\n\r\nuser/u/explorerScreens/d\r\n"))
        assertTrue(text.contains("name=\"file\"; filename=\"s.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"))
        assertTrue(text.endsWith("\r\n--B--\r\n"))
        val start = text.indexOf("image/jpeg\r\n\r\n") + "image/jpeg\r\n\r\n".length
        assertTrue(body.copyOfRange(start, start + jpeg.size).contentEquals(jpeg))
        assertFalse(text.contains("api_key") || text.contains("signature"))
    }

    @Test
    fun secureUrlParsing() {
        assertEquals("https://res.cloudinary.com/x/a.jpg",
            ScreenshotPlan.secureUrl("""{"secure_url":"https://res.cloudinary.com/x/a.jpg","url":"http://res.cloudinary.com/x/a.jpg"}"""))
        assertNull(ScreenshotPlan.secureUrl("""{"error":{"message":"bad"}}"""))
        assertNull(ScreenshotPlan.secureUrl("not json"))
        assertNull(ScreenshotPlan.secureUrl(null))
        assertNull(ScreenshotPlan.secureUrl("""{"secure_url":"http://insecure"}"""))
    }

    @Test
    fun statusShapeStudentIdOnly() {
        val s = ScreenshotPlan.status("123", "https://u", 5L, 800, 1280, "screen", yt, "stu1")
        assertEquals(setOf("id", "url", "at", "w", "h", "scope", "app", "studentId"), s.keys)
        assertEquals("123", s["id"]); assertEquals(800, s["w"]); assertEquals("screen", s["scope"])
        assertEquals(mapOf("package" to yt.pkg, "label" to "YouTube Kids"), s["app"])
        assertEquals("stu1", s["studentId"])
        val parent = ScreenshotPlan.status("1", "https://u", 5L, 1, 1, "app", null, "")
        assertNull(parent["studentId"]); assertNull(parent["app"])
        assertFalse(s.keys.any { it == "child" + "Id" })
        val r = RingPlan.remote("123", false, "The screen is off", 9L)
        assertEquals(mapOf("id" to "123", "ok" to false, "why" to "The screen is off", "at" to 9L), r)
    }

    @Test
    fun logLines() {
        assertEquals("Screenshot for the parent (screen)", ScreenshotPlan.logLine("screen", ""))
        assertEquals("Screenshot failed: Upload failed", ScreenshotPlan.logLine(null, "Upload failed"))
    }
}
