package dev.bananajeans.pitwall.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import kotlin.math.roundToLong

/**
 * Compact binary watch telemetry log ("PWTCH" format, version 1).
 *
 * Requirements this format serves (Wear OS tracking issue #15/#18/#21):
 *  - compact enough for high-rate IMU streams (no JSON per sample),
 *  - written incrementally: complete frames are on disk as recording proceeds,
 *  - complete vs incomplete logs are distinguishable,
 *  - corrupt/truncated data is detected, never silently treated as complete,
 *  - raw monotonic timestamps survive without lossy resampling,
 *  - readable as a stream: the reader never buffers the whole file (an
 *    hour-long log is tens of MB; reading it via readBytes() risks OOM on
 *    the phone).
 *
 * File layout (all integers little-endian):
 *
 *   header:
 *     magic "PWTCH"            5 bytes ASCII
 *     formatVersion            u8   (= 1)
 *     headerLength             u32  total header bytes incl. magic and this field
 *     metadataJson             headerLength - 10 bytes, UTF-8 JSON object
 *
 *   payload: zero or more frames (each independently CRC-protected):
 *     frameMagic 'F'           u8
 *     frameType                u8   1 = samples, 2 = accuracy event
 *     count                    u16  number of records in the frame
 *     sensorType              u8   android.hardware.Sensor.TYPE_* constant
 *     reserved                u8   0
 *     frameCrc32              u32  CRC32 of the frame bytes excluding this field
 *     records...
 *
 *   trailer (present only on a finalized log):
 *     magic "PWEND"            5 bytes ASCII
 *     payloadCrc32             u32  CRC32 over every payload byte
 *     payloadLength            u32  payload byte count
 *
 * Sample record (28 bytes): timestampDelta u64, x i32, y i32, z i32, w i32,
 * accuracy u8, padding u8[3]. Sensor floats are stored as
 * round(value * 1e6) clamped to i32 (saturates beyond ±2147.48, far outside
 * IMU ranges). Deltas are nanoseconds since the previous record of the same
 * sensor; the chain is seeded with the metadata's startedAtMonotonicNanos.
 * Accuracy frames advance their sensor's chain too.
 *
 * Reading rules:
 *  - Valid trailer (magic + CRC + length, at end of stream) => COMPLETE log.
 *  - Missing/bad trailer => INCOMPLETE: frames are recovered up to the first
 *    structurally invalid or CRC-failing frame. Consumers must surface this.
 *  - A frame CRC failure in an otherwise COMPLETE log is corruption and throws.
 *
 * Streaming: [read] consumes [input] frame by frame. Memory cost is the
 * header (<= 64 KiB), one frame buffer (<= ~1.8 MiB for a max-size frame) and
 * the decoded records. The trailer is recognized at a frame boundary only
 * when the stream ends immediately after it, so a literal "PWEND" inside the
 * payload is treated as data corruption, not as an early trailer.
 */
public object WatchLogCodec {

    public const val FORMAT_VERSION: Int = 1
    public const val MAGIC: String = "PWTCH"
    public const val TRAILER_MAGIC: String = "PWEND"
    private const val HEADER_FIXED: Int = 10
    public const val TRAILER_SIZE: Int = 13  // 5 (magic) + 4 (payload CRC32) + 4 (payload length)
    public const val FRAME_FIXED_BYTES: Int = 10
    public const val SAMPLE_RECORD_BYTES: Int = 28
    private const val ACCURACY_RECORD_BYTES: Int = 9
    private const val FRAME_TYPE_SAMPLES: Int = 1
    private const val FRAME_TYPE_ACCURACY: Int = 2
    private const val FLOAT_SCALE: Double = 1_000_000.0
    private const val MAX_METADATA_BYTES: Int = 64 * 1024
    /** One sample frame can hold up to 0xFFFF records of 28 bytes. */
    public const val MAX_FRAME_BYTES: Int = FRAME_FIXED_BYTES + 0xFFFF * SAMPLE_RECORD_BYTES
    private const val TRAILER_FIXED: Int = 5 // the magic prefix length used for partial-EOF checks

