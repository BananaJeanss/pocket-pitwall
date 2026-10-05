package dev.bananajeans.pitwall

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bananajeans.pitwall.protocol.WatchLogCodec
import dev.bananajeans.pitwall.protocol.WatchLogImporter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchOnlyImportTest {
    private lateinit var context: Context
    private lateinit var store: SessionStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        store = SessionStore(context)
    }

    private fun imported(id: String): WatchLogImporter.Result.Imported {
        val file = WatchDataStore.dir(context).resolve("$id.pwtch")
        file.outputStream().use { output ->
            val writer = WatchLogCodec.Writer(output, WatchLogCodec.Metadata(
                id, "0.3.0", "TestWatch", 1000, 10_000_000_000, emptyList(), 1
            ))
            writer.appendSamples(1, listOf(WatchLogCodec.Sample(1, 10_000_000_000, 0.0, 0.0, 9.8, 0.0, 3)))
            writer.appendSamples(4, listOf(
                WatchLogCodec.Sample(4, 10_000_000_000, 0.1, 0.0, 0.0, 0.0, 3),
                WatchLogCodec.Sample(4, 12_000_000_000, 0.2, 0.0, 0.0, 0.0, 3)
            ))
            writer.finish()
        }
        val log = file.inputStream().use { (WatchLogCodec.read(it) as WatchLogCodec.ReadResult.Complete).log }
        return WatchLogImporter.Result.Imported(log, file)
    }

    @Test fun watchOnlyRecordingAppearsInSessionsWithReviewableAndExportableData() {
        val result = imported("standalone-1")
        WatchTransferManager(context).attachToSession("standalone-1", result)
        val session = store.list().single { it.id == "standalone-1" }
        assertEquals("complete", session.status)
        assertEquals(2.0, session.duration, 0.001)
        assertEquals(0L, session.phoneStartElapsedNanos)
        assertNull(session.watch!!.sync)
        assertTrue(session.watch!!.logComplete)
        assertEquals(2, store.trace(session.id).rotation.size)
        assertEquals(1, store.trace(session.id).acceleration.size)
        assertTrue(store.raw(session.id).readText().contains("2.0,4,"))
        val zip = java.io.ByteArrayOutputStream()
        store.writeZip(session, zip)
        assertTrue(zip.size() > 0)
        WatchTransferManager(context).attachToSession("standalone-1", result)
        assertEquals(1, store.list().size)
    }

    @Test fun coordinatedImportPreservesPhoneSensorDataAndTiming() {
        val session = Session(id = "phone-1", created = 1000, status = "complete", duration = 5.0,
            phoneStartElapsedNanos = 99_000_000_000, notes = "Driver notes")
        store.save(session)
        store.raw(session.id).writeText("original phone samples")
        WatchTransferManager(context).attachToSession(session.id, imported(session.id))
        val loaded = store.list().single()
        assertEquals("original phone samples", store.raw(session.id).readText())
        assertEquals("Driver notes", loaded.notes)
        assertEquals(5.0, loaded.duration, 0.001)
        assertNotNull(loaded.watch)
    }
}
