package dev.bananajeans.pitwall.protocol

/**
 * Versioned phone <-> watch control protocol (Wear OS Data Layer MessageClient payloads).
 *
 * Rules
 *  - Every message is a JSON object with "t" (message type) and "v" (protocol
 *    version of the sender's app build).
 *  - Every session-scoped message carries the session UUID ("sid").
 *  - Receivers ignore unknown message types and tolerate unknown fields
 *    (forward compatibility); senders must bump [PROTOCOL_VERSION] when the
 *    meaning of an existing field changes.
 *  - Control messages are idempotent: receiving the same (type, sid) twice
 *    must be a no-op beyond acknowledging again.
 *
 * Transport binding (implemented in the app modules, not here):
 *  - MessageClient path "/pitwall" for control messages.
 *  - ChannelClient for completed binary log transfer (see WatchLogCodec).
 */
public object Messages {
    /** Bump on breaking change; keep readers permissive (ignore unknown types/fields). */
    public const val PROTOCOL_VERSION: Int = 1

    /** Single MessageClient path used for every control message. */
    public const val PATH: String = "/pitwall"

    // ---- message types -----------------------------------------------------
    public const val TYPE_HELLO: String = "hello"
    public const val TYPE_START: String = "start"
    public const val TYPE_START_ACK: String = "startAck"
    public const val TYPE_STOP: String = "stop"
    public const val TYPE_STOP_ACK: String = "stopAck"
    public const val TYPE_STATUS: String = "status"
    public const val TYPE_SYNC_PING: String = "syncPing"
    public const val TYPE_SYNC_PONG: String = "syncPong"
    public const val TYPE_RESULT: String = "result"
    public const val TYPE_TRANSFER_ACK: String = "transferAck"

    /** Maximum serialized message size the Data Layer allows us to rely on. */
    public const val MAX_MESSAGE_BYTES: Int = 64 * 1024

    public enum class Role(public val wire: String) { PHONE("phone"), WATCH("watch") }

    public sealed interface Message {
        public val type: String
    }

    /** Either side announces itself (capabilities/app version handshake). */
    public data class Hello(
        val role: Role,
        val appVersion: String,
        val protocolVersion: Int
    ) : Message { override val type: String get() = TYPE_HELLO }

    /** Phone tells the watch a session started. */
    public data class Start(
        val sessionId: String,
        val title: String,
        val direction: String,
        /** Wall-clock start in ms UTC as seen by the phone (context, not a sync source). */
        val startedAt: Long,
        val startSeq: Long
    ) : Message { override val type: String get() = TYPE_START }

    /** Watch confirms it is recording (or already was) for that session id. */
    public data class StartAck(
        val sessionId: String,
        val recording: Boolean,
        val watchAppVersion: String,
        val protocolVersion: Int
    ) : Message { override val type: String get() = TYPE_START_ACK }

    /** Phone ends the session; the watch finalizes its log and queues transfer. */
    public data class Stop(
        val sessionId: String,
        val stopSeq: Long
    ) : Message { override val type: String get() = TYPE_STOP }

    /** Watch confirms the recording was finalized (or was never started). */
    public data class StopAck(
        val sessionId: String,
        val finalized: Boolean,
        /** Present when finalized: id of the finalized log (== sessionId). */
        val logId: String?,
        val protocolVersion: Int
    ) : Message { override val type: String get() = TYPE_STOP_ACK }

    /** Periodic watch state broadcast so the phone UI can stay honest. */
    public data class Status(
        val sessionId: String?,
        val recording: Boolean,
        val localLoggingHealthy: Boolean,
        val pendingTransfers: Int,
        val batteryPct: Int? = null
    ) : Message { override val type: String get() = TYPE_STATUS }

    /** One round-trip measurement for clock offset estimation (phone -> watch). */
    public data class SyncPing(
        val sid: String,
        val t1PhoneNanos: Long
    ) : Message { override val type: String get() = TYPE_SYNC_PING }

    /** Watch echo for clock offset estimation (watch -> phone). */
    public data class SyncPong(
        val sid: String,
        val t1PhoneNanos: Long,
        val t2WatchNanos: Long,
        val t3WatchNanos: Long
    ) : Message { override val type: String get() = TYPE_SYNC_PONG }

    /** Compact post-session result summary pushed back to the watch. */
    public data class Result(
        val sessionId: String,
        val bestLapSeconds: Double?,
        val lapCount: Int,
        val steeringSmoothness: Double?,
        val correctionCount: Int?,
        val peakHr: Int?,
        val averageHr: Int?,
        val watchDataQuality: String?,
        val notes: String?
    ) : Message { override val type: String get() = TYPE_RESULT }

