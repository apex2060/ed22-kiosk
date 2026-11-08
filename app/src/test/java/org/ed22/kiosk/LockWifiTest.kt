package org.ed22.kiosk

import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockWifiTest {
    @Test
    fun labelsCoverEachLinkAndNeverInventAName() {
        assertEquals("Wi-Fi off", WifiIndicator(WifiLink.Off, "Cafe").label())
        assertEquals("Wi-Fi disconnected", indicatorOf(snap(enabled = true)).label())
        assertEquals("Connecting…", indicatorOf(snap(enabled = true, connecting = true)).label())
        assertEquals(
            "Connecting to Cafe",
            indicatorOf(snap(enabled = true, connecting = true, ssid = "Cafe")).label(),
        )
        assertEquals("Connected", indicatorOf(snap(enabled = true, onWifi = true, validated = true)).label())
        assertEquals(
            "Cafe",
            indicatorOf(snap(enabled = true, onWifi = true, validated = true, ssid = "\"Cafe\"")).label(),
        )
        assertEquals(
            "Connected, no internet",
            indicatorOf(snap(enabled = true, onWifi = true, validated = false)).label(),
        )
        assertEquals(
            "Cafe, no internet",
            indicatorOf(snap(enabled = true, onWifi = true, ssid = "Cafe")).label(),
        )
        val unknown = indicatorOf(snap(enabled = true, onWifi = true, validated = true, ssid = "<unknown ssid>"))
        assertNull(unknown.ssid)
        assertEquals("Connected", unknown.label())
        assertFalse(unknown.label().contains("unknown", ignoreCase = true))
    }

    @Test
    fun readableSsidDropsBlankAndUnknown() {
        assertNull(readableSsid(null))
        assertNull(readableSsid("  "))
        assertNull(readableSsid("<unknown ssid>"))
        assertNull(readableSsid("\"<unknown ssid>\""))
        assertNull(readableSsid("0x"))
        assertEquals("Cafe", readableSsid("\"Cafe\""))
    }

    @Test
    fun offWinsOverAStaleConnection() {
        val indicator = indicatorOf(snap(enabled = false, onWifi = true, validated = true, ssid = "Cafe"))
        assertEquals(WifiLink.Off, indicator.link)
        assertNull(indicator.ssid)
    }

    @Test
    fun connectedNetworkIsNotShownAsConnecting() {
        val indicator = indicatorOf(snap(enabled = true, connecting = true, onWifi = true, validated = true, ssid = "Cafe"))
        assertEquals(WifiLink.Connected, indicator.link)
        assertEquals("Cafe", indicator.ssid)
    }

    @Test
    fun networksCollapseToTheStrongestApAndMarkSaved() {
        val list = networksFrom(
            scans = listOf(
                RawAp("Cafe", "[WPA2-PSK-CCMP]", -70),
                RawAp("\"Cafe\"", "[WPA2-PSK-CCMP]", -40),
                RawAp("<unknown ssid>", "[ESS]", -20),
                RawAp("Guest", "[ESS]", -50),
                RawAp("Work", "[WPA2-EAP-CCMP]", -30),
            ),
            saved = listOf(SavedWifi("\"Cafe\"", 4), SavedWifi("Old", 2)),
        )
        assertEquals(listOf("Cafe", "Old", "Work", "Guest"), list.map { it.ssid })
        val cafe = list.first()
        assertTrue(cafe.saved)
        assertEquals(4, cafe.networkId)
        assertEquals(WifiSecurity.Wpa, cafe.security)
        assertEquals(-40, cafe.level)
        assertEquals(WifiSecurity.Open, list.first { it.ssid == "Guest" }.security)
        assertEquals(WifiSecurity.Unsupported, list.first { it.ssid == "Work" }.security)
        assertEquals("Cafe · Saved", rowCaption(cafe))
        assertEquals("Guest · Open", rowCaption(list.first { it.ssid == "Guest" }))
        assertEquals("Work · Not available", rowCaption(list.first { it.ssid == "Work" }))
    }

    @Test
    fun joinPlanUsesThePlatformAndDoesNotExposeThePassword() {
        val open = WifiNetwork("Guest", WifiSecurity.Open, saved = false, networkId = -1, level = -50)
        assertTrue(planJoin(open, "ignored-secret") is WifiJoin.Open)

        val saved = WifiNetwork("Cafe", WifiSecurity.Wpa, saved = true, networkId = 4, level = -40)
        val reuse = planJoin(saved, null)
        assertTrue(reuse is WifiJoin.Saved)
        assertEquals(4, (reuse as WifiJoin.Saved).networkId)

        val fresh = WifiNetwork("Cafe", WifiSecurity.Wpa, saved = false, networkId = -1, level = -40)
        assertTrue(planJoin(fresh, null) is WifiJoin.NeedPassword)
        assertEquals("Password must be at least 8 characters", (planJoin(fresh, "short") as WifiJoin.Reject).reason)
        val joined = planJoin(fresh, "correct-horse")
        assertTrue(joined is WifiJoin.Password)
        assertEquals("correct-horse", (joined as WifiJoin.Password).password)
        assertFalse(joined.toString().contains("correct-horse"))
        assertTrue(joined.toString().contains("Cafe"))

        val work = WifiNetwork("Work", WifiSecurity.Unsupported, saved = false, networkId = -1, level = -30)
        assertEquals("This network can't be joined here", (planJoin(work, "correct-horse") as WifiJoin.Reject).reason)
        assertNull(quoteWifi("bad\"quote"))
        assertEquals("\"Cafe\"", quoteWifi("Cafe"))
    }

    @Test
    fun infoBoxHasOnlyTheParentMenuButton() {
        assertEquals(listOf("Parent Menu"), wifiInfoActions())
        val forbidden = listOf("Close", "Refresh", "Reconnect", "Turn Wi-Fi on", "Turn Wi-Fi off", "Join", "Password")
        assertTrue(forbidden.none { it in wifiInfoActions() })
    }

    @Test
    fun pinFailCancelAndMissingPinDoNotOpenTheParentMenu() {
        assertEquals(ParentPinResult.Missing, parentPromptKind(hasPin = false, lockoutRemainingMs = 0))
        assertEquals(ParentPinResult.Lockout, parentPromptKind(hasPin = true, lockoutRemainingMs = 1_000))
        assertNull(parentPromptKind(hasPin = true, lockoutRemainingMs = 0))
        assertFalse(parentMenuGranted(ParentPinResult.Missing))
        assertFalse(parentMenuGranted(ParentPinResult.Lockout))
        assertFalse(parentMenuGranted(ParentPinResult.Cancel))
        assertFalse(parentMenuGranted(ParentPinResult.Wrong))
        assertTrue(parentMenuGranted(ParentPinResult.Ok))
    }

    @Test
    fun infoTextRefreshesFromTheLiveSnapshotAndOmitsUnknownNames() {
        val connected = infoText(indicatorOf(snap(enabled = true, onWifi = true, validated = true, ssid = "Cafe")))
        assertTrue(connected.startsWith("Cafe"))
        assertTrue(connected.contains("Status: Connected"))
        assertTrue(connected.contains("Network: Cafe"))
        val unknown = infoText(indicatorOf(snap(enabled = true, onWifi = true, validated = true, ssid = "<unknown ssid>")))
        assertTrue(unknown.contains("Network: Name unavailable"))
        assertFalse(unknown.contains("unknown ssid", ignoreCase = true))
        val off = infoText(indicatorOf(snap(enabled = false, ssid = "Cafe")))
        assertTrue(off.startsWith("Wi-Fi off"))
        assertTrue(off.contains("Network: Name unavailable"))
    }

    @Test
    fun readOnlyWifiInfoDoesNotPauseTheFiveSecondSleep() {
        val idle = LockIdle()
        idle.coverShown(0)
        idle.touch(1_000)
        assertEquals(6_000L, idle.deadline())
        assertEquals(6_000L, deadlineWhileWifiInfoShown(idle.deadline()))
        assertFalse(idle.alarm(5_999).turnScreenOff)
        assertTrue(idle.alarm(6_000).turnScreenOff)
    }

    @Test
    fun parentPinDialogStillPausesSleepUntilItCloses() {
        val idle = LockIdle()
        idle.coverShown(0)
        idle.touch(1_000)
        idle.dialogOpened()
        assertNull(idle.deadline())
        assertFalse(idle.alarm(6_000).turnScreenOff)
        val cancelled = idle.dialogClosed(4_000)
        assertEquals(5_000L, cancelled.nextDelayMs)
        assertEquals(9_000L, idle.deadline())
        assertFalse(idle.alarm(8_999).turnScreenOff)
        assertTrue(idle.alarm(9_000).turnScreenOff)
    }

    @Test
    fun wifiBroadcastsStayWithTheLockCoverListeners() {
        assertEquals(WifiManager.WIFI_STATE_CHANGED_ACTION, LockWifi.ACTION_WIFI_STATE)
        assertEquals(WifiManager.NETWORK_STATE_CHANGED_ACTION, LockWifi.ACTION_NETWORK_STATE)
        assertEquals(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION, LockWifi.ACTION_SUPPLICANT)
        assertEquals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION, LockWifi.ACTION_SCAN_RESULTS)
        assertEquals(LockIdle.coverBroadcastActions() + LockWifi.broadcastActions(), LockWifi.overlayBroadcasts())
        assertTrue(LockWifi.isWifiAction(LockWifi.ACTION_WIFI_STATE))
        assertFalse(LockWifi.isWifiAction(LockIdle.ACTION_SCREEN_OFF))
        assertFalse(LockWifi.broadcastActions().contains(LockWifi.ACTION_SCAN_RESULTS))

        var radios = 0
        val listeners = LockScreenListeners(
            register = { radios += LockWifi.broadcastActions().size },
            unregister = { radios -= LockWifi.broadcastActions().size },
        )
        listeners.onShown()
        listeners.onShown()
        assertEquals(LockWifi.broadcastActions().size, radios)
        listeners.onHidden()
        listeners.onHidden()
        assertEquals(0, radios)
        val again = LockScreenListeners(register = { radios++ }, unregister = { radios-- })
        again.onShown()
        assertEquals(1, radios)
        again.onHidden()
        assertEquals(0, radios)
    }

    private fun snap(
        enabled: Boolean,
        connecting: Boolean = false,
        onWifi: Boolean = false,
        validated: Boolean = false,
        ssid: String? = null,
    ) = WifiSnapshot(enabled, connecting, onWifi, validated, ssid)
}
