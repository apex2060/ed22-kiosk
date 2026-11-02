package org.ed22.kiosk

import android.app.Application

class KioskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceSync.init(this)
    }
}
