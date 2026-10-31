package org.ed22.kiosk

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.util.Locale
import java.util.TimeZone

/** Snapshots of system state, shaped as the JSON returned by `kiosk.get*()`. */
object DeviceInfo {

    fun battery(ctx: Context): JSONObject {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return JSONObject()
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            0 -> "none"
            else -> "other"
        }
        val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }
        return JSONObject()
            .put("level", if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            .put("full", status == BatteryManager.BATTERY_STATUS_FULL)
            .put("plugged", plugged)
            .put("health", health)
            .put("temperatureC", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
            .put("voltageMv", i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0))
    }

    fun network(ctx: Context): JSONObject {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val type = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        return JSONObject()
            .put("online", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("type", type)
            .put("metered", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false)
            .put("downstreamKbps", caps?.linkDownstreamBandwidthKbps ?: 0)
            .put("upstreamKbps", caps?.linkUpstreamBandwidthKbps ?: 0)
    }

    @Suppress("DEPRECATION")
    fun wifi(ctx: Context): JSONObject {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val wifiNetwork = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val info = wm.connectionInfo
        val connected = wifiNetwork != null && info != null && info.networkId != -1
        val result = JSONObject()
            .put("enabled", wm.isWifiEnabled)
            .put("connected", connected)
        if (!connected) return result
        // The SSID is only readable with location permission and location turned on.
        val ssid = info.ssid?.removeSurrounding("\"")?.takeUnless { it == "<unknown ssid>" }
        val ip = cm.getLinkProperties(wifiNetwork)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
        val signal = if (Build.VERSION.SDK_INT >= 30) {
            wm.calculateSignalLevel(info.rssi) * 4 / wm.maxSignalLevel.coerceAtLeast(1)
        } else {
            WifiManager.calculateSignalLevel(info.rssi, 5)
        }
        return result
            .put("ssid", ssid ?: JSONObject.NULL)
            .put("bssid", info.bssid?.takeUnless { it == "02:00:00:00:00:00" } ?: JSONObject.NULL)
            .put("rssi", info.rssi)
            .put("signalLevel", signal)
            .put("linkSpeedMbps", info.linkSpeed)
            .put("frequencyMhz", info.frequency)
            .put("ipAddress", ip ?: JSONObject.NULL)
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    fun device(ctx: Context): JSONObject {
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val dm = ctx.resources.displayMetrics
        val serial = try {
            Build.getSerial().takeUnless { it == Build.UNKNOWN }
        } catch (e: SecurityException) {
            null
        }
        return JSONObject()
            .put("deviceId", Prefs.deviceId(ctx))
            .put("serial", serial ?: JSONObject.NULL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("brand", Build.BRAND)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("appVersion", pkg.versionName)
            .put("isDeviceOwner", KioskPolicy.isDeviceOwner(ctx))
            .put("lockTaskActive", KioskPolicy.isLockTaskActive(ctx))
            .put("locale", Locale.getDefault().toLanguageTag())
            .put("timeZone", TimeZone.getDefault().id)
            .put("uptimeMs", SystemClock.elapsedRealtime())
            .put(
                "screen", JSONObject()
                    .put("widthPx", dm.widthPixels)
                    .put("heightPx", dm.heightPixels)
                    .put("density", dm.density.toDouble())
                    .put(
                        "orientation",
                        if (ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
                    )
            )
    }

    fun storage(): JSONObject {
        val stat = StatFs(Environment.getDataDirectory().path)
        return JSONObject()
            .put("totalBytes", stat.totalBytes)
            .put("freeBytes", stat.availableBytes)
    }

    fun memory(ctx: Context): JSONObject {
        val info = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return JSONObject()
            .put("totalBytes", info.totalMem)
            .put("availableBytes", info.availMem)
            .put("lowMemory", info.lowMemory)
    }

    fun jsonArrayOf(values: FloatArray): JSONArray =
        JSONArray().apply { values.forEach { put(if (it.isNaN() || it.isInfinite()) JSONObject.NULL else it.toDouble()) } }
}
