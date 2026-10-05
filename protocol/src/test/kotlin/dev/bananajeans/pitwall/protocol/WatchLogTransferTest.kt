package dev.bananajeans.pitwall.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WatchLogTransferTest {
    @Test fun recoveredIncompleteLogSurvivesTheActualTransferEnvelope() {
        val dir = Files.createTempDirectory("transfer-envelope").toFile()
        try {
            val source = dir.resolve("source.pwtch")
            source.outputStream().use { output ->
                val metadata = WatchLogCodec.Metadata("offline-1", "0.3.0", "TestWatch", 1000, 10_000_000_000, emptyList(), 1)
                val writer = WatchLogCodec.Writer(output, metadata)
                writer.appendSamples(4, listOf(WatchLogCodec.Sample(4, 10_000_000_100, 1.0, 2.0, 3.0, 0.0, 3)))
                writer.close() // recovered crash: no trailer
            }
            val meta = WatchLogCodec.SourceMeta("offline-1", WatchLogCodec.FORMAT_VERSION, source.length(), WatchLogCodec.SourceMeta.sha256Hex(source), false)
            val transfer = ByteArrayOutputStream()
            source.inputStream().use { WatchLogTransfer.write(it, meta, transfer) }
            val incoming = transfer.toByteArray().inputStream()
            val receivedMeta = WatchLogTransfer.readMetadata(incoming)
            assertEquals(meta, receivedMeta)
            val result = WatchLogImporter(dir.resolve("imported")).importFromFile("offline-1", incoming, sourceMeta = receivedMeta)
            assertTrue(result is WatchLogImporter.Result.Imported)
            assertEquals(false, result.log.complete)
            assertEquals(1, result.log.samples.size)

            val truncated = transfer.toByteArray().dropLast(1).toByteArray().inputStream()
            val truncatedMeta = WatchLogTransfer.readMetadata(truncated)
            val rejected = WatchLogImporter(dir.resolve("truncated")).importFromFile("offline-1", truncated, sourceMeta = truncatedMeta)
            assertTrue(rejected is WatchLogImporter.Result.Rejected)
        } finally { dir.deleteRecursively() }
    }

    @Test fun oversizedHeaderIsRejectedBeforeAllocation() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeInt(0x50575458); writeInt(Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { WatchLogTransfer.readMetadata(bytes.toByteArray().inputStream()) }
    }
}
