package dev.bananajeans.pitwall.protocol

/**
 * Pure state machine for phone-side session control over an unreliable
 * channel (issue #19). Encapsulates the idempotency/retry rules so they can
 * be unit-tested without Android or a Data Layer.
 *
 * Rules implemented here:
 *  - A session is controlled by (sessionId, seq) pairs; repeated sends with
 *    the same seq are retries, not new commands.
 *  - Receiving an ack for the current seq completes the command; late acks
 *    for older seqs are ignored.
 *  - Stop is only legal after start; start during an active session with a
 *    different id is rejected (one session at a time).
 *  - The machine never mutates the session itself; it only tracks what has
 *    been commanded and acknowledged.
 */
class SessionControl(private val now: () -> Long = System::nanoTime) {

    /** Command lifecycle. */
    enum class CommandState { IDLE, PENDING_START, RECORDING, PENDING_STOP, STOPPED }

    data class Snapshot(
        val sessionId: String?,
        val state: CommandState,
        val startSeq: Long,
        val stopSeq: Long,
        /** Millis since the pending command was sent (0 when none). */
        val pendingAgeMillis: Long
    )

    var sessionId: String? = null
        private set
    var state: CommandState = CommandState.IDLE
        private set
    var startSeq: Long = 0
        private set
    var stopSeq: Long = 0
        private set
    private var pendingSince: Long = 0

    /** The expected start sequence number for the current/next start command. */
    private var expectedStartSeq: Long = 0
    /** The expected stop sequence number for the current/next stop command. */
    private var expectedStopSeq: Long = 0

    val snapshot: Snapshot
        get() = Snapshot(
            sessionId, state, startSeq, stopSeq,
            if (state == CommandState.PENDING_START || state == CommandState.PENDING_STOP)
                (now() - pendingSince) / 1_000_000 else 0
        )

    /**
     * Begin controlling a session. Returns the [Messages.Start] to send, or
     * null when a start for this session is already in flight/acked (retry
     * callers can just re-read [snapshot] and resend the last command).
     */
    fun start(sessionId: String, title: String, direction: String): Messages.Start? {
        if (this.sessionId == sessionId &&
            (state == CommandState.PENDING_START || state == CommandState.RECORDING)
        ) return null // idempotent: already commanded
        if (state == CommandState.PENDING_START || state == CommandState.RECORDING) {
            throw IllegalStateException("Another session (${this.sessionId}) is active")
        }
        this.sessionId = sessionId
        startSeq += 1
        expectedStartSeq = startSeq
        state = CommandState.PENDING_START
        pendingSince = now()
        return Messages.Start(sessionId, title, direction, System.currentTimeMillis(), startSeq)
    }

    /** The Start message to (re)send for the current pending/active session. */
    fun startMessage(): Messages.Start? {
        val sid = sessionId ?: return null
        if (state != CommandState.PENDING_START && state != CommandState.RECORDING) return null
        return Messages.Start(sid, "", "", 0, startSeq)
    }

    fun onStartAck(ack: Messages.StartAck) {
        if (ack.sessionId != sessionId) return // stale/foreign ack
        if (state != CommandState.PENDING_START) return // duplicate ack
        // Only transition to RECORDING when the watch actually started recording.
        if (!ack.recording) {
            // Start failed on watch (e.g., sensors unavailable); stay in PENDING_START
            // so the phone can retry or surface the failure.
            return
        }
        state = CommandState.RECORDING
        pendingSince = 0
    }

    /** The Stop message to (re)send for the current pending/active session. */
    fun stopMessage(): Messages.Stop? {
        val sid = sessionId ?: return null
        if (state != CommandState.PENDING_STOP && state != CommandState.STOPPED) return null
        return Messages.Stop(sid, stopSeq)
    }

    /** Returns the [Messages.Stop] to send, or null when stop already in flight. */
    fun stop(): Messages.Stop? {
        val sid = sessionId ?: return null
        if (state == CommandState.PENDING_STOP || state == CommandState.STOPPED) return null
        if (state == CommandState.PENDING_START) {
            // Start never acked; treat as aborted rather than recorded.
        }
        stopSeq += 1
        expectedStopSeq = stopSeq
        state = CommandState.PENDING_STOP
        pendingSince = now()
        return Messages.Stop(sid, stopSeq)
    }

    fun onStopAck(ack: Messages.StopAck) {
        if (ack.sessionId != sessionId) return
        if (state != CommandState.PENDING_STOP) return
        // Only transition to STOPPED when the watch actually finalized the log.
        if (!ack.finalized) {
            // Finalization failed; stay in PENDING_STOP so the phone can retry.
            return
        }
        state = CommandState.STOPPED
        pendingSince = 0
    }

    /** Reset for a new session cycle (phone app restart or session cleanup). */
    fun reset() {
        sessionId = null
        state = CommandState.IDLE
        startSeq = 0
        stopSeq = 0
        expectedStartSeq = 0
        expectedStopSeq = 0
        pendingSince = 0
    }
}

/**
 * Watch-side duplicate suppression (issue #19): "Make duplicate/retried
 * control messages idempotent." The watch must start/stop the recorder at
 * most once per (sessionId, seq), and re-ack duplicates.
 */
class WatchSessionControl {

    private val handledStarts = LinkedHashMap<String, Long>() // sid -> max seq seen
    private val handledStops = LinkedHashMap<String, Long>()

    /**
     * Returns true when this start is new for the session (must start the
     * recorder); false when it is a retry (only re-ack).
     */
    fun shouldStart(sid: String, seq: Long): Boolean {
        val previous = handledStarts[sid] ?: Long.MIN_VALUE
        if (seq <= previous) {
            // Duplicate or reordered old packet - don't re-execute, just re-ack
            return false
        }
        handledStarts[sid] = seq
        trim(handledStarts)
        return true
    }

    /** Same for stops. */
    fun shouldStop(sid: String, seq: Long): Boolean {
        val previous = handledStops[sid] ?: Long.MIN_VALUE
        if (seq <= previous) {
            // Duplicate or reordered old packet - don't re-execute, just re-ack
            return false
        }
        handledStops[sid] = seq
        trim(handledStops)
        return true
    }

    private fun trim(map: LinkedHashMap<String, Long>) {
        // 32 sessions is far beyond one karting day; prevents unbounded growth.
        while (map.size > 32) map.remove(map.keys.first())
    }
}
