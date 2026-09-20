package dev.bananajeans.pitwall.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import dev.bananajeans.pitwall.protocol.PitwallJson
import dev.bananajeans.pitwall.protocol.WatchLogCodec
import java.io.File

/**
 * On-device sensor capability discovery (issue #17).
 *
 * Nothing in the recorder hard-codes Galaxy Watch7 capabilities: rates and
 * sensor availability are probed here, per recording, from the SensorManager.
 * The probe result is stored in every watch log's metadata and is exportable
 * as a human-readable diagnostics report for attaching to issues.
 */
object SensorProbe {

    /** Sensors we are interested in for karting telemetry, with fallback order. */
    data class Candidate(
        val type: Int,
        val wireName: String,
        /** Preferred sampling period microseconds, honored when supported. */
        val preferredPeriodMicros: Int,
        /** Period fallbacks (slowest last) when the preferred one is unsupported. */
        val fallbackPeriodsMicros: List<Int>
    )

    val ACC = Candidate(Sensor.TYPE_ACCELEROMETER, "accelerometer", 5_000, listOf(10_000, 20_000))
    val GYRO = Candidate(Sensor.TYPE_GYROSCOPE, "gyroscope", 5_000, listOf(10_000, 20_000))
    val GAME_ROT = Candidate(Sensor.TYPE_GAME_ROTATION_VECTOR, "gameRotation", 10_000, listOf(20_000))
    val ROT = Candidate(Sensor.TYPE_ROTATION_VECTOR, "rotation", 10_000, listOf(20_000))

    val all: List<Candidate> = listOf(ACC, GYRO, GAME_ROT, ROT)

    /** Result of probing one candidate. */
    data class Probed(
        val sensor: Sensor?,
        val chosenPeriodMicros: Int,
        /** True when the sensor exists and supports the preferred rate. */
        val atPreferredRate: Boolean
    )

    fun probe(manager: SensorManager, candidate: Candidate): Probed {
        val sensor = manager.getDefaultSensor(candidate.type) ?: return Probed(null, 0, false)
        // minDelay: minimum allowed sampling period in microseconds; 0 means
        // only on-change reporting is supported (no continuous streaming).
        val minDelay = sensor.minDelay
        var chosen = candidate.preferredPeriodMicros
        var atPreferred = false
        if (minDelay <= 0 || candidate.preferredPeriodMicros < minDelay) {
            chosen = candidate.fallbackPeriodsMicros.firstOrNull { minDelay > 0 && it >= minDelay }
                ?: (if (minDelay > 0) minDelay else 0)
        } else {
            atPreferred = true
        }
        return Probed(sensor, chosen, atPreferred)
    }

    /** Probe every candidate, returning availability + chosen rates. */
    fun probeAll(context: Context): List<Pair<Candidate, Probed>> {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return all.map { it to probe(manager, it) }
    }

    fun toSensorInfo(candidate: Candidate, probed: Probed): WatchLogCodec.Metadata.SensorInfo? {
        val sensor = probed.sensor ?: return null
        return WatchLogCodec.Metadata.SensorInfo(
            type = sensor.type,
            name = sensor.name,
            vendor = sensor.vendor,
            requestedRateHz = if (probed.chosenPeriodMicros > 0) 1_000_000.0 / probed.chosenPeriodMicros else 0.0,
            resolution = sensor.resolution.toDouble(),
            maxRange = sensor.maximumRange.toDouble(),
            minDelayMicros = sensor.minDelay,
            maxDelayMicros = sensor.maxDelay.toLong()
        )
    }

    /** Human-readable diagnostics report (attachable to issues). */
    fun report(context: Context): String = buildString {
        appendLine("Pocket Pitwall watch sensor diagnostics")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        appendLine("app: ${BuildConfig.VERSION_NAME}")
        appendLine()
        for ((candidate, probed) in probeAll(context)) {
            appendLine("== ${candidate.wireName} (type ${candidate.type}) ==")
            if (probed.sensor == null) {
                appendLine("  unavailable")
            } else {
                appendLine("  name: ${probed.sensor.name}")
                appendLine("  vendor: ${probed.sensor.vendor} v${probed.sensor.version}")
                appendLine("  power: ${probed.sensor.power} mA")
                appendLine("  resolution: ${probed.sensor.resolution}")
                appendLine("  max range: ${probed.sensor.maximumRange}")
                appendLine("  min delay: ${probed.sensor.minDelay} us (advertised max ${if (probed.sensor.minDelay > 0) 1_000_000.0 / probed.sensor.minDelay else 0.0} Hz)")
                appendLine("  max delay: ${probed.sensor.maxDelay} us")
                appendLine("  fifo max events: ${probed.sensor.fifoMaxEventCount}")
                appendLine("  is wake-up: ${probed.sensor.isWakeUpSensor}")
                appendLine("  chosen period: ${probed.chosenPeriodMicros} us" + if (probed.atPreferredRate) " (preferred)" else " (fallback)")
            }
            appendLine()
        }
        val storage = WatchLogStore.diagnosticsFile(context)
        appendLine("recording storage: ${if (storage.canWrite()) "writable" else "NOT writable"} at ${storage.parent}")
    }

    /** Machine-readable probe summary for the diagnostics screen/export. */
    fun summaryJson(context: Context): String {
        val entries = probeAll(context).mapNotNull { (candidate, probed) ->
            val info = toSensorInfo(candidate, probed) ?: return@mapNotNull null
            PitwallJson.obj(
                "sensor" to PitwallJson.s(candidate.wireName),
                "name" to PitwallJson.s(info.name),
                "vendor" to PitwallJson.s(info.vendor),
                "rateHz" to PitwallJson.n(info.requestedRateHz),
                "resolution" to PitwallJson.n(info.resolution),
                "maxRange" to PitwallJson.n(info.maxRange),
                "minDelayUs" to PitwallJson.n(info.minDelayMicros.toLong()),
                "maxDelayUs" to PitwallJson.n(info.maxDelayMicros),
                "fifoMax" to PitwallJson.n(probed.sensor!!.fifoMaxEventCount.toLong()),
                "wakeUp" to PitwallJson.b(probed.sensor.isWakeUpSensor),
                "preferredRateHonored" to PitwallJson.b(probed.atPreferredRate)
            )
        }
        return PitwallJson.write(
            PitwallJson.obj(
                "device" to PitwallJson.s("${Build.MANUFACTURER} ${Build.MODEL}"),
                "api" to PitwallJson.n(Build.VERSION.SDK_INT.toLong()),
                "app" to PitwallJson.s(BuildConfig.VERSION_NAME),
                "sensors" to PitwallJson.arr(entries)
            )
        )
    }
}
