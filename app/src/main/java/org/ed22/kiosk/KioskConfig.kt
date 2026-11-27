package org.ed22.kiosk

import android.net.Uri

object KioskConfig {
    /** The page the kiosk opens on boot and returns to from the parent menu. headless=true hides the ed22 site toolbar (Ryan, Nov 27 2026). */
    const val START_URL = "https://us.ed22.org/mobile?headless=true"

    /**
     * True when [url] is the /mobile start page but is missing headless=true.
     * Used after WebView restoreState so a tablet that still has the pre-upgrade /mobile URL
     * is sent to [START_URL] instead of keeping the toolbar visible until the next boot or Home.
     */
    fun needsHeadlessStart(url: String?): Boolean {
        val start = runCatching { java.net.URI(START_URL) }.getOrNull() ?: return false
        val u = url?.let { runCatching { java.net.URI(it) }.getOrNull() } ?: return false
        if (u.scheme != start.scheme || u.host != start.host) return false
        val path = u.path?.trimEnd('/') ?: ""
        if (path != (start.path?.trimEnd('/') ?: "")) return false
        val query = u.query ?: ""
        return !query.split('&').contains("headless=true")
    }

    /** Every top-level page visit is POSTed here as JSON. */
    const val HISTORY_URL = "https://us.ed22.org/me/history"

    /** History reporting is off for now: no visits are sent or queued, and any old queue is deleted. */
    const val HISTORY_ENABLED = false

    /**
     * The self-updater polls this JSON file: {versionCode, versionName, url, sha256}. `url` may be
     * relative to the manifest. It must be reachable without auth, over https (see README › Automatic updates).
     * Default: the update.json asset of the repository's latest GitHub Release.
     */
    const val UPDATE_MANIFEST_URL = "https://github.com/apex2060/ed22-kiosk/releases/latest/download/update.json"
    const val UPDATE_INTERVAL_HOURS = 6L

    /** Only pages on this domain (and its subdomains) get `window.kiosk` and automatic camera/mic/location access. */
    const val TRUSTED_DOMAIN = "ed22.org"

    val BRIDGE_ORIGIN_RULES = setOf("https://$TRUSTED_DOMAIN", "https://*.$TRUSTED_DOMAIN")

    /** The parent menu opens after this many taps in the bottom-left corner. */
    const val CORNER_TAPS = 4
    const val CORNER_TAP_WINDOW_MS = 3000L
    const val CORNER_SIZE_DP = 80

    fun isTrusted(uri: Uri?): Boolean {
        if (uri == null || uri.scheme != "https") return false
        val host = uri.host ?: return false
        return host == TRUSTED_DOMAIN || host.endsWith(".$TRUSTED_DOMAIN")
    }
}
