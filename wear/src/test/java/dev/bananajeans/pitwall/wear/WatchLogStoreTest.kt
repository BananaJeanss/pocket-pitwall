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
import java.io.File

/**
 * WatchLogStore lifecycle tests (issue #18/#21 acceptance): durable states,
 * crash recovery, retention cleanup and idempotent finalization.
 */
@RunWith(RobolectricTestRunner::class)
class WatchLogStoreTest {

    private lateinit var store: WatchLogStore
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric gives each test a fresh filesDir.
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

    private fun writeSampleLog(sessionId: String, finalize: Boolean, samples: Int = 5): File {
        store.startRecording(sessionId)
        val (writer, stream) = store.writer(sessionId, metadata(sessionId))
        for (i in 0 until samples) {
            writer.appendSamples(4, listOf(
                WatchLogCodec.Sample(4, 10_000_000_000L + i * 5_000_000L, 0.1 * i, -9.8, 0.5, 0.0, 3)
            ))
        }
        if (finalize) writer.finish() else writer.close()
        stream.close()
        store.markFinalized(sessionId, finalize)
        return store.logFile(sessionId)
    }

    @Test
    fun lifecycleRecordingToImported() {
        writeSampleLog("sess-a", finalize = true)
        assertEquals(WatchLogStore.State.FINALIZED, store.list().first().state)
        assertTrue(store.pendingTransfer().any { it.sessionId == "sess-a" })

        store.markImported("sess-a")
        assertEquals(WatchLogStore.State.IMPORTED, store.list().first().state)
        assertTrue(store.pendingTransfer().none { it.sessionId == "sess-a" })
    }

    @Test
    fun recoverConvertsOrphanedRecordingToFinalizedIncomplete() {
        store.startRecording("sess-b")
        val (writer, stream) = store.writer("sess-b", metadata("sess-b"))
        writer.appendSamples(4, listOf(WatchLogCodec.Sample(4, 10_000_000_100L, 0.0, 0.0, 0.0, 0.0, 3)))
        writer.close() // crash: no trailer
        stream.close()

        val result = store.recover()
        assertEquals(1, result.recoveredUnfinalized)
        val entry = store.list().first { it.sessionId == "sess-b" }
        assertEquals(WatchLogStore.State.FINALIZED, entry.state)
        // And it's now queued for transfer, not lost.
        assertTrue(store.pendingTransfer().any { it.sessionId == "sess-b" })
        // Reading it yields an INCOMPLETE log with the surviving samples.
        val read = WatchLogCodec.read(store.logFile("sess-b").inputStream())
        assertTrue(read is WatchLogCodec.ReadResult.Incomplete)
        assertEquals(1, (read as WatchLogCodec.ReadResult.Incomplete).log.samples.size)
    }

    @Test
    fun markImportedRejectsUnknownSessions() {
        val failed = runCatching { store.markImported("never-started") }
        assertTrue(failed.isFailure)
    }

    @Test
    fun finalizationIsIdempotentAndNeverDowngrades() {
        writeSampleLog("sess-c", finalize = true)
        store.markImported("sess-c")
        // A late duplicate finalize (e.g. retried stop message) must not
        // downgrade IMPORTED back to FINALIZED or re-queue the log.
        store.markFinalized("sess-c", complete = true)
        assertEquals(WatchLogStore.State.IMPORTED, store.list().first().state)
        assertTrue(store.pendingTransfer().none { it.sessionId == "sess-c" })
    }

    @Test
    fun invalidSessionIdsRejected() {
        val failed = runCatching { store.folder("../escape") }
        assertTrue(failed.isFailure)
    }

    @Test
    fun importedLogsPastRetentionAreCleaned() {
        writeSampleLog("sess-old", finalize = true)
        store.markImported("sess-old")
        // Backdate the log beyond the retention window.
        store.logFile("sess-old").setLastModified(System.currentTimeMillis() - 8 * 24 * 60 * 60 * 1000L)
        val result = store.recover()
        assertEquals(1, result.cleanedImported)
        assertTrue(store.list().none { it.sessionId == "sess-old" })
    }

    @Test
    fun importedLogsInsideRetentionAreKept() {
        writeSampleLog("sess-fresh", finalize = true)
        store.markImported("sess-fresh")
        val result = store.recover()
        assertEquals(0, result.cleanedImported)
        assertTrue(store.list().any { it.sessionId == "sess-fresh" })
    }

    @Test
    fun completeAndIncompleteLogsAreDistinguishable() {
        writeSampleLog("sess-complete", finalize = true)
        writeSampleLog("sess-crash", finalize = false)
        val entries = store.list().associateBy { it.sessionId }
        assertTrue(entries.getValue("sess-complete").finalizedComplete)
        assertFalse(entries.getValue("sess-crash").finalizedComplete)
    }
}
