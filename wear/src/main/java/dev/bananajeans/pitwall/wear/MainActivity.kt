package dev.bananajeans.pitwall.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText

/**
 * Minimal launchable watch face activity for the Wear OS foundation layer.
 *
 * Real recording UI arrives with the sensor/recording layers; this screen
 * exists so the module builds, installs and launches on a watch, and so the
 * app exists for Wear Data Layer node discovery.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchApp()
        }
    }
}

@Composable
private fun WatchApp() {
    TimeText()
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Pocket Pitwall",
            textAlign = TextAlign.Center
        )
        Text(
            text = "Watch telemetry companion\nis being set up.",
            textAlign = TextAlign.Center
        )
    }
}
