package dev.bananajeans.pitwall.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionRetryTest {
    @Test fun stopCancelsUnacknowledgedStartRetries() {
        val control = SessionControl()
        val start = requireNotNull(control.start("drive-1", "Kart", "Normal"))
        assertTrue(control.shouldRetry(start))
        val stop = requireNotNull(control.stop())
        assertFalse(control.shouldRetry(start))
        assertTrue(control.shouldRetry(stop))
    }

    @Test fun newSessionCancelsOlderStopRetries() {
        val control = SessionControl()
        control.start("drive-1", "Kart", "Normal")
        val oldStop = requireNotNull(control.stop())
        val newStart = requireNotNull(control.start("drive-2", "Kart", "Normal"))
        assertFalse(control.shouldRetry(oldStop))
        assertTrue(control.shouldRetry(newStart))
        control.onStartAck(Messages.StartAck("drive-2", true, "0.3.0", 1))
        assertFalse(control.shouldRetry(newStart))
    }
}
