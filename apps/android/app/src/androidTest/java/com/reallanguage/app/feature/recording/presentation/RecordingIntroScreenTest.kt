package com.reallanguage.app.feature.recording.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecordingIntroScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersReferenceStructureAndDispatchesBothActions() {
        val actions = mutableListOf<RecordingIntroAction>()
        composeRule.setContent {
            RlTheme {
                RecordingIntroScreen(RecordingIntroUiState(), actions::add)
            }
        }

        composeRule.onNodeWithTag("recording-intro-header").assertIsDisplayed()
        composeRule.onNodeWithTag("recording-intro-artwork").assertIsDisplayed()
        composeRule.onAllNodesWithTag("recording-intro-step", useUnmergedTree = true)
            .assertCountEquals(3)
        composeRule.onNodeWithTag("recording-intro-home").performClick()
        composeRule.onNodeWithTag("recording-intro-agree").performClick()

        assertEquals(
            listOf(RecordingIntroAction.ReturnHomeRequested, RecordingIntroAction.AgreeRequested),
            actions,
        )
    }
}
