package org.ed22.kiosk

import android.content.Intent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

class LockScreenTest {
    private val us = Locale.US

    @Test
    fun clockIsLocalHourAndMinute() {
        assertEquals("3:07", LockScreen.clock(LocalTime.of(15, 7), hour12 = true, us))
        assertEquals("9:05", LockScreen.clock(LocalTime.of(9, 5), hour12 = true, us))
        assertEquals("12:00", LockScreen.clock(LocalTime.of(0, 0), hour12 = true, us))
        assertEquals("12:00", LockScreen.clock(LocalTime.of(12, 0), hour12 = true, us))
        assertEquals("15:07", LockScreen.clock(LocalTime.of(15, 7), hour12 = false, us))
        assertEquals("00:05", LockScreen.clock(LocalTime.of(0, 5), hour12 = false, us))
        assertEquals("23:59", LockScreen.clock(LocalTime.of(23, 59), hour12 = false, us))
    }

    @Test
    fun dateLineMatchesDeviceLocale() {
        assertEquals("Saturday, November 7", LockScreen.date(LocalDate.of(2026, 11, 7), us))
        assertEquals("Thursday, January 1", LockScreen.date(LocalDate.of(2026, 1, 1), us))
        assertEquals("samedi, novembre 7", LockScreen.date(LocalDate.of(2026, 11, 7), Locale.FRANCE))
    }

    @Test
    fun batteryLineUsesDeviceInfoFields() {
        fun battery(level: Int, charging: Boolean, full: Boolean = false) =
            JSONObject().put("level", level).put("charging", charging).put("full", full)

        assertEquals("87%", LockScreen.batteryLine(battery(87, charging = false)))
        assertEquals("87% \u26A1", LockScreen.batteryLine(battery(87, charging = true)))
        assertEquals("100% \u26A1", LockScreen.batteryLine(battery(100, charging = false, full = true)))
        assertEquals("100% \u26A1", LockScreen.batteryLine(battery(100, charging = true, full = true)))
        assertEquals("0%", LockScreen.batteryLine(JSONObject()))
        assertEquals("100% \u26A1", LockScreen.batteryLine(battery(140, charging = true)))
        assertEquals("0%", LockScreen.batteryLine(battery(-3, charging = false)))
        // Plugged is not enough: the bolt follows charging / full, same as DeviceInfo.battery.
        val plugged = battery(80, charging = false).put("plugged", "ac")
        assertEquals("80%", LockScreen.batteryLine(plugged))
    }

    @Test
    fun coverKeepsTheOriginalMessageSizeAndColor() {
        assertEquals("This device is locked", LockScreen.LOCKED_MESSAGE)
        assertEquals(64f, LockScreen.CLOCK_TEXT_SP)
        assertEquals(0xFF1F2A44.toInt(), LockScreen.backgroundColor)
    }

    @Test
    fun broadcastsCoverTimeTickTimeZoneDateAndBattery() {
        assertEquals(
            listOf(
                "android.intent.action.TIME_TICK",
                "android.intent.action.TIME_SET",
                "android.intent.action.TIMEZONE_CHANGED",
                "android.intent.action.DATE_CHANGED",
            ),
            LockScreen.timeActions,
        )
        assertEquals("android.intent.action.BATTERY_CHANGED", LockScreen.ACTION_BATTERY_CHANGED)
        assertEquals(LockScreen.timeActions + LockScreen.ACTION_BATTERY_CHANGED, LockScreen.broadcastActions())
        assertTrue(LockScreen.isTimeAction(LockScreen.ACTION_TIME_TICK))
        assertTrue(LockScreen.isTimeAction(LockScreen.ACTION_TIME_CHANGED))
        assertTrue(LockScreen.isTimeAction(LockScreen.ACTION_TIMEZONE_CHANGED))
        assertTrue(LockScreen.isTimeAction(LockScreen.ACTION_DATE_CHANGED))
        assertFalse(LockScreen.isTimeAction(LockScreen.ACTION_BATTERY_CHANGED))
        assertTrue(LockScreen.isBatteryAction(LockScreen.ACTION_BATTERY_CHANGED))
        assertFalse(LockScreen.isBatteryAction(null))
        assertFalse(LockScreen.isTimeAction(null))
        // Same strings the platform sends. These are inlined constants, so the test does not load Android stubs.
        assertEquals(Intent.ACTION_TIME_TICK, LockScreen.ACTION_TIME_TICK)
        assertEquals(Intent.ACTION_TIME_CHANGED, LockScreen.ACTION_TIME_CHANGED)
        assertEquals(Intent.ACTION_TIMEZONE_CHANGED, LockScreen.ACTION_TIMEZONE_CHANGED)
        assertEquals(Intent.ACTION_DATE_CHANGED, LockScreen.ACTION_DATE_CHANGED)
        assertEquals(Intent.ACTION_BATTERY_CHANGED, LockScreen.ACTION_BATTERY_CHANGED)
    }
}

class LockScreenListenersTest {
    @Test
    fun showingTwiceRegistersOnceAndRemovalUnregistersOnce() {
        var registers = 0
        var unregisters = 0
        val listeners = LockScreenListeners(register = { registers++ }, unregister = { unregisters++ })
        listeners.onShown()
        listeners.onShown()
        assertEquals(1, registers)
        assertTrue(listeners.registered)
        listeners.onHidden()
        listeners.onHidden()
        assertEquals(1, unregisters)
        assertFalse(listeners.registered)
        // Locked again after unlock: register once more.
        listeners.onShown()
        assertEquals(2, registers)
        assertTrue(listeners.registered)
    }

    @Test
    fun hiddenWithoutShowingDoesNotUnregister() {
        var unregisters = 0
        val listeners = LockScreenListeners(register = {}, unregister = { unregisters++ })
        listeners.onHidden()
        assertEquals(0, unregisters)
        assertFalse(listeners.registered)
    }

    @Test
    fun failedRegisterStaysUnregisteredAndRetries() {
        var registers = 0
        val listeners = LockScreenListeners(
            register = {
                registers++
                if (registers == 1) throw IllegalStateException("nope")
            },
            unregister = {},
        )
        try {
            listeners.onShown()
            throw AssertionError("expected register to throw")
        } catch (e: IllegalStateException) {
            assertEquals("nope", e.message)
        }
        assertFalse(listeners.registered)
        listeners.onShown()
        assertTrue(listeners.registered)
        assertEquals(2, registers)
    }

    @Test
    fun failedUnregisterStaysRegisteredAndRetries() {
        var unregisters = 0
        val listeners = LockScreenListeners(
            register = {},
            unregister = {
                unregisters++
                if (unregisters == 1) throw IllegalStateException("busy")
            },
        )
        listeners.onShown()
        try {
            listeners.onHidden()
            throw AssertionError("expected unregister to throw")
        } catch (e: IllegalStateException) {
            assertEquals("busy", e.message)
        }
        assertTrue(listeners.registered)
        listeners.onHidden()
        assertFalse(listeners.registered)
        assertEquals(2, unregisters)
    }

    @Test
    fun recreatedInstanceRegistersIndependently() {
        var live = 0
        val first = LockScreenListeners(register = { live++ }, unregister = { live-- })
        first.onShown()
        val second = LockScreenListeners(register = { live++ }, unregister = { live-- })
        assertFalse(second.registered)
        second.onShown()
        assertEquals(2, live)
        first.onHidden()
        assertEquals(1, live)
        assertTrue(second.registered)
        second.onHidden()
        assertEquals(0, live)
        assertFalse(first.registered)
    }
}