    // ---- model ---------------------------------------------------------------

    /** One raw sensor sample exactly as delivered by the OS. */
    public data class Sample(
        val sensorType: Int,
        val timestampNanos: Long,
        val x: Double,
        val y: Double,
        val z: Double,
        val w: Double,
        val accuracy: Int
    )

    /** A timestamped accuracy change for a sensor (no values). */
    public data class AccuracyEvent(val sensorType: Int, val timestampNanos: Long, val accuracy: Int)

    /** Watch log metadata, stored as JSON in the header. */
    public data class Metadata(
        val sessionId: String,
        val watchAppVersion: String,
        val deviceModel: String,
        /** Wall clock (System.currentTimeMillis) at recording start, context only. */
        val startedAtWallMillis: Long,
        /** Watch monotonic clock (elapsedRealtimeNanos) at recording start. */
        val startedAtMonotonicNanos: Long,
        val sensorInfo: List<SensorInfo>,
        val protocolVersion: Int,
        val notes: String? = null
    ) {
        public data class SensorInfo(
            val type: Int,
            val name: String,
            val vendor: String,
            val requestedRateHz: Double,
            val resolution: Double,
            val maxRange: Double,
            val minDelayMicros: Int,
            val maxDelayMicros: Long
        )
    }

    public data class WatchLog(
        val metadata: Metadata,
        val samples: List<Sample>,
        val accuracyEvents: List<AccuracyEvent>,
        /** False when the trailer is missing/mismatched (crash or truncation). */
        val complete: Boolean,
        val incompleteReason: String?
    )

    public sealed interface ReadResult {
        public data class Complete(val log: WatchLog) : ReadResult
        public data class Incomplete(val log: WatchLog, val reason: String) : ReadResult
    }

    public class CorruptLogException(message: String) : Exception(message)

    // ---- writing ---------------------------------------------------------

    /**
     * Incremental writer. Writes the header immediately, appends complete
     * frames as recording proceeds, and finalizes with the trailer on
     * [finish]. Frames already written survive a crash before [finish] and
     * are readable as an Incomplete log.
     */
    public class Writer(private val out: OutputStream, metadata: Metadata) {

        private val payloadCrc = CRC32()
        private val previousTimestamps = HashMap<Int, Long>()
        private var payloadBytes = 0L
        private var finished = false

        init {
            val json = encodeMetadata(metadata)
            val headerLength = HEADER_FIXED + json.size
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            out.write(FORMAT_VERSION)
            writeU32(out, headerLength)
            out.write(json)
            previousTimestamps[SENSOR_CHAIN_SEED] = metadata.startedAtMonotonicNanos
        }

        /** Appends one frame of same-sensor samples. Samples must be timestamp-ordered. */
        public fun appendSamples(sensorType: Int, samples: List<Sample>) {
            check(!finished) { "Writer already finished" }
            if (samples.isEmpty()) return
            require(samples.all { it.sensorType == sensorType }) { "Mixed sensor types in one frame" }
            val frame = encodeSampleFrame(sensorType, samples)
            out.write(frame)
            payloadCrc.update(frame, 0, frame.size)
            payloadBytes += frame.size
        }

        public fun appendAccuracyEvent(event: AccuracyEvent) {
            check(!finished) { "Writer already finished" }
            val frame = encodeAccuracyFrame(event)
            out.write(frame)
            payloadCrc.update(frame, 0, frame.size)
            payloadBytes += frame.size
        }

        /** Writes the trailer. The log is complete only after this. */
        public fun finish() {
            check(!finished) { "Writer already finished" }
            require(payloadBytes <= Int.MAX_VALUE.toLong()) { "Payload exceeds 2 GiB" }
            out.write(TRAILER_MAGIC.toByteArray(Charsets.US_ASCII))
            writeU32(out, payloadCrc.value.toInt())
            writeU32(out, payloadBytes.toInt())
            out.flush()
            finished = true
        }

