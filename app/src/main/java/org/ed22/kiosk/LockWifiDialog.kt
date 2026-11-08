package org.ed22.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

/**
 * In-kiosk Wi-Fi panel. It does not open Android Settings or change the lock policy,
 * and does not record the password. While it is open the lock-screen idle timer is paused.
 */
object LockWifiDialog {
    private var current: AlertDialog? = null
    private var scanReceiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refreshSoon = Runnable {
        if (current?.isShowing == true) pendingReload?.invoke()
    }
    private var pendingReload: (() -> Unit)? = null

    private fun reloadSoon(reload: () -> Unit) {
        pendingReload = reload
        reload()
        handler.removeCallbacks(refreshSoon)
        handler.postDelayed(refreshSoon, 800)
        handler.postDelayed(refreshSoon, 2000)
    }

    fun dismiss() {
        current?.dismiss()
    }

    /** Read-only status. Does not pause the idle timer and has no Wi-Fi controls. */
    fun show(activity: MainActivity) {
        if (activity.isDestroyed || current?.isShowing == true) return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val status = TextView(activity).apply {
            textSize = 18f
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        val parent = Button(activity).apply {
            text = PARENT_MENU_BUTTON
            isAllCaps = false
            transformationMethod = null
            setOnClickListener {
                current?.dismiss()
                ParentMenu.prompt(activity)
            }
        }
        fun reload() {
            if (activity.isDestroyed) return
            status.text = infoText(indicatorOf(WifiControl.snapshot(activity)))
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(status)
            addView(parent, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(12)
            })
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Wi-Fi")
            .setView(body)
            .setCancelable(true)
            .create()
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            unregisterScan(activity)
            if (current === dialog) current = null
            activity.hideSystemUi()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (LockWifi.isWifiAction(intent?.action)) reload()
            }
        }
        scanReceiver = receiver
        try {
            ContextCompat.registerReceiver(
                activity,
                receiver,
                IntentFilter().apply { LockWifi.broadcastActions().forEach { addAction(it) } },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            current = dialog
            reload()
            dialog.show()
        } catch (e: Exception) {
            unregisterScan(activity)
            current = null
            throw e
        }
    }

    /** Join and radio controls. Only the unlocked parent menu calls this. */
    fun showParentControls(activity: MainActivity) {
        if (activity.isDestroyed) return
        activity.parentDialogShown()
        try {
            showPanel(activity)
        } catch (e: Exception) {
            unregisterScan(activity)
            current = null
            activity.parentDialogHidden()
            throw e
        }
    }

    private fun showPanel(activity: MainActivity) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val status = TextView(activity).apply {
            textSize = 16f
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val note = TextView(activity).apply {
            textSize = 14f
            setPadding(dp(20), 0, dp(20), dp(8))
        }
        val toggle = Button(activity)
        val reconnect = Button(activity).apply { text = "Reconnect" }
        val refresh = Button(activity).apply { text = "Refresh" }
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(note)
            addView(toggle)
            addView(reconnect)
            addView(refresh)
            addView(list)
        }
        fun reload() {
            if (activity.isDestroyed) return
            val indicator = indicatorOf(WifiControl.snapshot(activity))
            status.text = indicator.label()
            val enabled = indicator.link != WifiLink.Off
            toggle.text = if (enabled) "Turn Wi-Fi off" else "Turn Wi-Fi on"
            reconnect.visibility = if (enabled && indicator.link != WifiLink.Connected && indicator.link != WifiLink.Connecting) {
                View.VISIBLE
            } else {
                View.GONE
            }
            if (!WifiControl.canScan(activity) && enabled) {
                note.text = "Network names need a Wi-Fi permission that is not granted. Saved networks can still be used."
            } else if (!WifiControl.canChange(activity)) {
                note.text = "This install can't change Wi-Fi. Device owner is required on Android 10 and later."
            } else {
                note.text = "Tap a network to join. The password stays on this device."
            }
            list.removeAllViews()
            if (!enabled) return
            val networks = WifiControl.networks(activity)
            if (networks.isEmpty()) {
                list.addView(TextView(activity).apply {
                    text = "No networks found"
                    setPadding(dp(20), dp(8), dp(20), dp(8))
                })
                return
            }
            networks.forEach { network ->
                list.addView(TextView(activity).apply {
                    text = rowCaption(network)
                    textSize = 18f
                    setPadding(dp(20), dp(14), dp(20), dp(14))
                    isClickable = true
                    setOnClickListener { onNetwork(activity, network, status) }
                })
            }
        }
        toggle.setOnClickListener {
            val turnOn = indicatorOf(WifiControl.snapshot(activity)).link == WifiLink.Off
            if (!WifiControl.setEnabled(activity, turnOn)) {
                note.text = "Wi-Fi can't be changed unless this app is device owner."
            }
            reloadSoon { reload() }
        }
        reconnect.setOnClickListener {
            if (!WifiControl.reconnect(activity)) note.text = "Couldn't reconnect."
            reloadSoon { reload() }
        }
        refresh.setOnClickListener { reloadSoon { reload() } }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Wi-Fi")
            .setView(ScrollView(activity).apply {
                addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener {
            handler.removeCallbacks(refreshSoon)
            pendingReload = null
            unregisterScan(activity)
            if (current === dialog) current = null
            activity.parentDialogHidden()
            activity.hideSystemUi()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (LockWifi.isWifiAction(intent?.action) || intent?.action == LockWifi.ACTION_SCAN_RESULTS) reload()
            }
        }
        scanReceiver = receiver
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter().apply {
                LockWifi.broadcastActions().forEach { addAction(it) }
                addAction(LockWifi.ACTION_SCAN_RESULTS)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        current = dialog
        reload()
        dialog.show()
    }

    private fun onNetwork(activity: MainActivity, network: WifiNetwork, status: TextView) {
        when (val planned = planJoin(network, null)) {
            is WifiJoin.Reject -> status.text = planned.reason
            is WifiJoin.NeedPassword -> askPassword(activity, network, status)
            is WifiJoin.Open, is WifiJoin.Saved -> {
                val error = WifiControl.join(activity, planned)
                status.text = error ?: "Joining ${network.ssid}"
            }
            is WifiJoin.Password -> askPassword(activity, network, status)
        }
    }

    private fun askPassword(activity: MainActivity, network: WifiNetwork, status: TextView) {
        activity.parentDialogShown()
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            hint = "Password"
        }
        val holder = LinearLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(network.ssid)
            .setView(holder)
            .setPositiveButton("Join", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnDismissListener {
            input.setText("")
            activity.parentDialogHidden()
            activity.hideSystemUi()
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val planned = planJoin(network, input.text.toString())
                if (planned is WifiJoin.Reject) {
                    input.error = planned.reason
                    return@setOnClickListener
                }
                val error = WifiControl.join(activity, planned)
                input.setText("")
                dialog.dismiss()
                status.text = error ?: "Joining ${network.ssid}"
                pendingReload?.let { reloadSoon(it) }
            }
        }
        try {
            dialog.show()
        } catch (e: Exception) {
            activity.parentDialogHidden()
            throw e
        }
    }

    private fun unregisterScan(activity: MainActivity) {
        scanReceiver?.let {
            try {
                activity.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Already gone.
            }
        }
        scanReceiver = null
    }
}
