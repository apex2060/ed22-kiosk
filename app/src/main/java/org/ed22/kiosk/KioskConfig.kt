package org.ed22.kiosk

import android.net.Uri

object KioskConfig {
    /** The page the kiosk opens on boot and returns to from the parent menu. */
    const val START_URL = "https://us.ed22.org/me"

    /** Every top-level page visit is POSTed here as JSON. */
    const val HISTORY_URL = "https://us.ed22.org/me/history"

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
