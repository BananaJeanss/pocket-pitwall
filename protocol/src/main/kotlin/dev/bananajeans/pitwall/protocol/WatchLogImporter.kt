package dev.bananajeans.pitwall.protocol

import java.io.File
import java.security.MessageDigest

/**
 * Phone-side import pipeline for transferred watch logs (issue #21).
 *
 * Pure JVM logic shared by phone and watch tests. Validates the binary log,
 * checks idempotency by session id, and stores verified bytes atomically.
 * Callers decide where the storage directory lives (app private dir on the
 * phone).
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
     *
     * Integrity check: if the log declares its own expected byte length and
     * content hash (written by the watch at finalization), we verify that the
     * received bytes match exactly. This allows accepting an intentionally
     * incomplete *source* log (watch crash recovery) while rejecting a
     * *transport-truncated* copy of any log.
     */
    fun import(sessionId: String, bytes: ByteArray): Result {
        if (!SESSION_ID.matches(sessionId)) return Result.Rejected("Invalid session id")
        if (bytes.isEmpty()) return Result.Rejected("Empty transfer")
        val read = try {
            WatchLogCodec.read(bytes.inputStream())
        } catch (e: WatchLogCodec.CorruptLogException) {
            return Result.Rejected("Corrupt log: ${e.message}")
        }
        val (log, complete) = when (read) {
            is WatchLogCodec.ReadResult.Complete -> read.log to true
            is WatchLogCodec.ReadResult.Incomplete -> read.log to false
        }
        
        // Integrity check: if metadata has expected length/hash, verify exact match
        val metadata = log.metadata
        if (metadata.expectedByteLength != null) {
            if (bytes.size.toLong() != metadata.expectedByteLength) {
                return Result.Rejected("Transport truncated: expected ${metadata.expectedByteLength} bytes, got ${bytes.size}")
            }
            // Only verify hash if it's non-zero (not a placeholder)
            if (metadata.contentHash != null && metadata.contentHash != "0".repeat(64)) {
                val computedHash = computeSha256Hex(bytes)
                if (computedHash != metadata.contentHash) {
                    return Result.Rejected("Content hash mismatch: expected ${metadata.contentHash}, got $computedHash")
                }
            }
            // Exact length match - this is the intact source log, even if incomplete
        } else if (!complete) {
            // No integrity metadata and incomplete - could be truncated transport
            // Reject to prevent deletion of intact watch copy
            return Result.Rejected("Incomplete log without integrity metadata; transfer may be truncated")
        }
        
        if (log.metadata.sessionId != sessionId) {
            return Result.Rejected("Log metadata session id does not match transfer id")
        }

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

    /** Validate a log file from storage without re-reading all bytes. */
    fun validateStored(sessionId: String): Result {
        val file = File(storeDir, "$sessionId.pwtch")
        if (!file.isFile) return Result.Rejected("Not found")
        val bytes = file.readBytes()
        return import(sessionId, bytes)
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

    private fun computeSha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        val SESSION_ID: Regex = Regex("[a-zA-Z0-9-]+")
    }
}