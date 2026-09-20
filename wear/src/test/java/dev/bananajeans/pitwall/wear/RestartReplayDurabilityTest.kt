package dev.bananajeans.pitwall.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bananajeans.pitwall.protocol.WatchLogCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Restart + replay regression (issues #19/#20): after a watch process
 * death, recover() preserves the log, and the phone's replayed Start and
 * Stop must be answered from durable state with the log preserved
 * BYTE-FOR-BYTE.
 */
@RunWith(RobolectricTestRunner::class)
class RestartReplayDurabilityTest {

    private lateinit var store: WatchLogStore
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = WatchLogStore(context)
    }

    private fun metadata(sessionId: String) = WatchLogCodec.Metadata(
        sessionId = sessionId,
        watchAppVersion = "0.3.0",
        deviceModel = "TestWatch",
        startedAtWallMillis = 1730000000000L,
        startedAtMonotonicNanos = 10_000_000_000L,
        sensorInfo = emptyList(),
        protocolVersion = 1
    )

    private fun recordedBytes(sessionId: String): ByteArray {
        store.startRecording(sessionId)
        val (writer, stream) = store.writer(sessionId, metadata(sessionId))
        writer.appendSamples(4, listOf(
            WatchLogCodec.Sample(4, 10_000_000_100L, 0.1, -9.8, 0.5, 0.0, 3)
        ))
        writer.close() // crash: no trailer
        stream.close()
        return store.logFile(sessionId).readBytes()
    }

    @Test
    fun replayedStartAfterRestartPreservesRecoveredLogByteForByte() {
        val before = recordedBytes("sess-restart")

        // Watch process restart: recover() converts the orphan.
        store.recover()
        val afterRecover = store.logFile("sess-restart").readBytes()
        assertEquals("recover() must preserve every recorded byte",
            before.toList(), afterRecover.toList())
        assertTrue(store.pendingTransfer().any { it.sessionId == "sess-restart" })

        // Phone retries the same Start into a FRESH connection layer
        // (empty in-memory control state, like after process death).
        val freshControl = dev.bananajeans.pitwall.protocol.WatchSessionControl()
        val live = RecorderService.status.takeIf { it.recording }?.sessionId
        val durable = store.list().firstOrNull { it.sessionId == "sess-restart" }
        val action = SessionPolicy.onStart("sess-restart", live, durable?.state)
        assertEquals(SessionPolicy.StartAction.ACK_RECORDING, action)

        // Prove the failure mode is actually closed: had the old path
        // re-executed, the writer would truncate the file. The policy
        // never reaches the store here, so bytes must be untouched.
        assertEquals(before.toList(), store.logFile("sess-restart").readBytes().toList())

        // The ACK must carry recording=true so the phone accepts replay.
        freshControl.onStartCompleted("sess-restart", 1, true, "x", 1)
        assertTrue(freshControl.getStartResult("sess-restart", 1)?.recording == true)
    }

    @Test
    fun stopOfOrphanAfterRestartFinalizesFromDiskWithoutByteLoss() {
        val before = recordedBytes("sess-stop")

        // Stop racing startup (before recover() runs): the durable entry is
        // still RECORDING, so the policy must finalize from disk.
        val live = RecorderService.status.takeIf { it.recording }?.sessionId
        val durable = store.list().firstOrNull { it.sessionId == "sess-stop" }
        val decision = SessionPolicy.onStop("sess-stop", live, durable)
        assertEquals(SessionPolicy.StopAction.FINALIZE_ORPHAN, decision.action)

        // Execute the orphan path exactly like WearConnection does: finalize
        // from disk on the executor, preserving the prefix bytes.
        val complete = SessionPolicy.logLooksComplete(store.logFile("sess-stop"))
        store.markFinalized("sess-stop", complete)
        assertFalse("orphan has no trailer", complete)

        val after = store.logFile("sess-stop").readBytes()
        assertEquals("finalize-from-disk must not append or truncate",
            before.toList(), after.toList())

        // And the sidecar lets the phone distinguish this from transport
        // truncation (issue #21 groundwork).
        val meta = requireNotNull(store.sourceMeta("sess-stop"))
        assertFalse(meta.complete)
        assertEquals(before.size.toLong(), meta.expectedBytes)
        assertEquals(WatchLogCodec.SourceMeta.sha256Hex(store.logFile("sess-stop")), meta.sha256)
    }

    @Test
    fun stopAfterRecoverConvertedOrphanAcksFalseFromDurableState() {
        val before = recordedBytes("sess-rec")
        store.recover()

        val live = RecorderService.status.takeIf { it.recording }?.sessionId
        val durable = store.list().firstOrNull { it.sessionId == "sess-rec" }
        val decision = SessionPolicy.onStop("sess-rec", live, durable)
        assertEquals(SessionPolicy.StopAction.ACK_DURABLE, decision.action)
        assertFalse("recovered log is incomplete", decision.ackFinalized)
        assertEquals(before.toList(), store.logFile("sess-rec").readBytes().toList())
    }

    @Test
    fun stopOfFinalizedLogAcksFromDurableState() {
        // Finalized complete log: a replayed Stop ACKs true without touching
        // the recorder or the bytes.
        val log = store.logFile("sess-done")
        log.parentFile!!.mkdirs()
        val out = ByteArrayOutputStream()
        val writer = WatchLogCodec.Writer(out, metadata("sess-done"))
        writer.appendSamples(4, listOf(
            WatchLogCodec.Sample(4, 10_000_000_100L, 0.0, 0.0, 0.0, 0.0, 3)
        ))
        writer.finish()
        log.writeBytes(out.toByteArray())
        store.markFinalized("sess-done", complete = true)
        val bytes = log.readBytes()

        val decision = SessionPolicy.onStop(
            "sess-done", liveSession = null,
            durable = store.list().first { it.sessionId == "sess-done" }
        )
        assertEquals(SessionPolicy.StopAction.ACK_DURABLE, decision.action)
        assertTrue(decision.ackFinalized)
        assertEquals(bytes.toList(), log.readBytes().toList())
    }
}
