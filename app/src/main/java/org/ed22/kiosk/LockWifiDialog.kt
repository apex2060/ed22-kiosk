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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

/**
 * Lock-screen Wi-Fi box and the parent-only join panel. The box does not open Settings,
 * change the lock policy, or record a password. Reconnect retries saved networks only.
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

    /**
     * Status plus Reconnect when Wi-Fi is not on a network. Reconnect uses saved networks only.
     * Opening the box does not pause idle sleep; a reconnect attempt does, until it finishes or the box closes.
     */
    fun show(activity: MainActivity) {
        if (activity.isDestroyed || current?.isShowing == true) return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val status = TextView(activity).apply {
            textSize = 18f
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        val progress = ProgressBar(activity).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        val reconnect = labeledButton(activity, RECONNECT_BUTTON)
        val parent = labeledButton(activity, PARENT_MENU_BUTTON)
        var attemptStarted: Long? = null
        var failed = false
        var holdingSleep = false
        fun releaseSleep() {
            if (!holdingSleep) return
            holdingSleep = false
            activity.parentDialogHidden()
        }
        fun holdSleep() {
            if (holdingSleep) return
            holdingSleep = true
            activity.parentDialogShown()
        }
        var reload: (() -> Unit)? = null
        var tick: Runnable? = null
        fun stopAttempt(success: Boolean, reportFailure: Boolean) {
            if (attemptStarted == null && !holdingSleep) return
            attemptStarted = null
            tick?.let { handler.removeCallbacks(it) }
            handler.removeCallbacks(refreshSoon)
            failed = reportFailure && !success
            releaseSleep()
            reload?.invoke()
        }
        tick = Runnable {
            val tickRunnable = tick ?: return@Runnable
            val started = attemptStarted ?: return@Runnable
            val indicator = indicatorOf(WifiControl.snapshot(activity))
            when (reconnectPhase(started, android.os.SystemClock.uptimeMillis(), onNetwork(indicator.link))) {
                ReconnectPhase.Succeeded -> stopAttempt(success = true, reportFailure = false)
                ReconnectPhase.TimedOut -> stopAttempt(success = false, reportFailure = true)
                ReconnectPhase.Running -> {
                    WifiControl.reconnectSaved(activity)
                    reload?.invoke()
                    handler.postDelayed(tickRunnable, 2_000)
                }
            }
        }
        reload = {
            if (!activity.isDestroyed) {
            val indicator = indicatorOf(WifiControl.snapshot(activity))
            val started = attemptStarted
            val phase = if (started == null) null else reconnectPhase(
                started,
                android.os.SystemClock.uptimeMillis(),
                onNetwork(indicator.link),
            )
            if (phase == ReconnectPhase.Succeeded) {
                stopAttempt(success = true, reportFailure = false)
            } else if (phase == ReconnectPhase.TimedOut) {
                stopAttempt(success = false, reportFailure = true)
            } else {
                val controls = wifiInfoControls(indicator, attemptStarted != null, failed)
                status.text = controls.detail
                val showReconnect = RECONNECT_BUTTON in controls.actions
                reconnect.visibility = if (showReconnect) View.VISIBLE else View.GONE
                reconnect.isEnabled = controls.reconnectEnabled
                progress.visibility = if (controls.showProgress) View.VISIBLE else View.GONE
            }
            }
        }
        pendingReload = reload
        reconnect.setOnClickListener {
            if (attemptStarted != null || !reconnect.isEnabled) return@setOnClickListener
            failed = false
            if (reconnectPlan(
                    indicatorOf(WifiControl.snapshot(activity)).link != WifiLink.Off,
                    WifiControl.canChange(activity),
                ) == ReconnectPlan.Blocked
            ) {
                failed = true
                reload?.invoke()
                return@setOnClickListener
            }
            if (!WifiControl.reconnectSaved(activity) &&
                indicatorOf(WifiControl.snapshot(activity)).link == WifiLink.Off
            ) {
                failed = true
                reload?.invoke()
                return@setOnClickListener
            }
            attemptStarted = android.os.SystemClock.uptimeMillis()
            holdSleep()
            tick?.let { handler.removeCallbacks(it) }
            handler.postDelayed(tick!!, 2_000)
            reload?.invoke()
        }
        parent.setOnClickListener {
            stopAttempt(success = false, reportFailure = false)
            current?.dismiss()
            ParentMenu.prompt(activity)
        }
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(8)
                rightMargin = dp(8)
            }
            addView(reconnect, lp)
            addView(parent, LinearLayout.LayoutParams(lp))
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(status)
            addView(progress, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            })
            addView(buttons)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Wi-Fi")
            .setView(body)
            .setCancelable(true)
            .create()
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            stopAttempt(success = false, reportFailure = false)
            unregisterScan(activity)
            if (current === dialog) current = null
            activity.hideSystemUi()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (LockWifi.isWifiAction(intent?.action)) reload?.invoke()
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
            reload?.invoke()
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

    private fun labeledButton(activity: MainActivity, label: String) = Button(activity).apply {
        text = label
        isAllCaps = false
        transformationMethod = null
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
