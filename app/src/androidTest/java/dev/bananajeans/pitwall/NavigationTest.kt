package dev.bananajeans.pitwall

import java.io.FileInputStream
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import dev.bananajeans.pitwall.core.Telemetry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class NavigationTest {
    @get:Rule(order = 0) val ui = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val failureScreenshot = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            runCatching { capture("failure-${description.methodName}") }
        }
    }

    private fun screenshot(name: String) {
        require(name.matches(Regex("[a-zA-Z0-9-]+")))
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Semantics can update before SurfaceFlinger presents the new frame.
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 5000)
        capture(name)
    }

    private fun capture(name: String) {
        require(name.matches(Regex("[a-zA-Z0-9-]+")))
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        // Shell-owned output survives the test runner uninstalling the app.
        fun shell(command: String) {
            automation.executeShellCommand(command).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
        shell("mkdir -p /data/local/tmp/pitwall-screenshots")
        shell("screencap -p /data/local/tmp/pitwall-screenshots/$name.png")
    }

    private fun assertStatusBarContrast(light: Boolean) {
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val color = bitmap.getPixel(bitmap.width / 2, 2)
            val brightness = (android.graphics.Color.red(color) + android.graphics.Color.green(color) +
                android.graphics.Color.blue(color)) / 3.0
            assertTrue("Status-bar background must match ${if (light) "light" else "dark"} icons",
                if (light) brightness > 180 else brightness < 90)
        } finally { bitmap.recycle() }
    }

    @Test fun destinationsAndSystemBack() {
        ui.onNodeWithText("Start recording").assertIsDisplayed()
        ui.onNodeWithText("Track").performTextReplacement("Draft track")
        closeSoftKeyboard()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Draft track").assertExists()
        screenshot("01-record")
        ui.onAllNodesWithText("Settings").onLast().performClick()
        ui.onNodeWithText("Appearance").assertIsDisplayed()
        screenshot("02-settings")
        ui.onNodeWithContentDescription("Fullscreen").performClick()
        ui.onNodeWithText("Appearance").assertIsDisplayed()
        screenshot("05-fullscreen")
        ui.onNodeWithContentDescription("Fullscreen").performClick()
        pressBack()
        ui.onNodeWithText("Start recording").assertIsDisplayed()
        ui.onAllNodesWithText("Sessions").onLast().performClick()
        pressBack()
        ui.onNodeWithText("Start recording").assertIsDisplayed()
    }

    @Test fun themeSurvivesActivityRecreation() {
        ui.onAllNodesWithText("Settings").onLast().performClick()
        val current=AppSettings.read(ui.activity)
        ui.onNodeWithText("Theme: ${current.theme} ▾").performClick()
        ui.onNodeWithText("Dark",useUnmergedTree=true).performClick()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Theme: Dark ▾").assertIsDisplayed()
        assertEquals("Dark",AppSettings.read(ui.activity).theme)
        screenshot("10-dark-settings")
        assertStatusBarContrast(light = false)
        ui.onNodeWithText("Theme: Dark ▾").performClick()
        ui.onNodeWithText("Light",useUnmergedTree=true).performClick()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Theme: Light ▾").assertIsDisplayed()
        assertEquals("Light",AppSettings.read(ui.activity).theme)
        screenshot("03-light-settings")
        assertStatusBarContrast(light = true)
    }

    @Test fun savedSessionReviewAndBack() {
        val fixture=Session(title="UI regression fixture",status="complete",duration=50.0,
            marks=listOf(Telemetry.Mark(5.0,"SF",false),Telemetry.Mark(45.0,"SF",false)))
        val store = SessionStore(ui.activity)
        store.save(fixture)
        store.raw(fixture.id).writeText(buildString {
            appendLine("elapsed_s,sensor_type,x,y,z,w,accuracy")
            for (i in 0..1000) {
                val t = i / 20.0
                appendLine("$t,10,${kotlin.math.sin(t)},0,0,,3")
                appendLine("$t,4,0,0,${kotlin.math.cos(t) * 0.5},,3")
            }
        })
        SessionRepository.refresh()
        ui.waitUntil(5000) { SessionRepository.sessions.value.any { it.id==fixture.id } }
        ui.onAllNodesWithText("Sessions").onLast().performClick()
        screenshot("06-sessions")
        ui.onNodeWithText(fixture.title).performClick()
        ui.onNodeWithText("Lap sheet").assertIsDisplayed()
        screenshot("04-lap-sheet")
        ui.onNodeWithText("Timeline").performClick()
        ui.onNodeWithText("Motion timeline").assertIsDisplayed()
        ui.waitUntil(5000) { ui.onAllNodesWithText("1001 acceleration samples", substring = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("07-timeline")
        ui.onNodeWithText("Details").performClick()
        ui.onNodeWithText("Session details").assertIsDisplayed()
        screenshot("08-details")
        ui.onNodeWithText("Kart, conditions, notes").performTextInput("Persist across recreation")
        closeSoftKeyboard()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Session details").assertIsDisplayed()
        ui.onNodeWithText("Persist across recreation").assertExists()
        ui.waitUntil(5000) { SessionStore(ui.activity).list().any { it.id==fixture.id && it.notes=="Persist across recreation" } }
        closeSoftKeyboard()
        pressBack()
        ui.onNodeWithText(fixture.title).assertIsDisplayed()
        ui.runOnIdle {
            SessionRepository.save(fixture.copy(notes="Queued write"))
            SessionRepository.delete(fixture.id)
            SessionRepository.save(fixture.copy(notes="Stale write after delete"))
        }
        ui.waitUntil(5000) { SessionStore(ui.activity).list().none { it.id==fixture.id } }
    }

    @Test fun recordingCanBeStoppedAndSaved() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                ui.activity.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val title = "Recording screenshot fixture"
        ui.onNodeWithText("Track").performTextReplacement(title)
        closeSoftKeyboard()
        ui.onNodeWithText("Start recording").performClick()
        try {
            // Wait past service initialization so a transient active flag
            // cannot hide a failed sensor/foreground-service start.
            ui.waitUntil(5000) { RecorderService.active.value && RecorderService.elapsed.value >= 1.0 }
            ui.onNodeWithText("Recording · screen can be locked").assertIsDisplayed()
            ui.onNodeWithText("Stop & save").assertIsDisplayed()
            screenshot("09-recording")
            ui.onNodeWithText("Stop & save").performClick()
            ui.waitUntil(5000) { !RecorderService.active.value }
            ui.onNodeWithText("Start recording").assertIsDisplayed()
            ui.waitUntil(5000) {
                SessionStore(ui.activity).list().any { it.title == title && it.status == "complete" && it.duration >= 1.0 }
            }
        } finally {
            ui.activity.startService(android.content.Intent(ui.activity, RecorderService::class.java)
                .setAction(RecorderService.STOP))
        }
    }
}
