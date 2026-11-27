package org.ed22.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every http(s) page URL gets a single headless=true; query and hash are kept. */
class HeadlessUrlTest {
    @Test
    fun addsHeadlessAndKeepsQueryAndHash() {
        assertEquals(
            "https://example.com/page?a=1&b=2&headless=true#section",
            HeadlessUrl.withHeadless("https://example.com/page?a=1&b=2#section"),
        )
        assertEquals(
            "http://example.com/page?headless=true",
            HeadlessUrl.withHeadless("http://example.com/page"),
        )
    }

    @Test
    fun doesNotDuplicateAndLeavesAlreadyHeadlessUnchanged() {
        val url = "https://us.ed22.org/mobile?foo=1&headless=true#x"
        assertEquals(url, HeadlessUrl.withHeadless(url))
        assertFalse(HeadlessUrl.needsHeadless(url))
        assertEquals(KioskConfig.START_URL, HeadlessUrl.withHeadless(KioskConfig.START_URL))
        assertFalse(HeadlessUrl.needsHeadless(KioskConfig.START_URL))
        // Two headless params collapse to one, keeping other query values and the hash.
        assertEquals(
            "https://example.com/?a=1&headless=true#z",
            HeadlessUrl.withHeadless("https://example.com/?a=1&headless=true&headless=false#z"),
        )
    }

    @Test
    fun overwritesADifferentHeadlessValue() {
        // A page-supplied headless=false would show the toolbar; the kiosk forces true.
        assertEquals(
            "https://example.com/p?headless=true&q=1",
            HeadlessUrl.withHeadless("https://example.com/p?headless=false&q=1"),
        )
        assertEquals(
            "https://example.com/p?headless=true",
            HeadlessUrl.withHeadless("https://example.com/p?headless=0"),
        )
        assertEquals(
            "https://example.com/p?headless=true",
            HeadlessUrl.withHeadless("https://example.com/p?headless"),
        )
    }

    @Test
    fun leavesNonHttpSchemesUntouched() {
        for (url in listOf(
            "file:///android_asset/offline.html#https%3A%2F%2Fus.ed22.org%2Fmobile",
            "about:blank",
            "javascript:void(0)",
            "data:text/html,hi",
            "blob:https://us.ed22.org/abc",
            "intent://example.com#Intent;scheme=https;end",
            "tel:5551212",
        )) {
            assertEquals(url, HeadlessUrl.withHeadless(url))
            assertFalse(HeadlessUrl.needsHeadless(url))
        }
        assertFalse(HeadlessUrl.needsHeadless(null))
        assertFalse(HeadlessUrl.needsHeadless(""))
    }

    @Test
    fun keepsEncodedQueryParameters() {
        val raw = "https://example.com/search?q=a%20b%2Fc&lang=en#section-2"
        assertEquals(
            "https://example.com/search?q=a%20b%2Fc&lang=en&headless=true#section-2",
            HeadlessUrl.withHeadless(raw),
        )
        val already = "https://example.com/search?q=a%20b%2Fc&headless=true#section-2"
        assertEquals(already, HeadlessUrl.withHeadless(already))
    }

    @Test
    fun urlWithoutAPath() {
        assertEquals("https://example.com?headless=true", HeadlessUrl.withHeadless("https://example.com"))
        assertEquals("https://example.com?x=1&headless=true", HeadlessUrl.withHeadless("https://example.com?x=1"))
        assertEquals("https://example.com?headless=true#top", HeadlessUrl.withHeadless("https://example.com#top"))
    }

    @Test
    fun startUrlLogicStillHolds() {
        assertTrue(KioskConfig.needsHeadlessStart("https://us.ed22.org/mobile"))
        assertFalse(KioskConfig.needsHeadlessStart(KioskConfig.START_URL))
        // A restored other page is not the start page, but still needs the param.
        assertFalse(KioskConfig.needsHeadlessStart("https://us.ed22.org/me"))
        assertTrue(HeadlessUrl.needsHeadless("https://us.ed22.org/me"))
        assertEquals("https://us.ed22.org/me?headless=true", HeadlessUrl.withHeadless("https://us.ed22.org/me"))
    }
}
