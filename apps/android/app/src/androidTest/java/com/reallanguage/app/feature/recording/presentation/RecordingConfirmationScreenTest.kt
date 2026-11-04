package com.reallanguage.app.feature.recording.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.recording.domain.LocalRecordingSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecordingConfirmationScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @org.junit.Before fun landscape() {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun shortScreenWithLargeTextKeepsRecoveryButtonsInsideTheScreen() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                Box(Modifier.requiredSize(800.dp, 320.dp).testTag("short-recording-screen")) {
                    RlTheme { RecordingConfirmationScreen(
                        RecordingConfirmationUiState(recording = LocalRecordingSummary("old", 120, null), loading = false), {},
                    ) }
                }
            }
        }
        val screen = composeRule.onNodeWithTag("short-recording-screen").getUnclippedBoundsInRoot()
        for (tag in listOf("recording-confirmation-home", "recording-confirmation-delete", "recording-confirmation-preview")) {
            val button = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag extends below the short-screen viewport", button.bottom <= screen.bottom)
            val height = button.bottom - button.top
            assertTrue("$tag is squeezed into an unusable height: $height", height >= 40.dp)
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
        }
        val audioViewport = composeRule.onNodeWithTag("recording-confirmation-audio-text").getUnclippedBoundsInRoot()
        val duration = composeRule.onNodeWithText("02:00").getUnclippedBoundsInRoot()
        assertTrue("One full duration line must fit in the scrolling viewport", duration.bottom - duration.top <= audioViewport.bottom - audioViewport.top)
        composeRule.onNodeWithText("02:00").performScrollTo().assertIsDisplayed()
        val output = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "recording-confirmation-short-verified.png")
        output.outputStream().use { composeRule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun unknownStopReasonDoesNotPromiseSafeTranscription() {
        composeRule.setContent { RlTheme { RecordingConfirmationScreen(
            RecordingConfirmationUiState(recording = LocalRecordingSummary("old", 120, null), loading = false), {},
        ) } }
        composeRule.onNodeWithTag("recording-confirmation-transcribe").assertIsNotEnabled()
    }

    @Test
    fun rendersCompletedRecordingAndDispatchesEveryPrimaryAction() {
        val actions = mutableListOf<RecordingConfirmationAction>()
        composeRule.setContent {
            RlTheme {
                RecordingConfirmationScreen(
                    RecordingConfirmationUiState(
                        recording = LocalRecordingSummary("recording-1", 84),
                        loading = false,
                    ),
                    actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("recording-confirmation-header").assertIsDisplayed()
        composeRule.onNodeWithTag("recording-confirmation-artwork").assertIsDisplayed()
        composeRule.onAllNodesWithTag("recording-confirmation-fact", useUnmergedTree = true)
            .assertCountEquals(3)
        composeRule.onNodeWithTag("recording-confirmation-preview").performClick()
        composeRule.onNodeWithTag("recording-confirmation-delete").performClick()
        composeRule.onNodeWithTag("recording-confirmation-transcribe").performClick()
        composeRule.onNodeWithTag("recording-confirmation-home").performClick()

        assertEquals(
            listOf(
                RecordingConfirmationAction.PreviewRequested,
                RecordingConfirmationAction.DeleteRequested,
                RecordingConfirmationAction.TranscribeRequested,
                RecordingConfirmationAction.ReturnHomeRequested,
            ),
            actions,
        )
    }
}
