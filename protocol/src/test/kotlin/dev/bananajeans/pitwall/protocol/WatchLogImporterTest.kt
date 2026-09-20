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
        if (result is WatchLogImporter.Result.Rejected) {
            assertTrue(result.reason.contains("Incomplete"))
        }
    }

    @Test
    fun multipleLogsTracked() {
        val importer = WatchLogImporter(tmp.newFolder())
        importer.import("sess-a", buildLog("sess-a"))
        importer.import("sess-b", buildLog("sess-b"))
        assertEquals(listOf("sess-a", "sess-b"), importer.importedIds())
    }

    // ---- out-of-band source metadata (issue #21) ----------------------------

    @Test
    fun incompleteSourceWithValidSidecarIsAccepted() {
        val importer = WatchLogImporter(tmp.newFolder())
        val bytes = buildLog("sess-crash", samples = 3, finalize = false)
        val sidecar = WatchLogCodec.SourceMeta(
            sessionId = "sess-crash",
            formatVersion = WatchLogCodec.FORMAT_VERSION,
            expectedBytes = bytes.size.toLong(),
            sha256 = WatchLogCodec.SourceMeta.sha256Hex(bytes.writeToTemp()),
            complete = false
        )
        val result = importer.import("sess-crash", bytes, sidecar)
        assertTrue("expected Imported, got $result", result is WatchLogImporter.Result.Imported)
        val log = (result as WatchLogImporter.Result.Imported).log
        assertFalse(log.complete)
        assertEquals(3, log.samples.size)
    }

    @Test
    fun transportTruncatedCopyIsRejectedEvenWithSidecar() {
        val importer = WatchLogImporter(tmp.newFolder())
        val full = buildLog("sess-crash", samples = 5, finalize = true)
        val sidecar = WatchLogCodec.SourceMeta(
            sessionId = "sess-crash",
            formatVersion = WatchLogCodec.FORMAT_VERSION,
            expectedBytes = full.size.toLong(),
            sha256 = WatchLogCodec.SourceMeta.sha256Hex(full.writeToTemp()),
            complete = true
        )
        // The channel lost the tail: trailer gone, length short.
        val truncated = full.copyOfRange(0, full.size - 10)
        val result = importer.import("sess-crash", truncated, sidecar)
        assertTrue(result is WatchLogImporter.Result.Rejected)
        assertTrue((result as WatchLogImporter.Result.Rejected).reason.contains("truncated"))
    }

    @Test
    fun hashMismatchIsRejected() {
        val importer = WatchLogImporter(tmp.newFolder())
        val bytes = buildLog("sess-crash", samples = 3, finalize = false)
        val tampered = bytes.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val sidecar = WatchLogCodec.SourceMeta(
            sessionId = "sess-crash",
            formatVersion = WatchLogCodec.FORMAT_VERSION,
            expectedBytes = tampered.size.toLong(), // same length, wrong content
            sha256 = WatchLogCodec.SourceMeta.sha256Hex(bytes.writeToTemp()),
            complete = false
        )
        val result = importer.import("sess-crash", tampered, sidecar)
        assertTrue(result is WatchLogImporter.Result.Rejected)
        assertTrue((result as WatchLogImporter.Result.Rejected).reason.contains("hash"))
    }

    @Test
    fun incompleteWithoutAnySidecarIsStillRejected() {
        // Do NOT accept an incomplete payload just because it arrived.
        val importer = WatchLogImporter(tmp.newFolder())
        val result = importer.import("sess-crash", buildLog("sess-crash", samples = 2, finalize = false))
        assertTrue(result is WatchLogImporter.Result.Rejected)
    }

    @Test
    fun finalizedSourceCopyWithMissingTrailerIsRejectedNotAcceptedAsIncomplete() {
        // Source was complete, copy lost the trailer: must NOT be accepted
        // as a "legitimately incomplete" log.
        val importer = WatchLogImporter(tmp.newFolder())
        val full = buildLog("sess-x", samples = 4, finalize = true)
        val damaged = full.copyOfRange(0, full.size - WatchLogCodec.TRAILER_SIZE + 2)
        val sidecar = WatchLogCodec.SourceMeta(
            sessionId = "sess-x",
            formatVersion = WatchLogCodec.FORMAT_VERSION,
            expectedBytes = damaged.size.toLong(), // matched to the damaged copy
            sha256 = WatchLogCodec.SourceMeta.sha256Hex(damaged.writeToTemp()),
            complete = true // source WAS finalized
        )
        val result = importer.import("sess-x", damaged, sidecar)
        assertTrue(result is WatchLogImporter.Result.Rejected)
    }

    // ---- streaming import ----------------------------------------------------

    @Test
    fun importFromFileStreamsAndStoresIdentically() {
        val importer = WatchLogImporter(tmp.newFolder())
        val bytes = buildLog("sess-file")
        val file = bytes.writeToTemp()
        val sidecar = WatchLogCodec.SourceMeta(
            sessionId = "sess-file",
            formatVersion = WatchLogCodec.FORMAT_VERSION,
            expectedBytes = bytes.size.toLong(),
            sha256 = WatchLogCodec.SourceMeta.sha256Hex(file),
            complete = true
        )
        val result = importer.importFromFile("sess-file", file, sidecar)
        assertTrue("expected Imported, got $result", result is WatchLogImporter.Result.Imported)
        assertTrue(importer.importedFile("sess-file")!!.readBytes().contentEquals(bytes))

        // Duplicate via the same path is idempotent.
        assertTrue(importer.importFromFile("sess-file", file, sidecar) is WatchLogImporter.Result.Duplicate)
    }

    @Test
    fun importFromFileRejectsLengthHintMismatch() {
        val importer = WatchLogImporter(tmp.newFolder())
        val bytes = buildLog("sess-len")
        val file = bytes.writeToTemp()
        val result = importer.importFromFile("sess-len", file.inputStream(), lengthHint = bytes.size + 1L)
        assertTrue(result is WatchLogImporter.Result.Rejected)
        assertTrue((result as WatchLogImporter.Result.Rejected).reason.contains("truncated"))
    }

    @Test
    fun importFromFileLargeLogDoesNotNeedWholeFileInMemory() {
        // 30k samples of streamed frames, imported via the streaming path;
        // validates end-to-end equivalence with the incremental reader.
        val importer = WatchLogImporter(tmp.newFolder())
        val out = ByteArrayOutputStream()
        val metadata = WatchLogCodec.Metadata(
            sessionId = "sess-big",
            watchAppVersion = "t",
            deviceModel = "t",
            startedAtWallMillis = 1L,
            startedAtMonotonicNanos = 10_000_000_000L,
            sensorInfo = emptyList(),
            protocolVersion = 1
        )
        val writer = WatchLogCodec.Writer(out, metadata)
        val chunk = ArrayList<WatchLogCodec.Sample>(512)
        var n = 0
        for (i in 0 until 30_000) {
            chunk.add(WatchLogCodec.Sample(4, 10_000_000_000L + i * 5_000_000L, 0.1, -9.8, 0.5, 0.0, 3))
            if (chunk.size == 512) {
                writer.appendSamples(4, chunk.toList())
                n += chunk.size
                chunk.clear()
            }
        }
        if (chunk.isNotEmpty()) {
            writer.appendSamples(4, chunk.toList())
            n += chunk.size
        }
        writer.finish()
        val bytes = out.toByteArray()
        val file = bytes.writeToTemp()
        val result = importer.importFromFile("sess-big", file)
        assertTrue("expected Imported, got $result", result is WatchLogImporter.Result.Imported)
        val read = WatchLogCodec.read(importer.importedFile("sess-big")!!.inputStream())
        assertEquals(n, (read as WatchLogCodec.ReadResult.Complete).log.samples.size)
    }

    private fun ByteArray.writeToTemp(): java.io.File {
        val f = java.io.File.createTempFile("pwtch-test", ".bin")
        f.writeBytes(this)
        f.deleteOnExit()
        return f
    }
}
