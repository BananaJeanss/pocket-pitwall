package dev.bananajeans.pitwall

import android.content.Context
import android.util.AtomicFile
import dev.bananajeans.pitwall.core.Telemetry
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

data class Session(
    val id: String = UUID.randomUUID().toString(),
    val created: Long = System.currentTimeMillis(),
    val title: String = "Motorcity · Underground",
    val direction: String = "Normal",
    val status: String = "recording",
    val duration: Double = 0.0,
    val length: Double = 0.0,
    val marks: List<Telemetry.Mark> = emptyList(),
    val sensors: String = "",
    val notes: String = "",
    val track: List<Pair<Float, Float>> = emptyList(),
    val pins: Map<String, Pair<Float, Float>> = emptyMap()
) {
    fun json(): JSONObject = JSONObject().put("schemaVersion", 1).put("id", id).put("created", created)
        .put("title", title).put("direction", direction).put("status", status).put("duration", duration)
        .put("lengthMeters", length).put("sensors", sensors).put("notes", notes)
        .put("marks", JSONArray().apply { marks.forEach { put(JSONObject().put("seconds", it.t).put("kind", it.kind).put("estimated", it.estimated)) } })
        .put("track", JSONArray().apply { track.forEach { put(JSONArray().put(it.first).put(it.second)) } })
        .put("pins", JSONObject().apply { pins.forEach { (k,v) -> put(k, JSONArray().put(v.first).put(v.second)) } })
}

data class Trace(val acceleration: List<Telemetry.Point>, val rotation: List<Telemetry.Point>, val gaps: Int)

class SessionStore(context: Context) {
    private val root = File(context.filesDir, "sessions").apply { mkdirs() }

    fun folder(id: String): File {
        require(Regex("[a-zA-Z0-9-]+").matches(id))
        return File(root, id).apply { mkdirs() }
    }

    fun raw(id: String) = File(folder(id), "sensors.csv")

    fun save(s: Session) {
        val atomic = AtomicFile(File(folder(s.id), "session.json"))
        val stream = atomic.startWrite()
        try { stream.write(s.json().toString(2).toByteArray()); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    private fun parseSession(j: JSONObject, id: String): Session {
        require(j.optInt("schemaVersion", 1) == 1) { "Unsupported session format." }
        val marks = j.optJSONArray("marks") ?: JSONArray()
        val track = j.optJSONArray("track") ?: JSONArray()
        val pins = j.optJSONObject("pins") ?: JSONObject()
        return Session(
            id=id,
            created=j.getLong("created"),
            title=j.getString("title"),
            direction=j.optString("direction", "Normal"),
            status=j.optString("status", "complete"),
            duration=j.optDouble("duration", 0.0),
            length=j.optDouble("lengthMeters", 0.0),
            sensors=j.optString("sensors"),
            notes=j.optString("notes"),
            marks=(0 until marks.length()).map {
                val m=marks.getJSONObject(it)
                Telemetry.Mark(m.getDouble("seconds"), m.getString("kind"), m.optBoolean("estimated", false))
            },
            track=(0 until track.length()).map { track.getJSONArray(it).let { p -> p.getDouble(0).toFloat() to p.getDouble(1).toFloat() } },
            pins=pins.keys().asSequence().associateWith { pins.getJSONArray(it).let { p -> p.getDouble(0).toFloat() to p.getDouble(1).toFloat() } }
        )
    }

    fun list(): List<Session> = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
        runCatching {
            val j = JSONObject(AtomicFile(File(dir, "session.json")).openRead().bufferedReader().use { it.readText() })
            parseSession(j, dir.name)
        }.getOrNull()
    }.sortedByDescending { it.created }

    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Imported file is too large." }
            output.write(buffer, 0, read)
        }
    }

    fun importZip(input: InputStream): Session {
        val tempSensors = File.createTempFile("pitwall-import-", ".csv", root)
        try {
            var metadata: String? = null
            var hasSensors = false
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        when (entry.name.substringAfterLast('/')) {
                            "session.json" -> {
                                val bytes = ByteArrayOutputStream()
                                copyLimited(zip, bytes, 1_048_576)
                                metadata = bytes.toString(Charsets.UTF_8.name())
                            }
                            "sensors.csv" -> {
                                tempSensors.outputStream().buffered().use { out -> copyLimited(zip, out, 268_435_456) }
                                hasSensors = true
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(metadata != null) { "ZIP does not contain session.json." }
            require(hasSensors) { "ZIP does not contain sensors.csv." }

            val id = UUID.randomUUID().toString()
            val parsed = parseSession(JSONObject(metadata!!), id)
            val imported = parsed.copy(status=if (parsed.status == "recording") "interrupted" else parsed.status)
            val destination = raw(id)
            save(imported)
            if (!tempSensors.renameTo(destination)) {
                tempSensors.inputStream().use { source -> destination.outputStream().use { source.copyTo(it) } }
                tempSensors.delete()
            }
            return imported
        } catch (e: Exception) {
            tempSensors.delete()
            throw e
        }
    }

    fun trace(id: String): Trace {
        val a=ArrayList<Telemetry.Point>(); val g=ArrayList<Telemetry.Point>()
        var gaps=0
        if (!raw(id).exists()) return Trace(a,g,0)
        raw(id).useLines { lines -> lines.drop(1).forEach { line ->
            val p=line.split(',')
            if (p.size >= 6) {
                val t=p[0].toDoubleOrNull(); val type=p[1].toIntOrNull()
                val x=p[2].toDoubleOrNull(); val y=p[3].toDoubleOrNull(); val z=p[4].toDoubleOrNull()
                if (t!=null && x!=null && y!=null && z!=null && t.isFinite() && x.isFinite() && y.isFinite() && z.isFinite()) {
                    val target=when(type) { 10 -> a; 4 -> g; else -> null }
                    if (target!=null && (target.isEmpty() || t>target.last().t)) {
                        if (target.isNotEmpty() && t-target.last().t>.25) gaps++
                        target.add(Telemetry.Point(t,sqrt(x*x+y*y+z*z)))
                    }
                }
            }
        } }
        return Trace(a,g,gaps)
    }

    fun recover() {
        list().filter { it.status=="recording" }.forEach { s ->
            val duration=raw(s.id).takeIf { it.exists() }?.useLines { lines -> lines.mapNotNull { it.substringBefore(',').toDoubleOrNull() }.lastOrNull() } ?: 0.0
            save(s.copy(status="interrupted",duration=duration))
        }
    }

    fun delete(id: String) { folder(id).deleteRecursively() }

    fun lapCsv(s: Session): String = buildString {
        appendLine("lap,start_s,end_s,lap_s,s1_s,s2_s,s3_s,estimated,average_kmh_from_user_length")
        Telemetry.laps(s.marks).forEachIndexed { i,l ->
            fun value(v: Double) = if(v.isFinite()) v.toString() else ""
            appendLine(listOf(i+1,l.start,l.end,l.duration(),value(l.s1),value(l.s2),value(l.s3),l.estimated,value(Telemetry.averageKmh(s.length,l.duration()))).joinToString(","))
        }
    }
}
