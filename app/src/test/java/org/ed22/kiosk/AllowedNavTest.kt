package org.ed22.kiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Top-level navigations are allowed only for us.ed22.org, plus a few internal schemes. */
class AllowedNavTest {

    private fun yes(url: String, inAuthPopup: Boolean = false) =
        assertTrue(url, AllowedNav.allowsTopLevel(url, inAuthPopup))

    private fun no(url: String, inAuthPopup: Boolean = false) =
        assertFalse(url, AllowedNav.allowsTopLevel(url, inAuthPopup))

    @Test
    fun allowsExactUsEd22HostOnHttpAndHttps() {
        yes("https://us.ed22.org/mobile?headless=true")
        yes("https://us.ed22.org/mobile?headless=true#section")
        yes("https://us.ed22.org")
        yes("https://us.ed22.org/")
        yes("http://us.ed22.org/mobile")
        yes("HTTPS://US.ED22.ORG/x")
        yes("https://us.ed22.org:443/")
        yes("https://us.ed22.org:8080/path")
        // A trailing dot is still that host (FQDN).
        yes("https://us.ed22.org./mobile")
        // Userinfo on the real host does not change the host.
        yes("https://user:pass@us.ed22.org/mobile")
        // Encoded query values are irrelevant to the host check.
        yes("https://us.ed22.org/p?q=a%26b%3D2#h")
    }

    @Test
    fun refusesSubdomainsAndLookAlikes() {
        no("https://foo.us.ed22.org/")
        no("https://www.ed22.org/")
        no("https://ed22.org/")
        no("https://uk.ed22.org/mobile")
        no("https://us.ed22.org.evil.com/")
        no("https://evilus.ed22.org/")
        no("https://us.ed22.org.evil.com")
        no("https://notus.ed22.org/")
        no("https://us.ed22.com/")
        no("https://us.ed22.org.evil.com/mobile?headless=true")
    }

    @Test
    fun refusesUserinfoTricksAndMissingHost() {
        // host is evil.com; "us.ed22.org" is only the userinfo.
        no("https://us.ed22.org@evil.com/")
        no("https://us.ed22.org@evil.com/mobile")
        // Host in the fragment does not count.
        no("https://evil.com#@us.ed22.org")
        no("https://evil.com#us.ed22.org")
        // %40 is "@"; java.net.URI leaves host null — must not fall open.
        no("https://us.ed22.org%40evil.com/")
        no("https://")
        no("")
        no("not a url")
    }

    @Test
    fun refusesOtherHttpHostsAndIps() {
        no("https://example.com/")
        no("https://accounts.google.com/o/oauth2/v2/auth")
        no("https://homeschool-express.firebaseapp.com/__/auth/handler")
        no("https://192.168.1.1/")
        no("http://127.0.0.1/")
    }

    @Test
    fun authHostsOnlyInsideAuthPopups() {
        val google = "https://accounts.google.com/o/oauth2/v2/auth?client_id=x"
        val youtube = "https://accounts.youtube.com/signin"
        val firebase = "https://homeschool-express.firebaseapp.com/__/auth/handler"
        val otherFirebase = "https://other-project.firebaseapp.com/__/auth/handler"
        yes(google, inAuthPopup = true)
        yes(youtube, inAuthPopup = true)
        yes(firebase, inAuthPopup = true)
        yes(otherFirebase, inAuthPopup = true)
        no(google, inAuthPopup = false)
        no(youtube)
        no(firebase)
        // Not a blank cheque for Google or Firebase hosting.
        no("https://www.google.com/", inAuthPopup = true)
        no("https://google.com/", inAuthPopup = true)
        no("https://firebaseapp.com/", inAuthPopup = true)
        no("https://homeschool-express.web.app/__/auth/handler", inAuthPopup = true)
        no("https://evil.com/", inAuthPopup = true)
        no("https://notfirebaseapp.com/", inAuthPopup = true)
        // The real kiosk host is still allowed in a popup (e.g. a same-origin helper).
        yes("https://us.ed22.org/mobile", inAuthPopup = true)
    }

    @Test
    fun allowsInternalSchemesAndRefusesTheRest() {
        yes("file:///android_asset/offline.html")
        yes("file:///android_asset/offline.html#https://us.ed22.org/mobile")
        yes("file:///android_asset/")
        no("file://android_asset/offline.html")
        no("file:///sdcard/secret.html")
        no("file:///data/data/org.ed22.kiosk/files/x")
        no("file:///android_asset/../data/x")
        no("file:///android_asset/%2e%2e/data/x")
        yes("file:///android_asset/foo/../offline.html")

        yes("about:blank")
        yes("about:blank#foo")
        yes("about:srcdoc")
        no("about:config")
        no("about:access")

        yes("data:text/html,hello")
        yes("blob:https://us.ed22.org/uuid")
        yes("javascript:void(0)")

        no("intent://scan/#Intent;scheme=zxing;end")
        no("tel:+15551212")
        no("mailto:a@example.com")
        no("market://details?id=org.ed22.kiosk")
        no("content://media/external/images/1")
        no("ftp://us.ed22.org/file")
    }
}