        public fun close() { out.close() }

        private fun encodeSampleFrame(sensorType: Int, samples: List<Sample>): ByteArray {
            val buf = ByteArrayOutputStream(FRAME_FIXED_BYTES + samples.size * SAMPLE_RECORD_BYTES)
            buf.write('F'.code)
            buf.write(FRAME_TYPE_SAMPLES)
            writeU16(buf, samples.size)
            buf.write(sensorType and 0xFF)
            buf.write(0)
            buf.write(byteArrayOf(0, 0, 0, 0)) // frame CRC placeholder
            for (s in samples) {
                val previous = previousTimestamps.getOrPut(sensorType) { previousTimestamps.getValue(SENSOR_CHAIN_SEED) }
                if (s.timestampNanos < previous)
                    throw IllegalStateException("Non-monotonic ${sensorType} sample at ${s.timestampNanos}")
                writeU64(buf, s.timestampNanos - previous)
                writeI32(buf, scaleToI32(s.x))
                writeI32(buf, scaleToI32(s.y))
                writeI32(buf, scaleToI32(s.z))
                writeI32(buf, scaleToI32(s.w))
                buf.write(s.accuracy.coerceIn(0, 255))
                buf.write(byteArrayOf(0, 0, 0))
                previousTimestamps[sensorType] = s.timestampNanos
            }
            val frame = buf.toByteArray()
            patchFrameCrc(frame)
            return frame
        }

        private fun encodeAccuracyFrame(event: AccuracyEvent): ByteArray {
            val buf = ByteArrayOutputStream(FRAME_FIXED_BYTES + ACCURACY_RECORD_BYTES)
            buf.write('F'.code)
            buf.write(FRAME_TYPE_ACCURACY)
            writeU16(buf, 1)
            buf.write(event.sensorType and 0xFF)
            buf.write(0)
            buf.write(byteArrayOf(0, 0, 0, 0))
            val previous = previousTimestamps.getOrPut(event.sensorType) { previousTimestamps.getValue(SENSOR_CHAIN_SEED) }
            if (event.timestampNanos < previous)
                throw IllegalStateException("Non-monotonic accuracy event for ${event.sensorType}")
            writeU64(buf, event.timestampNanos - previous)
            buf.write(event.accuracy.coerceIn(0, 255))
            // The reader advances the per-sensor chain from accuracy frames
            // too; the writer must mirror that or later deltas disagree.
            previousTimestamps[event.sensorType] = event.timestampNanos
            val frame = buf.toByteArray()
            patchFrameCrc(frame)
            return frame
        }
    }

    // Key used to seed per-sensor timestamp chains; negative so it can never
    // collide with a real android.hardware.Sensor.TYPE_* constant.
    private const val SENSOR_CHAIN_SEED: Int = -1

    // ---- reading ---------------------------------------------------------

