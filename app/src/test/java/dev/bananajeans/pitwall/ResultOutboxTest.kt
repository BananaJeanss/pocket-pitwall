package dev.bananajeans.pitwall

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bananajeans.pitwall.protocol.Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Durable result delivery tests (issue #25 P1): a result summary survives a
 * phone process restart via the outbox, replays are idempotent, and clearing
 * removes exactly one session's pending copy.
 */
@RunWith(RobolectricTestRunner::class)
class ResultOutboxTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ResultOutbox.clearAll(context)
    }

    private fun result(sid: String, laps: Int = 3, timestamp: Long = 1730000000000L) = Messages.Result(
        sessionId = sid,
        bestLapSeconds = 61.2,
        lapCount = laps,
        steeringSmoothness = 0.82,
        correctionCount = 4,
        peakHr = 168,
        averageHr = 142,
        watchDataQuality = "ok",
        notes = null,
        timestamp = timestamp
    )

    @Test
    fun resultSurvivesProcessRestartScenario() {
        // Phone saves the result durably before/regardless of send success.
        ResultOutbox.save(context, result("sess-r1"))

        // Simulate process death + restart: a fresh read of disk must
        // return the complete, uncorrupted message.
        val restored = ResultOutbox.pending(context)
        assertEquals(1, restored.size)
        assertEquals("sess-r1", restored.first().sessionId)
        assertEquals(61.2, restored.first().bestLapSeconds!!, 1e-9)
        assertEquals(168, restored.first().peakHr)
        assertEquals(1730000000000L, restored.first().timestamp)
    }

    @Test
    fun delayedReplayedResultPreservesOrderingByTimestamp() {
        // Out-of-order delivery: an older session's result arrives late
        // (e.g. after reconnect). The timestamp written at send time is the
        // immutable phone session creation wall clock, so the watch can sort
        // correctly regardless of arrival order.
        val newer = result("sess-new", timestamp = 1730000100000L)
        val older = result("sess-old", timestamp = 1730000000000L)
        ResultOutbox.save(context, newer)
        ResultOutbox.save(context, older)

        val restored = ResultOutbox.pending(context) // sorted newest first
        assertEquals(listOf("sess-new", "sess-old"), restored.map { it.sessionId })
        assertTrue(restored.first().timestamp!! > restored.last().timestamp!!)
        // No timestamp fell back to receipt time: both keep their session
        // creation wall clocks.
        assertFalse(restored.any { it.timestamp == null })
    }

    @Test
    fun replayIsIdempotent() {
        val r = result("sess-idem")
        ResultOutbox.save(context, r)
        ResultOutbox.save(context, r)
        ResultOutbox.save(context, r)
        assertEquals(1, ResultOutbox.pending(context).size)
    }

    @Test
    fun clearRemovesOnlyOneSession() {
        ResultOutbox.save(context, result("sess-a"))
        ResultOutbox.save(context, result("sess-b"))
        ResultOutbox.clear(context, "sess-a")
        val remaining = ResultOutbox.pending(context)
        assertEquals(listOf("sess-b"), remaining.map { it.sessionId })
    }

    @Test
    fun invalidSessionIdIsIgnored() {
        ResultOutbox.save(context, result("../evil"))
        assertEquals(0, ResultOutbox.pending(context).size)
        ResultOutbox.clear(context, "../evil") // must not throw
    }
}
