package org.ed22.kiosk

import java.net.URI
import java.util.Locale

/**
 * Whether a top-level WebView navigation may load.
 *
 * Allowed http(s) pages have host **exactly** `us.ed22.org` (any port, http or https). Subdomains
 * (`foo.us.ed22.org`), other ed22.org hosts, and look-alikes (`us.ed22.org.evil.com`,
 * `evilus.ed22.org`) are refused. The host is taken from the parsed URI, so a userinfo trick
 * such as `https://us.ed22.org@evil.com/` is refused (`host` is `evil.com`).
 *
 * Internal schemes allowed for kiosk use: `file:///android_asset/…` (the offline page),
 * `about:blank` / `about:srcdoc`, `data:`, `blob:`, and `javascript:`.
 *
 * **Auth popups:** `/mobile` parent sign-in uses Firebase `signInWithPopup`, which the kiosk
 * already opens in an in-app WebView (see [MainActivity] `onCreateWindow`). Those popups must
 * reach Google/Firebase auth hosts or sign-in breaks. When [inAuthPopup] is true, this also
 * allows `accounts.google.com`, `accounts.youtube.com`, and `*.firebaseapp.com`. Those hosts
 * are **not** allowed in the main WebView, so a main-frame redirect to Google is still blocked.
 * Other Google hosts (`www.google.com`, `*.web.app`, …) are not on this list.
 *
 * This is not [KioskConfig.isTrusted]: the JS bridge still trusts every `ed22.org` subdomain.
 * Native calls (pairing, history, DNS alert, screenshots, allowed-app launch) do not go
 * through this check.
 */
object AllowedNav {
    const val PAGE_HOST = "us.ed22.org"

    /** Schemes that can stay inside the WebView; anything else (tel, mailto, market, intent, …) is refused. */
    val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript", "file")

    fun allowsTopLevel(url: String?, inAuthPopup: Boolean = false): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        return when (scheme) {
            "http", "https" -> {
                val host = normalizeHost(uri.host) ?: return false
                host == PAGE_HOST || (inAuthPopup && isAuthHost(host))
            }
            "file" -> isAssetFile(uri)
            "about" -> {
                val ssp = uri.schemeSpecificPart?.lowercase(Locale.US) ?: return false
                ssp == "blank" || ssp == "srcdoc" || ssp.startsWith("blank?") || ssp.startsWith("srcdoc?")
            }
            "data", "blob", "javascript" -> true
            else -> false
        }
    }

    /** Firebase/Google auth hosts, allowed only inside the in-app sign-in popup. */
    fun isAuthHost(host: String): Boolean {
        val h = normalizeHost(host) ?: return false
        return h == "accounts.google.com" || h == "accounts.youtube.com" || h.endsWith(".firebaseapp.com")
    }

    private fun normalizeHost(host: String?): String? {
        if (host.isNullOrBlank()) return null
        return host.trim().trimEnd('.').lowercase(Locale.US).takeIf { it.isNotEmpty() }
    }

    /** Only the app's packaged assets, e.g. `file:///android_asset/offline.html`. */
    private fun isAssetFile(uri: URI): Boolean {
        // Three-slash form: host is empty, path starts with /android_asset/.
        // Two-slash `file://android_asset/…` puts "android_asset" in the authority; refuse it.
        if (!uri.host.isNullOrEmpty()) return false
        if (!uri.rawAuthority.isNullOrEmpty()) return false
        val path = collapsePath(uri.path ?: return false)
        return path == "/android_asset" || path.startsWith("/android_asset/")
    }

    /** Collapse `.` / `..` so `file:///android_asset/../data/x` cannot pass as an asset path. */
    private fun collapsePath(path: String): String {
        val out = ArrayList<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out.add(seg)
            }
        }
        return "/" + out.joinToString("/")
    }
}
