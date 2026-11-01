package org.ed22.kiosk

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * QR / setup-wizard provisioning (Android 10+): tells the setup wizard we want to be the device
 * owner of a fully managed device.
 */
class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(
            RESULT_OK,
            // Literal values: these constants are API 29+ and minSdk is 26.
            Intent().putExtra("android.app.extra.PROVISIONING_MODE", 1 /* PROVISIONING_MODE_FULLY_MANAGED_DEVICE */)
        )
        finish()
    }
}

/** Runs at the end of provisioning (Android 10+): apply the kiosk lockdown, then let setup finish. */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KioskPolicy.apply(this)
        setResult(RESULT_OK)
        finish()
    }
}