    /** Reads and validates a whole log from [input], consuming it incrementally. */
    public fun read(input: InputStream): ReadResult {
        val header = readHeader(input)
        val samples = ArrayList<Sample>(1024)
        val accuracies = ArrayList<AccuracyEvent>()
        val lastTimestamps = HashMap<Int, Long>()
        val payloadCrc = CRC32()
        var payloadBytes = 0L
        var pos = 0L
        var stoppedAt: Long? = null
        var trailerCrc: Int? = null
        var trailerLen: Int? = null
        var trailerAtEnd = false

        val fixed = ByteArray(FRAME_FIXED_BYTES)
        loop@ while (true) {
            val headRead = readUpTo(input, fixed)
            if (headRead <= 0) break // clean EOF: no trailer
            if (headRead < FRAME_FIXED_BYTES) {
                stoppedAt = pos
                break
            }
            if (headRead < TRAILER_FIXED && matches(fixed, TRAILER_MAGIC, headRead)) {
                // Partial trailer magic at EOF: truncated tail.
                stoppedAt = pos
                break
            }
            if (matches(fixed, TRAILER_MAGIC)) {
                // Trailer candidate: valid only if it ends exactly at EOF.
                val trailer = ByteArray(TRAILER_SIZE)
                System.arraycopy(fixed, 0, trailer, 0, FRAME_FIXED_BYTES)
                if (readUpTo(input, trailer, FRAME_FIXED_BYTES, TRAILER_SIZE - FRAME_FIXED_BYTES) < TRAILER_SIZE - FRAME_FIXED_BYTES) {
                    stoppedAt = pos
                    break
                }
                trailerCrc = readU32(trailer, 5)
                trailerLen = readU32(trailer, 9)
                trailerAtEnd = input.read() == -1
                if (trailerAtEnd) break
                // Mid-payload "PWEND" bytes: corruption, not a trailer.
                stoppedAt = pos
                trailerCrc = null
                trailerLen = null
                break
            }
            if (fixed[0].toInt() != 'F'.code) {
                stoppedAt = pos
                break
            }
            val frameType = fixed[1].toInt() and 0xFF
            val count = readU16(fixed, 2)
            val recordSize = when (frameType) {
                FRAME_TYPE_SAMPLES -> SAMPLE_RECORD_BYTES
                FRAME_TYPE_ACCURACY -> ACCURACY_RECORD_BYTES
                else -> { stoppedAt = pos; break@loop }
            }
            val bodyLength = count * recordSize
            if (bodyLength > MAX_FRAME_BYTES) { stoppedAt = pos; break@loop }
            val frame = ByteArray(FRAME_FIXED_BYTES + bodyLength)
            System.arraycopy(fixed, 0, frame, 0, FRAME_FIXED_BYTES)
            val bodyRead = readUpTo(input, frame, FRAME_FIXED_BYTES, bodyLength)
            if (bodyRead < bodyLength) {
                stoppedAt = pos
                break
            }
            val frameLength = frame.size
            payloadCrc.update(frame, 0, frameLength)
            pos += frameLength

            val crc = CRC32()
            crc.update(frame, 0, 6)
            crc.update(frame, 10, frameLength - 10)
            if (crc.value.toInt() != readU32(frame, 6)) {
                stoppedAt = pos - frameLength
                break
            }

            val corrupted = decodeFrameInto(frame, frameType, count, header.metadata, lastTimestamps, samples, accuracies)
            if (corrupted) {
                stoppedAt = pos - frameLength
                break
            }
            payloadBytes += frameLength
        }

        var complete = false
        var incompleteReason: String? = "Log was not finalized"
        if (trailerCrc != null && trailerLen != null && trailerAtEnd) {
            if (stoppedAt != null) {
                // Payload CRC covers the frames we read; a parse stop under a
                // valid trailer means the writer produced a bad frame.
                throw CorruptLogException("Frame corruption at payload offset $stoppedAt despite valid trailer")
            }
            if (trailerLen == payloadBytes.toInt() && trailerCrc == payloadCrc.value.toInt()) {
                complete = true
                incompleteReason = null
            } else {
                incompleteReason = "Trailer checksum mismatch"
            }
        }
        if (!complete && stoppedAt != null && incompleteReason == "Log was not finalized") {
            incompleteReason = "Stopped at payload offset $stoppedAt: truncated"
        }
        val log = WatchLog(header.metadata, samples, accuracies, complete, null)
        return if (complete) ReadResult.Complete(log)
        else ReadResult.Incomplete(log.copy(incompleteReason = incompleteReason), incompleteReason ?: "incomplete")
    }

    /** Convenience overload for small/in-memory logs (tests, tiny logs). */
    public fun read(bytes: ByteArray): ReadResult = read(ByteArrayInputStream(bytes))

    private class Header(val metadata: Metadata)

