package org.ed22.kiosk

/**
 * Lock-screen Wi-Fi status and join decisions. No radio I/O and no passwords in [toString].
 *
 * The name is shown only when the platform actually provided one. `<unknown ssid>` stays blank.
 */
enum class WifiLink { Off, Disconnected, Connecting, Connected, NoInternet }

data class WifiIndicator(val link: WifiLink, val ssid: String?) {
    fun label(): String = when (link) {
        WifiLink.Off -> "Wi-Fi off"
        WifiLink.Disconnected -> "Wi-Fi disconnected"
        WifiLink.Connecting -> ssid?.let { "Connecting to $it" } ?: "Connecting…"
        WifiLink.Connected -> ssid ?: "Connected"
        WifiLink.NoInternet -> ssid?.let { "$it, no internet" } ?: "Connected, no internet"
    }
}

data class WifiSnapshot(
    val enabled: Boolean,
    val connecting: Boolean,
    val onWifi: Boolean,
    val validated: Boolean,
    val ssid: String?,
)

fun indicatorOf(snapshot: WifiSnapshot): WifiIndicator {
    if (!snapshot.enabled) return WifiIndicator(WifiLink.Off, null)
    val link = when {
        snapshot.onWifi && snapshot.validated -> WifiLink.Connected
        snapshot.onWifi -> WifiLink.NoInternet
        snapshot.connecting -> WifiLink.Connecting
        else -> WifiLink.Disconnected
    }
    return WifiIndicator(link, readableSsid(snapshot.ssid))
}

/** Null when the platform did not give a real name. Never invents one. */
fun readableSsid(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val name = raw.trim().removeSurrounding("\"").trim()
    if (name.isEmpty() || name.equals("<unknown ssid>", ignoreCase = true) || name == "0x") return null
    return name
}

enum class WifiSecurity { Open, Wpa, Unsupported }

fun securityOf(capabilities: String): WifiSecurity {
    val caps = capabilities.uppercase()
    return when {
        "EAP" in caps || "IEEE8021X" in caps -> WifiSecurity.Unsupported
        "WEP" in caps && "WPA" !in caps && "PSK" !in caps && "SAE" !in caps -> WifiSecurity.Unsupported
        "WPA" in caps || "PSK" in caps || "SAE" in caps -> WifiSecurity.Wpa
        else -> WifiSecurity.Open
    }
}

data class WifiNetwork(
    val ssid: String,
    val security: WifiSecurity,
    val saved: Boolean,
    val networkId: Int,
    val level: Int,
)

data class RawAp(val ssid: String?, val capabilities: String, val level: Int)

data class SavedWifi(val ssid: String?, val networkId: Int)

fun networksFrom(scans: List<RawAp>, saved: List<SavedWifi>): List<WifiNetwork> {
    val savedIds = LinkedHashMap<String, Int>()
    for (entry in saved) {
        val name = readableSsid(entry.ssid) ?: continue
        if (entry.networkId >= 0) savedIds[name] = entry.networkId
    }
    val best = HashMap<String, RawAp>()
    for (ap in scans) {
        val name = readableSsid(ap.ssid) ?: continue
        val previous = best[name]
        if (previous == null || ap.level > previous.level) best[name] = ap
    }
    return (savedIds.keys + best.keys).distinct().map { name ->
        val ap = best[name]
        val id = savedIds[name] ?: -1
        WifiNetwork(
            ssid = name,
            security = ap?.let { securityOf(it.capabilities) } ?: WifiSecurity.Wpa,
            saved = id >= 0,
            networkId = id,
            level = ap?.level ?: Int.MIN_VALUE,
        )
    }.sortedWith(compareByDescending<WifiNetwork> { it.saved }.thenByDescending { it.level })
}

fun rowCaption(network: WifiNetwork): String {
    val kind = when {
        network.saved -> "Saved"
        network.security == WifiSecurity.Open -> "Open"
        network.security == WifiSecurity.Wpa -> "Secured"
        else -> "Not available"
    }
    return "${network.ssid} · $kind"
}

sealed class WifiJoin {
    data class Reject(val reason: String) : WifiJoin()
    data class Open(val ssid: String) : WifiJoin()
    data class Saved(val networkId: Int, val ssid: String) : WifiJoin()
    data class NeedPassword(val ssid: String) : WifiJoin()
    class Password(val ssid: String, val password: String) : WifiJoin() {
        override fun toString() = "Password(ssid=$ssid)"
    }
}

fun planJoin(network: WifiNetwork, password: String?): WifiJoin {
    if (network.ssid.isBlank()) return WifiJoin.Reject("That network has no name")
    if (network.saved && network.networkId >= 0 && password.isNullOrEmpty()) {
        return WifiJoin.Saved(network.networkId, network.ssid)
    }
    return when (network.security) {
        WifiSecurity.Unsupported -> WifiJoin.Reject("This network can't be joined here")
        WifiSecurity.Open -> WifiJoin.Open(network.ssid)
        WifiSecurity.Wpa -> when {
            password == null -> WifiJoin.NeedPassword(network.ssid)
            password.length < 8 -> WifiJoin.Reject("Password must be at least 8 characters")
            password.length > 63 || password.any { it == '"' || it == '\n' || it == '\r' } ->
                WifiJoin.Reject("That password can't be used")
            else -> WifiJoin.Password(network.ssid, password)
        }
    }
}

