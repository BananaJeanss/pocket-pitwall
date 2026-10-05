package dev.bananajeans.pitwall.wear

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.wear.compose.material3.MaterialTheme
import dev.bananajeans.pitwall.protocol.Messages
import java.io.FileInputStream
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Captures the production UI on a genuine round Wear OS system image. */
class WatchScreenshotTest {
    @get:Rule(order = 0) val ui = createAndroidComposeRule<ScreenshotActivity>()
    @get:Rule(order = 1) val failureScreenshot = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            runCatching { capture("failure-${description.methodName}") }
        }
    }

    @Before fun requiresRoundDisplay() {
        assertTrue("Run on a round watch, not a phone emulator",
            InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.isScreenRound)
    }

    private fun capture(name: String) {
        require(name.matches(Regex("[a-zA-Z0-9-]+")))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (command in listOf("mkdir -p /data/local/tmp/pitwall-screenshots",
            "screencap -p /data/local/tmp/pitwall-screenshots/$name.png")) {
            automation.executeShellCommand(command).use { fd ->
                FileInputStream(fd.fileDescriptor).use { it.readBytes() }
            }
        }
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // The cold Wear OS launch overlay can outlive the first Compose frame.
        ui.waitUntil(20000) { ui.activity.hasWindowFocus() }
        ui.onRoot().captureToImage().asAndroidBitmap().recycle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 5000)
        capture(name)
    }

    @Test fun readyAndSensors() {
        val diagnostics = mutableStateOf(false)
        var started = false
        ui.setContent {
            MaterialTheme { WatchScreen(showDiagnostics = diagnostics.value,
                onToggleDiagnostics = { diagnostics.value = !diagnostics.value },
                onStart = { started = true }) }
        }
        ui.onNodeWithText("Ready (phone optional)").assertIsDisplayed()
        screenshot("01-ready")
        ui.onNodeWithText("Record now").performScrollTo().performClick()
        assertTrue(started)
        ui.onNodeWithText("Sensors").performScrollTo().performClick()
        ui.onNodeWithText("Hide sensors").performScrollTo().assertIsDisplayed()
        screenshot("02-sensors")
    }

    @Test fun recordingConnectedAndOffline() {
        val connected = mutableStateOf(true)
        var stopped = false
        ui.setContent {
            MaterialTheme { WatchScreen(status = RecorderStatus(recording = true,
                healthy = true, samples = 12480, elapsedSeconds = 125.0),
                phoneConnected = connected.value, onStop = { stopped = true }) }
        }
        ui.onNodeWithContentDescription("Recording in progress").assertIsDisplayed()
        ui.onNodeWithText("02:05").assertIsDisplayed()
        ui.onNodeWithText("Stop & save").performScrollTo().assertIsDisplayed()
        screenshot("03-recording-connected")
        ui.runOnIdle { connected.value = false }
        ui.onNodeWithText("phone away · logging on watch").assertIsDisplayed()
        screenshot("04-recording-offline")
        ui.onNodeWithText("Stop & save").performClick()
        assertTrue(stopped)
    }

    @Test fun queuedLogsAndRecorderError() {
        val status = mutableStateOf(RecorderStatus())
        var retried = false
        ui.setContent {
            MaterialTheme { WatchScreen(status = status.value, pending = 2,
                onStart = { retried = true }) }
        }
        ui.onNodeWithText("2 saved logs waiting for phone").assertIsDisplayed()
        screenshot("05-pending")
        ui.runOnIdle { status.value = RecorderStatus(error = "Watch storage is full") }
        ui.onNodeWithText("Recorder error: Watch storage is full").assertIsDisplayed()
        ui.onNodeWithText("Ready (phone optional)").assertDoesNotExist()
        screenshot("06-recorder-error")
        ui.onNodeWithText("Retry record").performScrollTo().performClick()
        assertTrue(retried)
    }

    @Test fun resultsAndIncompleteWarning() {
        var dismissed = false
        val result = Messages.Result("screenshot-session", 42.37, 12, 0.84, 7,
            172, 148, "incomplete", "Part of this recording was lost.")
        ui.setContent {
            MaterialTheme { WatchScreen(result = result, onDismissResults = { dismissed = true }) }
        }
        ui.onNodeWithText("Session results").assertIsDisplayed()
        ui.onNodeWithText("Best lap 42.37").assertIsDisplayed()
        screenshot("07-results")
        ui.onNodeWithText("Done").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Part of this recording was lost.").assertIsDisplayed()
        screenshot("08-results-warning")
        ui.onNodeWithText("Done").performClick()
        assertTrue(dismissed)
    }

    @Test fun productionActivityLaunch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (permission in listOf(android.Manifest.permission.BODY_SENSORS,
            android.Manifest.permission.POST_NOTIFICATIONS)) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var activity: MainActivity? = null
            scenario.onActivity { activity = it }
            ui.waitForIdle()
            instrumentation.waitForIdleSync()
            ui.waitUntil(20000) { activity?.hasWindowFocus() == true }
            ui.onNodeWithText("Ready (phone optional)").assertIsDisplayed()
            ui.onNodeWithText("Record now").assertIsDisplayed()
            ui.onRoot().captureToImage().asAndroidBitmap().recycle()
            instrumentation.uiAutomation.waitForIdle(250, 5000)
            capture("09-production-ready")
        }
    }
}
