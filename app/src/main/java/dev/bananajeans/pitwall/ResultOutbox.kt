package dev.bananajeans.pitwall

import android.content.Context
import android.util.AtomicFile
import dev.bananajeans.pitwall.protocol.Messages
import java.io.File

/**
 * Durable outbox for post-session [Messages.Result] summaries (issue #25 P1).
 *
 * Root cause fixed: pendingResult lived only in WatchLink memory. If the
 * watch log was ACKed/deleted and the result send failed before the phone
 * process died, the watch permanently lost the summary.
 *
 * One file per session id under filesDir/result-outbox/, written atomically
 * BEFORE the send is attempted, cleared only when the summary is considered
 * delivered. Replay after reconnect/restart is idempotent on the watch
 * (WatchResultsStore keeps one file per session id and re-saving is a no-op
 * beyond an atomic overwrite with identical content).
 */
object ResultOutbox {

    private val ID = Regex("[a-zA-Z0-9-]+")

    fun dir(context: Context): File = File(context.filesDir, "result-outbox").apply { mkdirs() }

    fun save(context: Context, result: Messages.Result) {
        if (!ID.matches(result.sessionId)) return
        // Encode via the message serializer: single source of truth.
        val text = String(Messages.encode(result), Charsets.UTF_8)
        val atomic = AtomicFile(File(dir(context), "${result.sessionId}.json"))
        val stream = atomic.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    fun pending(context: Context): List<Messages.Result> =
        dir(context).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { file ->
                runCatching {
                    Messages.decode(file.readText().toByteArray(Charsets.UTF_8)) as? Messages.Result
                }.getOrNull()
            }
            // Newest session first by the immutable session creation wall
            // clock; receipt time never enters the ordering.
            .sortedByDescending { it.timestamp ?: Long.MIN_VALUE }

    fun clear(context: Context, sessionId: String) {
        if (!ID.matches(sessionId)) return
        File(dir(context), "$sessionId.json").delete()
    }

    fun clearAll(context: Context) {
        dir(context).listFiles().orEmpty().forEach { it.delete() }
    }
}
