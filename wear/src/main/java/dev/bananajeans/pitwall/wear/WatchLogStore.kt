package dev.bananajeans.pitwall.wear

import android.content.Context
import android.util.AtomicFile
import dev.bananajeans.pitwall.protocol.Messages
import dev.bananajeans.pitwall.protocol.PitwallJson
import dev.bananajeans.pitwall.protocol.WatchLogCodec
import java.io.File
import java.io.FileOutputStream

/**
 * Durable local storage for watch recordings (issues #18/#21).
 *
 * Layout under filesDir:
 *   logs/<sessionId>/log.pwtch      finalized or in-progress binary log
 *   logs/<sessionId>/state.json     recording/import state (atomic writes)
 *
 * Lifecycle:
 *   RECORDING  -> the writer is open; crash leaves an INCOMPLETE log.
 *   FINALIZED  -> log.pwtch has a valid trailer; waiting for phone import.
 *   IMPORTED   -> phone confirmed durable import; eligible for retention
 *                 cleanup after a grace window.
 *
 * Everything is designed so that restarting the app or the watch at any
 * point loses nothing that reached disk, and completed logs stay queued
 * until the phone acknowledges.
 */
class WatchLogStore(private val context: Context) {

    enum class State { RECORDING, FINALIZED, IMPORTED }

    data class Entry(
        val sessionId: String,
        val state: State,
        val finalizedComplete: Boolean,
        val lengthBytes: Long,
        val lastModified: Long
    )

    private val root: File = File(context.filesDir, "logs").apply { mkdirs() }

    companion object {
        private val ID_PATTERN = Regex("[a-zA-Z0-9-]+")

        fun diagnosticsFile(context: Context): File =
            File(File(context.filesDir, "logs").apply { mkdirs() }, "diagnostics.txt")

        /** Logs acknowledged as imported older than this are deleted (7 days). */
        private const val IMPORTED_RETENTION_MS: Long = 7 * 24 * 60 * 60 * 1000L
    }

    fun folder(sessionId: String): File {
        require(ID_PATTERN.matches(sessionId)) { "Invalid session id" }
        return File(root, sessionId).apply { mkdirs() }
    }

    fun logFile(sessionId: String): File = File(folder(sessionId), "log.pwtch")

    /** Out-of-band source metadata sidecar (issue #21 integrity). */
    fun sourceMetaFile(sessionId: String): File = File(folder(sessionId), "source.meta")

    fun stateFile(sessionId: String): File = File(folder(sessionId), "state.json")

    fun isRecording(sessionId: String): Boolean = readState(sessionId)?.state == State.RECORDING

    fun startRecording(sessionId: String): File {
        val file = logFile(sessionId)
        file.parentFile!!.mkdirs()
        writeState(sessionId, State.RECORDING)
        return file
    }

    /** Marks the log finalized (or recovers an unfinalized one after a crash). */
    fun markFinalized(sessionId: String, complete: Boolean) {
        val current = readState(sessionId)
        if (current?.state == State.IMPORTED) return // never downgrade
        writeState(sessionId, State.FINALIZED, complete)
        writeSourceMeta(sessionId, complete)
    }

    /** Reads the durable source sidecar for a log, if one was written. */
    fun sourceMeta(sessionId: String): WatchLogCodec.SourceMeta? {
        if (!ID_PATTERN.matches(sessionId)) return null
        val file = sourceMetaFile(sessionId)
        if (!file.isFile) return null
        return runCatching { WatchLogCodec.SourceMeta.parseJson(file.readText()) }.getOrNull()
    }

