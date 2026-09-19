package dev.bananajeans.pitwall.wear

import android.content.Context
import android.content.Intent
import android.Manifest
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
 * Watch app entry point. Deliberately glanceable:
 *  - recording status (large, unmistakable)
 *  - pending transfer counts
 *  - sensor diagnostics summary (scroll)
 *  - a manual test-record control for bench validation without the phone
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

    // Poll recorder status while the screen is on (watch UIs must stay cheap).
    DisposableEffect(Unit) {
        val thread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                status = RecorderService.status
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        onDispose { thread.interrupt() }
    }

    // Refresh pending-transfer count when not recording (cheap, on demand).
    if (pending < 0 || !status.recording) {
        val store = remember { WatchLogStore(context) }
        val count = remember(status.recording) { store.pendingTransfer().size }
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
            status.error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            if (status.recording) {
                RecordingPanel(status)
            } else {
                IdlePanel(
                    pending = pending.coerceAtLeast(0),
                    showDiagnostics = showDiagnostics,
                    onToggleDiagnostics = { showDiagnostics = !showDiagnostics },
                    onStartTest = { startTestRecording(context) },
                    onStopTest = { stopTestRecording(context) }
                )
            }
            if (showDiagnostics) DiagnosticsPanel()
        }
    }
}

@Composable
private fun RecordingPanel(status: RecorderStatus) {
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
    Text(
        text = "Screen can turn off",
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun IdlePanel(
    pending: Int,
    showDiagnostics: Boolean,
    onToggleDiagnostics: () -> Unit,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit
) {
    Text(
        text = "Pocket Pitwall",
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center
    )
    Text(
        text = if (pending == 0) "Ready"
        else "$pending saved ${if (pending == 1) "log" else "logs"} waiting for phone",
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.primary
    )
    Button(onClick = onStartTest) { Text("Test record") }
    Button(onClick = onToggleDiagnostics) {
        Text(if (showDiagnostics) "Hide sensors" else "Sensors")
    }
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