    private fun readHeader(input: InputStream): Header {
        val head = ByteArray(HEADER_FIXED)
        if (readUpTo(input, head) < HEADER_FIXED)
            throw CorruptLogException("Log too short for header")
        if (String(head, 0, 5, Charsets.US_ASCII) != MAGIC)
            throw CorruptLogException("Not a PWTCH log")
        val version = head[5].toInt() and 0xFF
        if (version != FORMAT_VERSION)
            throw CorruptLogException("Unsupported format version $version")
        val headerLength = readU32(head, 6)
        if (headerLength < HEADER_FIXED || headerLength > HEADER_FIXED + MAX_METADATA_BYTES)
            throw CorruptLogException("Header length $headerLength outside bounds")
        val metadataBytes = ByteArray(headerLength - HEADER_FIXED)
        if (readUpTo(input, metadataBytes) < metadataBytes.size)
            throw CorruptLogException("Log too short for metadata (declared $headerLength bytes)")
        return Header(decodeMetadata(metadataBytes))
    }

    /** Reads up to buffer.size bytes (or [len] from [offset]), returns count read. */
    private fun readUpTo(input: InputStream, buffer: ByteArray, offset: Int = 0, len: Int = buffer.size - offset): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buffer, offset + total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun matches(bytes: ByteArray, magic: String, length: Int = magic.length): Boolean {
        for (i in 0 until length) if (bytes[i].toInt() != magic[i].code) return false
        return true
    }

    /** Decodes one CRC-verified frame into the record lists; true when invalid. */
    private fun decodeFrameInto(
        frame: ByteArray,
        frameType: Int,
        count: Int,
        metadata: Metadata,
        lastTimestamps: HashMap<Int, Long>,
        samples: MutableList<Sample>,
        accuracies: MutableList<AccuracyEvent>
    ): Boolean {
        if (frameType == FRAME_TYPE_SAMPLES) {
            val sensorType = frame[4].toInt() and 0xFF
            var p = FRAME_FIXED_BYTES
            for (i in 0 until count) {
                val delta = readU64(frame, p)
                if (delta < 0) return true
                val ts = lastTimestamps.getOrPut(sensorType) { metadata.startedAtMonotonicNanos } + delta
                samples.add(
                    Sample(
                        sensorType = sensorType,
                        timestampNanos = ts,
                        x = unscale(readI32(frame, p + 8)),
                        y = unscale(readI32(frame, p + 12)),
                        z = unscale(readI32(frame, p + 16)),
                        w = unscale(readI32(frame, p + 20)),
                        accuracy = frame[p + 24].toInt() and 0xFF
                    )
                )
                lastTimestamps[sensorType] = ts
                p += SAMPLE_RECORD_BYTES
            }
        } else {
            val sensorType = frame[4].toInt() and 0xFF
            val delta = readU64(frame, FRAME_FIXED_BYTES)
            if (delta < 0) return true
            val ts = lastTimestamps.getOrPut(sensorType) { metadata.startedAtMonotonicNanos } + delta
            accuracies.add(AccuracyEvent(sensorType, ts, frame[FRAME_FIXED_BYTES + 8].toInt() and 0xFF))
            lastTimestamps[sensorType] = ts
        }
        return false
    }

    // ---- metadata JSON ------------------------------------------------------

