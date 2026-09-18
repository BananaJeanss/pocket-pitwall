package dev.bananajeans.pitwall

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import dev.bananajeans.pitwall.core.Telemetry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.io.File

class NavigationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.getExternalFilesDir(null),"screenshots").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
    }

    @Test fun destinationsAndSystemBack() {
        ui.onNodeWithText("Start recording").assertIsDisplayed()
        screenshot("01-record")
        ui.onAllNodesWithText("Settings").onLast().performClick()
        ui.onNodeWithText("Appearance").assertIsDisplayed()
        screenshot("02-settings")
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
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Session details").assertIsDisplayed()
        pressBack()
        ui.onNodeWithText(fixture.title).assertIsDisplayed()
        ui.runOnIdle { SessionRepository.delete(fixture.id) }
    }
}
