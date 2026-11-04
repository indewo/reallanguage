package com.reallanguage.app.feature.recording.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.home.presentation.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ActiveRecordingScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersActiveTimerWithHomeContextAndDispatchesStop() {
        val actions = mutableListOf<ActiveRecordingAction>()
        composeRule.setContent {
            RlTheme {
                ActiveRecordingScreen(
                    state = ActiveRecordingUiState("recording-1", 37, recording = true),
                    onAction = actions::add,
                    homeState = HomeUiState(hasDueReview = true, hasIncompleteLearning = true),
                )
            }
        }

        composeRule.onNodeWithTag("home-review-card").assertIsDisplayed()
        composeRule.onNodeWithTag("home-incomplete-card").assertIsDisplayed()
        composeRule.onNodeWithTag("active-recording-timer").assertIsDisplayed()
        composeRule.onNodeWithTag("active-recording-stop").performClick()

        assertEquals(listOf(ActiveRecordingAction.StopRequested), actions)
    }

    @Test
    fun doubledFontKeepsOwnTimerAndStopLabelComplete() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                RlTheme {
                    ActiveRecordingScreen(ActiveRecordingUiState("recording-large", 6037, recording = true), {})
                }
            }
        }
        listOf("active-recording-timer", "active-recording-stop-label").forEach { tag ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertTrue("$tag must expose every line", layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 1f)
            assertTrue("$tag must not overflow horizontally", (0 until layout.lineCount).all { layout.getLineRight(it) - layout.getLineLeft(it) <= layout.size.width + 1f })
        }
        val density = composeRule.density.density
        assertTrue("Stop action retains a 48dp touch target", composeRule.onNodeWithTag("active-recording-stop").fetchSemanticsNode().layoutInfo.height >= 48f * density)
        composeRule.onNodeWithTag("active-recording-stop").assertIsDisplayed()
    }

    @Test
    fun retryRemainsAvailableAfterRuntimeFailure() {
        val actions = mutableListOf<ActiveRecordingAction>()
        composeRule.setContent {
            RlTheme {
                ActiveRecordingScreen(
                    state = ActiveRecordingUiState(elapsedSeconds = 8, error = ActiveRecordingError.RECORDING_FAILED),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("active-recording-retry").performClick()

        assertEquals(listOf(ActiveRecordingAction.RetryRequested), actions)
    }
}
