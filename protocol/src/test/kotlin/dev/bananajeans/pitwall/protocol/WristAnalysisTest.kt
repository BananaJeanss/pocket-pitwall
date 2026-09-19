package dev.bananajeans.pitwall.protocol

import dev.bananajeans.pitwall.core.Telemetry
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WristAnalysisTest {

    /** Gyroscope samples around the Z axis with an optional sine component. */
    private fun samples(
        count: Int,
        hz: Double = 100.0,
        startNanos: Long = 50_000_000_000L,
        zSignal: (i: Int, tSeconds: Double) -> Double = { _, _ -> 0.0 }
    ): List<WatchLogCodec.Sample> =
        (0 until count).map { i ->
            val t = i / hz
            WatchLogCodec.Sample(
                sensorType = 4,
                timestampNanos = startNanos + (t * 1e9).toLong(),
                x = 0.01 * sin(i * 0.1),
                y = 0.02 * sin(i * 0.05),
                z = zSignal(i, t),
                w = 0.0,
                accuracy = 3
            )
        }

    /** Fit mapping watch time -> phone time 1:1 with offset = 1 s. */
    private fun fit() = ClockSync.Fit(
        offsetWatchMinusPhone = 1e9, // phone = watch - 1 s
        driftPerNano = 0.0,
        bestRttNanos = 5_000_000.0,
        medianRttNanos = 8_000_000.0,
        residualRmsNanos = 100_000.0,
        exchangesUsed = 10,
        exchangesTotal = 10,
        rttOutliersRejected = 0
    )

    /** Watch start nanos used by [samples] (50 s). */
    private val watchStartNanos = 50_000_000_000L

    /** Phone monotonic start equivalent to watchStartNanos under [fit] (49 s). */
    private val phoneStartNanos = 49_000_000_000L

    private fun analyze(
        data: List<WatchLogCodec.Sample>,
        durationSeconds: Double,
        f: ClockSync.Fit = fit()
    ) = WristAnalysis.analyze(data, f, durationSeconds, phoneSessionStartNanos = phoneStartNanos)

    @Test
    fun refusesTooFewSamples() {
        val result = analyze(samples(50), 30.0)
        assertTrue(!result.usable)
        assertTrue(result.degradedReason!!.contains("Too few"))
        assertTrue(result.steeringRate.isEmpty())
    }

    @Test
    fun refusesWithoutClockSync() {
        val result = WristAnalysis.analyze(samples(1000), null, 30.0, phoneStartNanos)
        assertTrue(!result.usable)
        assertTrue(result.degradedReason!!.contains("clock synchronization"))
    }

    @Test
    fun picksDominantAxis() {
        // Z axis has strong steering signal; X/Y only tiny noise.
        val data = samples(1000, zSignal = { i, _ -> 0.8 * sin(i * 0.02) })
        val result = analyze(data, 30.0)
        assertTrue(result.usable)
        assertEquals(2, result.rotationAxis)
        assertTrue(result.steeringRate.isNotEmpty())
    }

    @Test
    fun steeringEventsAreReproducible() {
        // 30 s at 100 Hz with periodic steering peaks.
        val data = samples(3000, zSignal = { i, _ -> 1.2 * sin(2 * PI * (i / 3000.0) * 5) })
        val result1 = analyze(data, 30.0)
        val result2 = analyze(data, 30.0)
        assertTrue(result1.usable)
        assertEquals(result1.events, result2.events, "analysis must be deterministic")
        assertTrue(result1.events.isNotEmpty(), "steering peaks should produce events")
    }

    @Test
    fun oscillationDetectsCorrectionsVersusSmoothDriving() {
        // Smooth: slow single sweep. Noisy: many zero crossings.
        val smooth = samples(2000, zSignal = { _, t -> 1.0 * sin(2 * PI * t / 20.0) })
        val noisy = samples(2000, zSignal = { i, _ -> if (i % 8 < 4) 0.4 else -0.4 })
        val smoothResult = analyze(smooth, 20.0)
        val noisyResult = analyze(noisy, 20.0)
        assertTrue(noisyResult.oscillation > smoothResult.oscillation,
            "expected noisy (${noisyResult.oscillation}) > smooth (${smoothResult.oscillation})")
    }

    @Test
    fun watchTimeMappedIntoPhoneTimeline() {
        // offset 1 s (phone = watch - 1s) with matching timeline starts:
        // watch-relative t maps to phone-relative t unchanged (offsets cancel),
        // so a spike injected at watch-relative t=10 appears at ~10.0.
        val data = samples(1500, zSignal = { _, t -> if (kotlin.math.abs(t - 10.0) < 0.05) 2.0 else 0.0 })
        val result = analyze(data, durationSeconds = 15.0)
        assertTrue(result.usable)
        val peak = result.events.maxByOrNull { kotlin.math.abs(it.peakRate) }
        requireNotNull(peak)
        assertEquals(10.0, peak.tSeconds, 0.5)
    }

    @Test
    fun degradedWhenWatchDataStartsTooLate() {
        // All samples map far into the session (watch offset makes them land
        // beyond 50% of the 10 s session => degraded).
        val lateFit = ClockSync.Fit(
            offsetWatchMinusPhone = 30e9, // phone = watch - 30 s: all negative => dropped
            driftPerNano = 0.0,
            bestRttNanos = 5_000_000.0,
            medianRttNanos = 8_000_000.0,
            residualRmsNanos = 100_000.0,
            exchangesUsed = 10,
            exchangesTotal = 10,
            rttOutliersRejected = 0
        )
        val result = WristAnalysis.analyze(samples(1000), lateFit, durationSeconds = 10.0)
        assertTrue(!result.usable)
    }

    @Test
    fun perLapOscillationSplitsByLap() {
        val data = samples(3000, zSignal = { i, _ -> 0.5 * sin(i * 0.03) })
        val result = analyze(data, 30.0)
        // Two 10 s laps from SF marks (public :core API; Lap ctor is internal).
        val laps = Telemetry.laps(
            listOf(
                Telemetry.Mark(0.0, "SF", false),
                Telemetry.Mark(10.0, "SF", false),
                Telemetry.Mark(20.0, "SF", false)
            )
        )
        assertEquals(2, laps.size)
        val perLap = WristAnalysis.perLapOscillation(result, laps)
        assertEquals(2, perLap.size)
    }
}
