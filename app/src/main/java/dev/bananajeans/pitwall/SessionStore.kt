package dev.bananajeans.pitwall

import android.content.Context
import android.util.AtomicFile
import dev.bananajeans.pitwall.core.Telemetry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
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
    fun list(): List<Session> = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
        runCatching {
            val j = JSONObject(AtomicFile(File(dir, "session.json")).openRead().bufferedReader().use { it.readText() })
            val marks = j.optJSONArray("marks") ?: JSONArray()
            val track = j.optJSONArray("track") ?: JSONArray()
            val pins = j.optJSONObject("pins") ?: JSONObject()
            Session(id=dir.name, created=j.getLong("created"), title=j.getString("title"), direction=j.optString("direction", "Normal"),
                status=j.getString("status"), duration=j.optDouble("duration",0.0), length=j.optDouble("lengthMeters",0.0),
                sensors=j.optString("sensors"), notes=j.optString("notes"),
                marks=(0 until marks.length()).map { val m=marks.getJSONObject(it); Telemetry.Mark(m.getDouble("seconds"),m.getString("kind"),m.getBoolean("estimated")) },
                track=(0 until track.length()).map { track.getJSONArray(it).let { p -> p.getDouble(0).toFloat() to p.getDouble(1).toFloat() } },
                pins=pins.keys().asSequence().associateWith { pins.getJSONArray(it).let { p -> p.getDouble(0).toFloat() to p.getDouble(1).toFloat() } })
        }.getOrNull()
    }.sortedByDescending { it.created }
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
