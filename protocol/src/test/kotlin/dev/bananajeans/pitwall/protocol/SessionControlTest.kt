package dev.bananajeans.pitwall.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SessionControlTest {

    @Test
    fun startProducesMessageAndTransition() {
        val control = SessionControl()
        val message = control.start("s1", "Track", "Normal")
        assertNotNull(message)
        assertEquals("s1", message!!.sessionId)
        assertEquals(1, message.startSeq)
        assertEquals(SessionControl.CommandState.PENDING_START, control.state)
    }

    @Test
    fun duplicateStartIsIdempotent() {
        val control = SessionControl()
        assertNotNull(control.start("s1", "Track", "Normal"))
        assertNull(control.start("s1", "Track", "Normal"))
        assertEquals(1, control.startSeq)
    }

    @Test
    fun secondSessionRejectedWhileFirstActive() {
        val control = SessionControl()
        control.start("s1", "Track", "Normal")
        control.onStartAck(Messages.StartAck("s1", true, "0.3.0", 1))
        assertEquals(SessionControl.CommandState.RECORDING, control.state)
        assertFailsWith<IllegalStateException> { control.start("s2", "Other", "Normal") }
    }

    @Test
    fun ackCompletesPendingStart() {
        val control = SessionControl()
        control.start("s1", "Track", "Normal")
        control.onStartAck(Messages.StartAck("s1", true, "0.3.0", 1))
        assertEquals(SessionControl.CommandState.RECORDING, control.state)
        // Duplicate acks are ignored.
        control.onStartAck(Messages.StartAck("s1", true, "0.3.0", 1))
        assertEquals(SessionControl.CommandState.RECORDING, control.state)
    }

    @Test
    fun foreignAckIgnored() {
        val control = SessionControl()
        control.start("s1", "Track", "Normal")
        control.onStartAck(Messages.StartAck("other", true, "0.3.0", 1))
        assertEquals(SessionControl.CommandState.PENDING_START, control.state)
    }

    @Test
    fun stopLifecycleWithAcks() {
        val control = SessionControl()
        control.start("s1", "Track", "Normal")
        control.onStartAck(Messages.StartAck("s1", true, "0.3.0", 1))
        val stop = control.stop()
        assertNotNull(stop)
        assertEquals("s1", stop!!.sessionId)
        assertEquals(1, stop.stopSeq)
        assertEquals(SessionControl.CommandState.PENDING_STOP, control.state)
        assertNull(control.stop()) // idempotent
        control.onStopAck(Messages.StopAck("s1", true, "s1", 1))
        assertEquals(SessionControl.CommandState.STOPPED, control.state)
    }

    @Test
    fun stopWithoutStartIsNull() {
        assertNull(SessionControl().stop())
    }

    @Test
    fun retryResendsSameStartMessage() {
        val control = SessionControl()
        control.start("s1", "Track", "Normal")
        val first = control.startMessage()
        val second = control.startMessage()
        assertEquals(first, second)
        assertEquals(1, first!!.startSeq)
    }

    @Test
    fun resetClearsEverything() {
        val control = SessionControl()
        control.start("s1", "Track", "Normal")
        control.reset()
        assertEquals(SessionControl.CommandState.IDLE, control.state)
        assertNull(control.sessionId)
        assertNotNull(control.start("s1", "Track", "Normal"))
    }

    @Test
    fun watchControlSuppressesDuplicates() {
        val watch = WatchSessionControl()
        assertTrue(watch.shouldStart("s1", 1))
        assertTrue(!watch.shouldStart("s1", 1), "duplicate start must be suppressed")
        assertTrue(!watch.shouldStart("s1", 0), "older seq must be suppressed")
        assertTrue(watch.shouldStart("s1", 2), "higher seq is a new command")
        assertTrue(watch.shouldStop("s1", 1))
        assertTrue(!watch.shouldStop("s1", 1))
    }

    @Test
    fun watchControlRejectsReorderedOldPackets() {
        val watch = WatchSessionControl()
        // Handle seq=2 first (delayed seq=1 arrives later)
        assertTrue(watch.shouldStart("s1", 2))
        // Now delayed seq=1 arrives - should be suppressed
        assertTrue(!watch.shouldStart("s1", 1), "reordered old packet must be suppressed")
        // But a duplicate of the max seq should also be suppressed
        assertTrue(!watch.shouldStart("s1", 2))
        // New higher seq should execute
        assertTrue(watch.shouldStart("s1", 3))
    }

    @Test
    fun phoneControlStartAckRecordingFalseStaysPending() {
        val control = SessionControl()
        control.start("s1", "Track", "Normal")
        // Watch explicitly sends recording=false (startup failed)
        control.onStartAck(Messages.StartAck("s1", false, "0.3.0", 1))
        // Should NOT transition to RECORDING
        assertEquals(SessionControl.CommandState.PENDING_START, control.state)
        // Retry should still work
        val retry = control.startMessage()
        assertNotNull(retry)
        assertEquals(1, retry!!.startSeq)
    }

    @Test
    fun phoneControlStopAckFinalizedFalseStaysPending() {
        val control = SessionControl()
        control.start("s1", "Track", "Normal")
        control.onStartAck(Messages.StartAck("s1", true, "0.3.0", 1))
        control.stop()
        // Watch sends finalized=false (fsync/finalization failed)
        control.onStopAck(Messages.StopAck("s1", false, "s1", 1))
        // Should NOT transition to STOPPED
        assertEquals(SessionControl.CommandState.PENDING_STOP, control.state)
        // Retry should still work - use stopMessage() to get the pending stop
        val retry = control.stopMessage()
        assertNotNull(retry)
        assertEquals(1, retry!!.stopSeq)
    }

    @Test
    fun watchControlCachesAndReplaysStartResult() {
        val watch = WatchSessionControl()
        // First execution
        assertTrue(watch.shouldStart("s1", 1))
        // Simulate completion
        watch.onStartCompleted("s1", 1, true, "1.0", 1)
        // Duplicate should return cached result
        val result = watch.getStartResult("s1", 1)
        assertNotNull(result)
        assertTrue(result.recording)
        // Second duplicate call
        assertTrue(!watch.shouldStart("s1", 1))
        val result2 = watch.getStartResult("s1", 1)
        assertNotNull(result2)
        assertTrue(result2.recording)
    }

    @Test
    fun watchControlCachesAndReplaysStartFailure() {
        val watch = WatchSessionControl()
        assertTrue(watch.shouldStart("s1", 1))
        watch.onStartCompleted("s1", 1, false, "1.0", 1)
        val result = watch.getStartResult("s1", 1)
        assertNotNull(result)
        assertFalse(result.recording)
        // Duplicate should replay failure
        assertTrue(!watch.shouldStart("s1", 1))
        val result2 = watch.getStartResult("s1", 1)
        assertNotNull(result2)
        assertFalse(result2.recording)
    }

    @Test
    fun watchControlCachesAndReplaysStopResult() {
        val watch = WatchSessionControl()
        assertTrue(watch.shouldStop("s1", 1))
        watch.onStopCompleted("s1", 1, true)
        val result = watch.getStopResult("s1", 1)
        assertNotNull(result)
        assertTrue(result.finalized)
        // Duplicate should replay success
        assertTrue(!watch.shouldStop("s1", 1))
        val result2 = watch.getStopResult("s1", 1)
        assertNotNull(result2)
        assertTrue(result2.finalized)
    }

    @Test
    fun watchControlReplaysStopFailure() {
        val watch = WatchSessionControl()
        assertTrue(watch.shouldStop("s1", 1))
        watch.onStopCompleted("s1", 1, false)
        val result = watch.getStopResult("s1", 1)
        assertNotNull(result)
        assertFalse(result.finalized)
        // Duplicate should replay failure
        assertTrue(!watch.shouldStop("s1", 1))
        val result2 = watch.getStopResult("s1", 1)
        assertNotNull(result2)
        assertFalse(result2.finalized)
    }

    @Test
    fun watchControlDoesNotAckWhileInProgress() {
        val watch = WatchSessionControl()
        assertTrue(watch.shouldStart("s1", 1))
        // Not completed yet
        assertTrue(watch.isStartInProgress("s1", 1))
        assertNull(watch.getStartResult("s1", 1))
        // Completion
        watch.onStartCompleted("s1", 1, true, "1.0", 1)
        assertFalse(watch.isStartInProgress("s1", 1))
        assertNotNull(watch.getStartResult("s1", 1))
    }

    @Test
    fun watchControlDoesNotAckStopWhileInProgress() {
        val watch = WatchSessionControl()
        assertTrue(watch.shouldStop("s1", 1))
        // Not completed yet
        assertTrue(watch.isStopInProgress("s1", 1))
        assertNull(watch.getStopResult("s1", 1))
        // Completion
        watch.onStopCompleted("s1", 1, true)
        assertFalse(watch.isStopInProgress("s1", 1))
        assertNotNull(watch.getStopResult("s1", 1))
    }

    @Test
    fun watchControlMaxSeqPreventsRollback() {
        val watch = WatchSessionControl()
        // Handle seq=2 first
        assertTrue(watch.shouldStart("s1", 2))
        // Delayed seq=1 arrives - should be suppressed, max stays 2
        assertTrue(!watch.shouldStart("s1", 1))
        // Duplicate seq=2 - suppressed
        assertTrue(!watch.shouldStart("s1", 2))
        // New higher seq=3 - executes
        assertTrue(watch.shouldStart("s1", 3))
    }

    @Test
    fun watchControlStopMaxSeqPreventsRollback() {
        val watch = WatchSessionControl()
        assertTrue(watch.shouldStop("s1", 2))
        assertTrue(!watch.shouldStop("s1", 1))
        assertTrue(!watch.shouldStop("s1", 2))
        assertTrue(watch.shouldStop("s1", 3))
    }
}
