package org.ed22.kiosk

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The start page (/mobile) always opens headless (Ryan, Nov 27 2026). */
class StartUrlTest {
    @Test
    fun startUrlIsMobileWithHeadless() {
        val u = URI(KioskConfig.START_URL)
        assertEquals("https", u.scheme)
        assertEquals("us.ed22.org", u.host)
        assertEquals("/mobile", u.path)
        assertTrue(u.query.split('&').contains("headless=true"))
    }

    @Test
    fun offlineFallbackKeepsHeadless() {
        // No hash on the offline page -> the start page, headless included.
        assertEquals(KioskConfig.START_URL, Recovery.offlineTarget("file:///android_asset/offline.html", KioskConfig.START_URL))
    }

    @Test
    fun restoredMobileWithoutHeadlessIsUpgraded() {
        assertTrue(KioskConfig.needsHeadlessStart("https://us.ed22.org/mobile"))
        assertTrue(KioskConfig.needsHeadlessStart("https://us.ed22.org/mobile/"))
        assertTrue(KioskConfig.needsHeadlessStart("https://us.ed22.org/mobile?foo=1"))
        assertFalse(KioskConfig.needsHeadlessStart(KioskConfig.START_URL))
        assertFalse(KioskConfig.needsHeadlessStart("https://us.ed22.org/mobile?headless=true&foo=1"))
        // Other pages, even on the same host, stay where they were.
        assertFalse(KioskConfig.needsHeadlessStart("https://us.ed22.org/me"))
        assertFalse(KioskConfig.needsHeadlessStart("https://us.ed22.org/mobile/setup"))
        assertFalse(KioskConfig.needsHeadlessStart(null))
        assertFalse(KioskConfig.needsHeadlessStart("not a url"))
    }
}