    /** Phone confirms durable import of a completed watch log. */
    public data class TransferAck(
        val sessionId: String,
        val logId: String,
        val accepted: Boolean,
        /** Human-readable failure reason when accepted == false. */
        val reason: String? = null
    ) : Message { override val type: String get() = TYPE_TRANSFER_ACK }

    /** A valid JSON message whose type we do not understand (newer peer). */
    public data class Unknown(val rawType: String) : Message { override val type: String get() = "unknown" }

    // ---- encoding -----------------------------------------------------------

    private fun nullableString(value: String?): PitwallJson.Value =
        value?.let { PitwallJson.s(it) } ?: PitwallJson.Value.Null

    private fun nullableNum(value: Double?): PitwallJson.Value =
        value?.let { PitwallJson.n(it) } ?: PitwallJson.Value.Null

    private fun nullableNum(value: Int?): PitwallJson.Value =
        value?.let { PitwallJson.n(it.toLong()) } ?: PitwallJson.Value.Null

    public fun encode(message: Message): ByteArray {
        val v = PitwallJson.n(PROTOCOL_VERSION.toLong())
        val json = when (message) {
            is Hello -> PitwallJson.obj(
                "t" to PitwallJson.s(TYPE_HELLO), "v" to v,
                "role" to PitwallJson.s(message.role.wire),
                "app" to PitwallJson.s(message.appVersion),
                "pv" to PitwallJson.n(message.protocolVersion.toLong())
            )
            is Start -> PitwallJson.obj(
                "t" to PitwallJson.s(TYPE_START), "v" to v,
                "sid" to PitwallJson.s(message.sessionId),
                "title" to PitwallJson.s(message.title),
                "direction" to PitwallJson.s(message.direction),
                "startedAt" to PitwallJson.n(message.startedAt),
                "seq" to PitwallJson.n(message.startSeq)
            )
            is StartAck -> PitwallJson.obj(
                "t" to PitwallJson.s(TYPE_START_ACK), "v" to v,
                "sid" to PitwallJson.s(message.sessionId),
                "recording" to PitwallJson.b(message.recording),
                "app" to PitwallJson.s(message.watchAppVersion),
                "pv" to PitwallJson.n(message.protocolVersion.toLong())
            )
            is Stop -> PitwallJson.obj(
                "t" to PitwallJson.s(TYPE_STOP), "v" to v,
                "sid" to PitwallJson.s(message.sessionId),
                "seq" to PitwallJson.n(message.stopSeq)
            )
            is StopAck -> PitwallJson.obj(
                "t" to PitwallJson.s(TYPE_STOP_ACK), "v" to v,
                "sid" to PitwallJson.s(message.sessionId),
                "finalized" to PitwallJson.b(message.finalized),
                "logId" to nullableString(message.logId),
                "pv" to PitwallJson.n(message.protocolVersion.toLong())
            )
            is Status -> PitwallJson.obj(
                "t" to PitwallJson.s(TYPE_STATUS), "v" to v,
                "sid" to nullableString(message.sessionId),
                "recording" to PitwallJson.b(message.recording),
                "localOk" to PitwallJson.b(message.localLoggingHealthy),
                "pending" to PitwallJson.n(message.pendingTransfers.toLong()),
                "batt" to nullableNum(message.batteryPct)
            )
            is SyncPing -> PitwallJson.obj(
                "t" to PitwallJson.s(TYPE_SYNC_PING), "v" to v,
                "sid" to PitwallJson.s(message.sid),
                "t1" to PitwallJson.n(message.t1PhoneNanos)
            )
            is SyncPong -> PitwallJson.obj(
                "t" to PitwallJson.s(TYPE_SYNC_PONG), "v" to v,
                "sid" to PitwallJson.s(message.sid),
                "t1" to PitwallJson.n(message.t1PhoneNanos),
                "t2" to PitwallJson.n(message.t2WatchNanos),
                "t3" to PitwallJson.n(message.t3WatchNanos)
            )
            is Result -> PitwallJson.obj(
                "t" to PitwallJson.s(TYPE_RESULT), "v" to v,
                "sid" to PitwallJson.s(message.sessionId),
                "bestLap" to nullableNum(message.bestLapSeconds),
                "laps" to PitwallJson.n(message.lapCount.toLong()),
                "smooth" to nullableNum(message.steeringSmoothness),
                "corrections" to nullableNum(message.correctionCount),
                "peakHr" to nullableNum(message.peakHr),
                "avgHr" to nullableNum(message.averageHr),
                "quality" to nullableString(message.watchDataQuality),
                "notes" to nullableString(message.notes)
            )
            is TransferAck -> PitwallJson.obj(
                "t" to PitwallJson.s(TYPE_TRANSFER_ACK), "v" to v,
                "sid" to PitwallJson.s(message.sessionId),
                "logId" to PitwallJson.s(message.logId),
                "ok" to PitwallJson.b(message.accepted),
                "reason" to nullableString(message.reason)
            )
            is Unknown -> PitwallJson.obj("t" to PitwallJson.s(message.rawType), "v" to v)
        }
        val bytes = PitwallJson.write(json).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MESSAGE_BYTES) {
            "Control message exceeds ${MAX_MESSAGE_BYTES} bytes: ${message.type} is ${bytes.size} bytes"
        }
        return bytes
    }

    public fun decode(bytes: ByteArray): Message {
        val root = PitwallJson.parse(String(bytes, Charsets.UTF_8)) as? PitwallJson.Value.Object
            ?: throw PitwallJson.JsonException("Message must be a JSON object")
        return when (val t = root.string("t")) {
            TYPE_HELLO -> {
                val role = when (root.string("role")) {
                    Role.PHONE.wire -> Role.PHONE
                    Role.WATCH.wire -> Role.WATCH
                    else -> null
                }
                if (role == null) Unknown("hello")
                else Hello(
                    role,
                    root.string("app") ?: "unknown",
                    root.number("pv")?.toInt() ?: 0
                )
            }
            TYPE_START -> Start(
                sessionId = requiredString(root, "sid", "start"),
                title = root.string("title") ?: "Untitled",
                direction = root.string("direction") ?: "Normal",
                startedAt = root.number("startedAt")?.toLong() ?: 0L,
                startSeq = root.number("seq")?.toLong() ?: 0L
            )
            TYPE_START_ACK -> StartAck(
                sessionId = requiredString(root, "sid", "startAck"),
                recording = root.bool("recording") ?: false,
                watchAppVersion = root.string("app") ?: "unknown",
                protocolVersion = root.number("pv")?.toInt() ?: 0
            )
            TYPE_STOP -> Stop(
                sessionId = requiredString(root, "sid", "stop"),
                stopSeq = root.number("seq")?.toLong() ?: 0L
            )
            TYPE_STOP_ACK -> StopAck(
                sessionId = requiredString(root, "sid", "stopAck"),
                finalized = root.bool("finalized") ?: false,
                logId = root.string("logId"),
                protocolVersion = root.number("pv")?.toInt() ?: 0
            )
            TYPE_STATUS -> Status(
                sessionId = root.string("sid"),
                recording = root.bool("recording") ?: false,
                localLoggingHealthy = root.bool("localOk") ?: false,
                pendingTransfers = root.number("pending")?.toInt() ?: 0,
                batteryPct = root.number("batt")?.toInt()
            )
            TYPE_SYNC_PING -> SyncPing(
                sid = requiredString(root, "sid", "syncPing"),
                t1PhoneNanos = requiredNumber(root, "t1", "syncPing").toLong()
            )
            TYPE_SYNC_PONG -> SyncPong(
                sid = requiredString(root, "sid", "syncPong"),
                t1PhoneNanos = requiredNumber(root, "t1", "syncPong").toLong(),
                t2WatchNanos = requiredNumber(root, "t2", "syncPong").toLong(),
                t3WatchNanos = requiredNumber(root, "t3", "syncPong").toLong()
            )
            TYPE_RESULT -> Result(
                sessionId = requiredString(root, "sid", "result"),
                bestLapSeconds = root.number("bestLap")?.toDouble(),
                lapCount = root.number("laps")?.toInt() ?: 0,
                steeringSmoothness = root.number("smooth")?.toDouble(),
                correctionCount = root.number("corrections")?.toInt(),
                peakHr = root.number("peakHr")?.toInt(),
                averageHr = root.number("avgHr")?.toInt(),
                watchDataQuality = root.string("quality"),
                notes = root.string("notes")
            )
            TYPE_TRANSFER_ACK -> TransferAck(
                sessionId = requiredString(root, "sid", "transferAck"),
                logId = requiredString(root, "logId", "transferAck"),
                accepted = root.bool("ok") ?: false,
                reason = root.string("reason")
            )
            // t is nullable here (a JSON object with no "t" key).
            else -> Unknown(t ?: "unknown")
        }
    }

    private fun requiredString(root: PitwallJson.Value.Object, field: String, type: String): String =
        root.string(field) ?: throw PitwallJson.JsonException("$type: missing required field '$field'")

    private fun requiredNumber(root: PitwallJson.Value.Object, field: String, type: String): Number =
        root.number(field) ?: throw PitwallJson.JsonException("$type: missing required field '$field'")
}
