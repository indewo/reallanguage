package com.reallanguage.app.feature.recording.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.recording.domain.TranscriptionRequest
import com.reallanguage.app.feature.recording.domain.RecordingEndReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecordingTranscriptionScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersThreeStageProgressAndAllowsSafeReturnHome() {
        val actions = mutableListOf<RecordingTranscriptionAction>()
        composeRule.setContent {
            RlTheme {
                RecordingTranscriptionScreen(
                    RecordingTranscriptionUiState(
                        TranscriptionRequest("recording-1", 84, "audio/mp4", "zh-CN", RecordingEndReason.USER_STOPPED),
                    ),
                    actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("recording-transcription-header").assertIsDisplayed()
        composeRule.onNodeWithText("已完成").assertDoesNotExist()
        composeRule.onNodeWithText("离开此页不会删除本机录音").assertIsDisplayed()
        composeRule.onNodeWithTag("recording-transcription-artwork").assertIsDisplayed()
        composeRule.onAllNodesWithTag("recording-transcription-stage", useUnmergedTree = true)
            .assertCountEquals(3)
        composeRule.onNodeWithTag("recording-transcription-home").performClick()

        assertEquals(listOf(RecordingTranscriptionAction.ReturnHomeRequested), actions)
    }

    @Test
    fun stoppedProcessingNeverInventsFinishedStageOrActiveTrack() {
        composeRule.setContent {
            RlTheme {
                RecordingTranscriptionScreen(RecordingTranscriptionUiState(
                    request = TranscriptionRequest("recording-stopped", 84, "audio/mp4", "zh-CN", RecordingEndReason.USER_STOPPED),
                    processing = false,
                ))
            }
        }
        composeRule.onNodeWithText("已完成").assertDoesNotExist()
        composeRule.onNodeWithTag("recording-transcription-live-progress").assertDoesNotExist()
        composeRule.onAllNodesWithTag("recording-transcription-stage", useUnmergedTree = true).assertCountEquals(3)
        composeRule.onNodeWithText("离开此页不会删除本机录音").assertIsDisplayed()
        composeRule.onNodeWithTag("recording-transcription-home").assertIsDisplayed()
    }
}
