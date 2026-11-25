package org.ed22.kiosk

import org.json.JSONObject

/**
 * House of Learning *device* (parent-menu toggle + group pairing): pure decisions, JVM-tested.
 * Visit timing and family rules stay in [HolPlan] / [HolVisit].
 */
object HolDevicePlan {
    const val PAIR_URL = "https://us.ed22.org/project/mobile/cloud/holExplorerAuth"
    const val CODE_LEN = 8
    const val DEFAULT_WARN_MIN = HolPlan.DEFAULT_WARN_MIN
    const val AUDIT_START = "explorer.visitStart"
    const val AUDIT_END = "explorer.visitEnd"

    fun uidOf(deviceId: String) = "hol-device-$deviceId"

    fun pathOf(serviceId: String, deviceId: String) = "acl/db/groups/$serviceId/share/hol/explorer/$deviceId"

    fun auditCollection(serviceId: String) = "acl/db/groups/$serviceId/share/hol/explorerAudit"

    /** 8-character house pairing code (A–Z, 2–9 as the server stores it; 0/O/1/I are not used). */
    fun sanitizeCode(raw: String): String =
        raw.uppercase().replace(Regex("[^A-Z0-9]"), "").take(CODE_LEN)

    fun codeReady(code: String) = code.length == CODE_LEN

    fun isExplorerPath(path: String): Boolean {
        if (path.contains("/share/admin")) return false
        return Regex("""^acl/db/groups/([A-Za-z0-9]{6,40})/share/hol/explorer/([A-Za-z0-9_-]{1,64})$""").matches(path)
    }

    fun serviceIdOf(path: String): String? =
        Regex("""^acl/db/groups/([A-Za-z0-9]{6,40})/share/hol/explorer/""").find(path)?.groupValues?.get(1)

    fun isHolDeviceUid(uid: String?) = uid != null && uid.startsWith("hol-device-")

    /** Visiting family's kiosk.policy.set is allowed when a personal account is signed in, or when this is a HOL device. */
    fun canSetLocalPolicy(signedIn: Boolean, holOn: Boolean) = signedIn || holOn

    /** No screenshot capture or upload while House of Learning mode is on. */
    fun allowScreenshot(holOn: Boolean) = !holOn

    fun isHolLogout(cmd: JSONObject?) = cmd?.optString("type") == "holLogout"

    /**
     * `policy.hol` for kiosk.getAuth(), even when signed out. Local [on] is the source of truth
     * (the parent-menu toggle), not a personal device doc.
     */
    fun policyHol(on: Boolean, serviceId: String?, warnMin: Int = DEFAULT_WARN_MIN): JSONObject =
        JSONObject().put("on", on).put("warnMin", warnMin).also { o ->
            if (!serviceId.isNullOrBlank()) o.put("serviceId", serviceId)
        }

    /** Overlay the local toggle onto a policy object (pickPolicy result or empty). */
    fun applyLocalHol(policy: JSONObject, on: Boolean, serviceId: String?, warnMin: Int = DEFAULT_WARN_MIN): JSONObject {
        policy.put("hol", policyHol(on, serviceId, warnMin))
        return policy
    }

    /** Top-level fields written to the group explorer doc (no status.*, no screenshot, no tool totals). */
    val EXPLORER_FIELDS = setOf(
        "online", "appVersion", "battery", "visiting", "name", "current", "activeStudentId", "last", "updatedOn",
    )

    fun auditDetail(deviceId: String, title: String?, name: String?, current: Any?): Map<String, Any?> = mapOf(
        "deviceId" to deviceId,
        "title" to title,
        "name" to name,
        "current" to current,
    )
}
