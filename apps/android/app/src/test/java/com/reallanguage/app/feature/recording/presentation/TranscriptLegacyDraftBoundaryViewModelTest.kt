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
class TranscriptLegacyDraftBoundaryViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun reloadDuringLegacySaveCannotReplaceLaterTypingOrResetItsDiskRevision() = runTest {
        val saveGate = CompletableDeferred<Unit>(); val repo = Repository(saveGate = saveGate)
        val vm = viewModel(repo); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.TextChanged("第一版。")); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.TextChanged("最后完整的全文编辑。"))
        vm.onAction(TranscriptResultAction.RetryLoadRequested); advanceUntilIdle()
        assertEquals("最后完整的全文编辑。", vm.uiState.value.currentText)
        assertEquals(1, repo.loads)
        saveGate.complete(Unit); advanceUntilIdle()
        assertEquals("最后完整的全文编辑。", repo.value.fullText)
        assertEquals(listOf(0, 1), repo.revisions)
        assertTrue(vm.uiState.value.draftSaved)
        assertEquals(0, repo.repairs)
    }

    @Test fun reloadAfterLegacySaveFailureRetainsUnsavedTextForRetryInsteadOfReturningOldTranscript() = runTest {
        val repo = Repository(); repo.failSave = true
        val vm = viewModel(repo); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.TextChanged("磁盘失败也要保留的编辑。")); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.RetryLoadRequested); advanceUntilIdle()
        assertEquals("磁盘失败也要保留的编辑。", vm.uiState.value.currentText)
        assertTrue(vm.uiState.value.draftSaveFailed)
        assertEquals(1, repo.loads)
        repo.failSave = false
        vm.onAction(TranscriptResultAction.RetryDraftSaveRequested); advanceUntilIdle()
        assertEquals("磁盘失败也要保留的编辑。", repo.value.fullText)
        assertEquals(listOf(0, 0), repo.revisions)
        assertTrue(vm.canLeave())
    }

    @Test fun failedDraftIsSavedBeforeRepairAndDuplicateInFlightRequestDoesNotAddAnotherCall() = runTest {
        val repairGate = CompletableDeferred<Unit>(); val repo = Repository(repairGate = repairGate); repo.failSave = true
        val vm = viewModel(repo); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.TextChanged("尚未保存但当前可见的全文。")); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.RepairRequested); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.RepairRequested)
        vm.onAction(TranscriptResultAction.RetryLoadRequested); advanceUntilIdle()
        assertEquals(0, repo.repairs)
        assertTrue(vm.uiState.value.draftSaveFailed)
        assertFalse(vm.canLeave())
        repo.failSave = false
        vm.onAction(TranscriptResultAction.RetryDraftSaveRequested); advanceUntilIdle()
        assertEquals("尚未保存但当前可见的全文。", repo.value.fullText)
        vm.onAction(TranscriptResultAction.RepairRequested); advanceUntilIdle()
        vm.onAction(TranscriptResultAction.RepairRequested); advanceUntilIdle()
        assertEquals(1, repo.repairs)
        assertEquals("尚未保存但当前可见的全文。", repo.repairTexts.single())
        assertEquals("尚未保存但当前可见的全文。", vm.uiState.value.currentText)
        assertFalse(vm.canLeave())
        repairGate.complete(Unit); advanceUntilIdle()
        assertFalse(vm.uiState.value.draftSaveFailed)
        assertEquals(TranscriptRepairNotice.NO_CHANGE, vm.uiState.value.repairNotice)
        assertEquals(1, repo.loads)
    }

    private fun viewModel(repo: Repository) = TranscriptResultViewModel("r1", "t1", "repair", "confirm", repo)
    private class Repository(
        private val saveGate: CompletableDeferred<Unit>? = null,
        private val repairGate: CompletableDeferred<Unit>? = null,
    ) : TranscriptResultRepository {
        var value = RecordingTranscript("t1", "r1", "原始全文。")
        var failSave = false
        var loads = 0
        var repairs = 0
        val revisions = mutableListOf<Int>()
        val repairTexts = mutableListOf<String>()
        override suspend fun load(transcriptId: String): TranscriptLoadResult { loads++; return TranscriptLoadResult.Ready(value) }
        override suspend fun togglePlayback(recordingId: String) = RecordingPlaybackResult.Stopped
        override suspend fun confirm(transcriptId: String, confirmedText: String, commandId: String) = TranscriptConfirmResult.Failed
        override suspend fun repair(transcriptId: String, currentText: String, manuallyEdited: Boolean, commandId: String): TranscriptRepairResult {
            repairs++; repairTexts += currentText; repairGate?.await(); return TranscriptRepairResult.NoChange
        }
        override suspend fun saveDraft(transcriptId: String, text: String, manuallyEdited: Boolean, expectedRevision: Int): TranscriptDraftSaveResult {
            revisions += expectedRevision; saveGate?.await()
            if (failSave || expectedRevision != value.draftRevision) return TranscriptDraftSaveResult.Failed
            value = value.copy(fullText = text, draftRevision = expectedRevision + 1, manuallyEdited = manuallyEdited, hasUnconfirmedEdits = true)
            return TranscriptDraftSaveResult.Saved(value.draftRevision)
        }
    }
}
