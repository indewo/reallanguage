package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.recording.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptSegmentDeferredEditViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun typingDuringCandidateSaveStaysVisibleAndRunsAfterNewRevisionWithoutConcurrentWrites() = runTest {
        val repo = Repository(); val vm = viewModel(repo); advanceUntilIdle()
        vm.change(TranscriptSegmentChange.ChooseAlternative(0)); advanceUntilIdle()
        vm.edit("连续"); vm.edit("连续输入的最后全文。"); advanceUntilIdle()
        assertEquals("连续输入的最后全文。然后回家。", vm.uiState.value.currentText)
        assertTrue(vm.uiState.value.segmentDraftDirty)
        assertFalse(vm.canLeave())
        assertEquals(1, repo.changes.size)
        repo.gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(TranscriptSegmentChange.ChooseAlternative(0), TranscriptSegmentChange.Edit("连续输入的最后全文。")), repo.changes)
        assertEquals(listOf(0, 1), repo.revisions)
        assertEquals(1, repo.maximumConcurrentWrites)
        assertEquals("连续输入的最后全文。然后回家。", repo.value.fullText)
        assertFalse(vm.uiState.value.segmentDraftDirty)
        assertEquals(TranscriptSegmentReviewStatus.PENDING, vm.uiState.value.segmentReviews[0].status)
    }

    @Test fun failedCandidateSaveKeepsTypedTextAndRetrySavesOnlyThatTextAtOriginalRevision() = runTest {
        val repo = Repository(failMutation = true); val vm = viewModel(repo); advanceUntilIdle()
        vm.change(TranscriptSegmentChange.ChooseAlternative(0)); advanceUntilIdle()
        vm.edit("候选失败也不能丢掉的文字。"); repo.gate.complete(Unit); advanceUntilIdle()
        assertEquals("候选失败也不能丢掉的文字。然后回家。", vm.uiState.value.currentText)
        assertTrue(vm.uiState.value.draftSaveFailed)
        assertTrue(vm.uiState.value.segmentDraftDirty)
        assertFalse(vm.canLeave())
        vm.onAction(TranscriptResultAction.RetryDraftSaveRequested); advanceUntilIdle()
        assertEquals(listOf(0, 0), repo.revisions)
        assertEquals(listOf(TranscriptSegmentChange.ChooseAlternative(0), TranscriptSegmentChange.Edit("候选失败也不能丢掉的文字。")), repo.changes)
        assertEquals("候选失败也不能丢掉的文字。然后回家。", repo.value.fullText)
        assertFalse(vm.uiState.value.segmentDraftDirty)
        assertEquals(0, repo.repairs)
    }

    @Test fun typingWhileConfirmingSegmentDoesNotAutoConfirmNewTextOrAdvanceToNextSegment() = runTest {
        val repo = Repository(); val vm = viewModel(repo); advanceUntilIdle()
        vm.change(TranscriptSegmentChange.Confirm); advanceUntilIdle()
        vm.edit("确认期间又补充了文字。"); repo.gate.complete(Unit); advanceUntilIdle()
        assertEquals("s1", vm.uiState.value.activeSegmentId)
        assertEquals(TranscriptSegmentReviewStatus.PENDING, vm.uiState.value.segmentReviews[0].status)
        assertEquals(TranscriptSegmentTextOrigin.MANUAL, vm.uiState.value.segmentReviews[0].origin)
        assertEquals("确认期间又补充了文字。然后回家。", vm.uiState.value.currentText)
        vm.onAction(TranscriptResultAction.ConfirmRequested); advanceUntilIdle()
        assertEquals(0, repo.confirmations)
        assertEquals(listOf(TranscriptSegmentChange.Confirm, TranscriptSegmentChange.Edit("确认期间又补充了文字。")), repo.changes)
    }

    @Test fun deferredTextAfterInferenceSavePreservesInferenceProvenanceWithoutImplicitConfirmation() = runTest {
        val repo = Repository(unable = true); val vm = viewModel(repo); advanceUntilIdle()
        vm.change(TranscriptSegmentChange.ChooseInference); advanceUntilIdle()
        vm.edit("我去了果园，后来回家。"); repo.gate.complete(Unit); advanceUntilIdle()
        val row = vm.uiState.value.segmentReviews[0]
        assertEquals("我去了果园，后来回家。", row.text)
        assertEquals("我去了果园。", row.inferenceText)
        assertEquals(TranscriptSegmentTextOrigin.MANUAL, row.origin)
        assertEquals(TranscriptSegmentReviewStatus.PENDING, row.status)
    }

    @Test fun staleOrDifferentSegmentInputCannotReplaceValidDeferredInput() = runTest {
        val repo = Repository(); val vm = viewModel(repo); advanceUntilIdle()
        vm.change(TranscriptSegmentChange.ChooseOriginal); advanceUntilIdle()
        vm.edit("本段真正输入。");
        vm.onAction(TranscriptResultAction.SegmentTextEdited("s2", 0, "另一段迟到输入。"))
        vm.onAction(TranscriptResultAction.SegmentTextEdited("s1", 7, "错误修订输入。"))
        repo.gate.complete(Unit); advanceUntilIdle()
        assertEquals("本段真正输入。然后回家。", repo.value.fullText)
        assertEquals(TranscriptSegmentChange.Edit("本段真正输入。"), repo.changes.last())
    }

    @Test fun typingDuringRepairApplicationKeepsOtherRepairedSegmentsAndRemainsUnconfirmed() = runTest {
        val repo = Repository(); val vm = viewModel(repo); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.RepairRequested); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.AcceptRepairRequested); advanceUntilIdle()
        vm.edit("我的最新文字。"); advanceUntilIdle()
        assertEquals("我的最新文字。然后回家。", vm.uiState.value.currentText)
        repo.gate.complete(Unit); advanceUntilIdle()
        assertEquals("我的最新文字。然后坐车回家。", repo.value.fullText)
        assertEquals(listOf(1), repo.revisions)
        assertEquals(TranscriptSegmentReviewStatus.PENDING, repo.value.segmentReviews[0].status)
        assertEquals(1, repo.repairs)
    }

    private fun TranscriptResultViewModel.change(change: TranscriptSegmentChange) =
        onAction(TranscriptResultAction.SegmentChanged("s1", uiState.value.segmentRevision, change))
    private fun TranscriptResultViewModel.edit(text: String) =
        onAction(TranscriptResultAction.SegmentTextEdited("s1", uiState.value.segmentRevision, text))
    private fun viewModel(repo: Repository) = TranscriptResultViewModel("r1", "t1", "repair", "confirm", repo)

    private class Repository(private val failMutation: Boolean = false, unable: Boolean = false) : TranscriptResultRepository {
        val gate = CompletableDeferred<Unit>()
        var value = transcript.copy(segmentReviews = transcript.segmentReviews.map {
            if (unable && it.segmentId == "s1") it.copy(unableToConfirm = true, status = TranscriptSegmentReviewStatus.UNABLE_TO_CONFIRM) else it
        })
        val changes = mutableListOf<TranscriptSegmentChange>()
        val revisions = mutableListOf<Int>()
        var repairs = 0
        var confirmations = 0
        private var concurrentWrites = 0
        var maximumConcurrentWrites = 0
        override suspend fun load(transcriptId: String) = TranscriptLoadResult.Ready(value)
        override suspend fun togglePlayback(recordingId: String) = RecordingPlaybackResult.Stopped
        override suspend fun confirm(transcriptId: String, confirmedText: String, commandId: String): TranscriptConfirmResult { confirmations++; return TranscriptConfirmResult.Failed }
        override suspend fun confirmReviewed(transcriptId: String, confirmedText: String, expectedRevision: Int, commandId: String): TranscriptConfirmResult { confirmations++; return TranscriptConfirmResult.Failed }
        override suspend fun repair(transcriptId: String, currentText: String, manuallyEdited: Boolean, commandId: String): TranscriptRepairResult {
            repairs++
            return TranscriptRepairResult.Proposed("我去了公园。然后坐车回家。", listOf(
                RepairChange("然后回家。", "然后坐车回家。", RepairReason.CONFIRMED_OMISSION, listOf("s2")),
            ))
        }
        override suspend fun applyReviewedRepair(transcriptId: String, expectedRevision: Int, proposal: TranscriptRepairResult.Proposed): TranscriptSegmentUpdateResult {
            gate.await()
            val rows = TranscriptSegmentReviewRules.repair(value.evidence!!, value.segmentReviews, proposal)
            value = value.copy(segmentReviews = rows, fullText = TranscriptSegmentReviewRules.render(value.evidence!!, rows), draftRevision = expectedRevision + 1)
            return TranscriptSegmentUpdateResult.Saved(value)
        }
        override suspend fun updateSegment(transcriptId: String, segmentId: String, expectedRevision: Int, change: TranscriptSegmentChange): TranscriptSegmentUpdateResult {
            changes += change; revisions += expectedRevision
            concurrentWrites++; maximumConcurrentWrites = maxOf(maximumConcurrentWrites, concurrentWrites)
            try {
                if (change !is TranscriptSegmentChange.Edit) gate.await()
                if ((failMutation && change !is TranscriptSegmentChange.Edit) || expectedRevision != value.draftRevision) return TranscriptSegmentUpdateResult.Failed
                val rows = TranscriptSegmentReviewRules.change(value.evidence!!, value.segmentReviews, segmentId, change)
                value = value.copy(segmentReviews = rows, fullText = TranscriptSegmentReviewRules.render(value.evidence!!, rows), draftRevision = expectedRevision + 1)
                return TranscriptSegmentUpdateResult.Saved(value)
            } finally { concurrentWrites-- }
        }
    }

    companion object {
        private val segments = listOf(
            RecordingTranscriptSegment("s1", 0, 2000, "我去了公园。", "UNKNOWN", null, listOf("我去了果园。"), "我去了果园。", true, true),
            RecordingTranscriptSegment("s2", 2000, 4000, "然后回家。", "UNKNOWN", null, emptyList(), null, false, true),
        )
        private val transcript = RecordingTranscript("t1", "r1", "我去了公园。然后回家。",
            evidence = RecordingTranscriptEvidence("我去了公园。然后回家。", "zh-CN", 4, RecordingEndReason.USER_STOPPED, segments, 4000, false),
            segmentReviews = segments.map { TranscriptSegmentReview(it.segmentId, it.text) },
        )
    }
}