    /**
     * Writes the out-of-band source metadata sidecar: expected byte length,
     * streaming SHA-256 of the exact bytes on disk, and whether the source
     * is complete (valid trailer). The phone uses this to accept a
     * crash-recovered incomplete source while rejecting transport
     * truncation (issue #21).
     */
    private fun writeSourceMeta(sessionId: String, complete: Boolean) {
        val log = logFile(sessionId)
        if (!log.isFile || log.length() == 0L) return
        val meta = WatchLogCodec.SourceMeta(
            sessionId = sessionId,
            formatVersion = WatchLogCodec.FORMAT_VERSION,
            expectedBytes = log.length(),
            sha256 = WatchLogCodec.SourceMeta.sha256Hex(log),
            complete = complete
        )
        val atomic = AtomicFile(sourceMetaFile(sessionId))
        val stream = atomic.startWrite()
        try {
            stream.write(meta.encodeJson().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    fun markImported(sessionId: String) {
        val current = readState(sessionId)
        requireNotNull(current) { "No recording state for $sessionId" }
        writeState(sessionId, State.IMPORTED, current.finalizedComplete)
    }

    fun delete(sessionId: String) {
        folder(sessionId).deleteRecursively()
    }

    /** All logs with their durable state, oldest first. */
    fun list(): List<Entry> =
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            val state = readState(dir.name) ?: return@mapNotNull null
            val file = File(dir, "log.pwtch")
            Entry(dir.name, state.state, state.finalizedComplete, file.length(), file.lastModified())
        }.sortedBy { it.lastModified }

    fun pendingTransfer(): List<Entry> = list().filter { it.state == State.FINALIZED }

    fun writeState(sessionId: String, state: State, complete: Boolean? = null) {
        val current = readState(sessionId)
        val finalizedComplete = complete ?: current?.finalizedComplete ?: false
        val json = PitwallJson.obj(
            "state" to PitwallJson.s(state.name),
            "complete" to PitwallJson.b(finalizedComplete)
        )
        val atomic = AtomicFile(stateFile(sessionId))
        val stream = atomic.startWrite()
        try {
            stream.write(PitwallJson.write(json).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    private data class StoredState(val state: State, val finalizedComplete: Boolean)

    private fun readState(sessionId: String): StoredState? {
        if (!ID_PATTERN.matches(sessionId)) return null
        val file = stateFile(sessionId)
        if (!file.isFile) return null
        return runCatching {
            val root = PitwallJson.parse(file.readText())
            val obj = root as? PitwallJson.Value.Object ?: return@runCatching null
            val state = when (obj.string("state")) {
                "RECORDING" -> State.RECORDING
                "FINALIZED" -> State.FINALIZED
                "IMPORTED" -> State.IMPORTED
                else -> return@runCatching null
            }
            StoredState(state, obj.bool("complete") ?: false)
        }.getOrNull()
    }

    /**
     * Recovery pass after app/watch restart (issue #18 acceptance):
     * any session still marked RECORDING is turned into a FINALIZED,
     * incomplete log so it participates in transfer and never silently
     * disappears. Imported logs past retention are cleaned up (issue #21).
     */
    fun recover(): RecoveryResult {
        var recoveredUnfinalized = 0
        var cleaned = 0
        for (entry in list()) {
            if (entry.state == State.RECORDING) {
                // The log file has no trailer (the writer died); keep it as an
                // incomplete finalized log rather than losing it.
                markFinalized(entry.sessionId, complete = false)
                recoveredUnfinalized++
            }
            if (entry.state == State.FINALIZED) {
                // Self-heal: a crash between markFinalized and the sidecar
                // write (or a lost sidecar) must not strand a log without
                // integrity metadata; recompute from the bytes on disk.
                val log = logFile(entry.sessionId)
                val meta = sourceMeta(entry.sessionId)
                if (log.isFile && log.length() > 0L &&
                    (meta == null || meta.expectedBytes != log.length())
                ) {
                    writeSourceMeta(entry.sessionId, entry.finalizedComplete)
                }
            }
            if (entry.state == State.IMPORTED &&
                System.currentTimeMillis() - entry.lastModified > IMPORTED_RETENTION_MS
            ) {
                delete(entry.sessionId)
                cleaned++
            }
        }
        return RecoveryResult(recoveredUnfinalized, cleaned)
    }

    data class RecoveryResult(val recoveredUnfinalized: Int, val cleanedImported: Int)

    /** Opens an incremental writer; the recording service fsyncs between batches. */
    fun writer(sessionId: String, metadata: WatchLogCodec.Metadata): Pair<WatchLogCodec.Writer, FileOutputStream> {
        val stream = FileOutputStream(logFile(sessionId))
        return WatchLogCodec.Writer(stream, metadata) to stream
    }
}
