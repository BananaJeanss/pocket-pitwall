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

class NavigationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        require(name.matches(Regex("[a-zA-Z0-9-]+")))
        ui.waitForIdle()
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
        ui.onNodeWithText("Light",useUnmergedTree=true).performClick()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Theme: Light ▾").assertIsDisplayed()
        assertEquals("Light",AppSettings.read(ui.activity).theme)
        screenshot("03-light-settings")
    }

    @Test fun savedSessionReviewAndBack() {
        val fixture=Session(title="UI regression fixture",status="complete",duration=50.0,
            marks=listOf(Telemetry.Mark(5.0,"SF",false),Telemetry.Mark(45.0,"SF",false)))
        SessionStore(ui.activity).save(fixture)
        SessionRepository.refresh()
        ui.waitUntil(5000) { SessionRepository.sessions.value.any { it.id==fixture.id } }
        ui.onAllNodesWithText("Sessions").onLast().performClick()
        ui.onNodeWithText(fixture.title).performClick()
        ui.onNodeWithText("Lap sheet").assertIsDisplayed()
        screenshot("04-lap-sheet")
        ui.onNodeWithText("Timeline").performClick()
        ui.onNodeWithText("Motion timeline").assertIsDisplayed()
        ui.onNodeWithText("Details").performClick()
        ui.onNodeWithText("Session details").assertIsDisplayed()
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
}
