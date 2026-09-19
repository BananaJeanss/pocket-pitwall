package dev.bananajeans.pitwall.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import dev.bananajeans.pitwall.protocol.Messages
import dev.bananajeans.pitwall.protocol.WatchLogCodec
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

/**
 * Foreground recording service (issue #18): a reliable dumb logger.
 *
 * - Registers probed sensors at probed rates (never hard-coded Watch7 rates).
 * - Buffers samples per sensor and appends one CRC-protected frame per flush,
 *   keeping at most a few hundred samples in memory.
 * - Survives screen-off (partial wake lock + foreground service).
 * - Never depends on connectivity: everything is local until the session ends.
 * - Enforces a 60-minute cap like the phone app.
 */
/** Immutable snapshot of recorder state for the UI. */
data class RecorderStatus(
    val sessionId: String? = null,
    val recording: Boolean = false,
    val healthy: Boolean = false,
    val samples: Long = 0,
    val elapsedSeconds: Double = 0.0,
    val error: String? = null
)

class RecorderService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "dev.bananajeans.pitwall.wear.START"
        const val ACTION_STOP = "dev.bananajeans.pitwall.wear.STOP"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DIRECTION = "direction"
        private const val MAX_SESSION_MILLIS = 60L * 60 * 1000
        private const val FLUSH_INTERVAL_NANOS = 250_000_000L
        private const val MAX_PENDING_PER_SENSOR = 512

        private val statusRef = AtomicReference(RecorderStatus())
        val status: RecorderStatus get() = statusRef.get()

        private fun update(block: (RecorderStatus) -> RecorderStatus) {
            statusRef.updateAndGet { block(it) }
        }
    }

    private lateinit var manager: SensorManager
    private lateinit var store: WatchLogStore
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var writer: WatchLogCodec.Writer? = null
    private var stream: FileOutputStream? = null
    private var wake: PowerManager.WakeLock? = null
    private var sessionId: String? = null
    private var startedAtNanos = 0L
    private val lastFlushNanos = HashMap<Int, Long>()
    private val pending = HashMap<Int, ArrayList<WatchLogCodec.Sample>>()
    private var closing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(SENSOR_SERVICE) as SensorManager
        store = WatchLogStore(this)
        worker = HandlerThread("pitwall-watch-recorder").apply { start() }
        handler = Handler(worker.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                handler.post { finish(complete = true) }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val sid = intent.getStringExtra(EXTRA_SESSION_ID)
                if (sid.isNullOrBlank() || !Regex("[a-zA-Z0-9-]+").matches(sid)) {
                    update { it.copy(error = "Invalid session id") }
                    stopSelf()
                    return START_NOT_STICKY
                }
                handler.post { start(sid, intent.getStringExtra(EXTRA_TITLE) ?: "Session") }
            }
        }
        return START_NOT_STICKY
    }

    private fun start(sessionId: String, title: String) {
        if (status.recording || closing) return
        try {
            startForegroundWithNotification()
            val probes = SensorProbe.probeAll(this)
            val usable = probes.filter { it.second.sensor != null }
            require(usable.any { it.first.type == Sensor.TYPE_ACCELEROMETER } &&
                usable.any { it.first.type == Sensor.TYPE_GYROSCOPE }) {
                "Accelerometer and gyroscope are required."
            }

            this.sessionId = sessionId
            startedAtNanos = SystemClock.elapsedRealtimeNanos()
            lastFlushNanos.clear()
            store.startRecording(sessionId)
            val metadata = WatchLogCodec.Metadata(
                sessionId = sessionId,
                watchAppVersion = BuildConfig.VERSION_NAME,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                startedAtWallMillis = System.currentTimeMillis(),
                startedAtMonotonicNanos = startedAtNanos,
                sensorInfo = probes.mapNotNull { (candidate, probed) -> SensorProbe.toSensorInfo(candidate, probed) },
                protocolVersion = Messages.PROTOCOL_VERSION,
                notes = title
            )
            val (w, s) = store.writer(sessionId, metadata)
            writer = w
            stream = s

            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pitwall:watch-recording")
                .apply { acquire(MAX_SESSION_MILLIS + 60_000) }

            var registeredAccel = false
            var registeredGyro = false
            for ((candidate, probed) in probes) {
                val sensor = probed.sensor ?: continue
                val period = if (probed.chosenPeriodMicros > 0) probed.chosenPeriodMicros else SensorManager.SENSOR_DELAY_FASTEST
                if (manager.registerListener(this, sensor, period, handler)) {
                    when (sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> registeredAccel = true
                        Sensor.TYPE_GYROSCOPE -> registeredGyro = true
                    }
                }
            }
            require(registeredAccel && registeredGyro) { "Required IMU sensors (accelerometer and gyroscope) failed to register" }

            update { it.copy(sessionId = sessionId, recording = true, healthy = true, samples = 0, elapsedSeconds = 0.0, error = null) }

            handler.postDelayed({
                handler.postDelayed(this::tick, 1000)
            }, 1000)
            handler.postDelayed({ finish(complete = true) }, MAX_SESSION_MILLIS)
        } catch (e: Exception) {
            update { it.copy(error = e.message ?: "Recording failed", recording = false, healthy = false) }
            finish(complete = false)
        }
    }

    private fun tick() {
        if (closing || startedAtNanos == 0L) return
        update {
            it.copy(
                elapsedSeconds = (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1e9,
                healthy = writer != null
            )
        }
        handler.postDelayed(this::tick, 1000)
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("recording", "Session recording", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, "recording")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Pitwall recording")
            .setContentText("Wrist telemetry · 1 hour limit")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (closing || writer == null || event == null) return
        if (event.timestamp < startedAtNanos) return
        val batch = pending.getOrPut(event.sensor.type) { ArrayList(64) }
        batch.add(
            WatchLogCodec.Sample(
                sensorType = event.sensor.type,
                timestampNanos = event.timestamp,
                x = event.values[0].toDouble(),
                y = event.values[1].toDouble(),
                z = event.values[2].toDouble(),
                w = event.values.getOrElse(3) { 0f }.toDouble(),
                accuracy = event.accuracy
            )
        )
        val st = event.sensor.type
        if (batch.size >= MAX_PENDING_PER_SENSOR || event.timestamp - lastFlushNanos.getOrPut(st) { startedAtNanos } >= FLUSH_INTERVAL_NANOS) {
            flush(sensorType = st)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (closing || writer == null || sensor == null) return
        try {
            writer?.appendAccuracyEvent(
                WatchLogCodec.AccuracyEvent(sensor.type, SystemClock.elapsedRealtimeNanos(), accuracy)
            )
            stream?.fd?.sync()
        } catch (e: Exception) {
            update { it.copy(healthy = false, error = "Logging interrupted: ${e.message}") }
            finish(complete = false)
        }
    }

    private fun flush(sensorType: Int) {
        val batch = pending.remove(sensorType) ?: return
        if (batch.isEmpty()) return
        try {
            writer?.appendSamples(sensorType, batch)
            stream?.fd?.sync()
            lastFlushNanos[sensorType] = SystemClock.elapsedRealtimeNanos()
            update { it.copy(samples = it.samples + batch.size) }
        } catch (e: Exception) {
            update { it.copy(healthy = false, error = "Logging interrupted: ${e.message}") }
            finish(complete = false)
        }
    }

    private fun finish(complete: Boolean) {
        if (closing) return
        closing = true
        handler.removeCallbacksAndMessages(null)
        manager.unregisterListener(this)
        // Flush any partial batches so a complete stop loses nothing.
        for (type in ArrayList(pending.keys)) flush(type)
        var finalizedOk = complete
        try {
            if (complete) writer?.finish() else writer?.close()
            stream?.fd?.sync()
        } catch (e: Exception) {
            finalizedOk = false
            update { it.copy(error = "Could not finalize log: ${e.message}") }
        } finally {
            try { stream?.close() } catch (_: Exception) {}
            stream = null
            writer = null
            if (wake?.isHeld == true) wake?.release()
        }
        sessionId?.let { store.markFinalized(it, finalizedOk) }
        update { it.copy(recording = false, healthy = false) }
        Handler(Looper.getMainLooper()).post {
            if (Build.VERSION.SDK_INT >= 33) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.post {
            finish(complete = false)
            worker.quitSafely()
        }
        super.onDestroy()
    }
}
