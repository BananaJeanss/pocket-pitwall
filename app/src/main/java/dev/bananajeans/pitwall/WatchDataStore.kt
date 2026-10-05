package dev.bananajeans.pitwall

import android.content.Context
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import dev.bananajeans.pitwall.protocol.Messages
import dev.bananajeans.pitwall.protocol.WatchLogCodec
import dev.bananajeans.pitwall.protocol.WatchLogImporter
import dev.bananajeans.pitwall.protocol.WatchLogTransfer
import dev.bananajeans.pitwall.protocol.WristAnalysis
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Phone-side receiver for watch logs (issue #21).
 *
 * Uses the shared [WatchLogImporter] for validation/idempotency and adds the
 * transport: receive bytes over ChannelClient, then transferAck the watch
 * (ok=true permits watch retention cleanup; ok=false makes it retry later).
 *
 * Process-scoped singleton shared by the UI and PhoneDataLayerService.
 */
class WatchTransferManager internal constructor(private val context: Context) {

    data class TransferState(
        /** Session ids currently being received. */
        val active: Set<String> = emptySet(),
        /** Session ids stored and acknowledged. */
        val imported: Set<String> = emptySet(),
        /** Last NACK/error, for the UI. */
        val lastError: String? = null,
        val connected: Boolean? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = AtomicReference(TransferState())
    val currentState: TransferState get() = state.get()

    private val channelClient by lazy { Wearable.getChannelClient(context) }
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }
    private val importer by lazy { WatchLogImporter(WatchDataStore.dir(context)) }

    private val polling = AtomicBoolean(false)
    private val attachmentLock = Any()
    private val receiveLock = Any()

    fun start() {
        if (polling.compareAndSet(false, true)) {
            scope.launch {
                // Repair an attachment interrupted after durable raw import.
                importer.importedIds().forEach { id ->
                    runCatching {
                        val file = requireNotNull(importer.importedFile(id))
                        val log = file.inputStream().use { stream ->
                            when (val read = WatchLogCodec.read(stream)) {
                                is WatchLogCodec.ReadResult.Complete -> read.log
                                is WatchLogCodec.ReadResult.Incomplete -> read.log
                            }
                        }
                        attachToSession(id, WatchLogImporter.Result.Imported(log, file))
                        state.updateAndGet { it.copy(imported = it.imported + id) }
                    }.onFailure { error -> state.updateAndGet { it.copy(lastError = error.message) } }
                }
                while (polling.get()) {
                    if (!RecorderService.active.value) pullPending()
                    kotlinx.coroutines.delay(15_000)
                }
            }
        }
    }

    fun stop() {
        polling.set(false)
    }

    /** Asks the watch to open channels for its pending logs. The watch replies
     * by opening one channel per queued session; each incoming channel is
     * received, validated, stored and acked.
     */
    fun pullPending() {
        scope.launch {
            runCatching {
                val nodes = nodeClient.connectedNodes.await()
                state.updateAndGet { it.copy(connected = nodes.isNotEmpty()) }
                for (node in nodes) {
                    messageClient.sendMessage(node.id, PATH_PULL, ByteArray(0)).await()
                }
            }.onFailure {
                val error = it
                state.updateAndGet { it.copy(lastError = error.message) }
            }
        }
    }

    /**
     * Called when the phone's session has successfully stopped and the watch
     * has ACKed finalization. Pulls any newly finalized watch log.
     * This ensures the watch has finished writing the log before we ask for it.
     */
    fun pullAfterSessionStop() {
        pullPending()
    }

    /**
     * Trigger pull on reconnect (node reconnected after disconnect).
     * Ensures any logs finalized during disconnect get transferred.
     */
    fun syncPendingOnReconnect() {
        pullPending()
    }

    internal fun receive(channel: ChannelClient.Channel): kotlinx.coroutines.Job? {
        if (!channel.path.startsWith("/pitwall/log/")) return null
        val sessionId = channel.path.removePrefix("/pitwall/log/").takeIf {
            WatchLogImporter.SESSION_ID.matches(it)
        } ?: return null
        synchronized(receiveLock) {
            if (sessionId in state.get().active) {
                channelClient.close(channel)
                return null
            }
            state.updateAndGet { it.copy(active = it.active + sessionId) }
        }
        return scope.launch {
            // Channel reads block in Play services. Closing a stalled channel
            // releases its stream and lets a later pull retry this session.
            val watchdog = scope.launch {
                kotlinx.coroutines.delay(5 * 60_000L)
                runCatching { channelClient.close(channel).await() }
            }
            var tempFile: File? = null
            try {
                val tempDir = File(context.cacheDir, "watch-import").apply { mkdirs() }
                val file = File.createTempFile("import-$sessionId-", ".pwtch", tempDir)
                tempFile = file
                val sourceMeta = channelClient.getInputStream(channel).await().use { input ->
                    val meta = WatchLogTransfer.readMetadata(input)
                    require(meta.expectedBytes in 1..268_435_456L) { "Invalid watch log length" }
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                        output.flush()
                        output.fd.sync()
                    }
                    meta
                }
                when (val result = importAndAttach(sessionId, file, sourceMeta)) {
                    is WatchLogImporter.Result.Rejected -> {
                        state.updateAndGet { it.copy(lastError = result.reason) }
                        ack(sessionId, accepted = false, reason = result.reason)
                    }
                    else -> {
                        state.updateAndGet { it.copy(imported = it.imported + sessionId, lastError = null) }
                        // Only ACK after a durable, visible session exists.
                        ack(sessionId, accepted = true, reason = null)
                    }
                }
            } catch (e: Exception) {
                val reason = e.message ?: "Watch sync failed"
                state.updateAndGet { it.copy(lastError = reason) }
                ack(sessionId, accepted = false, reason = reason)
                android.util.Log.w("PitwallSync", "Could not import $sessionId", e)
            } finally {
                watchdog.cancel()
                tempFile?.delete()
                runCatching { channelClient.close(channel).await() }
                state.updateAndGet { it.copy(active = it.active - sessionId) }
            }
        }
    }

    internal fun importAndAttach(sessionId: String, file: File, meta: WatchLogCodec.SourceMeta): WatchLogImporter.Result {
        val result = importer.importFromFile(sessionId, file, meta)
        when (result) {
            is WatchLogImporter.Result.Imported -> attachToSession(sessionId, result)
            is WatchLogImporter.Result.Duplicate -> attachToSession(sessionId,
                WatchLogImporter.Result.Imported(result.log, requireNotNull(importer.importedFile(sessionId))))
            is WatchLogImporter.Result.Rejected -> Unit
        }
        return result
    }

    private fun ack(sessionId: String, accepted: Boolean, reason: String?) {
        scope.launch {
            runCatching {
                val nodes = nodeClient.connectedNodes.await()
                val bytes = Messages.encode(Messages.TransferAck(sessionId, sessionId, accepted, reason))
                for (node in nodes) {
                    messageClient.sendMessage(node.id, Messages.PATH, bytes)
                }
            }
        }
    }

    /**
     * Attaches imported watch metadata to the matching phone session.
     *
     * Normal coordinated recordings use the same session id. Standalone watch
     * recordings deliberately do not depend on a live phone link, so after a
     * reconnect we fall back to the closest completed phone session by wall
     * clock start time. This keeps track recording offline-first: record now,
     * transfer/associate later.
     *
     * If no plausible phone session exists, create a separate watch-only
     * session. Attachment must succeed before the watch receives an ACK.
     */
    internal fun attachToSession(sessionId: String, result: WatchLogImporter.Result.Imported): Session = synchronized(attachmentLock) {
        val store = SessionStore(context)
        if (!RecorderService.active.value) store.recover()
        val sessions = store.list()
        val exact = sessions.firstOrNull { it.id == sessionId || it.watch?.sourceSessionId == sessionId }
        val watchStart = result.log.metadata.startedAtWallMillis
        val watchDurationMillis = result.log.samples.maxOfOrNull {
            ((it.timestampNanos - result.log.metadata.startedAtMonotonicNanos) / 1_000_000L).coerceAtLeast(0)
        } ?: 0L
        val session = exact ?: sessions
            .asSequence()
            .filter { it.watch == null && it.status != "recording" }
            .filter { candidate ->
                val phoneDurationMillis = (candidate.duration * 1000).toLong()
                val overlap = minOf(candidate.created + phoneDurationMillis, watchStart + watchDurationMillis) -
                    maxOf(candidate.created, watchStart)
                phoneDurationMillis > 0 && watchDurationMillis > 0 &&
                    overlap >= minOf(phoneDurationMillis, watchDurationMillis) / 2
            }
            .map { candidate ->
                candidate to kotlin.math.abs(candidate.created - result.log.metadata.startedAtWallMillis)
            }
            .filter { (_, deltaMillis) -> deltaMillis <= OFFLINE_MATCH_WINDOW_MILLIS }
            .minByOrNull { (_, deltaMillis) -> deltaMillis }
            ?.first
            ?: createWatchOnlySession(store, sessionId, result.log)
        if (session.watch?.status == WatchSessionInfo.Status.IMPORTED) return@synchronized session
        val inSession = File(File(context.filesDir, "sessions"), session.id).apply { mkdirs() }
        val logName = "watch.pwtch"
        result.storedAt.inputStream().use { input ->
            File(inSession, logName).outputStream().use(input::copyTo)
        }
        val rates = result.log.metadata.sensorInfo.associate { it.type to it.requestedRateHz }
        // PER-SESSION clock state (P0 fix): the fit captured for THIS
        // session at its start, and the phone monotonic anchor persisted
        // when the session was created. Never the global latest fit, and
        // never the watch's monotonic value from log metadata.
        val sync = if (session.phoneStartElapsedNanos > 0L) WatchLink.captureSyncForSession(session.id) else null
        val phoneStart = session.phoneStartElapsedNanos.takeIf { it > 0L }
            ?: result.log.metadata.startedAtMonotonicNanos.let { watchStart ->
                // Legacy sessions recorded before the anchor existed:
                // map the watch monotonic start through the fit into the
                // phone domain rather than mixing epochs directly.
                sync?.let { it.phoneFromWatch(watchStart).toLong() }
            }
        // Derived driver-input metrics (issue #23): computed once at
        // import from the raw log + sync fit; recomputable from raw data.
        val analysis = runCatching {
            WristAnalysis.analyze(
                samples = result.log.samples.filter { it.sensorType == 4 },
                fit = sync,
                durationSeconds = session.duration,
                phoneSessionStartNanos = phoneStart ?: 0L
            )
        }.getOrNull()
        // HR summary is displayed on the watch in the results round-trip (layer 7);
        // peak/average are derived from log.heartRate there without re-parsing here.
        @Suppress("UNUSED_VARIABLE") val hrStats = result.log.heartRate.takeIf { it.isNotEmpty() }?.let { hr ->
            Triple(hr.minOf { it.bpm }, hr.maxOf { it.bpm }, hr.map { it.bpm }.average())
        }
        val info = WatchSessionInfo(
            status = WatchSessionInfo.Status.IMPORTED,
            sourceSessionId = sessionId,
            deviceModel = result.log.metadata.deviceModel,
            watchAppVersion = result.log.metadata.watchAppVersion,
            logComplete = result.log.complete,
            sampleCount = result.log.samples.size.toLong(),
            sensorRates = rates,
            sync = sync?.let {
                WatchSessionInfo.Sync(
                    offsetWatchMinusPhone = it.offsetWatchMinusPhone,
                    driftPerNano = it.driftPerNano,
                    bestRttNanos = it.bestRttNanos,
                    residualRmsNanos = it.residualRmsNanos,
                    exchangesUsed = it.exchangesUsed,
                    quality = it.quality
                )
            },
            phoneStartNanos = phoneStart,
            logFile = logName,
            metrics = analysis?.let { a ->
                WatchSessionInfo.Metrics(
                    steeringSmoothness = if (a.usable) 1.0 - a.oscillation else null,
                    correctionCount = if (a.usable) a.events.count { it.kind == WristAnalysis.EventKind.CORRECTION } else null,
                    quality = when {
                        !a.usable -> a.degradedReason
                        else -> "ok"
                    }
                )
            }
        )
        store.save(session.copy(watch = info))
        SessionRepository.refresh()
        // Push the compact summary back to the watch (issue #25).
        WatchLink.sendResult(
            Messages.Result(
                sessionId = session.id,
                bestLapSeconds = dev.bananajeans.pitwall.core.Telemetry.laps(session.marks)
                    .minByOrNull { it.duration() }?.duration()?.takeIf { it.isFinite() },
                lapCount = dev.bananajeans.pitwall.core.Telemetry.laps(session.marks).size,
                steeringSmoothness = info.metrics?.steeringSmoothness,
                correctionCount = info.metrics?.correctionCount,
                peakHr = result.log.heartRate.maxOfOrNull { it.bpm },
                averageHr = result.log.heartRate.map { it.bpm }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                watchDataQuality = info.metrics?.quality,
                notes = if (result.log.complete) null else "Watch log incomplete",
                // Immutable phone session creation wall-clock timestamp
                // (issue #25/#19 P1): stable chronological key across
                // reconnect/restart; never monotonic elapsed time.
                timestamp = session.created
            )
        )
        session.copy(watch = info)
    }

    private fun createWatchOnlySession(store: SessionStore, id: String, log: WatchLogCodec.WatchLog): Session {
        val origin = log.metadata.startedAtMonotonicNanos
        val samples = log.samples.filter { it.timestampNanos >= origin }.sortedBy { it.timestampNanos }
        val session = Session(
            id = id,
            created = log.metadata.startedAtWallMillis,
            title = "Watch · ${log.metadata.notes?.takeIf { it.isNotBlank() } ?: "Kart session"}",
            status = if (log.complete) "complete" else "interrupted",
            duration = samples.lastOrNull()?.let { (it.timestampNanos - origin) / 1e9 } ?: 0.0,
            sensors = log.metadata.sensorInfo.joinToString("; ") { "${it.type}: ${it.name}" },
            notes = "Watch-only recording. Accelerometer data includes gravity. No phone clock synchronization."
        )
        val file = store.raw(id)
        FileOutputStream(file).use { output ->
            val writer = output.bufferedWriter()
            writer.write("elapsed_s,sensor_type,x,y,z,w,accuracy\n")
            samples.forEach { sample ->
                writer.write("${(sample.timestampNanos - origin) / 1e9},${sample.sensorType},${sample.x},${sample.y},${sample.z},${sample.w},${sample.accuracy}\n")
            }
            writer.flush()
            output.fd.sync()
        }
        store.save(session)
        return session
    }

    /** Phone tells the watch to send its pending logs. */
    companion object {
        const val PATH_PULL = "/pitwall/log/pull"
        private const val OFFLINE_MATCH_WINDOW_MILLIS = 3 * 60 * 1000L

        /**
         * Shared process owner for service receives and UI-triggered pulls.
         * Application context prevents retaining an Activity; start() is
         * idempotent across Activity recreation.
         */
        @Volatile private var instance: WatchTransferManager? = null

        fun getInstance(context: Context): WatchTransferManager =
            instance ?: synchronized(this) {
                instance ?: WatchTransferManager(context.applicationContext).also { instance = it }
            }
    }
}

/** Storage locations for imported watch logs on the phone. */
object WatchDataStore {
    private const val DIR = "watch-logs"

    fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }
}
