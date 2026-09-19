package dev.bananajeans.pitwall.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class WatchLogImporterTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun buildLog(sessionId: String, samples: Int = 5, finalize: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        val metadata = WatchLogCodec.Metadata(
            sessionId = sessionId,
            watchAppVersion = "0.3.0",
            deviceModel = "TestWatch",
            startedAtWallMillis = 1730000000000L,
            startedAtMonotonicNanos = 10_000_000_000L,
            sensorInfo = emptyList(),
            protocolVersion = 1
        )
        val writer = WatchLogCodec.Writer(out, metadata)
        for (i in 0 until samples) {
            writer.appendSamples(4, listOf(
                WatchLogCodec.Sample(4, 10_000_000_000L + i * 5_000_000L, 0.1 * i, -9.8, 0.5, 0.0, 3)
            ))
        }
        if (finalize) writer.finish() else writer.close()
        return out.toByteArray()
    }

    @Test
    fun importStoresValidatedLog() {
        val importer = WatchLogImporter(tmp.newFolder())
        val result = importer.import("sess-1", buildLog("sess-1"))
        assertTrue(result is WatchLogImporter.Result.Imported)
        val log = (result as WatchLogImporter.Result.Imported).log
        assertTrue(log.complete)
        assertEquals(5, log.samples.size)
        assertTrue(importer.hasImported("sess-1"))
        assertEquals(listOf("sess-1"), importer.importedIds())
    }

    @Test
    fun duplicateDeliveryIsIdempotent() {
        val importer = WatchLogImporter(tmp.newFolder())
        val bytes = buildLog("sess-1")
        assertTrue(importer.import("sess-1", bytes) is WatchLogImporter.Result.Imported)
        val second = importer.import("sess-1", bytes)
        assertTrue(second is WatchLogImporter.Result.Duplicate)
        // The stored copy is untouched.
        assertTrue(importer.importedFile("sess-1")!!.readBytes().contentEquals(bytes))
    }

    @Test
    fun conflictingPayloadRejected() {
        val importer = WatchLogImporter(tmp.newFolder())
        assertTrue(importer.import("sess-1", buildLog("sess-1", samples = 5)) is WatchLogImporter.Result.Imported)
        val conflict = importer.import("sess-1", buildLog("sess-1", samples = 9))
        assertTrue(conflict is WatchLogImporter.Result.Rejected)
        // Original copy preserved.
        val read = WatchLogCodec.read(importer.importedFile("sess-1")!!.inputStream())
        assertEquals(5, (read as WatchLogCodec.ReadResult.Complete).log.samples.size)
    }

    @Test
    fun corruptTransferNeverStored() {
        val importer = WatchLogImporter(tmp.newFolder())
        val good = buildLog("sess-1")
        val corrupt = good.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x5A).toByte() }
        val result = importer.import("sess-1", corrupt)
        // Corrupt content either imports as incomplete-with-recovered-frames
        // or is rejected; it must NEVER be reported complete.
        when (result) {
            is WatchLogImporter.Result.Imported -> assertFalse(result.log.complete)
            is WatchLogImporter.Result.Rejected -> assertTrue(result.reason.contains("Corrupt") || result.reason.contains("Incomplete"))
            is WatchLogImporter.Result.Duplicate -> throw AssertionError("cannot duplicate on first import")
        }
    }

    @Test
    fun sessionMismatchRejected() {
        val importer = WatchLogImporter(tmp.newFolder())
        val result = importer.import("sess-A", buildLog("sess-B"))
        assertTrue(result is WatchLogImporter.Result.Rejected)
        assertTrue(!importer.hasImported("sess-A"))
        assertTrue(!importer.hasImported("sess-B"))
    }

    @Test
    fun invalidSessionIdsRejected() {
        val importer = WatchLogImporter(tmp.newFolder())
        assertTrue(importer.import("../evil", buildLog("x")) is WatchLogImporter.Result.Rejected)
        assertTrue(importer.import("", buildLog("x")) is WatchLogImporter.Result.Rejected)
        assertTrue(importer.import("s 1", buildLog("x")) is WatchLogImporter.Result.Rejected)
    }

    @Test
    fun emptyTransferRejected() {
        val importer = WatchLogImporter(tmp.newFolder())
        assertTrue(importer.import("sess-1", ByteArray(0)) is WatchLogImporter.Result.Rejected)
    }

    @Test
    fun incompleteLogImportsMarkedIncomplete() {
        val importer = WatchLogImporter(tmp.newFolder())
        val result = importer.import("sess-crash", buildLog("sess-crash", samples = 3, finalize = false))
        // Incomplete logs are now rejected to prevent deletion of intact watch copies
        // on truncated channel transfers. The watch will retry with a complete log.
        assertTrue(result is WatchLogImporter.Result.Rejected)
        assertTrue(result.reason.contains("Incomplete"))
    }

    @Test
    fun multipleLogsTracked() {
        val importer = WatchLogImporter(tmp.newFolder())
        importer.import("sess-a", buildLog("sess-a"))
        importer.import("sess-b", buildLog("sess-b"))
        assertEquals(listOf("sess-a", "sess-b"), importer.importedIds())
    }
}
