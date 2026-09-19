package dev.bananajeans.pitwall

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import dev.bananajeans.pitwall.protocol.ClockSync
import dev.bananajeans.pitwall.protocol.Messages
import dev.bananajeans.pitwall.protocol.SessionControl
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Phone-side Wear Data Layer link (issues #19/#20).
 *
 * Responsibilities:
 *  - watch discovery + connection state (never mandatory for a session),
 *  - start/stop watch recording when phone sessions start/stop (with the
 *    idempotent [SessionControl] state machine),
 *  - clock-sync exchanges feeding a [ClockSync.Fit] persisted with the
 *    session when the watch log is imported,
 *  - sending compact results to the watch after analysis (layer 7).
 *
 * Everything here degrades to no-ops when no watch is present; phone-only
 * behavior is byte-for-byte unchanged.
 */
object WatchLink {

    data class State(
        val watchConnected: Boolean = false,
        val watchName: String? = null,
        val control: SessionControl.Snapshot = SessionControl.Snapshot(null, SessionControl.CommandState.IDLE, 0, 0, 0),
        val lastSyncFit: ClockSync.Fit? = null,
        /** Result summary awaiting delivery to a (re)connecting watch. */
        val pendingResult: Messages.Result? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state = AtomicReference(State())
    val currentState: State get() = state.get()

    private lateinit var messageClient: MessageClient
    private lateinit var nodeClient: NodeClient
    private val control = SessionControl()
    private val exchanges = ArrayList<ClockSync.Exchange>()

    @Volatile private var initialized = false
    private val lock = Any()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (initialized) return
            initialized = true
            messageClient = Wearable.getMessageClient(context.applicationContext)
            nodeClient = Wearable.getNodeClient(context.applicationContext)
            messageClient.addListener(::onMessage)
            scope.launch {
                while (true) {
                    refreshNodes()
                    kotlinx.coroutines.delay(10_000)
                }
            }
            // Pre-session clock sync: the more samples, the better the drift fit.
            scope.launch {
                kotlinx.coroutines.delay(3_000)
                repeat(10) {
                    val t1 = SystemClock.elapsedRealtimeNanos()
                    send(Messages.SyncPing("clock-sync", t1))
                    kotlinx.coroutines.delay(300)
                }
            }
        }
    }

    private fun refreshNodes() {
        nodeClient.connectedNodes.addOnSuccessListener { nodes: List<Node> ->
            val watch = nodes.firstOrNull()
            val connected = watch != null
            val previous = state.get()
            if (connected != previous.watchConnected || watch?.displayName != previous.watchName) {
                state.set(
                    previous.copy(
                        watchConnected = connected,
                        watchName = watch?.displayName
                    )
                )
                // A newly (re)connected watch may have pending results to show.
                previous.pendingResult?.let { send(it) }
            }
        }
    }

    private fun send(message: Messages.Message) {
        if (!::nodeClient.isInitialized) return
        val bytes = Messages.encode(message)
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            for (node in nodes) {
                messageClient.sendMessage(node.id, Messages.PATH, bytes)
                    .addOnFailureListener {
                        state.set(state.get().copy(watchConnected = false))
                    }
            }
        }
    }

    private fun onMessage(event: MessageEvent) {
        val message = try {
            Messages.decode(event.data)
        } catch (_: Exception) {
            return
        }
        when (message) {
            is Messages.SyncPong -> {
                val t4 = SystemClock.elapsedRealtimeNanos()
                synchronized(exchanges) {
                    exchanges.add(ClockSync.Exchange(message.t1PhoneNanos, t4, message.t2WatchNanos, message.t3WatchNanos))
                    if (exchanges.size > 64) exchanges.removeAt(0)
                    runCatching { ClockSync.fit(exchanges.toList()) }.onSuccess { fit ->
                        state.set(state.get().copy(lastSyncFit = fit))
                    }
                }
            }
            is Messages.StartAck -> {
                synchronized(control) { control.onStartAck(message) }
                publishControl()
            }
            is Messages.StopAck -> {
                synchronized(control) { control.onStopAck(message) }
                publishControl()
            }
            is Messages.Status -> Unit // consumed via acks; UI uses its own state
            is Messages.Hello, is Messages.Start, is Messages.Stop,
            is Messages.SyncPing, is Messages.Result, is Messages.TransferAck,
            is Messages.Unknown -> Unit // watch->phone only, or handled elsewhere
        }
    }

    private fun publishControl() {
        state.set(state.get().copy(control = synchronized(control) { control.snapshot }))
    }

    /**
     * Called when a phone recording starts. Never throws and never blocks:
     * a session proceeds even if the watch is absent or errors.
     */
    fun onPhoneSessionStarted(sessionId: String, title: String, direction: String) {
        try {
            val message = synchronized(control) { control.start(sessionId, title, direction) }
            if (message != null) {
                sendWithRetry(message, maxAttempts = 10, baseDelayMs = 500)
            }
            publishControl()
        } catch (_: IllegalStateException) {
            // Previous session still winding down; the watch state machine
            // already has the authoritative view.
        }
        // Extra sync samples: session-start alignment anchors the drift fit.
        scope.launch {
            repeat(4) {
                val t1 = SystemClock.elapsedRealtimeNanos()
                send(Messages.SyncPing(sessionId, t1))
                kotlinx.coroutines.delay(250)
            }
        }
    }

    /** Called when a phone recording stops. Same never-fail contract. */
    fun onPhoneSessionStopped(sessionId: String) {
        try {
            val message = synchronized(control) { control.stop() }
            if (message != null) {
                sendWithRetry(message, maxAttempts = 10, baseDelayMs = 500)
            }
            publishControl()
        } catch (_: Exception) {
        }
    }

    /**
     * Sends a message with exponential backoff retry until a matching application
     * ACK is received. For control messages (Start/Stop) where the watch's
     * idempotent state machine handles duplicates, we can safely retry on send
     * failure or missing ACK without side effects.
     */
    private fun sendWithRetry(message: Messages.Message, maxAttempts: Int = 10, baseDelayMs: Long = 500) {
        scope.launch {
            var attempt = 0
            while (attempt < maxAttempts) {
                // Check if we already have the matching ACK
                val snap = synchronized(control) { control.snapshot }
                when (message) {
                    is Messages.Start -> {
                        if (snap.state == SessionControl.CommandState.RECORDING && snap.startSeq == message.startSeq) {
                            return@launch // Got the ACK we were waiting for
                        }
                    }
                    is Messages.Stop -> {
                        if (snap.state == SessionControl.CommandState.STOPPED && snap.stopSeq == message.stopSeq) {
                            return@launch // Got the ACK we were waiting for
                        }
                    }
                    else -> {} // Other message types don't have ACKs we wait for here
                }

                // Not yet acked - try to send
                val success = sendBlocking(message)
                if (!success) {
                    // Send failed at transport level - will retry
                }
                attempt++
                if (attempt < maxAttempts) {
                    val delay = baseDelayMs * (1L shl (attempt - 1)).coerceAtMost(10_000) // cap at 10s
                    kotlinx.coroutines.delay(delay)
                }
            }
            // All attempts failed; state machine will show PENDING_* and publishControl()
            // will surface the pendingAgeMillis for UI/retry awareness.
        }
    }

    /** Blocking send that returns true if the message was accepted for delivery. */
    private suspend fun sendBlocking(message: Messages.Message): Boolean = coroutineScope {
        val bytes = Messages.encode(message)
        val nodesTask = nodeClient.connectedNodes
        val nodes = nodesTask.await()
            .filter { (it as com.google.android.gms.wearable.Node).isNearby }
        val node = nodes.firstOrNull()
            ?.also { node ->
                try {
                    messageClient.sendMessage(node.id, Messages.PATH, bytes).await()
                    return@coroutineScope true
                } catch (_: Exception) {
                    state.set(state.get().copy(watchConnected = false))
                }
            }
        false
    }

    /**
     * Persisted sync snapshot for this session, captured at stop time so the
     * importer can align watch samples to the phone timeline (layer 5).
     */
    fun captureSyncForSession(): ClockSync.Fit? = state.get().lastSyncFit

    /** Send the compact post-session summary (issue #25/#19 result message). */
    fun sendResult(result: Messages.Result) {
        state.set(state.get().copy(pendingResult = result))
        send(result)
    }

    fun shutdownForTest() {
        synchronized(lock) {
            if (!initialized) return
            messageClient.removeListener(::onMessage)
            initialized = false
        }
    }
}