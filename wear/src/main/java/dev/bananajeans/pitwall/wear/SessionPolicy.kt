package dev.bananajeans.pitwall.wear

import dev.bananajeans.pitwall.protocol.WatchLogCodec
import java.io.File
import java.io.RandomAccessFile

/**
 * Durable Start/Stop decisions for the watch coordination layer.
 *
 * Root cause fixed here: duplicate suppression used to be process-memory
 * only. After a watch process restart a replayed Start opened a fresh
 * writer over the recovered log (FileOutputStream truncates), and a Stop
 * handled by a fresh service registered a callback that never fired (no
 * live sessionId), leaving the phone "in progress" forever.
 *
 * Every decision derives from the LIVE recorder snapshot plus the DURABLE
 * store state, so a fresh process answers deterministically from disk and
 * never re-executes a command for a session that already has a durable log.
 */
object SessionPolicy {

    /** What the connection layer should do with a (possibly replayed) Start. */
    enum class StartAction {
        /** Launch the recorder: fresh session (no durable state, nothing live). */
        EXECUTE,
        /** ACK recording=true without launching: already recording this
         *  session, or the session already has a durable log. */
        ACK_RECORDING,
        /** ACK recording=false without launching: busy with another session. */
        ACK_BUSY
    }

    fun onStart(sid: String, liveSession: String?, durableState: WatchLogStore.State?): StartAction = when {
        liveSession == sid -> StartAction.ACK_RECORDING
        liveSession != null -> StartAction.ACK_BUSY
        // Any durable entry (a recovered RECORDING orphan, FINALIZED,
        // IMPORTED) means the session already exists on disk; re-executing
        // would truncate it. recover() converts RECORDING orphans to
        // FINALIZED at process start, so a raw RECORDING entry here is
        // still an existing log that must never be overwritten.
        durableState != null -> StartAction.ACK_RECORDING
        else -> StartAction.EXECUTE
    }

    /** What the connection layer should do with a (possibly replayed) Stop. */
    enum class StopAction {
        /** Live recorder for this session: run the callback flow. */
        EXECUTE_LIVE,
        /** ACK immediately with the decided finalized flag. */
        ACK_DURABLE,
        /** Durable RECORDING without a live writer (crash orphan): finalize
         *  from disk first (never truncating), then ACK. */
        FINALIZE_ORPHAN
    }

    data class StopDecision(val action: StopAction, val ackFinalized: Boolean)

    fun onStop(sid: String, liveSession: String?, durable: WatchLogStore.Entry?): StopDecision {
        // A live recorder for this session answers via its stop callback
        // with the real finalized flag.
        if (liveSession == sid) return StopDecision(StopAction.EXECUTE_LIVE, ackFinalized = false)
        // Busy with another session, unknown session, or nothing durable:
        // deterministic NACK instead of an eternal "in progress".
        if (liveSession != null) return StopDecision(StopAction.ACK_DURABLE, ackFinalized = false)
        if (durable == null) return StopDecision(StopAction.ACK_DURABLE, ackFinalized = false)
        // Crash orphan: the writer died before markFinalized. Finalize from
        // disk (keeps every byte, writes the source sidecar), then ACK.
        if (durable.state == WatchLogStore.State.RECORDING) {
            return StopDecision(StopAction.FINALIZE_ORPHAN, ackFinalized = false)
        }
        return StopDecision(StopAction.ACK_DURABLE, ackFinalized = durable.finalizedComplete)
    }

    /**
     * Cheap completeness probe for a crash orphan: does the file end with
     * the PWTCH trailer magic? Full CRC validation still happens at import.
     */
    fun logLooksComplete(file: File): Boolean {
        if (!file.isFile || file.length() < 5L) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(raf.length() - 5)
                val tail = ByteArray(5)
                raf.readFully(tail)
                String(tail, Charsets.US_ASCII) == WatchLogCodec.TRAILER_MAGIC
            }
        } catch (_: Exception) {
            false
        }
    }
}
