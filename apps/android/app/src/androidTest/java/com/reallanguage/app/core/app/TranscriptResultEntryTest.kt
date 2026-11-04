package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.recording.domain.*
import com.reallanguage.app.feature.recording.presentation.TranscriptResultViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred

class TranscriptResultEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun systemBackUsesLiveDraftBoundaryAndOnlyLeavesAfterSaveAcknowledgement() {
        val gate = CompletableDeferred<Unit>()
        var returned = 0
        val saved = mutableListOf<String>()
        val repository = object : TranscriptResultRepository {
            override suspend fun load(transcriptId: String) = TranscriptLoadResult.Ready(RecordingTranscript("back-t", "back-r", "原始转写。"))
            override suspend fun togglePlayback(recordingId: String) = RecordingPlaybackResult.Stopped
            override suspend fun repair(transcriptId: String, currentText: String, manuallyEdited: Boolean, commandId: String) = TranscriptRepairResult.NoChange
            override suspend fun confirm(transcriptId: String, confirmedText: String, commandId: String) = TranscriptConfirmResult.Failed
            override suspend fun saveDraft(transcriptId: String, text: String, manuallyEdited: Boolean, expectedRevision: Int): TranscriptDraftSaveResult {
                gate.await()
                saved += text
                return TranscriptDraftSaveResult.Saved(expectedRevision + 1)
            }
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TranscriptResultViewModel("back-r", "back-t", "repair", "confirm", repository) as T
        }
        rule.setContent { RlTheme {
            // The host must not consume Back before the child validates pending data.
            androidx.activity.compose.BackHandler { returned++ }
            TranscriptResultEntry("back-t", factory, onReturn = { returned++ }, onConfirmed = {})
        } }
        rule.onNodeWithTag("transcript-result-editor").performTextReplacement("必须先保存再返回。")
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.runOnIdle { assertEquals(0, returned); gate.complete(Unit) }
        rule.waitUntil(5_000) { saved.isNotEmpty() }
        rule.waitForIdle()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed(); assertEquals(1, returned) }
        rule.runOnIdle { assertEquals(listOf("必须先保存再返回。"), saved) }
    }
    @Test fun returningFromSafetyReloadsSavedTranscriptAndCanConfirmAgain() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(5_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val shown = mutableStateOf(true)
        var loads = 0
        val confirmations = mutableListOf<String>()
        val repository = object : TranscriptResultRepository {
            override suspend fun load(transcriptId: String): TranscriptLoadResult {
                loads++
                return TranscriptLoadResult.Ready(RecordingTranscript("t1", "r1", "已保存原文$loads。"))
            }
            override suspend fun togglePlayback(recordingId: String) = RecordingPlaybackResult.Stopped
            override suspend fun repair(transcriptId: String, currentText: String, manuallyEdited: Boolean, commandId: String) = TranscriptRepairResult.NoChange
            override suspend fun confirm(transcriptId: String, confirmedText: String, commandId: String) = TranscriptConfirmResult.Confirmed("source-1")
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TranscriptResultViewModel("r1", "t1", "repair", "confirm", repository) as T
        }
        rule.setContent { RlTheme {
            if (shown.value) TranscriptResultEntry("t1", factory) { confirmations += it; shown.value = false }
            else TextButton(onClick = { shown.value = true }) { Text("从安全检查返回修改") }
        } }
        rule.onNodeWithTag("transcript-result-confirm").performClick()
        rule.onNodeWithText("从安全检查返回修改").performClick()
        rule.onNodeWithTag("transcript-result-confirm").performClick()
        rule.runOnIdle { assertEquals(2, loads); assertEquals(listOf("已保存原文1。", "已保存原文2。"), confirmations) }
    }
}
