package dev.bananajeans.pitwall

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bananajeans.pitwall.core.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val Lime=Color(0xFFC4F568)
private val Cyan=Color(0xFF7DDDD6)
private fun time(v: Double): String = if(v.isFinite()) String.format(Locale.US,"%.3f",v) else "—"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Lime,secondary=Cyan,background=Color(0xFF101410),surface=Color(0xFF181E18),onPrimary=Color(0xFF243400))) {
                Surface(Modifier.fillMaxSize()) { Pitwall() }
            }
        }
    }
}

@Composable private fun Pitwall() {
    val context=LocalContext.current
    val store=remember { SessionStore(context) }
    val scope=rememberCoroutineScope()
    val saveLock=remember { Mutex() }
    val active by RecorderService.active.collectAsStateWithLifecycle()
    val elapsed by RecorderService.elapsed.collectAsStateWithLifecycle()
    val recordingError by RecorderService.error.collectAsStateWithLifecycle()
    var sessions by remember { mutableStateOf(emptyList<Session>()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var compareId by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("Motorcity · Underground") }
    var reverse by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Session?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportType by rememberSaveable { mutableStateOf("zip") }
    suspend fun reload() { sessions=withContext(Dispatchers.IO) { store.list() } }
    LaunchedEffect(active) {
        withContext(Dispatchers.IO) { if(!active) store.recover() }
        reload()
    }
    fun save(s: Session) {
        sessions=sessions.map { if(it.id==s.id) s else it }
        scope.launch {
            saveLock.withLock {
                try { withContext(Dispatchers.IO) { store.save(s) } }
                catch(e: Exception) { message="Save failed: ${e.message}"; reload() }
            }
        }
    }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val s=sessions.find { it.id==exportId }
        if(uri!=null && s!=null) scope.launch {
            busy=true
            try {
                saveLock.withLock { withContext(Dispatchers.IO) {
                    val out=requireNotNull(context.contentResolver.openOutputStream(uri)) { "Cannot open destination" }
                    out.use {
                        when(exportType) {
                            "csv" -> it.write(store.lapCsv(s).toByteArray())
                            "json" -> it.write(s.json().toString(2).toByteArray())
                            else -> ZipOutputStream(it).use { zip ->
                                fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
                                entry("session.json",s.json().toString(2)); entry("laps.csv",store.lapCsv(s))
                                zip.putNextEntry(ZipEntry("sensors.csv")); if(store.raw(s.id).exists()) store.raw(s.id).inputStream().use { input -> input.copyTo(zip) }; zip.closeEntry()
                            }
                        }
                    }
                }
                }
                message="Export saved."
            } catch(e: Exception) { message="Export failed: ${e.message}" }
            busy=false
        }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(!granted) message="Notifications disabled. Recording status remains visible in the app and Android's active apps panel."
    }
    val selected=sessions.find { it.id==selectedId }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("POCKET PITWALL",style=MaterialTheme.typography.labelLarge,color=Lime)
        Text(if(selected==null) "Every lap.\nA little clearer." else "Session debrief",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
        Text("OFFLINE  /  NO GPS  /  MOTION TELEMETRY",style=MaterialTheme.typography.labelSmall,color=Cyan)
        message?.let { Text(it,color=Cyan); TextButton(onClick={message=null}) { Text("Dismiss") } }
        recordingError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(selected==null) {
            Card { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(active) "Recording · ${time(elapsed)} s" else "Ready for the next session",style=MaterialTheme.typography.titleLarge)
                if(!active) {
                    OutlinedTextField(title,{title=it.take(100)},label={Text("Session name")},modifier=Modifier.fillMaxWidth())
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(!reverse,{reverse=false},label={Text("Normal")})
                        FilterChip(reverse,{reverse=true},label={Text("Reverse")})
                    }
                }
                Text("Start while stationary, secure the phone as permitted by the venue, and lock the screen. Review when parked. Recording stops after one hour.")
                Button(onClick={
                    try {
                        if(active) context.startService(Intent(context,RecorderService::class.java).setAction(RecorderService.STOP))
                        else {
                            if(Build.VERSION.SDK_INT>=33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            context.startForegroundService(Intent(context,RecorderService::class.java).putExtra("title",title.ifBlank { "Untitled session" }).putExtra("direction",if(reverse) "Reverse" else "Normal"))
                        }
                    } catch(e: Exception) { message="Cannot start recording: ${e.message}" }
                },modifier=Modifier.fillMaxWidth()) { Text(if(active) "Stop & save" else "Start recording") }
            } }
            Text("Real motion. Honest limits.",style=MaterialTheme.typography.titleMedium)
            Text("Pocket sensors cannot reliably measure indoor position or instantaneous speed. Lap suggestions are experimental; timing markers need your review. Average speed is available only from a known lap length.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Your sessions · ${sessions.size}",style=MaterialTheme.typography.titleLarge)
            if(sessions.isEmpty()) Text("Your first recording will appear here. Everything stays on this phone until you export it.")
            sessions.forEach { s ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(s.title,style=MaterialTheme.typography.titleMedium)
                    Text("${SimpleDateFormat("dd MMM · HH:mm",Locale.getDefault()).format(Date(s.created))} · ${s.direction}")
                    Text("${time(s.duration)} s · ${Telemetry.laps(s.marks).size} laps · ${s.status}",color=Cyan)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={selectedId=s.id},enabled=!active) { Text("Review") }
                        TextButton(onClick={deleteTarget=s},enabled=!active) { Text("Delete") }
                    }
                } }
            }
        } else {
            TextButton(onClick={selectedId=null}) { Text("← All sessions") }
            Review(selected,store,sessions,compareId,{compareId=it},::save,{message=it})
            Text("Export session",style=MaterialTheme.typography.titleLarge)
            Text("ZIP contains raw sensors.csv, laps.csv and session.json. JSON includes markers and your track sketch.")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("zip","csv","json").forEach { type ->
                    OutlinedButton(onClick={exportId=selected.id; exportType=type; exporter.launch("pitwall-${selected.id}.$type")},enabled=!busy) { Text(type.uppercase()) }
                }
            }
        }
        Text("v0.1 · AI-generated experimental software",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    deleteTarget?.let { s -> AlertDialog(onDismissRequest={deleteTarget=null},title={Text("Delete this session?")},text={Text("${s.title} and its raw sensor data will be removed from this phone. Export first if you want a copy.")},confirmButton={TextButton(onClick={scope.launch { withContext(Dispatchers.IO) { store.delete(s.id) }; reload(); deleteTarget=null }}) { Text("Delete") }},dismissButton={TextButton(onClick={deleteTarget=null}) { Text("Cancel") }}) }
}

