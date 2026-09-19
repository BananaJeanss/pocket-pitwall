package dev.bananajeans.pitwall.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun watchControlBoundsMemory() {
        val watch = WatchSessionControl()
        for (i in 0 until 100) watch.shouldStart("session-$i", 1)
        // Internal maps are capped; a fresh-but-old session still starts once.
        assertTrue(watch.shouldStart("session-0", 1))
        assertTrue(!watch.shouldStart("session-99", 1))
    }
}
