package dev.bananajeans.pitwall.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WatchLogCodecTest {

    private fun metadata(sessionId: String = "sess-1") = WatchLogCodec.Metadata(
        sessionId = sessionId,
        watchAppVersion = "0.3.0",
        deviceModel = "Galaxy Watch7",
        startedAtWallMillis = 1730000000000L,
        startedAtMonotonicNanos = 50_000_000_000L,
        sensorInfo = listOf(
            WatchLogCodec.Metadata.SensorInfo(
                type = 4, name = "gyro", vendor = "Sensortec", requestedRateHz = 200.0,
                resolution = 0.001, maxRange = 34.9, minDelayMicros = 5000, maxDelayMicros = 100000
            )
        ),
        protocolVersion = 1
    )

    private fun samples(n: Int, sensorType: Int = 4, startNanos: Long = 50_000_000_100L, stepNanos: Long = 5_000_000): List<WatchLogCodec.Sample> =
        (0 until n).map { i ->
            WatchLogCodec.Sample(
                sensorType = sensorType,
                timestampNanos = startNanos + i * stepNanos,
                x = 0.25 * i, y = -9.81 + 0.01 * i, z = 0.5, w = 0.0, accuracy = 3
            )
        }

    private fun readComplete(bytes: ByteArray): WatchLogCodec.WatchLog =
        (WatchLogCodec.read(ByteArrayInputStream(bytes)) as WatchLogCodec.ReadResult.Complete).log

    @Test
    fun completeLogRoundTrip() {
        val out = ByteArrayOutputStream()
        val writer = WatchLogCodec.Writer(out, metadata())
        writer.appendSamples(4, samples(3))
        // samples(3) ends at 50_010_000_100; the event and second batch follow it.
        writer.appendAccuracyEvent(WatchLogCodec.AccuracyEvent(4, 50_020_000_000L, 2))
        writer.appendSamples(4, samples(3, startNanos = 50_030_000_000L))
        writer.finish()

        val complete = WatchLogCodec.read(ByteArrayInputStream(out.toByteArray())) as WatchLogCodec.ReadResult.Complete
        val log = complete.log
        assertEquals(6, log.samples.size)
        assertEquals(1, log.accuracyEvents.size)
        assertEquals("sess-1", log.metadata.sessionId)
        assertEquals(2, log.accuracyEvents[0].accuracy)
        assertEquals(1, log.metadata.sensorInfo.size)
        assertEquals(200.0, log.metadata.sensorInfo[0].requestedRateHz)
        // Values survive the 1e6 scaling round-trip.
        assertEquals(0.25, log.samples[4].x, 1e-9)
        assertEquals(-9.81, log.samples[0].y, 1e-9)
        // Timestamps are reconstructed exactly (no resampling).
        assertEquals(50_030_000_000L + 2 * 5_000_000, log.samples[5].timestampNanos)
    }

    @Test
    fun unfinalizedLogIsIncompleteButReadable() {
        val out = ByteArrayOutputStream()
        val writer = WatchLogCodec.Writer(out, metadata())
        writer.appendSamples(4, samples(3))
        writer.close() // no finish(): simulates crash mid-recording

        val incomplete = WatchLogCodec.read(ByteArrayInputStream(out.toByteArray())) as WatchLogCodec.ReadResult.Incomplete
        assertEquals(3, incomplete.log.samples.size)
        assertTrue(incomplete.reason!!.contains("finalized", ignoreCase = true))
    }

    @Test
    fun truncatedLogRecoversCompleteFramesOnly() {
        // One sample per frame so a mid-file cut loses only the tail frames.
        val out = ByteArrayOutputStream()
        val writer = WatchLogCodec.Writer(out, metadata())
        samples(10).forEach { writer.appendSamples(4, listOf(it)) }
        writer.finish()
        val bytes = out.toByteArray()
        // Drop the trailer plus ~2.5 frames worth of payload bytes.
        val truncated = bytes.copyOfRange(0, bytes.size - 100)

        val incomplete = WatchLogCodec.read(ByteArrayInputStream(truncated)) as WatchLogCodec.ReadResult.Incomplete
        assertTrue(incomplete.log.samples.size < 10, "expected to lose tail samples, kept ${incomplete.log.samples.size}")
        assertTrue(incomplete.log.samples.size >= 1, "expected earlier frames to survive")
        // Surviving samples are exactly the frame-aligned prefix.
        assertEquals(50_000_000_100L, incomplete.log.samples[0].timestampNanos)
    }

    @Test
    fun corruptedByteNeverReportsComplete() {
        val out = ByteArrayOutputStream()
        val writer = WatchLogCodec.Writer(out, metadata())
        samples(5).forEach { writer.appendSamples(4, listOf(it)) }
        writer.finish()
        val bytes = out.toByteArray()
        // Flip a bit inside the last sample record (before the trailer).
        bytes[bytes.size - 13 - 4] = (bytes[bytes.size - 13 - 4].toInt() xor 0x40).toByte()

        val result = runCatching { WatchLogCodec.read(ByteArrayInputStream(bytes)) }
        val outcome = result.getOrNull()
        assertTrue(result.isFailure || outcome is WatchLogCodec.ReadResult.Incomplete,
            "Corrupted payload must never surface as Complete")
        // Frames before the corruption are still recovered.
        if (outcome is WatchLogCodec.ReadResult.Incomplete) {
            assertTrue(outcome.log.samples.size in 1..4, "expected partial recovery, got ${outcome.log.samples.size}")
        }
    }

    @Test
    fun badMagicIsRejected() {
        val out = ByteArrayOutputStream()
        out.write("XXXXX".toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(32))
        assertFailsWith<WatchLogCodec.CorruptLogException> { WatchLogCodec.read(ByteArrayInputStream(out.toByteArray())) }
    }

    @Test
    fun nonMonotonicSamplesAreRejected() {
        val out = ByteArrayOutputStream()
        val writer = WatchLogCodec.Writer(out, metadata())
        val s = samples(2).toMutableList()
        s[1] = s[1].copy(timestampNanos = s[0].timestampNanos - 1)
        assertFailsWith<IllegalStateException> { writer.appendSamples(4, s) }
    }

    @Test
    fun perSensorTimestampChainsStayIndependent() {
        val out = ByteArrayOutputStream()
        val writer = WatchLogCodec.Writer(out, metadata())
        val gyro = samples(4, sensorType = 4, startNanos = 50_000_000_000L, stepNanos = 5_000_000)
        val accel = samples(4, sensorType = 10, startNanos = 50_000_002_500L, stepNanos = 5_000_000)
        for (i in 0 until 4) {
            writer.appendSamples(4, listOf(gyro[i], gyro[i].copy(timestampNanos = gyro[i].timestampNanos + 2_500_000L)))
            writer.appendSamples(10, listOf(accel[i], accel[i].copy(timestampNanos = accel[i].timestampNanos + 2_500_000L)))
        }
        writer.finish()
        val log = readComplete(out.toByteArray())
        assertEquals(16, log.samples.size)
        val gyroTimes = log.samples.filter { it.sensorType == 4 }.map { it.timestampNanos }
        val accelTimes = log.samples.filter { it.sensorType == 10 }.map { it.timestampNanos }
        assertEquals(8, gyroTimes.toSet().size)
        assertEquals(8, accelTimes.toSet().size)
        assertTrue(gyroTimes.zipWithNext().all { (a, b) -> b > a })
        assertTrue(accelTimes.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun secondFrameAfterGapHasAbsoluteTimestamps() {
        val out = ByteArrayOutputStream()
        val writer = WatchLogCodec.Writer(out, metadata())
        writer.appendSamples(4, samples(2))
        // Long gap, then more samples.
        writer.appendSamples(4, samples(2, startNanos = 50_010_000_100L))
        writer.finish()
        val log = readComplete(out.toByteArray())
        assertEquals(50_010_000_100L, log.samples[2].timestampNanos)
        assertEquals(50_010_000_100L + 5_000_000, log.samples[3].timestampNanos)
    }

    @Test
    fun writeIsCompactComparedToJsonPerSample() {
        // 28 bytes/sample binary vs a JSON line easily 60+ bytes.
        val out = ByteArrayOutputStream()
        val writer = WatchLogCodec.Writer(out, metadata())
        writer.appendSamples(4, samples(1000))
        writer.finish()
        val perSample = out.size().toDouble() / 1000
        assertTrue(perSample < 40, "Expected < 40 bytes/sample, got $perSample")
    }

    @Test
    fun accuracyEventBeforeAnySampleSeedsFromMetadataStart() {
        val out = ByteArrayOutputStream()
        val writer = WatchLogCodec.Writer(out, metadata())
        writer.appendAccuracyEvent(WatchLogCodec.AccuracyEvent(4, 50_000_000_500L, 1))
        writer.appendSamples(4, samples(1, startNanos = 50_000_001_000L))
        writer.finish()
        val log = readComplete(out.toByteArray())
        assertEquals(50_000_000_500L, log.accuracyEvents[0].timestampNanos)
        // The accuracy event advanced the chain, so this sample's delta is
        // measured from the event, not from the recording start.
        assertEquals(50_000_001_000L, log.samples[0].timestampNanos)
    }

    @Test
    fun saturatingScaleRoundTripsAtImuRanges() {
        val extreme = WatchLogCodec.Sample(4, 50_000_000_000L, 2000.0, -2000.0, 500.0, 0.5, 3)
        val out = ByteArrayOutputStream()
        val writer = WatchLogCodec.Writer(out, metadata())
        writer.appendSamples(4, listOf(extreme))
        writer.finish()
        val log = readComplete(out.toByteArray())
        // Within saturation limit (±2147.48): exact round-trip.
        assertEquals(2000.0, log.samples[0].x, 1e-6)
        assertEquals(-2000.0, log.samples[0].y, 1e-6)
    }

    @Test
    fun zeroSampleFinalizedLogIsReadable() {
        val out = ByteArrayOutputStream()
        val writer = WatchLogCodec.Writer(out, metadata())
        writer.finish()
        val complete = WatchLogCodec.read(ByteArrayInputStream(out.toByteArray())) as WatchLogCodec.ReadResult.Complete
        assertEquals(0, complete.log.samples.size)
    }
}
