package dev.bananajeans.pitwall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bananajeans.pitwall.core.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var transferManager: WatchTransferManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionRepository.initialize(applicationContext)
        WatchLink.initialize(applicationContext)
        transferManager = WatchTransferManager(this)
        transferManager.start()
        transferManager.pullPending()
        setContent {
            var settings by remember { mutableStateOf(AppSettings.read(this)) }
            val dark = when (settings.theme) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
            val colors = when {
                settings.dynamicColors && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                dark -> darkColorScheme(primary=Lime, secondary=Cyan, background=Color(0xFF101410), surface=Color(0xFF181E18), onPrimary=Color(0xFF243400))
                else -> lightColorScheme(primary=Color(0xFF446600), secondary=Color(0xFF006A65))
            }
            LaunchedEffect(dark, settings.fullscreen) {
                val bar = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle=bar, navigationBarStyle=bar)
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (settings.fullscreen) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
                }
            }
            MaterialTheme(colorScheme=colors) {
                Pitwall(settings) { settings=it; it.save(this) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Pitwall(settings: AppSettings, changeSettings: (AppSettings) -> Unit) {
    val context = LocalContext.current
    val store = remember { SessionStore(context) }
    val backups = remember { BackupStore(context) }
    val scope = rememberCoroutineScope()
    val sessions by SessionRepository.sessions.collectAsStateWithLifecycle()
    val ready by SessionRepository.ready.collectAsStateWithLifecycle()
    val saveError by SessionRepository.error.collectAsStateWithLifecycle()
    val active by RecorderService.active.collectAsStateWithLifecycle()
    val elapsed by RecorderService.elapsed.collectAsStateWithLifecycle()
    val recordingError by RecorderService.error.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf("Record") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var compareId by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf(0) }
    var title by rememberSaveable { mutableStateOf(settings.defaultTrack) }
    var reverse by rememberSaveable { mutableStateOf(settings.defaultReverse) }
    var help by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Session?>(null) }
    var busy by remember { mutableStateOf(false) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportType by rememberSaveable { mutableStateOf("zip") }
    var update by remember { mutableStateOf<UpdateState>(UpdateChecker.cached(context)) }
    val snackbar = remember { SnackbarHostState() }
    val savedStates = rememberSaveableStateHolder()
    fun notice(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    fun back() { if (selectedId != null) selectedId=null else destination="Record" }
    BackHandler(selectedId != null || destination != "Record") { back() }
    LaunchedEffect(active) { if (!active) SessionRepository.refresh() }
    LaunchedEffect(saveError, recordingError) { (saveError ?: recordingError)?.let { snackbar.showSnackbar(it) } }
    suspend fun checkUpdates() { update=UpdateState.Checking; update=UpdateChecker.check(context.applicationContext) }
    LaunchedEffect(settings.autoUpdates) {
        if (settings.autoUpdates && UpdateChecker.due(context)) checkUpdates()
    }
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { notice("No browser available.") }
    }
    fun startRecording() {
        runCatching {
            context.startForegroundService(Intent(context, RecorderService::class.java)
                .putExtra("title", title.ifBlank { "Untitled session" })
                .putExtra("direction", if (reverse) "Reverse" else "Normal"))
        }.onFailure { notice("Cannot start recording: ${it.message}") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startRecording() }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val session = sessions.find { it.id == exportId }
        if (uri != null && session != null) scope.launch {
            busy=true
            var staged: java.io.File? = null
            try {
                withContext(Dispatchers.IO) {
                    val suffix = ".$exportType"
                    staged = java.io.File.createTempFile("pitwall-export-", suffix, context.cacheDir)
                    staged!!.outputStream().buffered().use { out ->
                        when (exportType) {
                            "csv" -> {
                                val raw = store.raw(session.id)
                                require(raw.isFile) { "Sensor data is unavailable for this session." }
                                raw.inputStream().use { it.copyTo(out) }
                            }
                            "json" -> out.write(session.json().toString(2).toByteArray())
                            else -> store.writeZip(session, out)
                        }
                    }
                    require(staged!!.length() > 0L) { "Export produced no data." }
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { target ->
                        staged!!.inputStream().buffered().use { source -> source.copyTo(target) }
                        target.flush()
                    }
                }
                notice("Export saved")
            } catch (e: Exception) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                notice("Export failed: ${e.message}")
            } finally {
                withContext(Dispatchers.IO) { staged?.delete() }
                busy=false
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy=true
            try {
                val imported = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { store.importZip(it) }
                }
                SessionRepository.refresh()
                selectedId=null
                destination="Sessions"
                notice("Imported ${imported.title}")
            } catch (e: Exception) { notice("Import failed: ${e.message}") }
            finally { busy=false }
        }
    }
    val backupFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
                val previous = settings.backupTreeUri
                changeSettings(settings.copy(backupTreeUri=uri.toString(), autoBackups=true))
                if (previous.isNotBlank() && previous != uri.toString()) {
                    runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(previous), flags) }
                }
                scope.launch {
                    busy=true
                    try {
                        val result = withContext(Dispatchers.IO) { backups.backupAll(store, sessions, uri.toString()) }
                        notice(if (result.failed == 0) "Backup folder ready · ${result.backedUp} sessions backed up"
                            else "Backup folder ready · ${result.backedUp} saved, ${result.failed} failed")
                    } catch (e: Exception) { notice("Backup folder failed: ${e.message}") }
                    finally { busy=false }
                }
            } catch (e: Exception) { notice("Cannot use that folder: ${e.message}") }
        }
    }
    fun backupNow() {
        if (settings.backupTreeUri.isBlank()) { notice("Choose a backup folder first"); return }
        scope.launch {
            busy=true
            try {
                val result = withContext(Dispatchers.IO) { backups.backupAll(store, sessions, settings.backupTreeUri) }
                notice(if (result.failed == 0) "Backed up ${result.backedUp} sessions"
                    else "Backed up ${result.backedUp} · ${result.failed} failed")
            } catch (e: Exception) { notice("Backup failed: ${e.message}") }
            finally { busy=false }
        }
    }
    fun restoreBackups() {
        if (settings.backupTreeUri.isBlank()) { notice("Choose a backup folder first"); return }
        scope.launch {
            busy=true
            try {
                val result = withContext(Dispatchers.IO) { backups.restoreMissing(store, settings.backupTreeUri) }
                SessionRepository.refresh()
                notice("Restored ${result.imported} · ${result.skipped} already here · ${result.failed} failed")
            } catch (e: Exception) { notice("Restore failed: ${e.message}") }
            finally { busy=false }
        }
    }
    fun disconnectBackupFolder() {
        val tree = settings.backupTreeUri
        if (tree.isBlank()) return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(tree), flags) }
        changeSettings(settings.copy(backupTreeUri=""))
        notice("Backup folder disconnected")
    }
    val selected = sessions.find { it.id == selectedId }
    val reviewScroll = rememberSaveable(selectedId, section, saver=ScrollState.Saver) { ScrollState(0) }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(title={ Text(if (selectedId != null) selected?.title ?: "Session" else destination, maxLines=1) },
                    navigationIcon={ if (selectedId != null || destination != "Record") IconButton(onClick={back()}) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back") } },
                    actions={ IconButton(onClick={help=true}) { Icon(Icons.Default.HelpOutline,"Help") } })
                if (selected != null) ScrollableTabRow(selectedTabIndex=section, edgePadding=16.dp) {
                    listOf("Laps", "Timeline", "Compare", "Details").forEachIndexed { i, label -> Tab(selected=section==i,onClick={section=i},text={Text(label)}) }
                }
            }
        },
        bottomBar = { if (selectedId == null) NavigationBar {
            listOf("Record" to Icons.Default.RadioButtonChecked, "Sessions" to Icons.Default.History, "Settings" to Icons.Default.Settings).forEach { (label, icon) ->
                NavigationBarItem(selected=destination==label,onClick={destination=label},icon={Icon(icon,null)},label={Text(label)})
            }
        } },
        snackbarHost={SnackbarHost(snackbar)},
        contentWindowInsets=WindowInsets.safeDrawing
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
        if (selectedId != null) {
            if (selected != null) savedStates.SaveableStateProvider(selected.id) {
                Column(contentModifier.verticalScroll(reviewScroll).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Review(selected,store,sessions,compareId,{compareId=it},SessionRepository::save,::notice,section)
                    if (section == 3) {
                        Text("Export",style=MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf("zip","csv","json").forEach { type -> OutlinedButton(enabled=!busy,onClick={exportId=selected.id; exportType=type; exporter.launch("pitwall-${selected.id}.$type")}) { Text(type.uppercase()) } }
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(enabled=!busy,onClick={deleteTarget=selected}) { Icon(Icons.Default.Delete,null); Spacer(Modifier.width(8.dp)); Text("Delete session") }
                    }
                }
            } else Box(contentModifier,contentAlignment=Alignment.Center) { if (!ready) CircularProgressIndicator() else Text("Session unavailable") }
        } else when (destination) {
            "Sessions" -> LazyColumn(contentModifier,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if (!ready) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    OutlinedButton(
                        enabled=!busy && !active,
                        onClick={importer.launch(arrayOf("application/zip","application/octet-stream"))},
                        modifier=Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FileOpen,null); Spacer(Modifier.width(8.dp)); Text("Import session ZIP")
                    }
                }
                if (ready && sessions.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical=48.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Default.History,null,Modifier.size(48.dp)); Text("No sessions yet")
                        Button(onClick={destination="Record"}) { Text("Record a session") }
                    }
                }
                items(sessions,key={it.id}) { session ->
                    Card(onClick={selectedId=session.id; section=0},enabled=!active,modifier=Modifier.fillMaxWidth()) {
                        ListItem(headlineContent={Text(session.title,maxLines=2)},
                            supportingContent={Text("${SimpleDateFormat("dd MMM · HH:mm",Locale.getDefault()).format(Date(session.created))} · ${session.direction}\n${Telemetry.laps(session.marks).size} laps · ${time(session.duration)} s${if(session.status=="interrupted") " · interrupted" else ""}")},
                            trailingContent={Icon(Icons.Default.ChevronRight,null)})
                    }
                }
            }
            "Settings" -> Column(contentModifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                SettingsScreen(
                    settings=settings,
                    change={ next ->
                        if (next.defaultTrack != settings.defaultTrack) title=next.defaultTrack
                        if (next.defaultReverse != settings.defaultReverse) reverse=next.defaultReverse
                        changeSettings(next)
                    },
                    update=update,
                    check={scope.launch { checkUpdates() }},
                    open=::open,
                    backupFolder=backups.folderLabel(settings.backupTreeUri),
                    backupBusy=busy,
                    chooseBackupFolder={backupFolderPicker.launch(null)},
                    backupNow=::backupNow,
                    restoreBackups=::restoreBackups,
                    disconnectBackupFolder=::disconnectBackupFolder
                )
            }
            else -> Column(contentModifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                if (update is UpdateState.Available) AssistChip(onClick={open((update as UpdateState.Available).url)},label={Text("Update available")},leadingIcon={Icon(Icons.Default.SystemUpdate,null)})
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Icon(if(active) Icons.Default.RadioButtonChecked else Icons.Default.Flag,null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.primary)
                        Text(if(active) String.format(Locale.US,"%02d:%02d",elapsed.toInt()/60,elapsed.toInt()%60) else "Ready",style=MaterialTheme.typography.displayMedium,fontWeight=FontWeight.Medium)
                        Text(if(active) "Recording · screen can be locked" else "Start when parked",style=MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!active) {
                    OutlinedTextField(title,{title=it.take(100)},label={Text("Track")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(!reverse,{reverse=false},label={Text("Normal")}); FilterChip(reverse,{reverse=true},label={Text("Reverse")})
                    }
                }
                WatchStatusLine(active)
                Button(onClick={
                    if (active) context.startService(Intent(context,RecorderService::class.java).setAction(RecorderService.STOP))
                    else if (Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else startRecording()
                },modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                    Icon(if(active) Icons.Default.Stop else Icons.Default.PlayArrow,null); Spacer(Modifier.width(8.dp)); Text(if(active) "Stop & save" else "Start recording")
                }
                OutlinedButton(onClick={destination="Sessions"},modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.History,null); Spacer(Modifier.width(8.dp)); Text("Saved sessions") }
                if (!active) Text("Motion recording · position and speed unavailable",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (help) AlertDialog(onDismissRequest={help=false},title={Text("Quick guide")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Record while parked, secure your phone as the venue allows, then lock the screen. Stop when parked. Sessions stop after one hour.")
            Text("Timeline: add SF at each finish crossing. S2 and S3 mark the starts of sectors 2 and 3. Use track timing or video to identify crossings.")
            Text("Suggestions are estimated matches. Track sketches are references, not measured positions. Average speed needs a known lap length.")
            Text("Graphs use independent scales. Comparisons align lap time, not physical location. Pocket motion includes movement of your body.")
            Text("Data & backups: choose a user-owned folder to keep verified session ZIPs outside app storage. Use Restore / rescan after reinstalling. Details also supports manual ZIP, CSV and JSON exports.")
        }
    },confirmButton={TextButton(onClick={help=false}) { Text("Got it") }})
    deleteTarget?.let { session -> AlertDialog(onDismissRequest={deleteTarget=null},title={Text("Delete session?")},text={Text("This removes ${session.title} and its sensor data. Export first to keep a copy.")},
        confirmButton={TextButton(onClick={SessionRepository.delete(session.id); selectedId=null; destination="Sessions"; deleteTarget=null; savedStates.removeState(session.id)}) { Text("Delete") }},
        dismissButton={TextButton(onClick={deleteTarget=null}) { Text("Cancel") }}) }
}

@Composable private fun SettingsScreen(
    settings: AppSettings,
    change: (AppSettings)->Unit,
    update: UpdateState,
    check: ()->Unit,
    open: (String)->Unit,
    backupFolder: String?,
    backupBusy: Boolean,
    chooseBackupFolder: ()->Unit,
    backupNow: ()->Unit,
    restoreBackups: ()->Unit,
    disconnectBackupFolder: ()->Unit
) {
    Text("Appearance",style=MaterialTheme.typography.titleMedium)
    Picker("Theme",listOf("System","Light","Dark"),listOf("System","Light","Dark").indexOf(settings.theme).coerceAtLeast(0)) { change(settings.copy(theme=listOf("System","Light","Dark")[it])) }
    SettingSwitch("Wallpaper colors",settings.dynamicColors,{change(settings.copy(dynamicColors=it))},Build.VERSION.SDK_INT>=31)
    SettingSwitch("Fullscreen",settings.fullscreen,{change(settings.copy(fullscreen=it))})
    HorizontalDivider()
    Text("Recording defaults",style=MaterialTheme.typography.titleMedium)
    OutlinedTextField(settings.defaultTrack,{change(settings.copy(defaultTrack=it.take(100)))},label={Text("Default track")},singleLine=true,modifier=Modifier.fillMaxWidth())
    SettingSwitch("Reverse direction",settings.defaultReverse,{change(settings.copy(defaultReverse=it))})
    HorizontalDivider()
    Text("Data & backups",style=MaterialTheme.typography.titleMedium)
    Text(
        if (backupFolder == null) "No backup folder selected" else "Backup folder · $backupFolder",
        style=MaterialTheme.typography.bodyMedium
    )
    Text(
        "Session backups live outside Pocket Pitwall and survive app uninstall. The private copy remains the working copy.",
        style=MaterialTheme.typography.bodySmall
    )
    OutlinedButton(onClick=chooseBackupFolder,enabled=!backupBusy) {
        Icon(Icons.Default.FolderOpen,null); Spacer(Modifier.width(8.dp))
        Text(if (backupFolder == null) "Choose backup folder" else "Change backup folder")
    }
    SettingSwitch(
        "Automatic session backups",
        settings.autoBackups,
        { change(settings.copy(autoBackups=it)) },
        enabled=backupFolder != null && !backupBusy
    )
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick=backupNow,enabled=backupFolder != null && !backupBusy,modifier=Modifier.weight(1f)) {
            Icon(Icons.Default.Backup,null); Spacer(Modifier.width(6.dp)); Text("Back up now")
        }
        OutlinedButton(onClick=restoreBackups,enabled=backupFolder != null && !backupBusy,modifier=Modifier.weight(1f)) {
            Icon(Icons.Default.Restore,null); Spacer(Modifier.width(6.dp)); Text("Restore / rescan")
        }
    }
    if (backupBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (backupFolder != null) {
        TextButton(onClick=disconnectBackupFolder,enabled=!backupBusy) { Text("Disconnect backup folder") }
    }
    HorizontalDivider()
    Text("Updates",style=MaterialTheme.typography.titleMedium)
    SettingSwitch("Check automatically",settings.autoUpdates,{change(settings.copy(autoUpdates=it))})
    Text("Daily check on launch. Only GitHub is contacted; recordings stay on your phone.",style=MaterialTheme.typography.bodySmall)
    Text(when(update) {
        UpdateState.Idle -> "Version ${BuildConfig.VERSION_NAME}"
        UpdateState.Checking -> "Checking…"
        UpdateState.Current -> "You're up to date · ${BuildConfig.VERSION_NAME}"
        UpdateState.NoRelease -> "No published release yet"
        is UpdateState.Available -> "${update.version} is available"
        is UpdateState.Failed -> update.message
    })
    OutlinedButton(onClick=check,enabled=update!=UpdateState.Checking) { Icon(Icons.Default.Refresh,null); Spacer(Modifier.width(8.dp)); Text("Check for updates") }
    if (update is UpdateState.Available) Button(onClick={open(update.url)}) { Text("View update") }
    HorizontalDivider()
    Text("About",style=MaterialTheme.typography.titleMedium)
    Text("Pocket Pitwall ${BuildConfig.VERSION_NAME}\nAI-generated with OpenAI Codex · MIT licence",style=MaterialTheme.typography.bodySmall)
    TextButton(onClick={open("https://github.com/BananaJeanss/pocket-pitwall")}) { Text("Source & issues") }
}

@Composable private fun SettingSwitch(label: String,value: Boolean,change: (Boolean)->Unit,enabled: Boolean=true) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(label,Modifier.weight(1f)); Switch(checked=value,onCheckedChange=change,enabled=enabled,modifier=Modifier.semantics { contentDescription=label })
    }
}

/** One-line watch availability indicator; absent/invisible when no watch exists. */
@Composable private fun WatchStatusLine(recording: Boolean) {
    var state by remember { mutableStateOf(WatchLink.currentState) }
    LaunchedEffect(Unit) {
        while (true) {
            state = WatchLink.currentState
            kotlinx.coroutines.delay(2000)
        }
    }
    if (!state.watchConnected) {
        Text(
            "No watch connected · phone-only recording works normally",
            style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        val label = when {
            recording && state.control.state == dev.bananajeans.pitwall.protocol.SessionControl.CommandState.RECORDING -> "Watch recording"
            recording -> "Watch starting…"
            state.control.state == dev.bananajeans.pitwall.protocol.SessionControl.CommandState.STOPPED -> "Watch log saved"
            else -> "Watch connected · will record with sessions"
        }
        Text(
            label,
            style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.primary
        )
    }
}
