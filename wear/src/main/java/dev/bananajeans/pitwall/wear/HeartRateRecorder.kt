package dev.bananajeans.pitwall.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Optional heart-rate recording (issue #24).
 *
 * Uses the standard Android heart-rate sensor (Sensor.TYPE_HEART_RATE) —
 * available on Wear OS devices via the standard framework SensorManager and
 * backed on Samsung watches by their Health platform without any proprietary
 * SDK dependency. Samsung Health Sensor SDK is deliberately NOT used.
 *
 * Failure tolerance contract (issue #24 acceptance):
 *  - No permission / no sensor / sensor errors never affect IMU recording;
 *    the IMU log keeps flowing.
 *  - HR samples go into a separate stream so partial HR data is honest.
 */
class HeartRateRecorder(
    private val context: Context,
    private val sensorManager: SensorManager,
    private val onSample: (bpm: Int, timestampNanos: Long, accuracy: Int) -> Unit
) : SensorEventListener {

    private var registered = false
    @Volatile var lastBpm: Int? = null
        private set

    /** True when HR can be attempted (sensor exists + permission granted). */
    val available: Boolean get() = sensor != null && hasPermission()

    private val sensor: Sensor? by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE) }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts HR collection. Returns false (and touches nothing) when the
     * sensor is missing or permission is denied — callers treat HR as absent.
     */
    fun start(periodMicros: Int = 1_000_000): Boolean {
        val sensor = sensor ?: return false
        if (!hasPermission()) return false
        if (registered) return true
        registered = try {
            sensorManager.registerListener(this, sensor, periodMicros)
        } catch (_: Exception) {
            false
        }
        return registered
    }

    fun stop() {
        if (registered) {
            sensorManager.unregisterListener(this)
            registered = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.values.isEmpty()) return
        val bpm = event.values[0]
        if (bpm <= 0f || bpm > 260f) return // sensor warm-up / invalid
        lastBpm = bpm.toInt()
        onSample(lastBpm!!, event.timestamp, event.accuracy)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** Requests BODY_SENSORS at runtime (watch UI calls this on launch).
         *  API 33+ grants BODY_SENSORS at install time; requesting it there
         *  is a no-op at best and a crash path on some watch skins, so only
         *  older builds need the runtime request. */
        fun needsPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS) !=
                PackageManager.PERMISSION_GRANTED
    }
}
