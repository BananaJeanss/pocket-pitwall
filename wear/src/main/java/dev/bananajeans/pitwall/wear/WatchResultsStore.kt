package dev.bananajeans.pitwall.wear

import android.content.Context
import android.util.AtomicFile
import androidx.lifecycle.MutableLiveData
import dev.bananajeans.pitwall.protocol.Messages
import dev.bananajeans.pitwall.protocol.PitwallJson
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Persisted post-session results on the watch (issue #25).
 *
 * The phone pushes a compact [Messages.Result] after analysis; the watch
 * stores it durably so results remain viewable after the watch screen turns
 * off, the app restarts, or the phone leaves Bluetooth range. One result per
 * session; newest sessions first.
 *
 * Uses MutableLiveData so the UI can observe changes reactively.
 */
object WatchResultsStore {

    private val ID = Regex("[a-zA-Z0-9-]+")

    fun dir(context: Context): File = File(context.filesDir, "results").apply { mkdirs() }

    /** Live data for observing result list changes. */
    private val resultsLiveData = MutableLiveData<List<Messages.Result>>()

    fun observeResults(context: Context): MutableLiveData<List<Messages.Result>> = resultsLiveData

    fun save(context: Context, result: Messages.Result) {
        if (!ID.matches(result.sessionId)) return
        
        // Use the canonical phone session timestamp from the result if available,
        // otherwise fall back to file modification time
        val timestamp = result.timestamp ?: System.currentTimeMillis()
        
        val json = PitwallJson.obj(
            "sid" to PitwallJson.s(result.sessionId),
            "bestLap" to (result.bestLapSeconds?.let { PitwallJson.n(it) } ?: PitwallJson.Value.Null),
            "laps" to PitwallJson.n(result.lapCount.toLong()),
            "smooth" to (result.steeringSmoothness?.let { PitwallJson.n(it) } ?: PitwallJson.Value.Null),
            "corrections" to (result.correctionCount?.let { PitwallJson.n(it.toLong()) } ?: PitwallJson.Value.Null),
            "peakHr" to (result.peakHr?.let { PitwallJson.n(it.toLong()) } ?: PitwallJson.Value.Null),
            "avgHr" to (result.averageHr?.let { PitwallJson.n(it.toLong()) } ?: PitwallJson.Value.Null),
            "quality" to (result.watchDataQuality?.let { PitwallJson.s(it) } ?: PitwallJson.Value.Null),
            "notes" to (result.notes?.let { PitwallJson.s(it) } ?: PitwallJson.Value.Null),
            // Store result timestamp for chronological sorting
            "timestamp" to PitwallJson.n(timestamp)
        )
        val atomic = AtomicFile(File(dir(context), "${result.sessionId}.json"))
        val stream = atomic.startWrite()
        try {
            stream.write(PitwallJson.write(json).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
        // Refresh live data
        refreshLiveData(context)
    }

    fun list(context: Context): List<Messages.Result> =
        dir(context).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { file ->
                runCatching {
                    val obj = PitwallJson.parse(file.readText()) as PitwallJson.Value.Object
                    Messages.Result(
                        sessionId = obj.string("sid") ?: return@mapNotNull null,
                        bestLapSeconds = obj.number("bestLap")?.toDouble(),
                        lapCount = obj.number("laps")?.toInt() ?: 0,
                        steeringSmoothness = obj.number("smooth")?.toDouble(),
                        correctionCount = obj.number("corrections")?.toInt(),
                        peakHr = obj.number("peakHr")?.toInt(),
                        averageHr = obj.number("avgHr")?.toInt(),
                        watchDataQuality = obj.string("quality"),
                        notes = obj.string("notes"),
                        timestamp = obj.number("timestamp")?.toLong()
                    )
                }.getOrNull()
            }
            // Sort by canonical phone session timestamp (newest first),
            // fall back to file lastModified
            .sortedByDescending { result ->
                val file = File(dir(context), "${result.sessionId}.json")
                val obj = try { PitwallJson.parse(file.readText()) as PitwallJson.Value.Object } catch (_: Exception) { return@sortedByDescending 0L }
                obj.number("timestamp")?.toLong() ?: file.lastModified()
            }

    fun latest(context: Context): Messages.Result? = list(context).firstOrNull()

    fun delete(context: Context, sessionId: String) {
        if (!ID.matches(sessionId)) return
        File(dir(context), "$sessionId.json").delete()
        refreshLiveData(context)
    }

    private fun refreshLiveData(context: Context) {
        // Post to main thread for LiveData
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            resultsLiveData.postValue(list(context))
        }
    }
}
