package dev.bananajeans.pitwall.protocol

import java.io.File

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
