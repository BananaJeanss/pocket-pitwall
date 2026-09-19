package dev.bananajeans.pitwall.protocol

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClockSyncTest {

    /** Builds synthetic exchanges with a known offset, drift and RTT jitter. */
    private class Scenario(
        val offsetNanos: Double,          // watch = offset + phone (at t=0)
        val driftPpm: Double,              // watch runs fast by this fraction
        val baseRttNanos: Double = 8_000_000.0,
        val jitterNanos: Double = 1_000_000.0,
        val seed: Int = 42
    ) {
        val random = Random(seed)

        fun exchanges(count: Int, startPhoneNanos: Long = 100_000_000_000L, spacingNanos: Long = 2_000_000_000L): List<ClockSync.Exchange> {
            return (0 until count).map { i ->
                val t1 = startPhoneNanos + i * spacingNanos
                val rtt = baseRttNanos + random.nextDouble() * jitterNanos
                val watchToPhoneHalf = rtt / 2
                // Watch time as function of phone time: w(t) = offset + (1+drift)*t
                val t2 = offsetNanos + (1 + driftPpm) * t1 + watchToPhoneHalf + (random.nextDouble() - 0.5) * jitterNanos / 2
                val t3 = t2 + 100_000.0 + random.nextDouble() * 50_000
                val t4 = t1 + rtt
                ClockSync.Exchange(
                    t1PhoneNanos = t1,
                    t4PhoneNanos = t4.toLong(),
                    t2WatchNanos = t2.toLong(),
                    t3WatchNanos = t3.toLong()
                )
            }
        }
    }

    @Test
    fun recoversKnownOffsetAndDrift() {
        val scenario = Scenario(offsetNanos = 37.5e9, driftPpm = 16e-6)
        val fit = ClockSync.fit(scenario.exchanges(12))
        // The fit maps watchNanos = a + b*phoneNanos.
        val mapped = fit.watchFromPhone(100_000_000_000L)
        val expected = 37.5e9 + (1 + 16e-6) * 100_000_000_000L
        assertTrue(abs(mapped - expected) < 2_000_000, "Offset+drift mapping off by ${mapped - expected} ns")
        assertEquals(ClockSync.Quality.EXCELLENT, fit.quality)
    }

    @Test
    fun rejectsRttOutliers() {
        val scenario = Scenario(offsetNanos = 1e9, driftPpm = 0.0)
        val exchanges = scenario.exchanges(10).toMutableList()
        // Poison a few exchanges with huge RTT (device asleep / reconnection).
        exchanges[2] = exchanges[2].copy(t4PhoneNanos = exchanges[2].t4PhoneNanos + 500_000_000L)
        exchanges[7] = exchanges[7].copy(t4PhoneNanos = exchanges[7].t4PhoneNanos + 800_000_000L)
        val fit = ClockSync.fit(exchanges)
        assertEquals(2, fit.rttOutliersRejected)
        assertEquals(8, fit.exchangesUsed)
        // Offset should still be accurate to well under the injected noise.
        val mapped = fit.watchFromPhone(110_000_000_000L)
        val expected = 1e9 + 110_000_000_000L
        assertTrue(abs(mapped - expected) < 5_000_000, "Outlier rejection failed: off by ${mapped - expected} ns")
    }

    @Test
    fun driftOverSessionLengthIsCorrected() {
        // 10 ppm over a 15-minute session is 9 ms of accumulated error;
        // a fixed offset would be off by that much at the end.
        val drift = 10e-6
        val scenario = Scenario(offsetNanos = 2e9, driftPpm = drift)
        val exchanges = scenario.exchanges(20, spacingNanos = 45_000_000_000L) // 45 s apart, 15 min total
        val fit = ClockSync.fit(exchanges)
        val endPhone = 100_000_000_000L + 19 * 45_000_000_000L
        val expectedWatch = 2e9 + (1 + drift) * endPhone
        val mapped = fit.watchFromPhone(endPhone)
        assertTrue(abs(mapped - expectedWatch) < 1_000_000, "Drift correction off by ${mapped - expectedWatch} ns (fixed-offset would err ~9 ms)")
    }

    @Test
    fun mapsWatchTimestampsIntoPhoneTimeline() {
        val scenario = Scenario(offsetNanos = 5e9, driftPpm = 5e-6)
        val fit = ClockSync.fit(scenario.exchanges(10))
        val watchSampleTime = 200_000_000_000L
        val expectedPhone = (watchSampleTime - 5e9) / (1 + 5e-6)
        val actual = fit.phoneFromWatch(watchSampleTime)
        assertTrue(abs(actual - expectedPhone) < 1_000_000, "phoneFromWatch off by ${actual - expectedPhone} ns")
    }

    @Test
    fun qualityDegradesWithResiduals() {
        val noisy = Scenario(offsetNanos = 0.0, driftPpm = 0.0, jitterNanos = 60_000_000.0, baseRttNanos = 40_000_000.0)
        val fit = ClockSync.fit(noisy.exchanges(10))
        assertTrue(fit.residualRmsNanos > 5_000_000, "Expected POOR/GOOD with heavy jitter, residual=${fit.residualRmsNanos}")
        val noisyGrade = fit.quality
        assertTrue(noisyGrade == ClockSync.Quality.POOR || noisyGrade == ClockSync.Quality.GOOD)
    }

    @Test
    fun tooFewExchangesThrows() {
        val scenario = Scenario(offsetNanos = 0.0, driftPpm = 0.0)
        assertFailsWith<IllegalArgumentException> { ClockSync.fit(emptyList()) }
        assertFailsWith<IllegalArgumentException> { ClockSync.fit(scenario.exchanges(1)) }
    }

    @Test
    fun negativeRttRejected() {
        val scenario = Scenario(offsetNanos = 0.0, driftPpm = 0.0)
        val exchanges = scenario.exchanges(3)
        // Negative RTT means the watch dwell time (t3-t2) exceeded the phone
        // round trip (t4-t1): the exchange is unusable, not a valid sample.
        val broken = exchanges[0].copy(
            t3WatchNanos = exchanges[0].t2WatchNanos + 60_000_000L,
            t4PhoneNanos = exchanges[0].t1PhoneNanos + 10_000_000L
        )
        assertFailsWith<IllegalArgumentException> { ClockSync.fit(listOf(broken) + exchanges.drop(1)) }
    }

    @Test
    fun fitsFromMessageRoundTripExchanges() {
        // End-to-end: the phone sends pings, watch answers via the message
        // protocol, and the fit is computed from decoded messages only.
        val scenario = Scenario(offsetNanos = 12.5e9, driftPpm = 2e-6)
        val raw = scenario.exchanges(8)
        val messages = raw.map { e ->
            Messages.SyncPing("sess-1", e.t1PhoneNanos) to
                Messages.SyncPong("sess-1", e.t1PhoneNanos, e.t2WatchNanos, e.t3WatchNanos)
        }
        val exchanges = messages.mapIndexed { i, (ping, pong) ->
            val decodedPing = Messages.decode(Messages.encode(ping)) as Messages.SyncPing
            val decodedPong = Messages.decode(Messages.encode(pong)) as Messages.SyncPong
            ClockSync.Exchange(
                t1PhoneNanos = decodedPing.t1PhoneNanos,
                t4PhoneNanos = raw[i].t4PhoneNanos,
                t2WatchNanos = decodedPong.t2WatchNanos,
                t3WatchNanos = decodedPong.t3WatchNanos
            )
        }
        val fit = ClockSync.fit(exchanges)
        val expected = 12.5e9 + (1 + 2e-6) * 110_000_000_000L
        assertTrue(abs(fit.watchFromPhone(110_000_000_000L) - expected) < 5_000_000)
    }

    @Test
    fun constantZeroRttStillFits() {
        // Symmetric link, no jitter. The residual offset error is bounded by
        // half the watch's ping-dwell time (~150 us here) — the classic NTP
        // asymmetry limit, not a fit defect.
        val scenario = Scenario(offsetNanos = 3e9, driftPpm = 0.0, baseRttNanos = 1_000_000.0, jitterNanos = 0.0)
        val fit = ClockSync.fit(scenario.exchanges(5))
        assertTrue(abs(fit.watchFromPhone(100_000_000_000L) - (3e9 + 100_000_000_000L)) < 200_000)
    }
}
