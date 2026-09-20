package dev.bananajeans.pitwall.wear

import android.content.Context
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import dev.bananajeans.pitwall.protocol.Messages
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 *  4. Watch releases (deletes) the local copy only on ok=true; on ok=false
 *     it backs off and the phone pulls again later.
 *
 * The queue is re-derived from disk on every step, so app/watch restarts
 * cannot lose, orphan or double-send a log (duplicate opens are tolerated
 * by the phone's idempotent import).
 */
class TransferQueue(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = WatchLogStore(context)
    private val channelClient by lazy { Wearable.getChannelClient(context) }
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }

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

    /** Backoff timestamp after a NACK; pull messages during backoff are ignored. */
    @Volatile private var retryAfter: Long = 0

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
    fun serveAll() {
        if (System.currentTimeMillis() < retryAfter) return
        if (RecorderService.status.recording) return // don't steal bandwidth mid-session
        val pending = store.pendingTransfer()
        if (pending.isEmpty()) return
        scope.launch {
            runCatching {
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    for (entry in pending) {
                        openAndStream(node.id, entry.sessionId)
                    }
                }
            }
        }
    }

    private suspend fun openAndStream(nodeId: String, sessionId: String) {
        val file: File = store.logFile(sessionId)
        if (!file.isFile) return
        val channel = channelClient.openChannel(nodeId, channelPath(sessionId)).await()
        try {
            channelClient.getOutputStream(channel).await().use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
                output.flush()
            }
        } catch (_: Exception) {
            runCatching { channelClient.close(channel) }
        }
    }

    /**
     * Handles the phone's transferAck. Called from WearConnection's message
     * dispatcher. ok=true releases the log; ok=false backs off. Duplicate
     * acks are no-ops (the store refuses to downgrade IMPORTED).
     */
    fun onAck(message: Messages.TransferAck) {
        if (!message.accepted) {
            retryAfter = System.currentTimeMillis() + 60_000
            return
        }
        if (store.stateOf(message.logId) == WatchLogStore.State.FINALIZED) {
            store.markImported(message.logId)
            store.delete(message.logId)
        }
        refreshPendingCount()
    }

    /** Queue depth for the UI. */
    fun pendingCount(): Int = store.pendingTransfer().size
}
