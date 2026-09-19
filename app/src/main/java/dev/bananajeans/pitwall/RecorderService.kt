package dev.bananajeans.pitwall

import android.app.*
import android.content.Intent
import android.hardware.*
import android.os.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedWriter
import java.io.FileOutputStream

class RecorderService : Service(), SensorEventListener {
    companion object {
        val active = MutableStateFlow(false)
        val elapsed = MutableStateFlow(0.0)
        val error = MutableStateFlow<String?>(null)
        const val STOP = "dev.bananajeans.pitwall.STOP"
    }
    private lateinit var manager: SensorManager
    private lateinit var store: SessionStore
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var session: Session? = null
    private var output: FileOutputStream? = null
    private var writer: BufferedWriter? = null
    private var wake: PowerManager.WakeLock? = null
    private var origin = 0L
    private var lastFlush = 0L
    @Volatile private var closing = false
    private var samples = 0L
    private val ticker = object : Runnable {
        override fun run() {
            if (closing || origin == 0L) return
            elapsed.value = (SystemClock.elapsedRealtimeNanos() - origin) / 1e9
            handler.postDelayed(this, 1000)
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        manager=getSystemService(SENSOR_SERVICE) as SensorManager
        store=SessionStore(this)
        worker=HandlerThread("pitwall-recorder").apply { start() }
        handler=Handler(worker.looper)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action==STOP) { handler.post { finish("complete") }; return START_NOT_STICKY }
        if(active.value || closing) return START_NOT_STICKY
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("recording","Session recording",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,RecorderService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,"recording").setSmallIcon(dev.bananajeans.pitwall.R.drawable.ic_pitwall)
            .setContentTitle("Pocket Pitwall is recording").setContentText("Motion sensors · tap to review · 1 hour limit")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Stop & save",stop).build()).build()
        try { startForeground(1,notification) }
        catch (e: Exception) { error.value="Cannot start recording: ${e.message}"; stopSelf(); return START_NOT_STICKY }
        active.value=true; error.value=null; elapsed.value=0.0
        val title=intent?.getStringExtra("title")?.take(100) ?: "Motorcity · Underground"
        val direction=intent?.getStringExtra("direction") ?: "Normal"
        handler.post {
            try {
                val sensors=listOf(Sensor.TYPE_LINEAR_ACCELERATION,Sensor.TYPE_GYROSCOPE,Sensor.TYPE_GAME_ROTATION_VECTOR).mapNotNull { manager.getDefaultSensor(it) }
                require(sensors.any { it.type==Sensor.TYPE_LINEAR_ACCELERATION } && sensors.any { it.type==Sensor.TYPE_GYROSCOPE }) { "Linear acceleration and gyroscope sensors are required." }
                origin=SystemClock.elapsedRealtimeNanos(); lastFlush=origin
                session=Session(title=title,direction=direction,sensors=sensors.joinToString("; ") { "${it.type}: ${it.name} (${it.vendor})" })
                store.save(session!!)
                output=FileOutputStream(store.raw(session!!.id))
                writer=output!!.bufferedWriter().apply { write("elapsed_s,sensor_type,x,y,z,w,accuracy\n"); flush() }
                wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Pitwall:recording").apply { acquire(3_610_000) }
                sensors.forEach { require(manager.registerListener(this,it,20_000,handler)) { "Could not start sensor ${it.name}" } }
                handler.post(ticker)
                handler.postDelayed({ finish("complete") },3_600_000)
                // Tell the watch to start its own local recording (best effort).
                WatchLink.onPhoneSessionStarted(session!!.id, title, direction)
            } catch(e: Exception) { error.value=e.message ?: "Recording failed"; finish("interrupted") }
        }
        return START_NOT_STICKY
    }
    override fun onSensorChanged(event: SensorEvent) {
        if(closing || writer==null || event.timestamp<origin) return
        try {
            val t=(event.timestamp-origin)/1e9
            val v=event.values
            samples++
            writer!!.write("$t,${event.sensor.type},${v[0]},${v[1]},${v[2]},${v.getOrElse(3){0f}},${event.accuracy}\n")
            if(event.timestamp-lastFlush >= 1_000_000_000) {
                writer!!.flush(); output!!.fd.sync(); elapsed.value=t; lastFlush=event.timestamp
            }
        } catch(e: Exception) { error.value="Recording interrupted: ${e.message}"; finish("interrupted") }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    private fun finish(status: String) {
        if(closing) return
        closing=true
        handler.removeCallbacksAndMessages(null)
        manager.unregisterListener(this)
        var finalStatus=if (samples == 0L) "interrupted" else status
        try {
            writer?.flush()
            output?.fd?.sync()
        } catch(e: Exception) {
            finalStatus="interrupted"
            error.value="Could not flush recording: ${e.message}"
        } finally {
            try { writer?.close() ?: output?.close() }
            catch(e: Exception) { finalStatus="interrupted"; error.value="Could not close recording: ${e.message}" }
            writer=null
            output=null
            if(wake?.isHeld==true) wake?.release()
        }
        session?.let { s ->
            val finished = s.copy(status=finalStatus,duration=((SystemClock.elapsedRealtimeNanos()-origin)/1e9).coerceIn(0.0,3600.0))
            runCatching {
                store.save(finished)
                val settings = AppSettings.read(this)
                if (settings.autoBackups && settings.backupTreeUri.isNotBlank()) {
                    BackupStore(this).backupSession(store, finished, settings.backupTreeUri)
                }
            }.onFailure {
                error.value = if (store.hasSession(finished.id)) {
                    "Session saved, but automatic backup failed: ${it.message}"
                } else {
                    "Could not save session metadata: ${it.message}"
                }
            }
            // Tell the watch to finalize its log and queue the transfer.
            WatchLink.onPhoneSessionStopped(finished.id)
        }

        Handler(Looper.getMainLooper()).post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    override fun onDestroy() {
        handler.post {
            finish("interrupted")
            active.value=false
            worker.quitSafely()
        }
        super.onDestroy()
    }
}