/** Quote a value for [android.net.wifi.WifiConfiguration]. Null if it cannot be quoted safely. */
fun quoteWifi(value: String): String? {
    if (value.isBlank() || value.any { it == '"' || it == '\n' || it == '\r' }) return null
    return "\"$value\""
}

/** The only control on the lock-screen Wi-Fi information box. */
const val PARENT_MENU_BUTTON = "Parent Menu"

fun wifiInfoActions(): List<String> = listOf(PARENT_MENU_BUTTON)

/**
 * Result of asking for the parent menu. The menu itself opens only for [Ok].
 * The check is the on-device PIN store, so it does not depend on being online.
 */
enum class ParentPinResult { Missing, Lockout, Cancel, Wrong, Ok }

/**
 * True only when the stored PIN matched. There is no default PIN.
 * Connectivity is not an argument: the hash is on the device, so this works offline.
 */
fun parentMenuGranted(result: ParentPinResult): Boolean = result == ParentPinResult.Ok

/** No dialog when there is no stored PIN or the lockout is active. Null means ask for the PIN. */
fun parentPromptKind(hasPin: Boolean, lockoutRemainingMs: Long): ParentPinResult? = when {
    !hasPin -> ParentPinResult.Missing
    lockoutRemainingMs > 0 -> ParentPinResult.Lockout
    else -> null
}

/** Read-only lines. A missing name stays "Name unavailable"; it is not replaced. */
fun infoText(indicator: WifiIndicator): String {
    val status = when (indicator.link) {
        WifiLink.Off -> "Off"
        WifiLink.Disconnected -> "Disconnected"
        WifiLink.Connecting -> "Connecting"
        WifiLink.Connected -> "Connected"
        WifiLink.NoInternet -> "Connected, no internet"
    }
    val network = indicator.ssid ?: "Name unavailable"
    return "${indicator.label()}\nStatus: $status\nNetwork: $network"
}

/**
 * The information box does not pause idle sleep. [deadline] is the countdown already running
 * after the tap that opened it. A reconnect attempt, the PIN prompt, and parent dialogs pause separately.
 */
fun deadlineWhileWifiInfoShown(deadline: Long?): Long? = deadline

const val RECONNECT_BUTTON = "Reconnect"
const val RECONNECTING_LABEL = "Reconnecting…"
const val RECONNECT_FAILED = "Couldn't reconnect."
const val RECONNECT_TIMEOUT_MS = 25_000L

enum class ReconnectPlan { EnableThenReconnect, ReconnectSaved, Blocked }

/** Saved-network reconnect only. Never selects a new network or asks for a password. */
fun reconnectPlan(enabled: Boolean, canChange: Boolean): ReconnectPlan = when {
    !canChange && !enabled -> ReconnectPlan.Blocked
    !enabled -> ReconnectPlan.EnableThenReconnect
    else -> ReconnectPlan.ReconnectSaved
}

enum class ReconnectPhase { Running, Succeeded, TimedOut }

fun reconnectPhase(startedAt: Long, now: Long, onNetwork: Boolean, timeoutMs: Long = RECONNECT_TIMEOUT_MS): ReconnectPhase = when {
    onNetwork -> ReconnectPhase.Succeeded
    now - startedAt >= timeoutMs -> ReconnectPhase.TimedOut
    else -> ReconnectPhase.Running
}

fun onNetwork(link: WifiLink): Boolean = link == WifiLink.Connected || link == WifiLink.NoInternet

data class WifiInfoControls(
    val detail: String,
    val actions: List<String>,
    val reconnectEnabled: Boolean,
    val showProgress: Boolean,
)

fun wifiInfoControls(indicator: WifiIndicator, attemptRunning: Boolean, failed: Boolean): WifiInfoControls {
    val showReconnect = attemptRunning || indicator.link == WifiLink.Off || indicator.link == WifiLink.Disconnected
    val detail = buildString {
        append(infoText(indicator))
        if (attemptRunning) append("\n").append(RECONNECTING_LABEL)
        if (failed && !attemptRunning) append("\n").append(RECONNECT_FAILED)
    }
    val actions = if (showReconnect) listOf(RECONNECT_BUTTON, PARENT_MENU_BUTTON) else listOf(PARENT_MENU_BUTTON)
    return WifiInfoControls(
        detail = detail,
        actions = actions,
        reconnectEnabled = showReconnect && !attemptRunning,
        showProgress = attemptRunning,
    )
}

object LockWifi {
    const val ACTION_WIFI_STATE = "android.net.wifi.WIFI_STATE_CHANGED"
    const val ACTION_NETWORK_STATE = "android.net.wifi.STATE_CHANGE"
    const val ACTION_SUPPLICANT = "android.net.wifi.supplicant.STATE_CHANGE"
    const val ACTION_SCAN_RESULTS = "android.net.wifi.SCAN_RESULTS"

    fun broadcastActions(): List<String> = listOf(ACTION_WIFI_STATE, ACTION_NETWORK_STATE, ACTION_SUPPLICANT)

    fun isWifiAction(action: String?) = action != null && action in broadcastActions()

    /** Clock, battery, screen, and Wi-Fi. One registration for the life of the lock cover. */
    fun overlayBroadcasts(): List<String> = LockIdle.coverBroadcastActions() + broadcastActions()
}
