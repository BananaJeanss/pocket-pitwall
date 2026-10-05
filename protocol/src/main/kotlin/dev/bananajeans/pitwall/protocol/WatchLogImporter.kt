package dev.bananajeans.pitwall.protocol

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Phone-side import pipeline for transferred watch logs (issue #21).
 *
 * Pure JVM logic shared by phone and watch tests. Validates the binary log,
 * checks idempotency by session id, and stores verified bytes atomically.
 * Callers decide where the storage directory lives (app private dir on the
 * phone).
 *
 * Integrity model (issue #21):
 *  - a COMPLETE log is validated by its in-file trailer (CRC + structure);
 *  - an INCOMPLETE log is only accepted when the transfer carries the
 *    durable out-of-band [WatchLogCodec.SourceMeta] written by the watch
 *    (expected length + SHA-256 + complete flag). Then:
 *      * intact recovered incomplete source (length+hash match) -> accept
 *      * transport-truncated copy (length or hash mismatch)     -> reject
 *    Anything incomplete WITHOUT sidecar metadata is rejected: "no
 *    trailer" alone never proves the source was incomplete.
 */
class WatchLogImporter(private val storeDir: File) {

    init {
        storeDir.mkdirs()
    }

    /** Import result. */
    sealed interface Result {
        /** First successful import of this log. */
        data class Imported(val log: WatchLogCodec.WatchLog, val storedAt: File) : Result
        /** Already imported before; safe no-op for duplicate deliveries. */
        data class Duplicate(val log: WatchLogCodec.WatchLog) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * Validates and stores a transferred log. Idempotent: importing the same
     * bytes for the same session twice yields [Result.Duplicate] on the
     * second call and never corrupts the stored copy. A *different* payload
     * for an existing session id is rejected (session ids are unique; a
     * mismatch means the watch reused an id or the transfer was mangled).
     */
    fun import(sessionId: String, bytes: ByteArray): Result =
        import(sessionId, bytes, sourceMeta = null)

    /**
     * As [import], with out-of-band source metadata from the watch. Required
     * for incomplete sources; optional (but still verified) for complete ones.
     */
    fun import(sessionId: String, bytes: ByteArray, sourceMeta: WatchLogCodec.SourceMeta?): Result {
        if (!SESSION_ID.matches(sessionId)) return Result.Rejected("Invalid session id")
        if (bytes.isEmpty()) return Result.Rejected("Empty transfer")
        val lengthCheck = checkLength(sessionId, bytes.size.toLong(), sourceMeta)
        if (lengthCheck != null) return lengthCheck
        // Hash check BEFORE parsing: with a sidecar we have an expected
        // digest, so a mangled payload must be rejected for content (not
        // misreported as a structural parse failure).
        if (sourceMeta != null) {
            val actual = sha256Hex(bytes)
            if (actual != sourceMeta.sha256) {
                return Result.Rejected("Content hash mismatch: expected ${sourceMeta.sha256}, got $actual")
            }
        }
        val read = try {
            WatchLogCodec.read(bytes.inputStream())
        } catch (e: WatchLogCodec.CorruptLogException) {
            return Result.Rejected("Corrupt log: ${e.message}")
        }
        val (log, complete) = when (read) {
            is WatchLogCodec.ReadResult.Complete -> read.log to true
            is WatchLogCodec.ReadResult.Incomplete -> read.log to false
        }
        if (log.metadata.sessionId != sessionId) {
            return Result.Rejected("Log metadata session id does not match transfer id")
        }
        if (!complete) {
            // No in-file trailer. Safe only when the watch vouched for these
            // exact bytes out-of-band (length+hash verified above).
            if (sourceMeta == null) {
                return Result.Rejected("Incomplete log without source metadata; transfer may be truncated")
            }
            if (sourceMeta.complete) {
                // Source was COMPLETE but this copy has no trailer: the
                // transfer lost its tail. Never accept as "incomplete".
                return Result.Rejected("Transfer truncated: source was finalized but payload has no trailer")
            }
        }
        // The sidecar hash (when present) was verified before parsing.

        val destination = File(storeDir, "$sessionId.pwtch")
        if (destination.isFile) {
            return if (destination.readBytes().contentEquals(bytes)) {
                Result.Duplicate(log)
            } else {
                Result.Rejected("A different log already exists for session $sessionId")
            }
        }

        val temp = File(storeDir, ".$sessionId.importing")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(destination)) {
                temp.inputStream().use { input -> destination.outputStream().use(input::copyTo) }
                temp.delete()
            }
            fsync(destination)
        } catch (e: Exception) {
            temp.delete()
            return Result.Rejected("Could not store log: ${e.message}")
        }
        return Result.Imported(log.copy(complete = complete), destination)
    }

    /**
     * Streaming variant: validates and stores the log from [input] without
     * ever holding the whole payload in memory (an hour-long log is tens of
     * MB; readBytes() risks OOM on the phone). Bytes are length-checked
     * against [lengthHint] (or [WatchLogCodec.SourceMeta.expectedBytes] when
     * a sidecar is present), hashed while streaming, parsed from a temp
     * file, then moved into the store atomically.
     */
    fun importFromFile(sessionId: String, input: InputStream, lengthHint: Long = -1, sourceMeta: WatchLogCodec.SourceMeta? = null): Result {
        if (!SESSION_ID.matches(sessionId)) return Result.Rejected("Invalid session id")
        val expected = sourceMeta?.expectedBytes ?: lengthHint
        val temp = File(storeDir, ".$sessionId.importing")
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var copied = 0L
            FileOutputStream(temp).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    copied += n
                }
                out.flush()
                out.fd.sync()
            }
            if (copied == 0L) {
                temp.delete()
                return Result.Rejected("Empty transfer")
            }
            if (expected >= 0 && copied != expected) {
                temp.delete()
                return Result.Rejected("Transport truncated: expected $expected bytes, got $copied")
            }
            if (sourceMeta != null) {
                if (sourceMeta.sessionId != sessionId) {
                    temp.delete()
                    return Result.Rejected("Source metadata session id mismatch")
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != sourceMeta.sha256) {
                    temp.delete()
                    return Result.Rejected("Content hash mismatch: expected ${sourceMeta.sha256}, got $actual")
                }
            }
            val read = try {
                WatchLogCodec.read(temp.inputStream())
            } catch (e: WatchLogCodec.CorruptLogException) {
                temp.delete()
                return Result.Rejected("Corrupt log: ${e.message}")
            }
            val (log, complete) = when (read) {
                is WatchLogCodec.ReadResult.Complete -> read.log to true
                is WatchLogCodec.ReadResult.Incomplete -> read.log to false
            }
            if (log.metadata.sessionId != sessionId) {
                temp.delete()
                return Result.Rejected("Log metadata session id does not match transfer id")
            }
            if (!complete) {
                if (sourceMeta == null) {
                    temp.delete()
                    return Result.Rejected("Incomplete log without source metadata; transfer may be truncated")
                }
                if (sourceMeta.complete) {
                    temp.delete()
                    return Result.Rejected("Transfer truncated: source was finalized but payload has no trailer")
                }
                // intact crash-recovered source (length+hash verified above)
            }

            val destination = File(storeDir, "$sessionId.pwtch")
            if (destination.isFile) {
                val same = filesEqual(destination, temp)
                temp.delete()
                return if (same) {
                    Result.Duplicate(log)
                } else {
                    Result.Rejected("A different log already exists for session $sessionId")
                }
            }

            if (!temp.renameTo(destination)) {
                temp.inputStream().use { i -> destination.outputStream().use(i::copyTo) }
                temp.delete()
            }
            fsync(destination)
            return Result.Imported(log.copy(complete = complete), destination)
        } catch (e: Exception) {
            temp.delete()
            return Result.Rejected("Import failed: ${e.message}")
        }
    }

    /** Convenience overload for a file source. */
    fun importFromFile(sessionId: String, file: File, sourceMeta: WatchLogCodec.SourceMeta? = null): Result =
        importFromFile(sessionId, file.inputStream(), file.length(), sourceMeta)

    private fun checkLength(sessionId: String, byteCount: Long, sourceMeta: WatchLogCodec.SourceMeta?): Result.Rejected? {
        sourceMeta?.let { meta ->
            if (meta.sessionId != sessionId) {
                return Result.Rejected("Source metadata session id mismatch")
            }
            if (byteCount != meta.expectedBytes) {
                return Result.Rejected("Transport truncated: expected ${meta.expectedBytes} bytes, got $byteCount")
            }
        }
        return null
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** Byte-by-byte comparison without loading either file fully. */
    private fun filesEqual(f1: File, f2: File): Boolean {
        if (f1.length() != f2.length()) return false
        f1.inputStream().use { s1 ->
            f2.inputStream().use { s2 ->
                val b1 = ByteArray(64 * 1024)
                val b2 = ByteArray(64 * 1024)
                while (true) {
                    val n1 = s1.read(b1)
                    val n2 = s2.read(b2)
                    if (n1 != n2) return false
                    if (n1 < 0) return true
                    for (i in 0 until n1) if (b1[i] != b2[i]) return false
                }
                return true
            }
        }
    }

    fun hasImported(sessionId: String): Boolean =
        SESSION_ID.matches(sessionId) && File(storeDir, "$sessionId.pwtch").isFile

    fun importedFile(sessionId: String): File? =
        if (hasImported(sessionId)) File(storeDir, "$sessionId.pwtch") else null

    fun importedIds(): List<String> =
        storeDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".pwtch") }
            .map { it.name.removeSuffix(".pwtch") }
            .sorted()

    private fun fsync(file: File) {
        java.io.RandomAccessFile(file, "r").use { it.channel.force(true) }
    }

    companion object {
        val SESSION_ID: Regex = Regex("[a-zA-Z0-9-]+")
    }
}
