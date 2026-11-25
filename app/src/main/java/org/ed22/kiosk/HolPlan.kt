package org.ed22.kiosk

import org.json.JSONObject

/**
 * House of Learning visit: pure decisions (no Android calls, so JVM tests cover them).
 * The tablet stays signed in as the house; a visiting family uses [kiosk.policy.set] while a visit is on,
 * and [HolVisit] is the native safety net that ends it.
 */
object HolPlan {
    const val FEATURE = "hol"
    const val DEFAULT_WARN_MIN = 5
    const val LOG_KIND = "hol"

    data class Visit(val endsAtMs: Long, val warnMin: Int)

    /** When the 5-minute (or [warnMin]) warning should show. */
    fun warnAt(endsAtMs: Long, warnMin: Int = DEFAULT_WARN_MIN): Long = endsAtMs - warnMin.coerceAtLeast(0) * 60_000L

    fun visit(endsAtMs: Long, warnMin: Int?): Visit = Visit(endsAtMs, (warnMin ?: DEFAULT_WARN_MIN).let { if (it > 0) it else DEFAULT_WARN_MIN })

    /** A later [endsAtMs] is an extension (it replaces the stored end). Any new end time replaces the old one. */
    fun isExtension(prevEndsAtMs: Long?, newEndsAtMs: Long) = prevEndsAtMs != null && newEndsAtMs > prevEndsAtMs

    /** The stored visit's end has been reached: run the same steps as kiosk.hol.end("visit ended"). */
    fun shouldEnd(visit: Visit?, now: Long) = visit != null && now >= visit.endsAtMs

    /**
     * Show the warning once, at or after [warnAt], and only while the visit has not already ended.
     * [warnedFor] is the warn-at timestamp we already showed (so an extension can warn again).
     */
    fun shouldWarn(visit: Visit, now: Long, warnedFor: Long?): Boolean {
        val at = warnAt(visit.endsAtMs, visit.warnMin)
        if (now < at || now >= visit.endsAtMs) return false
        return warnedFor != at
    }

    /**
     * Page [kiosk.storage] keys to drop at the end of a visit. Device-level keys (anything else) are kept.
     * Always includes `mobile.*`, `hol*` (any case), `activeScene` and `activeReserve`.
     */
    fun clearStorageKey(key: String): Boolean {
        val k = key.lowercase()
        return k.startsWith("mobile.") || k.startsWith("hol") || k == "activescene" || k == "activereserve"
    }

    fun warnText(warnMin: Int): String {
        val n = warnMin.coerceAtLeast(1)
        return if (n == 1) "This visit ends in 1 minute." else "This visit ends in $n minutes."
    }

    fun endLog(reason: String) = "HOL visit ended: $reason"

    /**
     * `status.hol` while a visit is in progress. [name] / [studentId] come from hol.report once a child is picked.
     * studentId only, never childId.
     */
    fun visitingStatus(endsAtMs: Long, studentId: String?, name: String?, last: Map<String, Any?>? = null): Map<String, Any?> = linkedMapOf(
        "on" to true,
        "visitor" to true,
        "studentId" to studentId?.takeIf { it.isNotBlank() },
        "name" to name?.takeIf { it.isNotBlank() },
        "endsAtMs" to endsAtMs,
        "last" to last,
    )

    /** `status.hol` after a visit ends (the tablet is still a House of Learning device). */
    fun endedStatus(at: Long, why: String): Map<String, Any?> = linkedMapOf(
        "on" to true,
        "visitor" to false,
        "studentId" to null,
        "name" to null,
        "endsAtMs" to null,
        "last" to mapOf("at" to at, "why" to why),
    )

    /** status.hol when no visit is in progress ([on] from policy.hol.on). */
    fun restingStatus(on: Boolean, last: Map<String, Any?>? = null): Map<String, Any?> = linkedMapOf(
        "on" to on,
        "visitor" to false,
        "studentId" to null,
        "name" to null,
        "endsAtMs" to null,
        "last" to last,
    )
}
