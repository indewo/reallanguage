package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.recording.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptSegmentConfirmationViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun requiredSegmentLoadsWithoutAutomaticallyAdoptingInferenceOrCallingAi() = runTest {
        val repo = Repository(); val vm = viewModel(repo); advanceUntilIdle()
        assertEquals("s1", vm.uiState.value.activeSegmentId)
        assertEquals(0, vm.uiState.value.segmentRevision)
        assertEquals(original, vm.uiState.value.currentText)
        assertEquals(TranscriptSegmentReviewStatus.PENDING, vm.uiState.value.segmentReviews[0].status)
        assertEquals(0, repo.repairs)
        assertEquals(0, repo.changes.size)
    }

    @Test fun finalSubmitCannotSkipExplicitRequiredSegmentConfirmation() = runTest {
        val repo = Repository(); val vm = viewModel(repo); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.ConfirmRequested); advanceUntilIdle()
        assertEquals(0, repo.confirmations)
        assertEquals(TranscriptSegmentError.CONFIRMATION_REQUIRED, vm.uiState.value.segmentError)
    }

    @Test fun currentSegmentAndRevisionAreCheckedBeforeRepositoryMutation() = runTest {
        val repo = Repository(); val vm = viewModel(repo); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.SegmentChanged("s2", 0, TranscriptSegmentChange.Confirm))
        vm.onAction(TranscriptResultAction.SegmentChanged("s1", 7, TranscriptSegmentChange.Confirm))
        advanceUntilIdle(); assertEquals(0, repo.changes.size)
        vm.onAction(TranscriptResultAction.SegmentChanged("s1", 0, TranscriptSegmentChange.CannotConfirm))
        advanceUntilIdle()
        assertEquals(listOf(TranscriptSegmentChange.CannotConfirm), repo.changes)
        assertEquals(1, vm.uiState.value.segmentRevision)
        assertEquals(TranscriptSegmentReviewStatus.UNABLE_TO_CONFIRM, vm.uiState.value.segmentReviews[0].status)
    }

    @Test fun explicitlyChosenInferenceStillRequiresConfirmationAndReopenKeepsProvenance() = runTest {
        val repo = Repository(); val vm = viewModel(repo); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.SegmentChanged("s1", 0, TranscriptSegmentChange.CannotConfirm)); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.SegmentChanged("s1", 1, TranscriptSegmentChange.ChooseInference)); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.ConfirmRequested); advanceUntilIdle()
        assertEquals(0, repo.confirmations)
        vm.onAction(TranscriptResultAction.SegmentChanged("s1", 2, TranscriptSegmentChange.Confirm)); advanceUntilIdle()
        val restored = viewModel(repo); advanceUntilIdle()
        assertEquals(TranscriptSegmentTextOrigin.INFERENCE, restored.uiState.value.segmentReviews[0].origin)
        restored.onAction(TranscriptResultAction.ConfirmRequested); advanceUntilIdle()
        assertEquals(1, repo.confirmations)
        assertEquals("我去了果园。我又回家了。", repo.confirmedText)
        assertEquals(3, repo.confirmedRevision)
    }

    @Test fun failedSaveDoesNotPretendASelectionWasPersistedAndCanRetrySameRevision() = runTest {
        val repo = Repository(); repo.failSave = true
        val vm = viewModel(repo); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.SegmentChanged("s1", 0, TranscriptSegmentChange.Confirm)); advanceUntilIdle()
        assertEquals(0, vm.uiState.value.segmentRevision)
        assertEquals(TranscriptSegmentReviewStatus.PENDING, vm.uiState.value.segmentReviews[0].status)
        assertEquals(TranscriptSegmentError.SAVE_FAILED, vm.uiState.value.segmentError)
        repo.failSave = false
        vm.onAction(TranscriptResultAction.SegmentChanged("s1", 0, TranscriptSegmentChange.Confirm)); advanceUntilIdle()
        assertEquals(1, vm.uiState.value.segmentRevision)
        assertEquals(TranscriptSegmentReviewStatus.CONFIRMED, vm.uiState.value.segmentReviews[0].status)
    }

    @Test fun unscopedFullTextActionCannotReplaceRequiredSegmentsWithAnUnmappedDraft() = runTest {
        val repo = Repository(); val vm = viewModel(repo); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.TextChanged("覆盖多段的文本。")); advanceUntilIdle()
        assertEquals(original, vm.uiState.value.currentText)
        assertEquals(0, repo.wholeDraftSaves)
        assertEquals(TranscriptSegmentError.CONFIRMATION_REQUIRED, vm.uiState.value.segmentError)
    }

    @Test fun unlocatableRepairRemainsAnUnappliedProposalAndDoesNotEraseCurrentSegmentText() = runTest {
        val repo = Repository()
        val proposal = TranscriptRepairResult.Proposed("跨段改写的提案。", listOf(
            RepairChange("我去了公园。我又回家了。", "跨段改写的提案。", RepairReason.CONFIRMED_DISFLUENCY, listOf("s1", "s2")),
        ))
        repo.proposal = proposal
        val vm = viewModel(repo); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.RepairRequested); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.AcceptRepairRequested); advanceUntilIdle()
        assertEquals(original, vm.uiState.value.currentText)
        assertEquals(proposal, vm.uiState.value.repairComparison)
        assertEquals(TranscriptSegmentError.REPAIR_MAPPING_FAILED, vm.uiState.value.segmentError)
        assertEquals(0, repo.wholeDraftSaves)
        assertEquals(0, repo.confirmations)
    }

    private fun viewModel(repo: Repository) = TranscriptResultViewModel("r1", "t1", "repair", "confirm", repo)
    private class Repository : TranscriptResultRepository {
        var value = transcript
        var failSave = false
        var repairs = 0
        var confirmations = 0
        var wholeDraftSaves = 0
        var confirmedText: String? = null
        var confirmedRevision: Int? = null
        var proposal: TranscriptRepairResult = TranscriptRepairResult.NoChange
        val changes = mutableListOf<TranscriptSegmentChange>()
        override suspend fun load(transcriptId: String) = TranscriptLoadResult.Ready(value)
        override suspend fun togglePlayback(recordingId: String) = RecordingPlaybackResult.Stopped
        override suspend fun repair(transcriptId: String, currentText: String, manuallyEdited: Boolean, commandId: String): TranscriptRepairResult { repairs++; return proposal }
        override suspend fun saveDraft(transcriptId: String, text: String, manuallyEdited: Boolean, expectedRevision: Int): TranscriptDraftSaveResult { wholeDraftSaves++; return TranscriptDraftSaveResult.Saved(expectedRevision + 1) }
        override suspend fun updateSegment(transcriptId: String, segmentId: String, expectedRevision: Int, change: TranscriptSegmentChange): TranscriptSegmentUpdateResult {
            changes += change
            if (failSave || expectedRevision != value.draftRevision) return TranscriptSegmentUpdateResult.Failed
            val before = value.segmentReviews.first { it.segmentId == segmentId }
            val after = when (change) {
                TranscriptSegmentChange.CannotConfirm -> before.copy(status = TranscriptSegmentReviewStatus.UNABLE_TO_CONFIRM, unableToConfirm = true)
                TranscriptSegmentChange.ChooseInference -> before.copy(text = "我去了果园。", origin = TranscriptSegmentTextOrigin.INFERENCE, inferenceText = "我去了果园。", status = TranscriptSegmentReviewStatus.PENDING)
                TranscriptSegmentChange.Confirm -> before.copy(status = TranscriptSegmentReviewStatus.CONFIRMED)
                else -> before.copy(status = TranscriptSegmentReviewStatus.PENDING)
            }
            val rows = value.segmentReviews.map { if (it.segmentId == segmentId) after else it }
            value = value.copy(segmentReviews = rows, fullText = rows.joinToString("") { it.text }, draftRevision = expectedRevision + 1, hasUnconfirmedEdits = true)
            return TranscriptSegmentUpdateResult.Saved(value)
        }
        override suspend fun confirm(transcriptId: String, confirmedText: String, commandId: String): TranscriptConfirmResult { confirmations++; return TranscriptConfirmResult.Confirmed("source-1") }
        override suspend fun confirmReviewed(transcriptId: String, confirmedText: String, expectedRevision: Int, commandId: String): TranscriptConfirmResult {
            confirmations++; this.confirmedText = confirmedText; confirmedRevision = expectedRevision
            return TranscriptConfirmResult.Confirmed("source-1")
        }
    }
    companion object {
        private const val original = "我去了公园。我又回家了。"
        private val segments = listOf(
            RecordingTranscriptSegment("s1", 0, 2000, "我去了公园。", "UNKNOWN", null, listOf("我去了果园。"), "我去了果园。", true, true),
            RecordingTranscriptSegment("s2", 2000, 4000, "我又回家了。", "UNKNOWN", null, emptyList(), null, false),
        )
        private val transcript = RecordingTranscript("t1", "r1", original,
            evidence = RecordingTranscriptEvidence(original, "zh-CN", 4, RecordingEndReason.USER_STOPPED, segments, 4000, false),
            segmentReviews = segments.map { TranscriptSegmentReview(it.segmentId, it.text) },
        )
    }
}
