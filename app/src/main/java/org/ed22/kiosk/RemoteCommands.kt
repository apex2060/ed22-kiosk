package org.ed22.kiosk

import org.json.JSONObject

/** status.features and pure decisions for policy.command and the return to the kiosk (no Android calls, so they run in JVM tests). */
object RemoteCommands {
    /** openApp commands whose id (epoch ms) is older than this are ignored, so a tablet back online hours later doesn't open the app. */
    const val OPEN_APP_MAX_AGE_MS = 2 * 60_000L

    /** `status.features` (kept here so a JVM test can check it). /me sends openApp only when "openApp" is listed. */
    val FEATURES = listOf(
        "rules", "ruleLimits", "strict", "localPolicy", "elsewhere", "appsUsageByChild", "appsEvent", "openApp",
        // openTool: the native app brings Little Explorer to the front; /mobile still opens the tool.
        "openToolFront",
        // status.log (recent device events) and status.lastExit {at, why}.
        "deviceLog",
    )

    /** Commands with an epoch-ms id that are only run while fresh and while the device is not locked. */
    val TIMED_TYPES = setOf("openApp", "openTool")

    /** [run] = do it; [handled] = remember the id so it never runs again; [reason] for the log when not run. */
    data class Decision(val run: Boolean, val handled: Boolean, val reason: String)

    /** The command's id as a string, or null when there is none. */
    fun id(cmd: JSONObject?): String? = cmd?.opt("id")?.takeUnless { it == JSONObject.NULL }?.toString()

    /**
     * [lastId] = the id handled last (run-once-per-id). For openApp and openTool also: the id must be epoch ms no older than
     * [OPEN_APP_MAX_AGE_MS], the device not [locked], and (openApp only) the package allowed now ([isAllowed], the same check as launch()).
     */
    fun decide(cmd: JSONObject?, lastId: String?, nowMs: Long, locked: Boolean, isAllowed: (String) -> Boolean): Decision {
        val id = id(cmd) ?: return Decision(false, false, "no command")
        if (id == lastId) return Decision(false, false, "already handled")
        val type = cmd!!.optString("type")
        if (type !in TIMED_TYPES) return Decision(true, true, "")
        val sent = id.toLongOrNull() ?: return Decision(false, true, "id is not epoch ms")
        if (nowMs - sent > OPEN_APP_MAX_AGE_MS) return Decision(false, true, "too old (${(nowMs - sent) / 1000}s)")
        if (locked) return Decision(false, true, "device is locked")
        // openTool: the page opens the tool (it dedupes by id); natively it only brings the kiosk to the front.
        if (type == "openTool") return Decision(true, true, "")
        val pkg = cmd.optString("package")
        if (pkg.isBlank()) return Decision(false, true, "no package")
        if (!isAllowed(pkg)) return Decision(false, true, "$pkg is not allowed right now")
        return Decision(true, true, "")
    }

    /** openTool: bring the kiosk activity to the front only when it is not already there (the page does the rest). */
    fun bringToFront(type: String, kioskInFront: Boolean): Boolean = type == "openTool" && !kioskInFront

    /**
     * The packages that make enforce() send the tablet back to the kiosk: allowed before ([lastAllowed]) but not now,
     * plus the app in front ([openPkg], set by launch()) when it is no longer allowed. Empty = stay.
     */
    fun noLongerAllowed(lastAllowed: Set<String>?, allowed: Set<String>, openPkg: String?): Set<String> =
        lastAllowed?.minus(allowed).orEmpty() + listOfNotNull(openPkg?.takeIf { it !in allowed })
}