@Composable private fun Review(s: Session,store: SessionStore,sessions: List<Session>,compareId: String?,onCompare: (String?) -> Unit,save: (Session) -> Unit,notice: (String) -> Unit) {
    val scope=rememberCoroutineScope()
    var trace by remember(s.id) { mutableStateOf<Trace?>(null) }
    var loadError by remember(s.id) { mutableStateOf<String?>(null) }
    var cursor by rememberSaveable(s.id) { mutableStateOf(0f) }
    var exact by rememberSaveable(s.id) { mutableStateOf("0") }
    var length by rememberSaveable(s.id) { mutableStateOf(if(s.length>0) s.length.toString() else "") }
    var name by rememberSaveable(s.id) { mutableStateOf(s.title) }
    var notes by rememberSaveable(s.id) { mutableStateOf(s.notes) }
    var expected by rememberSaveable(s.id) { mutableStateOf("45") }
    var suggestions by remember(s.id) { mutableStateOf(emptyList<Telemetry.Mark>()) }
    var analyzing by remember { mutableStateOf(false) }
    var lapIndex by rememberSaveable(s.id) { mutableStateOf(0) }
    var compareLap by rememberSaveable(s.id,compareId) { mutableStateOf(0) }
    var otherTrace by remember(compareId) { mutableStateOf<Trace?>(null) }
    LaunchedEffect(s.id) { try { trace=withContext(Dispatchers.IO) { store.trace(s.id) } } catch(e: Exception) { loadError=e.message } }
    LaunchedEffect(compareId) { otherTrace=compareId?.let { id -> withContext(Dispatchers.IO) { runCatching { store.trace(id) }.getOrNull() } } }
    val laps=remember(s.marks) { Telemetry.laps(s.marks) }
    val maxTime=maxOf(s.duration,trace?.acceleration?.lastOrNull()?.t ?: 0.0,trace?.rotation?.lastOrNull()?.t ?: 0.0).toFloat().coerceAtLeast(1f)
    Text(s.title,style=MaterialTheme.typography.titleLarge)
    Text("${s.direction} · ${s.status} · ${time(s.duration)} s",color=Cyan)
    OutlinedTextField(name,{name=it.take(100)},label={Text("Session name")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(notes,{notes=it.take(2000)},label={Text("Kart, conditions, notes")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(length,{length=it},label={Text("Known lap length in metres (optional)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
    TextButton(onClick={ val meters=if(length.isBlank()) 0.0 else length.toDoubleOrNull(); if(meters==null || !meters.isFinite() || meters<0 || meters>10000) notice("Enter a valid length from 0 to 10,000 m.") else save(s.copy(title=name.ifBlank { s.title },notes=notes,length=meters)) }) { Text("Save details") }
    Text("Motion timeline",style=MaterialTheme.typography.titleLarge)
    Text("Lime: linear acceleration magnitude (m/s²). Cyan: rotation magnitude (rad/s). Each trace has its own scale; these are phone movements, not kart speed.")
    loadError?.let { Text("Could not load sensors: $it",color=MaterialTheme.colorScheme.error) }
    if(trace==null && loadError==null) LinearProgressIndicator(Modifier.fillMaxWidth())
    trace?.let { tr ->
        Text("${tr.acceleration.size} acceleration samples · ${tr.rotation.size} rotation samples · ${tr.gaps} gaps >250 ms",style=MaterialTheme.typography.labelMedium)
        Chart(tr.acceleration,tr.rotation,0.0,maxTime.toDouble(),cursor.toDouble(),s.marks)
    }
    Slider(cursor.coerceIn(0f,maxTime),{cursor=it; exact=time(it.toDouble())},valueRange=0f..maxTime)
    OutlinedTextField(exact,{exact=it; it.toFloatOrNull()?.takeIf { v -> v.isFinite() && v in 0f..maxTime }?.let { v -> cursor=v }},label={Text("Marker timestamp (seconds)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
    Text("SF = start/finish crossing. S2 = start of sector 2. S3 = start of sector 3. Add every crossing at its timestamp; a sketch pin alone cannot determine a crossing.")
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("SF","S2","S3").forEach { kind -> OutlinedButton(onClick={
            val t=exact.toDoubleOrNull()
            if(t==null || !t.isFinite() || t<0 || t>maxTime) notice("Choose a timestamp inside the recording.")
            else if(s.marks.any { kotlin.math.abs(it.t-t)<.05 }) notice("A marker already exists within 0.05 s. Remove it first.")
            else save(s.copy(marks=(s.marks+Telemetry.Mark(t,kind,false)).sortedBy { it.t }))
        }) { Text("+ $kind") } }
    }
    s.marks.sortedBy { it.t }.forEach { mark ->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("${mark.kind} · ${time(mark.t)} s${if(mark.estimated) " · estimated" else " · manual"}",modifier=Modifier.weight(1f))
            TextButton(onClick={cursor=mark.t.toFloat(); exact=time(mark.t)}) { Text("Go") }
            TextButton(onClick={save(s.copy(marks=s.marks.filterNot { it===mark }))}) { Text("×") }
        }
    }
    Card { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Find similar finish crossings",style=MaterialTheme.typography.titleMedium)
        Text("Set the cursor to a known SF crossing, at least 2 s into the recording. Matches use a 4 s rotation pattern and ±20% lap-time range. They can miss laps or match the wrong corner; review each candidate.")
        OutlinedTextField(expected,{expected=it},label={Text("Expected lap time (10–180 s)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
        Button(enabled=trace!=null && !analyzing,onClick={scope.launch {
            analyzing=true
            try { suggestions=withContext(Dispatchers.Default) { Telemetry.suggest(trace!!.rotation,cursor.toDouble(),expected.toDouble()) }; notice("${suggestions.size} candidate crossings. Accepting keeps them labelled estimated.") }
            catch(e: Exception) { notice(e.message ?: "Could not analyze") }
            analyzing=false
        }}) { Text(if(analyzing) "Analyzing…" else "Suggest crossings") }
        suggestions.forEach { candidate -> Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick={cursor=candidate.t.toFloat(); exact=time(candidate.t)}) { Text("${time(candidate.t)} s") }
            TextButton(onClick={ if(s.marks.none { kotlin.math.abs(it.t-candidate.t)<.5 }) save(s.copy(marks=(s.marks+candidate).sortedBy { it.t })); suggestions=suggestions.filterNot { it===candidate } }) { Text("Accept") }
            TextButton(onClick={suggestions=suggestions.filterNot { it===candidate }}) { Text("Skip") }
        } }
    } }
    TrackSketch(s,save)
    Text("Lap sheet",style=MaterialTheme.typography.titleLarge)
    if(laps.isEmpty()) Text("Add two SF crossings to define a complete lap. Add one S2 and one S3 inside it for sector splits.")
    laps.forEachIndexed { i,lap -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text("LAP ${i+1} · ${time(lap.duration())} s${if(lap.estimated) " · EST" else " · MANUAL"}",color=Lime,style=MaterialTheme.typography.titleMedium)
        Text("S1 ${time(lap.s1)}   S2 ${time(lap.s2)}   S3 ${time(lap.s3)}")
        if(s.length>0) Text("${time(Telemetry.averageKmh(s.length,lap.duration()))} km/h average · from entered length")
    } } }
    if(laps.isNotEmpty()) {
        Text("Compare laps",style=MaterialTheme.typography.titleLarge)
        Text("Overlay normalized elapsed-time progress, not track position. Independent scales compare signal shape; they do not establish the same physical location.")
        Picker("Reference lap",laps.mapIndexed { i,l -> "${i+1} · ${time(l.duration())} s" },lapIndex.coerceIn(laps.indices)) { lapIndex=it }
        val choices=listOf(s)+sessions.filter { it.id!=s.id && it.direction==s.direction }
        val other=choices.find { it.id==compareId } ?: s
        Picker("Comparison session",choices.map { it.title+" · "+it.id.take(4) },choices.indexOf(other)) { onCompare(choices[it].id) }
        val otherLaps=Telemetry.laps(other.marks)
        if(otherLaps.isNotEmpty()) {
            val a=laps[lapIndex.coerceIn(laps.indices)]
            val b=otherLaps[compareLap.coerceIn(otherLaps.indices)]
            Picker("Comparison lap",otherLaps.mapIndexed { i,l -> "${i+1} · ${time(l.duration())} s" },compareLap.coerceIn(otherLaps.indices)) { compareLap=it }
            Text("Comparison − reference: ${time(b.duration()-a.duration())} s",style=MaterialTheme.typography.titleMedium,color=Cyan)
            Text("Sector deltas: ${time(b.s1-a.s1)} / ${time(b.s2-a.s2)} / ${time(b.s3-a.s3)} s")
            val reference=trace?.rotation.orEmpty().filter { it.t>=a.start && it.t<=a.end }.map { Telemetry.Point((it.t-a.start)/a.duration(),it.value) }
            val comparison=(if(other.id==s.id) trace else otherTrace)?.rotation.orEmpty().filter { it.t>=b.start && it.t<=b.end }.map { Telemetry.Point((it.t-b.start)/b.duration(),it.value) }
            Chart(reference,comparison,0.0,1.0,null,emptyList())
            Text("0% → 100% of lap time · rotation magnitude",style=MaterialTheme.typography.labelSmall)
        } else Text("No complete laps in the comparison session.")
    }
}

@Composable private fun Picker(label: String,options: List<String>,index: Int,onSelect: (Int)->Unit) {
    var open by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick={open=true}) { Text("$label: ${options.getOrElse(index){"—"}} ▾") }
        DropdownMenu(open,{open=false}) { options.forEachIndexed { i,text -> DropdownMenuItem(text={Text(text)},onClick={onSelect(i); open=false}) } }
    }
}

@Composable private fun Chart(a: List<Telemetry.Point>,b: List<Telemetry.Point>,start: Double,end: Double,cursor: Double?,marks: List<Telemetry.Mark>) {
    Canvas(Modifier.fillMaxWidth().height(180.dp).background(Color(0xFF1C241C)).semantics { contentDescription="Motion graph; lime reference, cyan secondary trace. Timing values appear below." }) {
        val range=(end-start).coerceAtLeast(.001)
        for(i in 1..3) drawLine(Color(0xFF344034),Offset(0f,size.height*i/4),Offset(size.width,size.height*i/4),1f)
        fun plot(points: List<Telemetry.Point>,color: Color) {
            if(points.isEmpty()) return
            val max=points.maxOf { it.value }.coerceAtLeast(.01)
            val stride=(points.size/1000).coerceAtLeast(1)
            val path=Path(); var begun=false; var previous: Telemetry.Point?=null
            points.forEachIndexed { i,p ->
                val gap=previous?.let { p.t-it.t>.25 } ?: true
                if(gap) begun=false
                if(i%stride==0 || i==points.lastIndex) {
                    val x=((p.t-start)/range*size.width).toFloat(); val y=(size.height-12-(p.value/max)*(size.height-24)).toFloat()
                    if(!begun) { path.moveTo(x,y); begun=true } else path.lineTo(x,y)
                }
                previous=p
            }
            drawPath(path,color,style=Stroke(2.dp.toPx()))
        }
        plot(a,Lime); plot(b,Cyan)
        marks.forEach { val x=((it.t-start)/range*size.width).toFloat(); drawLine(if(it.kind=="SF") Color.White else Color(0xFFE8B878),Offset(x,0f),Offset(x,size.height),1f) }
        cursor?.let { val x=((it-start)/range*size.width).toFloat(); drawLine(Color(0xFFFF9DA8),Offset(x,0f),Offset(x,size.height),3f) }
    }
}

@Composable private fun TrackSketch(s: Session,save: (Session)->Unit) {
    var mode by remember { mutableStateOf("Draw") }
    Text("Track reference",style=MaterialTheme.typography.titleLarge)
    Text("Tap successive points to sketch the circuit, then choose SF, S2 or S3 and tap its physical location. This is your reference drawing, not a measured path. Pins do not create timing markers.")
    Picker("Tap action",listOf("Draw","SF","S2","S3"),listOf("Draw","SF","S2","S3").indexOf(mode)) { mode=listOf("Draw","SF","S2","S3")[it] }
    Canvas(Modifier.fillMaxWidth().height(230.dp).background(Color(0xFF1C241C)).semantics { contentDescription="Tap to draw track reference or position selected sector pin" }.pointerInput(s,mode) {
        detectTapGestures { p ->
            val point=(p.x/size.width).coerceIn(0f,1f) to (p.y/size.height).coerceIn(0f,1f)
            if(mode=="Draw") save(s.copy(track=(s.track+point).take(500))) else save(s.copy(pins=s.pins+(mode to point)))
        }
    }) {
        fun xy(p: Pair<Float,Float>)=Offset(p.first*size.width,p.second*size.height)
        if(s.track.size>1) {
            val path=Path(); path.moveTo(xy(s.track[0]).x,xy(s.track[0]).y)
            s.track.drop(1).forEach { path.lineTo(xy(it).x,xy(it).y) }; path.close()
            drawPath(path,Color(0xFF647764),style=Stroke(12.dp.toPx()))
        }
        s.pins.forEach { (kind,p) -> drawCircle(when(kind) { "SF" -> Lime; "S2" -> Cyan; else -> Color(0xFFE8B878) },8.dp.toPx(),xy(p)) }
    }
    Text("SF: lime · S2: cyan · S3: amber",style=MaterialTheme.typography.labelSmall)
    Row { TextButton(onClick={save(s.copy(track=s.track.dropLast(1)))},enabled=s.track.isNotEmpty()) { Text("Undo point") }; TextButton(onClick={save(s.copy(track=emptyList(),pins=emptyMap()))}) { Text("Clear sketch") } }
}
