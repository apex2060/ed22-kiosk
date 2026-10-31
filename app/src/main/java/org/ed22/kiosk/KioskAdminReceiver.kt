package org.ed22.kiosk

import android.app.admin.DeviceAdminReceiver

/** Needed so the app can be made device owner with `adb shell dpm set-device-owner`. */
class KioskAdminReceiver : DeviceAdminReceiver()
