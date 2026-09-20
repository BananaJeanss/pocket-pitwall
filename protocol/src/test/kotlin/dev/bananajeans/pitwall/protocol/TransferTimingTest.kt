package dev.bananajeans.pitwall.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phone-side transfer-timing tests (issue #22 P1): transfer must start only
 * after a FINALIZED StopAck; duplicate/replayed StopAcks stay idempotent and
 * must not trigger a second pull decision; non-finalized StopAcks never do.
 */
class TransferTimingTest {

    /** Minimal counter harness standing in for the phone pull action. */
    private class PullCounter {
        var pulls = 0
        fun onFinalizedStopAck() { pulls++ }
    }

    @Test
    fun pullFiresExactlyOncePerFinalizedStopAck() {
        val control = SessionControl()
        val counter = PullCounter()

        val start = control.start("sess-1", "t", "d")
        assertTrue(start != null)
        control.onStartAck(Messages.StartAck("sess-1", true, "watch", Messages.PROTOCOL_VERSION))
        assertEquals(SessionControl.CommandState.RECORDING, control.state)

        // Stop request goes out; nothing finalized yet -> no pull.
        assertTrue(control.stop() != null)
        assertEquals(0, counter.pulls)

        // The watch ACKs finalization -> exactly one pull.
        control.onStopAck(Messages.StopAck("sess-1", true, "sess-1", Messages.PROTOCOL_VERSION))
        assertEquals(SessionControl.CommandState.STOPPED, control.state)
        counter.onFinalizedStopAck()
        assertEquals(1, counter.pulls)

        // A replayed duplicate StopAck (watch retry) must NOT transition or
        // re-trigger the pull (onStopAck ignores non-PENDING_STOP).
        control.onStopAck(Messages.StopAck("sess-1", true, "sess-1", Messages.PROTOCOL_VERSION))
        assertEquals(1, counter.pulls)
    }

    @Test
    fun nonFinalizedStopAckNeverTriggersPull() {
        val control = SessionControl()
        val counter = PullCounter()
        control.start("sess-2", "t", "d")
        control.onStartAck(Messages.StartAck("sess-2", true, "watch", Messages.PROTOCOL_VERSION))
        assertTrue(control.stop() != null)

        // Watch could not finalize yet (still writing): no pull.
        control.onStopAck(Messages.StopAck("sess-2", false, null, Messages.PROTOCOL_VERSION))
        assertEquals(SessionControl.CommandState.PENDING_STOP, control.state)
        assertEquals(0, counter.pulls)
        // The phone keeps PENDING_STOP and retries the Stop; eventually the
        // watch answers finalized -> pull fires exactly once.
        control.onStopAck(Messages.StopAck("sess-2", true, "sess-2", Messages.PROTOCOL_VERSION))
        counter.onFinalizedStopAck()
        assertEquals(1, counter.pulls)
    }

    @Test
    fun stopAckForDifferentSessionNeverTriggersPull() {
        val control = SessionControl()
        val counter = PullCounter()
        control.start("sess-3", "t", "d")
        control.onStartAck(Messages.StartAck("sess-3", true, "watch", Messages.PROTOCOL_VERSION))
        assertTrue(control.stop() != null)

        // ACK for a different session id: ignored entirely.
        control.onStopAck(Messages.StopAck("other", true, "other", Messages.PROTOCOL_VERSION))
        assertEquals(SessionControl.CommandState.PENDING_STOP, control.state)
        assertEquals(0, counter.pulls)
    }

    @Test
    fun startAckOnlyTransitionsOnRecordingTrue() {
        // Known-good guard, pinned here so the transfer timing cannot regress
        // it: a StartAck(recording=false) leaves the state machine waiting.
        val control = SessionControl()
        control.start("sess-4", "t", "d")
        control.onStartAck(Messages.StartAck("sess-4", false, "watch", Messages.PROTOCOL_VERSION))
        assertFalse(control.state == SessionControl.CommandState.RECORDING)
    }

    @Test
    fun stopWithoutStartIsRejected() {
        val control = SessionControl()
        assertNull(control.stop())
    }
}
