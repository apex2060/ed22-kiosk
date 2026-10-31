package org.ed22.kiosk

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** First-run PIN setup, also reused from the parent menu to change the PIN. */
class PinSetupActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_CHANGE = "change"
    }

    private val changing get() = intent.getBooleanExtra(EXTRA_CHANGE, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_setup)

        val pin = findViewById<EditText>(R.id.pin)
        val confirm = findViewById<EditText>(R.id.confirm)
        val error = findViewById<TextView>(R.id.error)
        if (changing) findViewById<TextView>(R.id.title).text = "Change parent PIN"
        if (!KioskPolicy.isDeviceOwner(this)) findViewById<View>(R.id.owner_warning).visibility = View.VISIBLE

        fun save() {
            val a = pin.text.toString()
            val b = confirm.text.toString()
            val problem = when {
                !PinStore.PIN_REGEX.matches(a) -> "The PIN must be 4 to 8 digits."
                a != b -> "The two PINs don't match."
                else -> null
            }
            if (problem != null) {
                error.text = problem
                error.visibility = View.VISIBLE
                return
            }
            PinStore.setPin(this, a)
            Toast.makeText(this, "Parent PIN saved", Toast.LENGTH_SHORT).show()
            if (!changing) startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        findViewById<Button>(R.id.save).setOnClickListener { save() }
        confirm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save()
                true
            } else {
                false
            }
        }
    }
}
