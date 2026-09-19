package dev.bananajeans.pitwall.wear

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import dev.bananajeans.pitwall.protocol.ClockSync
import dev.bananajeans.pitwall.protocol.Messages
import dev.bananajeans.pitwall.protocol.WatchSessionControl
import dev.bananajeans.pitwall.protocol.StartResult
import dev.bananajeans.pitwall.protocol.StopResult
import dev.bananajeans.pitwall.wear.RecorderService
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Wear Data Layer plumbing for the watch (issues #19/#20).
 *
 * Owns:
 *  - phone-node discovery + connection state for the UI,
 *  - control-message decode/dispatch to the recorder,
 *  - syncPing/syncPong round trips feeding [ClockSync].
 *
 * The watch recorder itself never touches this class: losing the phone
 * mid-session only degrades coordination, never logging.
 */
class WearConnection(private val context: Context) {

    data class ConnectionState(
        val phoneConnected: Boolean = false,
        val phoneName: String? = null,
        val lastSyncFit: ClockSync.Fit? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)

    private val state = AtomicReference(ConnectionState())
    val connectionState: ConnectionState get() = state.get()

    /** Fires when the connection state changes (for the UI). */
    @Volatile var listener: (() -> Unit)? = null

    private val watchControl = WatchSessionControl()
    private val exchanges = ArrayList<ClockSync.Exchange>()
    private val sid = "clock-sync"

    // Track in-progress stop finalization so duplicate stops can re-ack correctly
    private val stopInProgress = mutableSetOf<String>()

    private val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        handleMessage(event)
    }

    fun start() {
        messageClient.addListener(messageListener)
        refreshNodes()
        scope.launch {
            while (true) {
                refreshNodes()
                kotlinx.coroutines.delay(10_000)
            }
        }
    }

    fun stop() {
        messageClient.removeListener(messageListener)
        scope.cancel()
    }

    private fun refreshNodes() {
        nodeClient.connectedNodes.addOnSuccessListener { nodes: List<Node> ->
            val phone = nodes.firstOrNull()
            val connected = phone != null
            val changed = connected != state.get().phoneConnected || phone?.displayName != state.get().phoneName
            state.set(ConnectionState(connected, phone?.displayName, state.get().lastSyncFit))
            if (changed) listener?.invoke()
        }
    }

    fun send(message: Messages.Message) {
        val bytes = Messages.encode(message)
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                for (node in nodes) {
                    messageClient.sendMessage(node.id, Messages.PATH, bytes)
                }
            }
    }

    /** Runs N pings and refines the stored fit (used before/during sessions). */
    fun synchronize(rounds: Int = 8) {
        scope.launch {
            repeat(rounds) {
                val t1 = SystemClock.elapsedRealtimeNanos()
                send(Messages.SyncPing(sid, t1))
                // t4/t2/t3 arrive via handleMessage; pacing keeps RTTs independent.
                kotlinx.coroutines.delay(250)
            }
        }
    }

    private fun handleMessage(event: MessageEvent) {
        val message = try {
            Messages.decode(event.data)
        } catch (e: Exception) {
            return // malformed peer message: ignore, never crash
        }
        when (message) {
            is Messages.Hello -> state.set(
                ConnectionState(true, state.get().phoneName, state.get().lastSyncFit)
            )
            is Messages.SyncPing -> {
                // Watch side: reply immediately. t2 = receive, t3 = send.
                val t2 = SystemClock.elapsedRealtimeNanos()
                val t3 = SystemClock.elapsedRealtimeNanos()
                send(Messages.SyncPong(message.sid, message.t1PhoneNanos, t2, t3))
            }
            is Messages.SyncPong -> {
                // We are the phone side in production, but a watch that both
                // pings and pongs should not mix its own echoes into its fit.
                return
            }
            is Messages.Start -> onStart(message)
            is Messages.Stop -> onStop(message)
            is Messages.Status, is Messages.StartAck, is Messages.StopAck,
            is Messages.Result, is Messages.TransferAck, is Messages.Unknown ->
                Unit // phone->watch only, or handled in later layers
        }
    }

    private fun onStart(message: Messages.Start) {
        // Check if this is a new start command or a duplicate/retry
        val isNew = watchControl.shouldStart(message.sessionId, message.startSeq)
        
        if (!isNew) {
            // Duplicate: replay the actual cached result if available
            val result = watchControl.getStartResult(message.sessionId, message.startSeq)
            if (result != null) {
                send(Messages.StartAck(message.sessionId, result.recording, result.appVersion, result.protocolVersion))
            } else if (watchControl.isStartInProgress(message.sessionId, message.startSeq)) {
                // Original start is still in progress - don't ACK yet, phone will retry
                // The callback will send the ACK when it completes
            } else {
                // Shouldn't happen: seq <= previous but no result cached
                // Fall back to sending a failure ack to unblock phone
                send(Messages.StartAck(message.sessionId, false, BuildConfig.VERSION_NAME, Messages.PROTOCOL_VERSION))
            }
            return
        }

        // New start sequence - launch recorder and wait for callback
        val intent = android.content.Intent(context, RecorderService::class.java)
            .setAction(RecorderService.ACTION_START)
            .putExtra(RecorderService.EXTRA_SESSION_ID, message.sessionId)
            .putExtra(RecorderService.EXTRA_TITLE, message.title)
            .putExtra(RecorderService.EXTRA_DIRECTION, message.direction)
        
        val ackCallback = object : RecorderService.Companion.RecordingCallback {
            override fun onRecordingStarted(success: Boolean) {
                watchControl.onStartCompleted(
                    message.sessionId, 
                    message.startSeq, 
                    success, 
                    BuildConfig.VERSION_NAME, 
                    Messages.PROTOCOL_VERSION
                )
                send(Messages.StartAck(message.sessionId, success, BuildConfig.VERSION_NAME, Messages.PROTOCOL_VERSION))
            }
        }
        RecorderService.setRecordingCallback(message.sessionId, ackCallback)
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            RecorderService.clearRecordingCallback(message.sessionId)
            watchControl.onStartCompleted(
                message.sessionId,
                message.startSeq,
                false,
                BuildConfig.VERSION_NAME,
                Messages.PROTOCOL_VERSION
            )
            send(Messages.StartAck(message.sessionId, false, BuildConfig.VERSION_NAME, Messages.PROTOCOL_VERSION))
        }
    }

    private fun onStop(message: Messages.Stop) {
        val isNew = watchControl.shouldStop(message.sessionId, message.stopSeq)

        if (!isNew) {
            // Duplicate: replay the actual cached result if available
            val result = watchControl.getStopResult(message.sessionId, message.stopSeq)
            if (result != null) {
                send(Messages.StopAck(message.sessionId, result.finalized, message.sessionId, Messages.PROTOCOL_VERSION))
            } else if (watchControl.isStopInProgress(message.sessionId, message.stopSeq)) {
                // Original stop is still in progress - don't ACK yet, phone will retry
            } else {
                // Shouldn't happen: seq <= previous but no result cached
                send(Messages.StopAck(message.sessionId, false, message.sessionId, Messages.PROTOCOL_VERSION))
            }
            return
        }

        // New stop sequence - start finalization and wait for callback
        stopInProgress.add(message.sessionId)
        val ackCallback = object : RecorderService.Companion.StopCallback {
            override fun onStopped(finalized: Boolean) {
                stopInProgress.remove(message.sessionId)
                watchControl.onStopCompleted(message.sessionId, message.stopSeq, finalized)
                send(Messages.StopAck(message.sessionId, finalized, message.sessionId, Messages.PROTOCOL_VERSION))
            }
        }
        RecorderService.setStopCallback(message.sessionId, ackCallback)
        context.startService(
            android.content.Intent(context, RecorderService::class.java)
                .setAction(RecorderService.ACTION_STOP)
        )
    }

    fun recordExchange(t1: Long, t2: Long, t3: Long, t4: Long) {
        synchronized(exchanges) {
            exchanges.add(ClockSync.Exchange(t1, t4, t2, t3))
            if (exchanges.size > 64) exchanges.removeAt(0)
            val fit = runCatching { ClockSync.fit(exchanges.toList()) }.getOrNull()
            if (fit != null) {
                state.set(ConnectionState(state.get().phoneConnected, state.get().phoneName, fit))
            }
        }
    }
}