package org.ed22.kiosk

/**
 * Adds `headless=true` to every http(s) page URL so the ed22 (and other site) toolbars stay hidden
 * in Little Explorer, on any host.
 *
 * - Other query parameters and the `#hash` are kept; encoded bytes in the query are not rewritten
 *   when the URL already has `headless=true`.
 * - `headless=true` is never added twice. The parameter name is matched case-insensitively.
 * - If `headless` is already present with another value (`false`, `0`, empty), it is replaced with
 *   `true`. The kiosk always wants the toolbar hidden; leaving a page-supplied `headless=false`
 *   would undo that.
 * - Extra `headless` parameters are collapsed to a single `headless=true`.
 * - Non-http(s) URLs (file, about, intent, javascript, data, blob, …) are returned unchanged.
 * - Native API calls (PAIR_URL, HISTORY_URL, cloud POSTs, XHR/fetch, the DNS alert POST) are not
 *   loaded through this helper.
 */
object HeadlessUrl {
    /**
     * The URL to load for a page, or [url] unchanged when it is already headless or is not http(s).
     * Returning the same string when no change is needed lets callers skip a reload and avoid loops.
     */
    fun withHeadless(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return url
        val scheme = url.substring(0, schemeEnd)
        if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) return url

        val hashAt = url.indexOf('#')
        val beforeHash = if (hashAt >= 0) url.substring(0, hashAt) else url
        val hash = if (hashAt >= 0) url.substring(hashAt) else ""
        val queryAt = beforeHash.indexOf('?')
        val base = if (queryAt >= 0) beforeHash.substring(0, queryAt) else beforeHash
        val query = if (queryAt >= 0) beforeHash.substring(queryAt + 1) else ""
        val parts = if (query.isEmpty()) mutableListOf() else query.split('&').toMutableList()

        val headlessIdx = parts.indices.filter { isHeadlessName(parts[it]) }
        val trueIdx = headlessIdx.firstOrNull { isTrueValue(parts[it]) }
        if (trueIdx != null && headlessIdx.size == 1) return url

        if (trueIdx != null) {
            val keep = trueIdx
            val next = parts.filterIndexed { i, p -> i == keep || !isHeadlessName(p) }
            return "$base?${next.joinToString("&")}$hash"
        }
        if (headlessIdx.isNotEmpty()) {
            val first = headlessIdx.first()
            parts[first] = "headless=true"
            val next = parts.filterIndexed { i, p -> i == first || !isHeadlessName(p) }
            return "$base?${next.joinToString("&")}$hash"
        }
        parts.add("headless=true")
        return "$base?${parts.joinToString("&")}$hash"
    }

    /** True when [url] is http(s) and does not yet have a single `headless=true`. */
    fun needsHeadless(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        return withHeadless(url) != url
    }

    private fun isHeadlessName(part: String): Boolean =
        part.substringBefore('=').equals("headless", ignoreCase = true)

    private fun isTrueValue(part: String): Boolean {
        val value = if (part.contains('=')) part.substringAfter('=') else ""
        return value.equals("true", ignoreCase = true)
    }
}
