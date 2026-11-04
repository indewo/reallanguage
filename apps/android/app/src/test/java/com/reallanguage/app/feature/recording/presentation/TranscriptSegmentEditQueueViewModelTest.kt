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
class TranscriptSegmentEditQueueViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun rapidTypingKeepsAllVisibleCharactersAndSeriallyPersistsOnlyTheLatestPendingSnapshot() = runTest {
        val gate = CompletableDeferred<Unit>(); val repo = Repository(gate); val vm = viewModel(repo); advanceUntilIdle()
        vm.edit("第一"); advanceUntilIdle()
        vm.edit("第一版"); vm.edit("第一版最终文本。")
        assertEquals("第一版最终文本。第二段。", vm.uiState.value.currentText)
        assertEquals("第一版最终文本。", vm.uiState.value.segmentDraftText)
        assertTrue(vm.uiState.value.segmentDraftDirty)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("第一", "第一版最终文本。"), repo.texts)
        assertEquals(listOf(0, 1), repo.revisions)
        assertEquals(2, vm.uiState.value.segmentRevision)
        assertEquals("第一版最终文本。第二段。", vm.uiState.value.currentText)
        assertFalse(vm.uiState.value.segmentDraftDirty)
    }

    @Test fun failedFirstSaveKeepsLatestFullTextAndRetriesWithoutErasingInputOrCallingAi() = runTest {
        val gate = CompletableDeferred<Unit>(); val repo = Repository(gate); repo.fail = true
        val vm = viewModel(repo); advanceUntilIdle()
        vm.edit("未保存"); advanceUntilIdle(); vm.edit("必须完整保留的最终输入。")
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("必须完整保留的最终输入。第二段。", vm.uiState.value.currentText)
        assertTrue(vm.uiState.value.segmentDraftDirty)
        assertEquals(TranscriptSegmentError.SAVE_FAILED, vm.uiState.value.segmentError)
        assertEquals(0, vm.uiState.value.segmentRevision)
        repo.fail = false
        vm.onAction(TranscriptResultAction.RetryDraftSaveRequested); advanceUntilIdle()
        assertEquals("必须完整保留的最终输入。第二段。", repo.value.fullText)
        assertEquals(listOf(0, 0), repo.revisions)
        assertFalse(vm.uiState.value.segmentDraftDirty)
        assertEquals(0, repo.repairs)
    }

    @Test fun saveInFlightBlocksFinalConfirmationCandidateAdoptionSegmentSwitchAndLeave() = runTest {
        val gate = CompletableDeferred<Unit>(); val repo = Repository(gate); val vm = viewModel(repo); advanceUntilIdle()
        vm.edit("正在写入。 "); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.ConfirmRequested)
        vm.onAction(TranscriptResultAction.SegmentSelected("s2", 0))
        vm.onAction(TranscriptResultAction.SegmentChanged("s1", 0, TranscriptSegmentChange.ChooseOriginal))
        assertFalse(vm.canLeave())
        advanceUntilIdle()
        assertEquals("s1", vm.uiState.value.activeSegmentId)
        assertEquals(0, repo.confirmations)
        assertEquals(1, repo.texts.size)
        gate.complete(Unit); advanceUntilIdle()
        assertTrue(vm.canLeave())
    }

    @Test fun lateWrongSegmentAndStaleRevisionCannotOverwriteCurrentInput() = runTest {
        val repo = Repository(); val vm = viewModel(repo); advanceUntilIdle()
        vm.edit("已保存。 "); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.SegmentTextEdited("s2", 1, "错误段。"))
        vm.onAction(TranscriptResultAction.SegmentTextEdited("s1", 0, "迟到版本。"))
        advanceUntilIdle()
        assertEquals("已保存。 第二段。", vm.uiState.value.currentText)
        assertEquals(listOf("已保存。 "), repo.texts)
    }

    @Test fun foreignLateSavedTranscriptIsRejectedWithoutDisplayingOtherRecordingText() = runTest {
        val repo = Repository(); repo.foreignResult = true
        val vm = viewModel(repo); advanceUntilIdle()
        vm.edit("当前录音的输入。 "); advanceUntilIdle()
        assertEquals("当前录音的输入。 第二段。", vm.uiState.value.currentText)
        assertEquals("r1", vm.uiState.value.transcript?.recordingId)
        assertEquals(0, vm.uiState.value.segmentRevision)
        assertTrue(vm.uiState.value.segmentDraftDirty)
        assertEquals(TranscriptSegmentError.SAVE_FAILED, vm.uiState.value.segmentError)
    }

    @Test fun failedSavePreventsReloadFromDiscardingPendingInput() = runTest {
        val repo = Repository(); repo.fail = true
        val vm = viewModel(repo); advanceUntilIdle()
        vm.edit("失败也不能丢。 "); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.RetryLoadRequested); advanceUntilIdle()
        assertEquals("失败也不能丢。 第二段。", vm.uiState.value.currentText)
        assertFalse(vm.canLeave())
        assertEquals(1, repo.loads)
    }

    private fun TranscriptResultViewModel.edit(text: String) = onAction(TranscriptResultAction.SegmentTextEdited("s1", uiState.value.segmentRevision, text))
    private fun viewModel(repository: Repository) = TranscriptResultViewModel("r1", "t1", "repair", "confirm", repository)
    private class Repository(private val gate: CompletableDeferred<Unit>? = null) : TranscriptResultRepository {
        var value = transcript
        var fail = false
        var foreignResult = false
        var confirmations = 0
        var repairs = 0
        var loads = 0
        val texts = mutableListOf<String>()
        val revisions = mutableListOf<Int>()
        override suspend fun load(transcriptId: String): TranscriptLoadResult { loads++; return TranscriptLoadResult.Ready(value) }
        override suspend fun togglePlayback(recordingId: String) = RecordingPlaybackResult.Stopped
        override suspend fun repair(transcriptId: String, currentText: String, manuallyEdited: Boolean, commandId: String): TranscriptRepairResult { repairs++; return TranscriptRepairResult.NoChange }
        override suspend fun confirm(transcriptId: String, confirmedText: String, commandId: String): TranscriptConfirmResult { confirmations++; return TranscriptConfirmResult.Confirmed("source-1") }
        override suspend fun updateSegment(transcriptId: String, segmentId: String, expectedRevision: Int, change: TranscriptSegmentChange): TranscriptSegmentUpdateResult {
            if (change !is TranscriptSegmentChange.Edit) return TranscriptSegmentUpdateResult.Failed
            texts += change.text; revisions += expectedRevision; gate?.await()
            if (fail || expectedRevision != value.draftRevision) return TranscriptSegmentUpdateResult.Failed
            val rows = TranscriptSegmentReviewRules.change(value.evidence!!, value.segmentReviews, segmentId, change)
            value = value.copy(segmentReviews = rows, fullText = TranscriptSegmentReviewRules.render(value.evidence!!, rows), draftRevision = expectedRevision + 1, manuallyEdited = true)
            return TranscriptSegmentUpdateResult.Saved(if (foreignResult) value.copy(recordingId = "foreign-recording") else value)
        }
    }
    companion object {
        private val segments = listOf(
            RecordingTranscriptSegment("s1", 0, 2000, "第一段。", "UNKNOWN", null, emptyList(), null, false, true),
            RecordingTranscriptSegment("s2", 2000, 4000, "第二段。", "UNKNOWN", null, emptyList(), null, false),
        )
        private val transcript = RecordingTranscript("t1", "r1", "第一段。第二段。",
            evidence = RecordingTranscriptEvidence("第一段。第二段。", "zh-CN", 4, RecordingEndReason.USER_STOPPED, segments, 4000, false),
            segmentReviews = segments.map { TranscriptSegmentReview(it.segmentId, it.text) },
        )
    }
}
