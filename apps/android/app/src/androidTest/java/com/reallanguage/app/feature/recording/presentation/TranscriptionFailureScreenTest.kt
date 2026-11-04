package com.reallanguage.app.feature.recording.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TranscriptionFailureScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun preservesRecordingAndOffersRetryOrReturn() {
        val actions = mutableListOf<TranscriptionFailureAction>()
        composeRule.setContent {
            RlTheme {
                TranscriptionFailureScreen(
                    TranscriptionFailureUiState("recording-1", durationSeconds = 84),
                    actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("transcription-failure-header").assertIsDisplayed()
        composeRule.onNodeWithTag("transcription-failure-recording").assertIsDisplayed()
        composeRule.onNodeWithTag("transcription-failure-preserved").assertIsDisplayed()
        composeRule.onNodeWithTag("transcription-failure-retry").performClick()
        composeRule.onNodeWithTag("transcription-failure-return").performClick()

        assertEquals(
            listOf(
                TranscriptionFailureAction.RetryRequested,
                TranscriptionFailureAction.ReturnRequested,
            ),
            actions,
        )
    }
}
