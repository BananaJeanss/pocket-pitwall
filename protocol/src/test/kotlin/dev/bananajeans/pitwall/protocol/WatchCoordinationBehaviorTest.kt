package dev.bananajeans.pitwall.protocol

import dev.bananajeans.pitwall.protocol.ClockSync
import dev.bananajeans.pitwall.protocol.Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol-level tests for coordination behavior that the watch and phone
 * services rely on (issue #19/#20 acceptance): duplicate suppression across
 * restart boundaries, ack handling, and message round-trips used by the
 * Data Layer transport.
 */
class WatchCoordinationBehaviorTest {

    @Test
    fun startMessageRoundTripCarriesAllFields() {
        val original = Messages.Start("sess-42", "Motorcity", "Reverse", 1730000000000L, 3)
        val decoded = Messages.decode(Messages.encode(original)) as Messages.Start
        assertEquals(original, decoded)
    }

    @Test
    fun watchSuppressionSurvivesRepeatedRetries() {
        val control = WatchSessionControl()
        // Phone retries the same start 5 times (flaky link).
        val acks = (1..5).count { control.shouldStart("s1", 1) }
        check(acks == 1) { "exactly one start action per (sid, seq), got $acks" }
    }

    @Test
    fun watchStopWithoutStartIsStillAcked() {
        val control = WatchSessionControl()
        // A stop for a session the watch never started must still be accepted
        // (idempotent finalize = no-op) and acked once.
        assertTrue(control.shouldStop("ghost", 1))
        assertFalse(control.shouldStop("ghost", 1))
    }

    @Test
    fun syncPongRoundTripPreservesTimestamps() {
        val pong = Messages.SyncPong("s1", 111L, 222_000_000_000L, 222_000_150_000L)
        val decoded = Messages.decode(Messages.encode(pong)) as Messages.SyncPong
        assertEquals(pong, decoded)
    }

    @Test
    fun fitFromTypicalSessionIsUsable() {
        // Simulate 6 exchanges with 10 ms RTT, ±1 ms jitter, 2 s offset.
        var rng = 12345L
        fun next(): Double { rng = rng * 6364136223846793005L + 1442695040888963407L; return ((rng shr 33) and 0xFFFF) / 65536.0 }
        val exchanges = (0 until 6).map { i ->
            val t1 = 100_000_000_000L + i * 3_000_000_000L
            val rtt = 10_000_000.0 + (next() - 0.5) * 2_000_000.0
            val t2 = (2e9 + t1 + rtt / 2).toLong()
            val t3 = t2 + 120_000L
            ClockSync.Exchange(t1, t1 + rtt.toLong(), t2, t3)
        }
        val fit = ClockSync.fit(exchanges)
        assertTrue(fit.exchangesUsed >= 4)
        assertTrue(fit.quality == ClockSync.Quality.EXCELLENT || fit.quality == ClockSync.Quality.GOOD)
        // Mapping a watch sample lands within 5 ms of truth.
        val phone = fit.phoneFromWatch(2e9.toLong() + 105_000_000_000L)
        assertTrue(Math.abs(phone - 105_000_000_000L) < 5_000_000)
    }

    @Test
    fun transferAckRoundTrip() {
        val ack = Messages.TransferAck("s1", "s1", true)
        assertEquals(ack, Messages.decode(Messages.encode(ack)))
        val nack = Messages.TransferAck("s1", "s1", false, "crc mismatch")
        assertEquals(nack, Messages.decode(Messages.encode(nack)))
    }
}
