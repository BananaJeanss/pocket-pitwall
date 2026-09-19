package dev.bananajeans.pitwall.wear

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import java.util.Locale
import java.util.UUID

/**
 * Watch app entry point (issue #25).
 *
 * State machine, glanceable-first:
 *   Ready -> Recording -> Saved/Transferring -> Results
 *
 * Rules:
 *  - Recording status is unmistakable (large REC), trustworthy with the
 *    screen off (foreground service + wake lock do the real work).
 *  - Phone disconnection is NOT an error while local logging is healthy:
 *    the UI shows a quiet "phone away" note, never a failure.
 *  - Results come from the phone's canonical analysis; the watch never
 *    derives conflicting numbers.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recover orphaned recordings from a previous process/watch restart.
        WatchLogStore(this).recover()
        // BODY_SENSORS is needed only for optional heart-rate; IMU recording
        // works without it (issue #24 tolerance contract).
        if (HeartRateRecorder.needsPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS), 1)
        }
        setContent {
            MaterialTheme {
                WatchApp()
            }
        }
    }
}

@Composable
private fun WatchApp() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(RecorderService.status) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(-1) }
    var connection by remember {
        mutableStateOf((context.applicationContext as PitwallWatchApplication).connection.connectionState)
    }
    // Observe results reactively via LiveData
    val resultsLiveData = WatchResultsStore.observeResults(context)
    var results by mutableStateOf(WatchResultsStore.list(context))
    var showResult by remember { mutableStateOf(false) }

    // Observe results LiveData for reactive updates
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(resultsLiveData) {
        resultsLiveData.observe(lifecycleOwner, { newResults ->
            results = newResults ?: emptyList()
        })
    }

    // Poll for state changes
    DisposableEffect(Unit) {
        val app = context.applicationContext as PitwallWatchApplication
        val thread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                status = RecorderService.status
                connection = app.connection.connectionState
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        onDispose { thread.interrupt() }
    }

    // Refresh pending count - always re-read when not recording (transfer completion)
    if (pending < 0 || !status.recording) {
        val store = remember { WatchLogStore(context) }
        val count = remember(status.recording, connection.phoneConnected) { store.pendingTransfer().size }
        if (count != pending) pending = count
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TimeText()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                status.recording -> RecordingPanel(status, connection.phoneConnected)
                showResult && results.isNotEmpty() -> ResultPanel(results.first(), onDone = { showResult = false })
                else -> IdlePanel(
                    context = context,
                    pending = pending.coerceAtLeast(0),
                    phoneConnected = connection.phoneConnected,
                    showDiagnostics = showDiagnostics,
                    resultsAvailable = results.isNotEmpty(),
                    hasError = status.error != null,
                    errorMessage = status.error,
                    onToggleDiagnostics = { showDiagnostics = !showDiagnostics },
                    onShowResults = { showResult = true },
                    onStartTest = { startTestRecording(context) },
                    onStopTest = { stopTestRecording(context) }
                )
            }
            if (showDiagnostics) DiagnosticsPanel()
        }
    }
}

@Composable
private fun RecordingPanel(status: RecorderStatus, phoneConnected: Boolean) {
    Text(
        text = "REC",
        color = Color(0xFFFF5252),
        style = MaterialTheme.typography.displayMedium,
        modifier = Modifier.semantics { contentDescription = "Recording in progress" }
    )
    Text(
        text = formatElapsed(status.elapsedSeconds),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center
    )
    Text(
        text = if (status.healthy) "logging OK · ${status.samples} samples" else "logging problem",
        color = if (status.healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center
    )
    // Phone loss is NOT a failure while local logging is healthy (issue #25).
    Text(
        text = when {
            status.healthy && phoneConnected -> "phone connected"
            status.healthy -> "phone away · logging safely on watch"
            else -> "check watch storage"
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (status.healthy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun IdlePanel(
    context: Context,
    pending: Int,
    phoneConnected: Boolean,
    showDiagnostics: Boolean,
    resultsAvailable: Boolean,
    hasError: Boolean,
    errorMessage: String?,
    onToggleDiagnostics: () -> Unit,
    onShowResults: () -> Unit,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit
) {
    Text(
        text = "Pocket Pitwall",
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center
    )
    when {
        hasError -> {
            // Show recorder error prominently - never fall through to "Ready"
            Text(
                "Recorder error: ${errorMessage ?: "unknown"}",
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Button(onClick = onStartTest) { Text("Retry record") }
        }
        pending > 0 -> Text(
            "$pending saved ${if (pending == 1) "log" else "logs"} waiting for phone",
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        resultsAvailable -> Button(onClick = onShowResults) { Text("Last result") }
        else -> Text(
            if (phoneConnected) "Ready · phone connected" else "Ready (phone optional)",
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
    }
    Button(onClick = onStartTest) { Text("Test record") }
    Button(onClick = onToggleDiagnostics) {
        Text(if (showDiagnostics) "Hide sensors" else "Sensors")
    }
}

@Composable
private fun ResultPanel(result: dev.bananajeans.pitwall.protocol.Messages.Result, onDone: () -> Unit) {
    Text(
        text = "Session results",
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center
    )
    result.bestLapSeconds?.let {
        Text("Best lap ${formatSeconds(it)}", color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
    }
    Text("${result.lapCount} ${if (result.lapCount == 1) "lap" else "laps"}", textAlign = TextAlign.Center)
    result.steeringSmoothness?.let { smooth ->
        val pct = (smooth * 100).toInt().coerceIn(0, 100)
        Text("Steering smoothness ${pct}%", textAlign = TextAlign.Center)
    }
    result.correctionCount?.let { Text("$it corrections", textAlign = TextAlign.Center) }
    if (result.peakHr != null || result.averageHr != null) {
        Text(
            "HR peak ${result.peakHr ?: "—"} · avg ${result.averageHr ?: "—"}",
            textAlign = TextAlign.Center
        )
    }
    result.watchDataQuality?.takeIf { it != "ok" }?.let { quality ->
        Text(
            "Watch data: $quality",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center
        )
    }
    // Show notes (especially incomplete-log warnings)
    result.notes?.takeIf { it.isNotBlank() }?.let { note ->
        Text(
            note,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center
        )
    }
    Button(onClick = onDone) { Text("Done") }
}

@Composable
private fun DiagnosticsPanel() {
    val context = LocalContext.current
    val lines = remember {
        SensorProbe.probeAll(context).map { (candidate, probed) ->
            if (probed.sensor == null) "${candidate.wireName}: unavailable"
            else "${candidate.wireName}: ${probed.sensor.name} @ " +
                (if (probed.chosenPeriodMicros > 0) "%.0f Hz".format(Locale.US, 1_000_000.0 / probed.chosenPeriodMicros) else "on-change")
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        for (line in lines) {
            Text(text = line, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Start)
        }
    }
}

private fun startTestRecording(context: Context) {
    context.startForegroundService(
        Intent(context, RecorderService::class.java)
            .setAction(RecorderService.ACTION_START)
            .putExtra(RecorderService.EXTRA_SESSION_ID, UUID.randomUUID().toString())
            .putExtra(RecorderService.EXTRA_TITLE, "Watch test recording")
    )
}

private fun stopTestRecording(context: Context) {
    context.startService(
        Intent(context, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
    )
}

private fun formatElapsed(seconds: Double): String {
    val total = seconds.toLong()
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}

private fun formatSeconds(seconds: Double): String =
    String.format(Locale.US, "%.2f", seconds)
