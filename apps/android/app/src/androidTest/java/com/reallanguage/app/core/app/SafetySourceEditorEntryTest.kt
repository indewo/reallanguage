package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.diary.presentation.ConfirmedDiaryInputValidator
import com.reallanguage.app.feature.diary.presentation.DiaryInputViewModel
import com.reallanguage.app.feature.safety.domain.*
import com.reallanguage.app.feature.safety.presentation.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SafetySourceEditorEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Before fun landscape() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(5_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun revisitingSameSafetyCaseLoadsAgainInsteadOfWaitingForAnAlreadyConsumedEffect() {
        val visible = mutableStateOf(true)
        var loads = 0
        val delivered = mutableListOf<SafetyCaseDetails>()
        val factory = factory { SafetyHandlingLoaderViewModel("case-original", SafetyHandlingDetailsRepository {
            loads++
            SafetyHandlingDetailsResult.Ready(details().copy(userReasonZh = "加载版本 $loads"))
        }) }
        rule.setContent { RlTheme {
            if (visible.value) SafetyHandlingLoadingEntry(factory, "case-original", delivered::add)
        } }
        rule.waitUntil { delivered.size == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        rule.runOnIdle { visible.value = true }; rule.waitForIdle()
        assertEquals(2, loads)
        assertEquals(listOf("加载版本 1", "加载版本 2"), delivered.map { it.userReasonZh })
    }

    @Test fun revisitingAfterReturnToEditDoesNotReuseSubmittedAndDisabledHandlingState() {
        val visible = mutableStateOf(true)
        val repository = RecordingCommands()
        var edited = 0
        val route = HomeDiaryRoute.SafetyHandling(details(), HomeDiaryRoute.Input("原始内容。"))
        val factory = factory { SafetyHandlingViewModel(route.details, repository) }
        rule.setContent { RlTheme {
            if (visible.value) SafetyHandlingEntry(factory, route, {}, { edited++ }, {})
        } }
        rule.onNodeWithText("返回修改").assertIsDisplayed().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { edited == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        rule.runOnIdle { visible.value = true }; rule.waitForIdle()
        rule.onNodeWithText("返回修改").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(2, repository.edits)
        assertEquals(2, edited)
    }

    @Test fun revisitingRejectedReviewDoesNotKeepTheOldSubmissionSpinnerAndLockedButtons() {
        val visible = mutableStateOf(true)
        val repository = RecordingCommands()
        var pending = 0
        val route = HomeDiaryRoute.SafetyHandling(details(), HomeDiaryRoute.Input("原始内容。"))
        val factory = factory { SafetyHandlingViewModel(route.details, repository) }
        rule.setContent { RlTheme {
            if (visible.value) SafetyHandlingEntry(factory, route, {}, {}, { pending++ })
        } }
        rule.onNodeWithText("提交人工复核").assertIsDisplayed().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { pending == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        rule.runOnIdle { visible.value = true }; rule.waitForIdle()
        rule.onNodeWithText("使用脱敏版本").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(1, repository.accepts)
        assertEquals(1, repository.reviews)
    }

    @Test fun sameDiaryTextCanBeEditedAndSubmittedAgainAfterReturningFromSafety() {
        val visible = mutableStateOf(true)
        val events = mutableListOf<HomeDiaryEvent>()
        var creations = 0
        val factory = factory { creations++; DiaryInputViewModel(ConfirmedDiaryInputValidator, "同一份内容。") }
        rule.setContent { RlTheme {
            if (visible.value) DiaryInputEntry(factory, "同一份内容。", onEvent = events::add)
        } }
        rule.onNodeWithTag("diary-submit").performClick()
        rule.waitUntil { events.size == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        rule.runOnIdle { visible.value = true }; rule.waitForIdle()
        rule.onNodeWithTag("diary-submit").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(2, creations)
        assertEquals(listOf(HomeDiaryEvent.Submit("同一份内容。"), HomeDiaryEvent.Submit("同一份内容。")), events)
    }

    @Test fun recordingMessageWithoutOriginalIdentityCannotDeletePayloadOrPretendToOpenAnEditor() {
        assertUnavailable(SourceType.RECORDING, SourceContentShape.ARTICLE)
    }

    @Test fun sceneMessageWithoutOriginalSessionCannotDeletePayloadOrPretendToOpenAnEditor() {
        assertUnavailable(SourceType.AI_SCENE, SourceContentShape.DIALOGUE)
    }

    private fun assertUnavailable(type: SourceType, shape: SourceContentShape) {
        val repository = RecordingCommands()
        var editsDelivered = 0
        val original = details().originalSource.copy(sourceType = type, contentShape = shape)
        val data = details().copy(originalSource = original, deidentifiedSource = original.copy(text = "处理后内容。"))
        val route = HomeDiaryRoute.SafetyHandling(data, HomeDiaryRoute.Messages("message-entry"))
        val factory = factory { SafetyHandlingViewModel(data, repository) }
        rule.setContent { RlTheme { SafetyHandlingEntry(factory, route, {}, { editsDelivered++ }, {}) } }
        rule.onNodeWithText("返回修改").assertIsNotEnabled().performClick()
        rule.onNodeWithContentDescription("返回修改").assertIsNotEnabled().performClick()
        rule.onNodeWithTag("safety-source-editor-unavailable").assertIsDisplayed()
        rule.onNodeWithText("使用脱敏版本").assertIsEnabled()
        rule.waitForIdle()
        assertEquals(0, repository.edits)
        assertEquals(0, editsDelivered)
        assertEquals(0, repository.reviews)
        assertEquals(0, repository.accepts)
    }

    private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }

    private fun details(): SafetyCaseDetails {
        val original = ConfirmedSource("原始内容。", SourceType.DIARY, SourceContentShape.ARTICLE)
        return SafetyCaseDetails("case-original", original, "内容需要处理。", original.copy(text = "处理后内容。"), emptyList())
    }

    private class RecordingCommands : SafetyHandlingRepository {
        var edits = 0
        var reviews = 0
        var accepts = 0
        override suspend fun acceptDeidentified(safetyCaseId: String): SafetyHandlingCommandResult {
            accepts++; return SafetyHandlingCommandResult.Completed
        }
        override suspend fun returnToEdit(safetyCaseId: String): SafetyHandlingCommandResult {
            edits++; return SafetyHandlingCommandResult.Completed
        }
        override suspend fun submitManualReview(safetyCaseId: String): ManualReviewSubmissionResult {
            reviews++; return ManualReviewSubmissionResult.Submitted("review-original")
        }
    }
}