    private fun encodeMetadata(metadata: Metadata): ByteArray {
        val sensors = metadata.sensorInfo.map { info ->
            PitwallJson.obj(
                "type" to PitwallJson.n(info.type.toLong()),
                "name" to PitwallJson.s(info.name),
                "vendor" to PitwallJson.s(info.vendor),
                "rate" to PitwallJson.n(info.requestedRateHz),
                "res" to PitwallJson.n(info.resolution),
                "max" to PitwallJson.n(info.maxRange),
                "minDelay" to PitwallJson.n(info.minDelayMicros.toLong()),
                "maxDelay" to PitwallJson.n(info.maxDelayMicros)
            )
        }
        val json = PitwallJson.obj(
            "schema" to PitwallJson.n(FORMAT_VERSION.toLong()),
            "sid" to PitwallJson.s(metadata.sessionId),
            "app" to PitwallJson.s(metadata.watchAppVersion),
            "model" to PitwallJson.s(metadata.deviceModel),
            "wallStart" to PitwallJson.n(metadata.startedAtWallMillis),
            "monoStart" to PitwallJson.n(metadata.startedAtMonotonicNanos),
            "sensors" to PitwallJson.arr(sensors),
            "pv" to PitwallJson.n(metadata.protocolVersion.toLong()),
            "notes" to (metadata.notes?.let { PitwallJson.s(it) } ?: PitwallJson.Value.Null)
        )
        val encoded = PitwallJson.write(json).toByteArray(Charsets.UTF_8)
        if (encoded.size > MAX_METADATA_BYTES)
            throw CorruptLogException("Metadata JSON is ${encoded.size} bytes (max $MAX_METADATA_BYTES)")
        return encoded
    }

    private fun decodeMetadata(bytes: ByteArray): Metadata {
        if (bytes.isEmpty()) throw CorruptLogException("Empty metadata JSON")
        val root = PitwallJson.parse(String(bytes, Charsets.UTF_8)) as? PitwallJson.Value.Object
            ?: throw CorruptLogException("Metadata is not a JSON object")
        val schema = root.number("schema")?.toInt() ?: 0
        if (schema != FORMAT_VERSION)
            throw CorruptLogException("Metadata schema $schema not supported (expected $FORMAT_VERSION)")
        val sensors = root.array("sensors")?.items?.mapNotNull { item ->
            val o = item as? PitwallJson.Value.Object ?: return@mapNotNull null
            Metadata.SensorInfo(
                type = o.number("type")?.toInt() ?: 0,
                name = o.string("name") ?: "unknown",
                vendor = o.string("vendor") ?: "unknown",
                requestedRateHz = o.number("rate")?.toDouble() ?: 0.0,
                resolution = o.number("res")?.toDouble() ?: 0.0,
                maxRange = o.number("max")?.toDouble() ?: 0.0,
                minDelayMicros = o.number("minDelay")?.toInt() ?: 0,
                maxDelayMicros = o.number("maxDelay")?.toLong() ?: 0L
            )
        } ?: emptyList()
        return Metadata(
            sessionId = root.string("sid") ?: throw CorruptLogException("Metadata missing sessionId"),
            watchAppVersion = root.string("app") ?: "unknown",
            deviceModel = root.string("model") ?: "unknown",
            startedAtWallMillis = root.number("wallStart")?.toLong() ?: 0L,
            startedAtMonotonicNanos = root.number("monoStart")?.toLong() ?: 0L,
            sensorInfo = sensors,
            protocolVersion = root.number("pv")?.toInt() ?: 0,
            notes = root.string("notes")
        )
    }

    // ---- low-level byte helpers -------------------------------------------

    private fun patchFrameCrc(frame: ByteArray) {
        val crc = CRC32()
        crc.update(frame, 0, 6)
        crc.update(frame, 10, frame.size - 10)
        val value = crc.value.toInt()
        for (i in 0..3) frame[6 + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeU32(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun writeI32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun writeU64(out: ByteArrayOutputStream, value: Long) {
        var v = value
        repeat(8) {
            out.write((v and 0xFF).toInt())
            v = v shr 8
        }
    }

    private fun readU16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun readU32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun readI32(bytes: ByteArray, at: Int): Int = readU32(bytes, at)

    private fun readU64(bytes: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (bytes[at + i].toLong() and 0xFF)
        return v
    }

    private fun scaleToI32(value: Double): Int {
        if (!value.isFinite()) return 0
        return (value * FLOAT_SCALE).roundToLong()
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun unscale(raw: Int): Double = raw / FLOAT_SCALE
}
