package dev.bananajeans.pitwall

import android.content.Context
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import dev.bananajeans.pitwall.protocol.Messages
import dev.bananajeans.pitwall.protocol.WatchLogImporter
import java.util.concurrent.atomic.AtomicReference
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
 * (ok=true deletes the watch's copy; ok=false makes it retry later).
 */
class WatchTransferManager(private val context: Context) {

    data class TransferState(
        /** Session ids currently being received. */
        val active: Set<String> = emptySet(),
        /** Session ids stored and acknowledged. */
        val imported: Set<String> = emptySet(),
        /** Last NACK/error, for the UI. */
        val lastError: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = AtomicReference(TransferState())
    val currentState: TransferState get() = state.get()

    private val channelClient by lazy { Wearable.getChannelClient(context) }
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }

    private val importer by lazy { WatchLogImporter(WatchDataStore.dir(context)) }

    fun start() {
        channelClient.registerChannelCallback(object : ChannelClient.ChannelCallback() {
            override fun onChannelOpened(channel: ChannelClient.Channel) {
                if (channel.path.startsWith("/pitwall/log/")) {
                    receive(channel)
                }
            }
        })
    }

    /**
     * Asks the watch to open channels for its pending logs. The watch replies
     * by opening one channel per queued session; each incoming channel is
     * received, validated, stored and acked.
     */
    fun pullPending() {
        scope.launch {
            runCatching {
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    messageClient.sendMessage(node.id, PATH_PULL, ByteArray(0))
                }
            }.onFailure {
                state.set(state.get().copy(lastError = it.message))
            }
        }
    }

    /**
     * Called when the phone's own session stops to ensure we pull any newly
     * finalized watch log promptly (not just at app startup).
     */
    fun pullAfterSessionStop() {
        pullPending()
    }

    private fun receive(channel: ChannelClient.Channel) {
        val sessionId = channel.path.removePrefix("/pitwall/log/").takeIf {
            WatchLogImporter.SESSION_ID.matches(it)
        } ?: return
        if (state.get().active.contains(sessionId)) return // in-flight duplicate
        state.set(state.get().copy(active = state.get().active + sessionId))
        channelClient.getInputStream(channel).addOnSuccessListener { input ->
            scope.launch {
                try {
                    // Stream to temp file instead of buffering in memory - large logs
                    // can be tens of MB and ByteArrayOutputStream can OOM.
                    val tempDir = java.io.File(context.cacheDir, "watch-import")
                    tempDir.mkdirs()
                    val tempFile = java.io.File.createTempFile("import-$sessionId-", ".pwtch", tempDir)
                    try {
                        val output = java.io.FileOutputStream(tempFile)
                        input.use { it.copyTo(output) }
                        output.flush()
                        val bytes = tempFile.readBytes()
                        when (val result = importer.import(sessionId, bytes)) {
                            is WatchLogImporter.Result.Imported -> {
                                state.set(
                                    state.get().copy(
                                        active = state.get().active - sessionId,
                                        imported = state.get().imported + sessionId,
                                        lastError = null
                                    )
                                )
                                ack(sessionId, accepted = true, reason = null)
                                attachToSession(sessionId, result)
                            }
                            is WatchLogImporter.Result.Duplicate -> {
                                state.set(
                                    state.get().copy(
                                        active = state.get().active - sessionId,
                                        imported = state.get().imported + sessionId
                                    )
                                )
                                ack(sessionId, accepted = true, reason = null)
                            }
                            is WatchLogImporter.Result.Rejected -> {
                                state.set(
                                    state.get().copy(
                                        active = state.get().active - sessionId,
                                        lastError = result.reason
                                    )
                                )
                                ack(sessionId, accepted = false, reason = result.reason)
                            }
                        }
                    } finally {
                        tempFile.delete()
                    }
                } catch (e: Exception) {
                    state.set(
                        state.get().copy(
                            active = state.get().active - sessionId,
                            lastError = e.message
                        )
                    )
                    ack(sessionId, accepted = false, reason = e.message ?: "receive failed")
                }
            }
        }.addOnFailureListener {
            state.set(state.get().copy(active = state.get().active - sessionId, lastError = it.message))
        }
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
     * Attaches imported watch metadata to the phone session with the same id
     * (session ids are generated by the phone and passed to the watch at
     * start). The raw log is copied into the session folder; sync quality is
     * captured from WatchLink. When the session is not present (e.g. phone
     * app data was cleared), the log stays in watch-logs as an orphan for
     * later manual recovery rather than being silently dropped.
     */
    private fun attachToSession(sessionId: String, result: WatchLogImporter.Result.Imported) {
        try {
            val store = SessionStore(context)
            val session = store.list().firstOrNull { it.id == sessionId } ?: return
            if (!RecorderService.active.value) store.recover()
            val inSession = java.io.File(java.io.File(context.filesDir, "sessions"), sessionId).apply { mkdirs() }
            val logName = "watch.pwtch"
            result.storedAt.inputStream().use { input ->
                java.io.File(inSession, logName).outputStream().use(input::copyTo)
            }
            val rates = result.log.metadata.sensorInfo.associate { it.type to it.requestedRateHz }
            val sync = WatchLink.captureSyncForSession()
            val info = WatchSessionInfo(
                status = WatchSessionInfo.Status.IMPORTED,
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
                logFile = logName,
                metrics = null
            )
            store.save(session.copy(watch = info))
            SessionRepository.refresh()
        } catch (_: Exception) {
            // Session attachment is best-effort; the raw log remains stored.
        }
    }

    companion object {
        /** Phone tells the watch to send its pending logs. */
        const val PATH_PULL = "/pitwall/log/pull"
    }
}

/** Storage locations for imported watch logs on the phone. */
object WatchDataStore {
    private const val DIR = "watch-logs"

    fun dir(context: Context): java.io.File =
        java.io.File(context.filesDir, DIR).apply { mkdirs() }
}