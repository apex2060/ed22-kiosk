package org.ed22.kiosk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Device-local Wi-Fi changes for the lock screen.
 *
 * On Android 10 and later, turning Wi-Fi on or joining a network works for the device owner
 * (and other privileged callers). This app targets that SDK, so a normal install cannot do it.
 * There is no Settings screen and nothing here is written to Firestore. Passwords are not logged.
 */
object WifiControl {
    private const val TAG = "WifiControl"

    private val CONNECTING = setOf(
        SupplicantState.AUTHENTICATING,
        SupplicantState.ASSOCIATING,
        SupplicantState.ASSOCIATED,
        SupplicantState.FOUR_WAY_HANDSHAKE,
        SupplicantState.GROUP_HANDSHAKE,
    )

    fun canChange(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || KioskPolicy.isDeviceOwner(ctx)

    fun snapshot(ctx: Context): WifiSnapshot {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val enabled = runCatching { wm.isWifiEnabled }.getOrDefault(false)
        if (!enabled) return WifiSnapshot(false, false, false, false, null)
        val info = runCatching { wm.connectionInfo }.getOrNull()
        val connecting = info?.supplicantState in CONNECTING
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val wifi = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val caps = wifi?.let { cm.getNetworkCapabilities(it) }
        return WifiSnapshot(
            enabled = true,
            connecting = connecting && caps == null,
            onWifi = caps != null,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            ssid = readableSsid(info?.ssid),
        )
    }

    fun setEnabled(ctx: Context, on: Boolean): Boolean {
        if (!canChange(ctx)) return false
        return try {
            @Suppress("DEPRECATION")
            ctx.applicationContext.getSystemService(WifiManager::class.java).setWifiEnabled(on)
        } catch (e: SecurityException) {
            Log.w(TAG, "setWifiEnabled not allowed")
            false
        }
    }

    fun reconnect(ctx: Context): Boolean {
        if (!canChange(ctx)) return false
        return try {
            @Suppress("DEPRECATION")
            ctx.applicationContext.getSystemService(WifiManager::class.java).reconnect()
        } catch (e: SecurityException) {
            Log.w(TAG, "reconnect not allowed")
            false
        }
    }

    /** Saved networks plus scan results. Empty scans when permission is missing; that is not an error. */
    @Suppress("DEPRECATION", "MissingPermission")
    fun networks(ctx: Context): List<WifiNetwork> {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val saved = runCatching {
            wm.configuredNetworks.orEmpty().map { SavedWifi(it.SSID, it.networkId) }
        }.getOrDefault(emptyList())
        val scans = if (!canScan(ctx)) {
            emptyList()
        } else {
            runCatching { wm.startScan() }
            runCatching {
                wm.scanResults.orEmpty().map { RawAp(it.SSID, it.capabilities ?: "", it.level) }
            }.getOrDefault(emptyList())
        }
        return networksFrom(scans, saved)
    }

    fun canScan(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && granted(ctx, Manifest.permission.NEARBY_WIFI_DEVICES)) return true
        return granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /**
     * Joins [request] with the platform Wi-Fi stack. Returns null on success, or a message with no secret.
     */
    fun join(ctx: Context, request: WifiJoin): String? {
        if (request is WifiJoin.Reject) return request.reason
        if (!canChange(ctx)) return "Joining a network needs device owner on this Android version."
        return try {
            when (request) {
                is WifiJoin.Saved -> enable(ctx, request.networkId)
                is WifiJoin.Open -> add(ctx, request.ssid, null)
                is WifiJoin.Password -> add(ctx, request.ssid, request.password)
                is WifiJoin.NeedPassword -> "Password must be at least 8 characters"
                is WifiJoin.Reject -> request.reason
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Wi-Fi join not allowed")
            "Joining a network needs device owner on this Android version."
        }
    }

    @Suppress("DEPRECATION")
    private fun enable(ctx: Context, networkId: Int): String? {
        if (networkId < 0) return "Couldn't join that network"
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        wm.disconnect()
        val ok = wm.enableNetwork(networkId, true)
        wm.reconnect()
        return if (ok) null else "Couldn't join that network"
    }

    @Suppress("DEPRECATION")
    private fun add(ctx: Context, ssid: String, password: String?): String? {
        val quotedSsid = quoteWifi(ssid) ?: return "That network name can't be used"
        val quotedKey = password?.let { quoteWifi(it) ?: return "That password can't be used" }
        val conf = WifiConfiguration().apply {
            SSID = quotedSsid
            if (quotedKey == null) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = quotedKey
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
        }
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        var id = wm.addNetwork(conf)
        if (id == -1) {
            id = wm.configuredNetworks.orEmpty().firstOrNull { readableSsid(it.SSID) == ssid }?.networkId ?: -1
        }
        if (id == -1) return "Couldn't join that network"
        return enable(ctx, id)
    }

    private fun granted(ctx: Context, permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
}
