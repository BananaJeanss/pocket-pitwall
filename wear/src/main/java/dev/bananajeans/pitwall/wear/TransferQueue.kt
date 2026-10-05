package dev.bananajeans.pitwall.wear

import android.content.Context
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import dev.bananajeans.pitwall.protocol.Messages
import dev.bananajeans.pitwall.protocol.WatchLogTransfer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.tasks.await

/**
 * Watch-side transfer queue (issue #21).
 *
 * The canonical pending copy of a completed log stays on the watch until the
 * phone acknowledges durable import via a transferAck. Flow:
 *
 *  1. Phone sends an empty message on PATH_PULL ("send me your logs").
 *  2. Watch opens one ChannelClient channel per queued log at
 *     /pitwall/log/<sessionId> and streams log.pwtch into it.
 *  3. Phone validates + stores, then sends TransferAck(ok).
 *  4. Watch marks the local copy imported only on ok=true and retains it
 *     for seven days. Each failed log retries independently.
 *
 * The queue is re-derived from disk on every step, so app/watch restarts
 * cannot lose, orphan or double-send a log (duplicate opens are tolerated
 * by the phone's idempotent import).
 */
class TransferQueue internal constructor(
    private val context: Context,
    private val transport: Transport = WearTransport(context),
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val store = WatchLogStore(context)
    private var servingJob: Job? = null
    private val retryAfter = ConcurrentHashMap<String, Long>()

    internal interface Transport {
        suspend fun connectedNodes(): List<String>
        suspend fun send(nodeId: String, sessionId: String, file: File, metadata: dev.bananajeans.pitwall.protocol.WatchLogCodec.SourceMeta)
    }

    private class WearTransport(context: Context) : Transport {
        private val channels = Wearable.getChannelClient(context)
        private val nodes = Wearable.getNodeClient(context)
        override suspend fun connectedNodes() = nodes.connectedNodes.await().map { it.id }
        override suspend fun send(nodeId: String, sessionId: String, file: File, metadata: dev.bananajeans.pitwall.protocol.WatchLogCodec.SourceMeta) {
            val channel = channels.openChannel(nodeId, channelPath(sessionId)).await()
            try {
                channels.getOutputStream(channel).await().use { output ->
                    file.inputStream().use { input -> WatchLogTransfer.write(input, metadata, output) }
                }
                // The receiver closes the channel after consuming the stream.
                // Closing here could invalidate bytes still buffered in transit.
            } catch (e: Exception) {
                runCatching { channels.close(channel).await() }
                throw e
            }
        }
    }

    /** Live pending-transfer count for the UI (issue #25 P2). Posted on
     *  every queue mutation (ack/delete/serve), so the count can no longer
     *  go stale via unrelated state keys. */
    private val pendingCountLive = androidx.lifecycle.MutableLiveData<Int>()

    fun observePendingCount(): androidx.lifecycle.LiveData<Int> = pendingCountLive

    private fun refreshPendingCount() {
        val count = store.pendingTransfer().size
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            pendingCountLive.postValue(count)
        }
    }

    companion object {
        /** Phone asks the watch to send its pending logs. */
        const val PATH_PULL = "/pitwall/log/pull"

        /** Channel path prefix carrying a session's binary log. */
        const val PATH_LOG_PREFIX = "/pitwall/log/"

        fun channelPath(sessionId: String) = "$PATH_LOG_PREFIX$sessionId"
    }

    /**
     * Called once at app start; no periodic work here — transfer is pull-based
     * (phone-initiated), which doubles as retry-on-reconnect.
     */
    fun start() { /* pull-driven; hook kept for lifecycle symmetry */ }

    /**
     * Responds to a pull request: opens one channel per pending log per
     * connected phone node. Idempotent; safe on duplicate requests.
     */
    @Synchronized fun serveAll(): Job? {
        refreshPendingCount()
        if (RecorderService.status.recording) return null // don't steal bandwidth mid-session
        servingJob?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val nodes = transport.connectedNodes()
                for (node in nodes) {
                    for (entry in store.pendingTransfer()) {
                        if (RecorderService.status.recording) break
                        if (now() < (retryAfter[entry.sessionId] ?: 0L)) continue
                        if (store.stateOf(entry.sessionId) != WatchLogStore.State.FINALIZED) continue
                        // A missing/broken first log must not abort the batch.
                        runCatching { openAndStream(node, entry.sessionId) }.onFailure {
                            retryAfter[entry.sessionId] = now() + 10_000
                            android.util.Log.w("PitwallSync", "Could not send ${entry.sessionId}", it)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("PitwallSync", "Could not discover phone", e)
            } finally {
                refreshPendingCount()
            }
        }
        servingJob = job
        job.start()
        return job
    }

    private suspend fun openAndStream(nodeId: String, sessionId: String) {
        val file: File = store.logFile(sessionId)
        require(file.isFile) { "Missing watch log $sessionId" }
        val metadata = requireNotNull(store.sourceMeta(sessionId)) { "Missing integrity metadata $sessionId" }
        transport.send(nodeId, sessionId, file, metadata)
    }

    /**
     * Handles the phone's transferAck. Called from WearConnection's message
     * dispatcher. ok=true releases the log; ok=false backs off. Duplicate
     * acks are no-ops (the store refuses to downgrade IMPORTED).
     */
    fun onAck(message: Messages.TransferAck) {
        if (!message.accepted) {
            retryAfter[message.logId] = now() + 10_000
            return
        }
        retryAfter.remove(message.logId)
        if (store.stateOf(message.logId) == WatchLogStore.State.FINALIZED) {
            store.markImported(message.logId)
        }
        refreshPendingCount()
    }

    /** Queue depth for the UI. */
    fun pendingCount(): Int = store.pendingTransfer().size
}
