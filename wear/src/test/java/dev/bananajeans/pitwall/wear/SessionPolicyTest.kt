package dev.bananajeans.pitwall.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Durable Start/Stop policy tests (issues #19/#20 regressions):
 *
 * - a replayed Start after a watch process restart must NEVER re-execute
 *   over an existing durable log (FileOutputStream would truncate it),
 * - a Stop handled by a fresh process must ACK deterministically from
 *   durable state instead of registering a callback that never fires,
 * - a crash orphan (RECORDING without a live writer) finalizes from disk
 *   and preserves every byte.
 */
class SessionPolicyTest {

    // ---- Start decisions ----------------------------------------------------

    @Test
    fun freshSessionWithNoDurableStateExecutes() {
        assertEquals(
            SessionPolicy.StartAction.EXECUTE,
            SessionPolicy.onStart("s1", liveSession = null, durableState = null)
        )
    }

    @Test
    fun duplicateStartWhileRecordingAcksRecordingTrue() {
        assertEquals(
            SessionPolicy.StartAction.ACK_RECORDING,
            SessionPolicy.onStart("s1", liveSession = "s1", durableState = null)
        )
    }

    @Test
    fun startWhileBusyWithOtherSessionAcksBusy() {
        assertEquals(
            SessionPolicy.StartAction.ACK_BUSY,
            SessionPolicy.onStart("s1", liveSession = "s2", durableState = null)
        )
    }

    @Test
    fun replayedStartAfterRestartWithDurableLogNeverExecutes() {
        // The P0 regression: process died, recover() preserved the log,
        // phone retried the same Start. Must ACK, never open a writer.
        for (state in WatchLogStore.State.values()) {
            assertEquals(
                "durable state $state must not re-execute",
                SessionPolicy.StartAction.ACK_RECORDING,
                SessionPolicy.onStart("s1", liveSession = null, durableState = state)
            )
        }
    }

    // ---- Stop decisions -----------------------------------------------------

    @Test
    fun stopWithLiveSessionExecutesLive() {
        val d = SessionPolicy.onStop("s1", liveSession = "s1", durable = null)
        assertEquals(SessionPolicy.StopAction.EXECUTE_LIVE, d.action)
    }

    @Test
    fun stopForUnknownSessionAcksFalseInsteadOfHanging() {
        val d = SessionPolicy.onStop("ghost", liveSession = null, durable = null)
        assertEquals(SessionPolicy.StopAction.ACK_DURABLE, d.action)
        assertFalse(d.ackFinalized)
    }

    @Test
    fun stopWhileBusyWithOtherSessionAcksFalse() {
        val d = SessionPolicy.onStop("s1", liveSession = "s2", durable = null)
        assertEquals(SessionPolicy.StopAction.ACK_DURABLE, d.action)
        assertFalse(d.ackFinalized)
    }

    @Test
    fun stopOfAlreadyFinalizedCompleteLogAcksTrue() {
        val d = SessionPolicy.onStop(
            "s1", liveSession = null,
            durable = WatchLogStore.Entry("s1", WatchLogStore.State.FINALIZED, true, 100L, 0L)
        )
        assertEquals(SessionPolicy.StopAction.ACK_DURABLE, d.action)
        assertTrue(d.ackFinalized)
    }

    @Test
    fun stopOfAlreadyFinalizedIncompleteLogAcksFalse() {
        val d = SessionPolicy.onStop(
            "s1", liveSession = null,
            durable = WatchLogStore.Entry("s1", WatchLogStore.State.FINALIZED, false, 100L, 0L)
        )
        assertEquals(SessionPolicy.StopAction.ACK_DURABLE, d.action)
        assertFalse(d.ackFinalized)
    }

    @Test
    fun stopOfCrashOrphanFinalizesFromDisk() {
        val d = SessionPolicy.onStop(
            "s1", liveSession = null,
            durable = WatchLogStore.Entry("s1", WatchLogStore.State.RECORDING, false, 100L, 0L)
        )
        assertEquals(SessionPolicy.StopAction.FINALIZE_ORPHAN, d.action)
    }

    // ---- logLooksComplete ----------------------------------------------------

    @Test
    fun logLooksCompleteDetectsTrailerAndTruncation() {
        val tmp = File.createTempFile("pwtch", ".bin")
        try {
            // Data + trailer magic ending exactly at EOF -> complete.
            tmp.writeBytes(byteArrayOf(1, 2, 3) + "PWEND".toByteArray(Charsets.US_ASCII))
            assertTrue(SessionPolicy.logLooksComplete(tmp))

            // One byte short of the trailer -> truncated.
            val truncated = tmp.readBytes().dropLast(1).toByteArray()
            tmp.writeBytes(truncated)
            assertFalse(SessionPolicy.logLooksComplete(tmp))

            // Too short to hold a trailer.
            tmp.writeBytes(byteArrayOf(1))
            assertFalse(SessionPolicy.logLooksComplete(tmp))
        } finally {
            tmp.delete()
        }
    }
}
