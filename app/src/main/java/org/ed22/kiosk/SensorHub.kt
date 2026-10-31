package org.ed22.kiosk

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Sensor subscriptions made by the page through `kiosk.sensors.watch()`. */
class SensorHub(ctx: Context) {
    companion object {
        val TYPES = linkedMapOf(
            "accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "gyroscope" to Sensor.TYPE_GYROSCOPE,
            "magnetometer" to Sensor.TYPE_MAGNETIC_FIELD,
            "gravity" to Sensor.TYPE_GRAVITY,
            "linear_acceleration" to Sensor.TYPE_LINEAR_ACCELERATION,
            "rotation_vector" to Sensor.TYPE_ROTATION_VECTOR,
            "game_rotation_vector" to Sensor.TYPE_GAME_ROTATION_VECTOR,
            "light" to Sensor.TYPE_LIGHT,
            "proximity" to Sensor.TYPE_PROXIMITY,
            "pressure" to Sensor.TYPE_PRESSURE,
            "humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
            "ambient_temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
            "step_counter" to Sensor.TYPE_STEP_COUNTER,
            "step_detector" to Sensor.TYPE_STEP_DETECTOR,
        )
        private const val MIN_RATE_MS = 10L
    }

    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val watches = mutableMapOf<Int, Watch>()
    private var nextId = 1
    private var paused = false

    private inner class Watch(
        val id: Int,
        val type: String,
        val sensor: Sensor,
        val rateMs: Long,
        val listener: (JSONObject) -> Unit,
    ) : SensorEventListener {
        private var last = 0L

        override fun onSensorChanged(event: SensorEvent) {
            val now = SystemClock.elapsedRealtime()
            if (now - last < rateMs) return
            last = now
            listener(
                JSONObject()
                    .put("watchId", id)
                    .put("type", type)
                    .put("values", DeviceInfo.jsonArrayOf(event.values))
                    .put("accuracy", event.accuracy)
                    .put("timestamp", System.currentTimeMillis())
            )
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    fun list(): JSONArray = JSONArray().apply {
        TYPES.forEach { (name, type) ->
            val s = sm.getDefaultSensor(type) ?: return@forEach
            put(
                JSONObject()
                    .put("type", name)
                    .put("name", s.name)
                    .put("vendor", s.vendor)
                    .put("maxRange", s.maximumRange.toDouble())
                    .put("resolution", s.resolution.toDouble())
                    .put("minDelayUs", s.minDelay)
            )
        }
    }

    fun watch(type: String, rateMs: Long, listener: (JSONObject) -> Unit): Int {
        val sensorType = TYPES[type] ?: throw IllegalArgumentException("Unknown sensor type: $type")
        val sensor = sm.getDefaultSensor(sensorType) ?: throw IllegalStateException("This device has no $type sensor")
        val watch = Watch(nextId++, type, sensor, rateMs.coerceAtLeast(MIN_RATE_MS), listener)
        watches[watch.id] = watch
        if (!paused) register(watch)
        return watch.id
    }

    fun clear(id: Int) {
        watches.remove(id)?.let { sm.unregisterListener(it) }
    }

    fun clearAll() {
        watches.values.forEach { sm.unregisterListener(it) }
        watches.clear()
    }

    fun pause() {
        paused = true
        watches.values.forEach { sm.unregisterListener(it) }
    }

    fun resume() {
        if (!paused) return
        paused = false
        watches.values.forEach(::register)
    }

    private fun register(w: Watch) {
        sm.registerListener(w, w.sensor, (w.rateMs * 1000).toInt())
    }
}
