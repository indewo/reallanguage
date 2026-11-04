package com.reallanguage.app.feature.recording.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.recording.domain.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TranscriptSegmentConfirmationScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Before fun landscape() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(5_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun singleRequiredSegmentShowsOriginalAndCandidatesWithoutAutomaticallyOfferingOrAdoptingInference() {
        val actions = mutableListOf<TranscriptResultAction>()
        val state = state()
        rule.setContent { RlTheme { TranscriptResultScreen(state, actions::add) } }
        rule.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "64-segment-confirmation.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        rule.onNodeWithTag("transcript-segment-original").performScrollTo().assertTextContains("我去了公园。")
        rule.onNodeWithTag("transcript-segment-candidate-0").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("transcript-segment-inference").assertDoesNotExist()
        rule.onNodeWithTag("transcript-result-confirm").assertIsDisplayed().assertIsNotEnabled()
        assertTrue(actions.isEmpty())
        rule.onNodeWithTag("transcript-segment-cannot-confirm").performScrollTo().performClick()
        assertEquals(listOf(TranscriptResultAction.SegmentChanged("s1", 0, TranscriptSegmentChange.CannotConfirm)), actions)
    }

    @Test fun unableToConfirmRevealsExplicitInferenceAndAdoptionStillDoesNotMeanSegmentConfirmed() {
        val actions = mutableListOf<TranscriptResultAction>()
        val state = state(unable = true)
        rule.setContent { RlTheme { TranscriptResultScreen(state, actions::add) } }
        rule.onNodeWithTag("transcript-segment-inference").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("transcript-segment-inference-warning").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("transcript-segment-use-inference").performScrollTo().performClick()
        assertEquals(listOf(TranscriptResultAction.SegmentChanged("s1", 0, TranscriptSegmentChange.ChooseInference)), actions)
        rule.onNodeWithTag("transcript-result-confirm").assertIsNotEnabled()
        assertTrue(actions.none { it == TranscriptResultAction.ConfirmRequested })
    }

    @Test fun manuallyEditedInferenceKeepsVisibleProvenanceAndExplicitSegmentConfirmation() {
        val actions = mutableListOf<TranscriptResultAction>()
        val initial = state(unable = true)
        val review = initial.segmentReviews[0].copy(text = "我去了果园，后来回家。", origin = TranscriptSegmentTextOrigin.MANUAL, inferenceText = "我去了果园。")
        val state = initial.copy(segmentReviews = listOf(review), currentText = review.text)
        rule.setContent { RlTheme { TranscriptResultScreen(state, actions::add) } }
        rule.onNodeWithTag("transcript-segment-adopted-inference").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("transcript-segment-confirm").performScrollTo().performClick()
        assertEquals(listOf(TranscriptResultAction.SegmentChanged("s1", 0, TranscriptSegmentChange.Confirm)), actions)
    }

    @Test fun savingKeepsEditorEnabledButBlocksSegmentSwitchChoicesAndFinalSubmission() {
        val actions = mutableListOf<TranscriptResultAction>()
        val state = state(second = true).copy(segmentSaving = true, segmentDraftDirty = true, draftSaving = true, segmentDraftText = "尚在输入。")
        rule.setContent { RlTheme { TranscriptResultScreen(state, actions::add) } }
        rule.onNodeWithTag("transcript-result-editor").performScrollTo().assertIsEnabled().performTextReplacement("继续输入的完整内容。")
        assertTrue(actions.contains(TranscriptResultAction.SegmentTextEdited("s1", 0, "继续输入的完整内容。")))
        rule.onNodeWithTag("transcript-segment-tab-s2").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("transcript-segment-candidate-0").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("transcript-segment-confirm").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("transcript-result-confirm").assertIsNotEnabled()
    }

    @Test fun doubleFontLongCandidateAndFailureRemainReadableScrollableAndRetryable() {
        val actions = mutableListOf<TranscriptResultAction>()
        val state = state(candidate = "这是一段需要完整阅读的候选，不能截断。".repeat(10) + "候选末尾完整可读。")
            .copy(segmentDraftDirty = true, draftSaveFailed = true, segmentError = TranscriptSegmentError.SAVE_FAILED)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { RlTheme {
                Box(Modifier.requiredSize(640.dp, 360.dp)) { TranscriptResultScreen(state, actions::add) }
            } }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("transcript-segment-candidate-text-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().multiParagraph.didExceedMaxLines)
        assertTrue(layouts.single().multiParagraph.height <= layouts.single().size.height + 1)
        rule.onNodeWithTag("transcript-segment-error").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("transcript-result-draft-retry").performScrollTo().performClick()
        assertTrue(actions.contains(TranscriptResultAction.RetryDraftSaveRequested))
        rule.onNodeWithTag("transcript-result-confirm").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun finalActionEnablesOnlyAfterAllRequiredSegmentsAreConfirmedAndNoUnsavedDraftRemains() {
        val initial = state(second = true)
        val reviews = initial.segmentReviews.map { it.copy(status = TranscriptSegmentReviewStatus.CONFIRMED) }
        val state = initial.copy(segmentReviews = reviews)
        rule.setContent { RlTheme { TranscriptResultScreen(state, {}) } }
        rule.onNodeWithTag("transcript-result-confirm").assertIsEnabled()
    }

    @Test fun returnAndLaterHandlingDelegateToNavigationGuardWithoutDiscardingCurrentDraft() {
        val actions = mutableListOf<TranscriptResultAction>()
        var returnRequests = 0
        val state = state().copy(segmentDraftDirty = true, segmentDraftText = "仍在本页的完整编辑。")
        rule.setContent { RlTheme { TranscriptResultScreen(state, actions::add, onReturn = { returnRequests++ }) } }
        rule.onNodeWithTag("transcript-result-return").assertIsDisplayed().performClick()
        rule.onNodeWithTag("transcript-result-later").assertIsDisplayed().performClick()
        rule.onNodeWithTag("transcript-result-confirm").assertIsNotEnabled()
        assertEquals(2, returnRequests)
        assertTrue(actions.isEmpty())
    }

    private fun state(unable: Boolean = false, second: Boolean = false, candidate: String = "我去了果园。"): TranscriptResultUiState {
        val segments = buildList {
            add(RecordingTranscriptSegment("s1", 0, 2000, "我去了公园。", "UNKNOWN", null, listOf(candidate), "我去了果园。", true, true))
            if (second) add(RecordingTranscriptSegment("s2", 2000, 4000, "然后我回家了。", "UNKNOWN", null, emptyList(), null, false, true))
        }
        val original = segments.joinToString("") { it.text }
        val reviews = segments.map { TranscriptSegmentReview(it.segmentId, it.text, unableToConfirm = unable,
            status = if (unable) TranscriptSegmentReviewStatus.UNABLE_TO_CONFIRM else TranscriptSegmentReviewStatus.PENDING) }
        val transcript = RecordingTranscript("t1", "r1", original,
            evidence = RecordingTranscriptEvidence(original, "zh-CN", if (second) 4 else 2, RecordingEndReason.USER_STOPPED, segments, segments.last().endMs, false),
            segmentReviews = reviews)
        return TranscriptResultUiState(transcript = transcript, currentText = original, loading = false, segmentReviews = reviews, activeSegmentId = "s1")
    }
}
