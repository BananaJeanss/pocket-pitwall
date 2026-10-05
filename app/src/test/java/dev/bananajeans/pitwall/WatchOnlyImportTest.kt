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

    @Test fun nearbyButNonOverlappingPhoneSessionIsNotMatched() {
        val old = Session(id = "previous-drive", created = 0, status = "complete", duration = 0.5)
        store.save(old)
        WatchTransferManager(context).attachToSession("later-watch-drive", imported("later-watch-drive"))
        assertNull(store.list().single { it.id == old.id }.watch)
        assertNotNull(store.list().single { it.id == "later-watch-drive" }.watch)
    }

    @Test fun overlappingStandaloneLogAttachesToPhoneSession() {
        val phone = Session(id = "phone-overlap", created = 900, status = "complete", duration = 3.0)
        store.save(phone)
        store.raw(phone.id).writeText("phone samples stay intact")
        WatchTransferManager(context).attachToSession("standalone-overlap", imported("standalone-overlap"))
        assertEquals(1, store.list().size)
        assertNotNull(store.list().single().watch)
        assertEquals("phone samples stay intact", store.raw(phone.id).readText())
        WatchTransferManager(context).attachToSession("standalone-overlap", imported("standalone-overlap"))
        assertEquals("A replay must find the original phone association", 1, store.list().size)
        assertEquals("standalone-overlap", store.list().single().watch!!.sourceSessionId)
    }

    @Test fun multipleCompletedWatchLogsAllBecomeVisibleSessions() {
        val manager = WatchTransferManager(context)
        for (id in listOf("heat-1", "heat-2", "heat-3")) {
            val result = imported(id)
            val meta = WatchLogCodec.SourceMeta(id, WatchLogCodec.FORMAT_VERSION,
                result.storedAt.length(), WatchLogCodec.SourceMeta.sha256Hex(result.storedAt), true)
            manager.importAndAttach(id, result.storedAt, meta)
        }
        assertEquals(setOf("heat-1", "heat-2", "heat-3"), store.list().map { it.id }.toSet())
        assertTrue(store.list().all { it.watch?.status == WatchSessionInfo.Status.IMPORTED })
    }

    @Test fun attachmentFailureIsReportedAndRawImportCanBeRepairedOnRetry() {
        val result = imported("blocked")
        val meta = WatchLogCodec.SourceMeta("blocked", WatchLogCodec.FORMAT_VERSION,
            result.storedAt.length(), WatchLogCodec.SourceMeta.sha256Hex(result.storedAt), true)
        val obstruction = java.io.File(context.filesDir, "sessions/blocked")
        obstruction.writeText("Cannot create session folder")
        val manager = WatchTransferManager(context)
        assertThrows(Exception::class.java) { manager.importAndAttach("blocked", result.storedAt, meta) }
        assertTrue(result.storedAt.isFile)
        assertTrue(store.list().isEmpty())
        obstruction.delete()
        assertTrue(manager.importAndAttach("blocked", result.storedAt, meta) is WatchLogImporter.Result.Duplicate)
        assertNotNull(store.list().single().watch)
    }

    @Test fun manifestCanReceiveWatchChannelsWithoutAnActivity() {
        val services = context.packageManager.queryIntentServices(android.content.Intent(
            "com.google.android.gms.wearable.CHANNEL_EVENT").setData(android.net.Uri.parse(
                "wear://watch/pitwall/log/heat-2")), 0)
        assertTrue(services.any { it.serviceInfo.name == PhoneDataLayerService::class.java.name && it.serviceInfo.exported })
    }
}
