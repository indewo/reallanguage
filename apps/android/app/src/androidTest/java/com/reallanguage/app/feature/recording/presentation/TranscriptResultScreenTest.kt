package com.reallanguage.app.feature.recording.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithText
import com.reallanguage.app.feature.recording.domain.RecordingTranscriptEvidence
import com.reallanguage.app.feature.recording.domain.RecordingEndReason
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.recording.domain.RecordingTranscript
import com.reallanguage.app.feature.recording.domain.TranscriptRepairResult
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TranscriptResultScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun repairIsVisiblyDisabledUntilTheDraftIsSafelySaved() {
        val transcript = RecordingTranscript("t1", "r1", "正在保存的文字。", RecordingTranscriptEvidence(
            "原始文字。", "zh", 4, RecordingEndReason.USER_STOPPED, emptyList(), 4000, false,
        ))
        val state = androidx.compose.runtime.mutableStateOf(TranscriptResultUiState(
            transcript = transcript, currentText = transcript.fullText, loading = false, draftSaving = true,
        ))
        composeRule.setContent { RlTheme { TranscriptResultScreen(state.value, {}) } }
        composeRule.onNodeWithTag("transcript-result-repair").performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle { state.value = state.value.copy(draftSaving = false, draftSaveFailed = true) }
        composeRule.onNodeWithTag("transcript-result-repair").assertIsNotEnabled()
        composeRule.onNodeWithTag("transcript-result-draft-retry").performScrollTo().assertIsDisplayed()
    }

    @Test fun draftSavingSavedAndFailedStatesAreVisibleAndFailureCanRetry() {
        val actions = mutableListOf<TranscriptResultAction>()
        val state = androidx.compose.runtime.mutableStateOf(TranscriptResultUiState(
            transcript = RecordingTranscript("t1", "r1", "正在编辑的文字。"), currentText = "正在编辑的文字。", loading = false, draftSaving = true,
        ))
        composeRule.setContent { RlTheme { TranscriptResultScreen(state.value, actions::add) } }
        composeRule.onNodeWithText("正在保存编辑…").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { state.value = state.value.copy(draftSaving = false, draftSaved = true) }
        composeRule.onNodeWithText("编辑已保存在本机").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { state.value = state.value.copy(draftSaved = false, draftSaveFailed = true) }
        composeRule.onNodeWithTag("transcript-result-draft-retry").performScrollTo().performClick()
        assertTrue(actions.contains(TranscriptResultAction.RetryDraftSaveRequested))
        composeRule.onNodeWithTag("transcript-result-confirm").assertIsDisplayed()
    }

    @Test fun missingHistoricalEvidenceExplainsDisabledRepairButStillAllowsManualConfirmation() {
        composeRule.setContent { RlTheme {
            TranscriptResultScreen(TranscriptResultUiState(
                transcript = RecordingTranscript("t1", "r1", "旧转写文字。"), currentText = "旧转写文字。", loading = false,
            ), {})
        } }
        composeRule.onNodeWithTag("transcript-result-repair").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("transcript-result-evidence-unavailable").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("transcript-result-confirm").assertIsDisplayed()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(instrumentation.targetContext.filesDir, "transcript-legacy-state.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @org.junit.Before fun landscape() {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun confirmationRemainsVisibleWithLongComparisonAndOriginalAudioBoundaryIsNotHidden() {
        val transcript = RecordingTranscript("t1", "r1", "今天去了公园。", RecordingTranscriptEvidence(
            "今天去了公园。", "zh", 120, RecordingEndReason.MAX_DURATION, emptyList(), 113500, true,
        ))
        composeRule.setContent {
            RlTheme { Box(Modifier.requiredSize(915.5.dp, 429.5.dp)) {
                TranscriptResultScreen(TranscriptResultUiState(
                    transcript = transcript, currentText = transcript.fullText, loading = false,
                    repairComparison = TranscriptRepairResult.Proposed("很长的建议内容。".repeat(60), emptyList()),
                ), onAction = {})
            } }
        }
        composeRule.onNodeWithTag("transcript-result-confirm").assertIsDisplayed()
        composeRule.onNodeWithText("本机录音 · 02:00").assertIsDisplayed()
        composeRule.onNodeWithTag("transcript-result-boundary").performScrollTo().assertIsDisplayed()
    }

    @Test fun editorCannotChangeWhileConfirmationIsSaving() {
        composeRule.setContent {
            RlTheme { TranscriptResultScreen(TranscriptResultUiState(
                transcript = RecordingTranscript("t1", "r1", "保留正在提交的原文。"),
                currentText = "保留正在提交的原文。", loading = false, confirming = true,
            ), onAction = {}) }
        }
        composeRule.onNodeWithTag("transcript-result-editor").assertIsNotEnabled()
    }

    @Test
    fun rendersEditableTranscriptComparisonAndDispatchesActions() {
        val actions = mutableListOf<TranscriptResultAction>()
        composeRule.setContent {
            RlTheme {
                TranscriptResultScreen(
                    TranscriptResultUiState(
                        transcript = RecordingTranscript("t1", "r1", "我昨天，去公园。", RecordingTranscriptEvidence(
                            "我昨天，去公园。", "zh", 4, RecordingEndReason.USER_STOPPED,
                            listOf(com.reallanguage.app.feature.recording.domain.RecordingTranscriptSegment("s1", 0, 4000,
                                "我昨天，去公园。", "UNKNOWN", null, emptyList(), null, false)), 4000, false)),
                        currentText = "我昨天，去公园。",
                        loading = false,
                        repairComparison = TranscriptRepairResult.Proposed("我昨天去了公园。", emptyList()),
                    ),
                    actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("transcript-result-header").assertIsDisplayed()
        composeRule.onNodeWithTag("transcript-result-artwork").assertIsDisplayed()
        composeRule.onNodeWithTag("transcript-result-editor").assertIsDisplayed()
        composeRule.onNodeWithTag("transcript-result-comparison").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("transcript-result-preview").performClick()
        composeRule.onNodeWithTag("transcript-result-repair").performClick()
        composeRule.onNodeWithTag("transcript-result-keep").performScrollTo().performClick()
        composeRule.onNodeWithTag("transcript-result-accept").performScrollTo().performClick()
        composeRule.onNodeWithTag("transcript-result-confirm").assertIsDisplayed().performClick()

        assertTrue(actions.containsAll(listOf(
            TranscriptResultAction.PreviewRequested,
            TranscriptResultAction.RepairRequested,
            TranscriptResultAction.KeepCurrentRequested,
            TranscriptResultAction.AcceptRepairRequested,
            TranscriptResultAction.ConfirmRequested,
        )))
    }
}
