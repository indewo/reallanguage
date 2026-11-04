package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.recording.domain.RecordingPlaybackResult
import com.reallanguage.app.feature.recording.domain.RecordingTranscript
import com.reallanguage.app.feature.recording.domain.RepairChange
import com.reallanguage.app.feature.recording.domain.RepairReason
import com.reallanguage.app.feature.recording.domain.TranscriptConfirmResult
import com.reallanguage.app.feature.recording.domain.TranscriptDraftSaveResult
import com.reallanguage.app.feature.recording.domain.TranscriptLoadResult
import com.reallanguage.app.feature.recording.domain.TranscriptRepairResult
import com.reallanguage.app.feature.recording.domain.TranscriptResultRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptResultViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun reopeningDisplaysSavedRepairWithoutCallingTheProvider() = runTest {
        val repository = FakeRepository(loadResult = TranscriptLoadResult.Ready(transcript.copy(recoveredRepair = proposedRepair)))
        val vm = viewModel(repository)
        advanceUntilIdle()
        assertEquals(proposedRepair, vm.uiState.value.repairComparison)
        assertEquals(0, repository.repairCalls)
        vm.onAction(TranscriptResultAction.KeepCurrentRequested)
        advanceUntilIdle()
        assertEquals(1, repository.dismissCalls)
        assertNull(vm.uiState.value.repairComparison)
    }

    @Test fun reopeningSavedNoChangeDisplaysTheOriginalOutcomeWithoutGenerating() = runTest {
        val repository = FakeRepository(loadResult = TranscriptLoadResult.Ready(transcript.copy(recoveredRepair = TranscriptRepairResult.NoChange)))
        val vm = viewModel(repository)
        advanceUntilIdle()
        assertEquals(TranscriptRepairNotice.NO_CHANGE, vm.uiState.value.repairNotice)
        assertEquals(0, repository.repairCalls)
    }

    @Test fun repairDoesNotSubmitTextWhileItsDraftSaveIsStillUnknown() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository(draftGate = gate)
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.onAction(TranscriptResultAction.TextChanged("保存中的原文。"))
        advanceUntilIdle()
        vm.onAction(TranscriptResultAction.RepairRequested)
        advanceUntilIdle()
        val callsWhileSaving = repository.repairCalls
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, callsWhileSaving)
    }

    @Test fun repairDoesNotChargeForAnUnsavedFailedDraft() = runTest {
        val repository = FakeRepository(draftFails = true)
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.onAction(TranscriptResultAction.TextChanged("尚未存下的原文。"))
        advanceUntilIdle()
        vm.onAction(TranscriptResultAction.RepairRequested)
        advanceUntilIdle()
        assertEquals(0, repository.repairCalls)
    }

    @Test fun backCannotDropAnOlderTranscriptEditUntilDraftIsSaved() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository(draftGate = gate)
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.onAction(TranscriptResultAction.TextChanged("必须保存的完整转写。"))
        advanceUntilIdle()
        assertFalse(vm.canLeave())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(true, vm.canLeave())
    }

    @Test fun backCannotDiscardDraftSaveFailureButRetryCanReleaseTheBoundary() = runTest {
        val repository = FakeRepository(draftFails = true)
        val vm = viewModel(repository)
        advanceUntilIdle()
        vm.onAction(TranscriptResultAction.TextChanged("保存失败仍属于用户的内容。"))
        advanceUntilIdle()
        assertFalse(vm.canLeave())
        repository.draftFails = false
        vm.onAction(TranscriptResultAction.RetryDraftSaveRequested)
        advanceUntilIdle()
        assertEquals(true, vm.canLeave())
    }

    @Test fun backCannotPretendAnInFlightConfirmationWasCancelled() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(FakeRepository(confirmGate = gate))
        advanceUntilIdle()
        vm.onAction(TranscriptResultAction.ConfirmRequested)
        advanceUntilIdle()
        assertFalse(vm.canLeave())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(true, vm.canLeave())
    }

    @Test fun rapidEditsAreSerializedAndTheLatestTextIsSavedWithTheNextRevision() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository(draftGate = gate)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.TextChanged("第一版"))
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.TextChanged("最终版本"))
        assertEquals(true, viewModel.uiState.value.draftSaving)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("第一版", "最终版本"), repository.savedDrafts)
        assertEquals(listOf(0, 1), repository.draftRevisions)
        assertEquals(true, viewModel.uiState.value.draftSaved)
        assertEquals("最终版本", viewModel.uiState.value.currentText)
    }

    @Test fun draftFailureIsVisibleAndRetryKeepsTheUsersText() = runTest {
        val repository = FakeRepository(draftFails = true)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.TextChanged("不能丢失的修改。"))
        advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.draftSaveFailed)
        assertFalse(viewModel.uiState.value.draftSaved)
        repository.draftFails = false
        viewModel.onAction(TranscriptResultAction.RetryDraftSaveRequested)
        advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.draftSaved)
        assertEquals("不能丢失的修改。", viewModel.uiState.value.currentText)
        assertEquals(listOf(0, 0), repository.draftRevisions)
    }

    @Test fun editIsSavedBeforeConfirmationWithoutCallingAi() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.TextChanged("要恢复的编辑。"))
        advanceUntilIdle()
        assertEquals(listOf("要恢复的编辑。"), repository.savedDrafts)
        assertEquals(0, repository.repairCalls)
        assertEquals(0, repository.confirmCommands.size)
    }

    @Test fun acceptedRepairIsPersistedAsUnconfirmedDraftNotAsFormalSource() = runTest {
        val repository = FakeRepository(repairResult = proposedRepair)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.RepairRequested)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.AcceptRepairRequested)
        advanceUntilIdle()
        assertEquals(listOf(proposedRepair.repairedText), repository.savedDrafts)
        assertEquals(0, repository.confirmCommands.size)
    }

    @Test fun submittedTranscriptCannotBeChangedOrSubmittedAgainWhileNavigationIsPending() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.ConfirmRequested)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.TextChanged("保存后但跳转前的另一段文字。"))
        viewModel.onAction(TranscriptResultAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals(1, repository.confirmCommands.size)
        assertEquals(transcript.fullText, viewModel.uiState.value.currentText)
    }

    @Test fun blankTranscriptCannotSubmitEvenIfTriggeredOutsideTheDisabledButton() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.TextChanged("  "))
        viewModel.onAction(TranscriptResultAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals(0, repository.confirmCommands.size)
    }

    @Test fun playbackFailureFromNativeCallbackUpdatesTheVisibleResult() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.PreviewRequested)
        advanceUntilIdle()
        repository.playbackState.value = RecordingPlaybackResult.Failed
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.playing)
        assertEquals(TranscriptResultError.PLAYBACK_FAILED, viewModel.uiState.value.error)
    }

    @Test fun reopenedConfirmedEditsRemainMarkedAsManualForRepair() = runTest {
        val repository = FakeRepository(loadResult = TranscriptLoadResult.Ready(transcript.copy(manuallyEdited = true)))
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.RepairRequested)
        advanceUntilIdle()
        assertEquals(true, repository.lastManuallyEdited)
    }

    @Test fun wrongRecordingCannotBeShownOrConfirmedUnderCurrentAudio() = runTest {
        val repository = FakeRepository(loadResult = TranscriptLoadResult.Ready(transcript.copy(recordingId = "other-recording")))
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        assertEquals(TranscriptResultError.LOAD_FAILED, viewModel.uiState.value.error)
        viewModel.onAction(TranscriptResultAction.ConfirmRequested)
        advanceUntilIdle()
        assertNull(repository.confirmedText)
    }

    @Test fun loadsEditableTranscriptWithoutAutomaticRepair() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        assertEquals(transcript.fullText, viewModel.uiState.value.currentText)
        assertEquals(0, repository.repairCalls)
    }

    @Test fun manualEditIsPreservedAsRepairInput() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.TextChanged("我昨天去了公园。"))
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.RepairRequested)
        advanceUntilIdle()
        assertEquals("我昨天去了公园。", repository.lastRepairText)
        assertEquals(true, repository.lastManuallyEdited)
    }

    @Test fun noChangeKeepsCurrentTextAndShowsObjectiveResult() = runTest {
        val repository = FakeRepository(repairResult = TranscriptRepairResult.NoChange)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.RepairRequested)
        advanceUntilIdle()
        assertEquals(transcript.fullText, viewModel.uiState.value.currentText)
        assertEquals(TranscriptRepairNotice.NO_CHANGE, viewModel.uiState.value.repairNotice)
    }

    @Test fun proposedRepairRequiresExplicitAcceptance() = runTest {
        val repository = FakeRepository(repairResult = proposedRepair)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.RepairRequested)
        advanceUntilIdle()
        assertEquals(transcript.fullText, viewModel.uiState.value.currentText)
        assertEquals(proposedRepair, viewModel.uiState.value.repairComparison)
        viewModel.onAction(TranscriptResultAction.AcceptRepairRequested)
        assertEquals("我昨天去了公园。", viewModel.uiState.value.currentText)
        assertNull(viewModel.uiState.value.repairComparison)
    }

    @Test fun keepCurrentRejectsProposalWithoutChangingText() = runTest {
        val viewModel = viewModel(FakeRepository(repairResult = proposedRepair))
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.RepairRequested)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.KeepCurrentRequested)
        advanceUntilIdle() // Dismissal now completes only after its durable write succeeds.
        assertEquals(transcript.fullText, viewModel.uiState.value.currentText)
        assertNull(viewModel.uiState.value.repairComparison)
    }

    @Test fun previewUsesLocalRecordingPlayback() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.PreviewRequested)
        advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.playing)
    }

    @Test fun confirmUsesVisibleTextAndOpensSafetyCheck() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.TextChanged("我修改后的转写。"))
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(TranscriptResultAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals("我修改后的转写。", repository.confirmedText)
        assertEquals(TranscriptResultEffect.OpenSafetyCheck("source-1", "我修改后的转写。"), effect.await())
    }

    @Test fun repairAndConfirmFailuresStayOnResultPage() = runTest {
        val repository = FakeRepository(
            repairResult = TranscriptRepairResult.Failed,
            confirmResult = TranscriptConfirmResult.Failed,
        )
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.RepairRequested)
        advanceUntilIdle()
        assertEquals(TranscriptResultError.REPAIR_FAILED, viewModel.uiState.value.error)
        viewModel.onAction(TranscriptResultAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals(TranscriptResultError.CONFIRM_FAILED, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.confirming)
    }

    @Test fun sameTextRetryKeepsCommandButChangedTextUsesNewRepairAndConfirmationCommands() = runTest {
        val repository = FakeRepository(confirmResult = TranscriptConfirmResult.Failed)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        repeat(2) {
            viewModel.onAction(TranscriptResultAction.RepairRequested)
            advanceUntilIdle()
            viewModel.onAction(TranscriptResultAction.ConfirmRequested)
            advanceUntilIdle()
        }
        viewModel.onAction(TranscriptResultAction.TextChanged("新的用户确认文本。"))
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.RepairRequested)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals(repository.repairCommands[0], repository.repairCommands[1])
        assertNotEquals(repository.repairCommands[0], repository.repairCommands[2])
        assertEquals(repository.confirmCommands[0], repository.confirmCommands[1])
        assertNotEquals(repository.confirmCommands[0], repository.confirmCommands[2])
    }

    @Test fun confirmInFlightKeepsVisibleTextAndRejectsConcurrentEditOrReload() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository(confirmGate = gate)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.ConfirmRequested)
        advanceUntilIdle()
        viewModel.onAction(TranscriptResultAction.TextChanged("未提交的不同文本。"))
        viewModel.onAction(TranscriptResultAction.RetryLoadRequested)
        advanceUntilIdle()
        assertEquals(transcript.fullText, viewModel.uiState.value.currentText)
        assertEquals(true, viewModel.uiState.value.confirming)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(transcript.fullText, repository.confirmedText)
    }

    private fun viewModel(repository: FakeRepository) = TranscriptResultViewModel(
        recordingId = "r1", transcriptId = "t1", repairCommandId = "repair-1",
        confirmCommandId = "confirm-1", repository = repository,
    )

    private class FakeRepository(
        var repairResult: TranscriptRepairResult = TranscriptRepairResult.NoChange,
        var confirmResult: TranscriptConfirmResult = TranscriptConfirmResult.Confirmed("source-1"),
        val confirmGate: CompletableDeferred<Unit>? = null,
        val loadResult: TranscriptLoadResult = TranscriptLoadResult.Ready(transcript),
        val draftGate: CompletableDeferred<Unit>? = null,
        var draftFails: Boolean = false,
    ) : TranscriptResultRepository {
        override val playbackState = kotlinx.coroutines.flow.MutableStateFlow<RecordingPlaybackResult>(RecordingPlaybackResult.Stopped)
        var repairCalls = 0
        var dismissCalls = 0
        override suspend fun dismissRepair(transcriptId: String): Boolean { dismissCalls++; return true }
        var lastRepairText: String? = null
        var lastManuallyEdited: Boolean? = null
        var confirmedText: String? = null
        val repairCommands = mutableListOf<String>()
        val confirmCommands = mutableListOf<String>()
        val savedDrafts = mutableListOf<String>()
        val draftRevisions = mutableListOf<Int>()
        override suspend fun saveDraft(transcriptId: String, text: String, manuallyEdited: Boolean, expectedRevision: Int): TranscriptDraftSaveResult {
            savedDrafts += text
            draftRevisions += expectedRevision
            draftGate?.await()
            if (draftFails) return TranscriptDraftSaveResult.Failed
            return TranscriptDraftSaveResult.Saved(expectedRevision + 1)
        }
        override suspend fun load(transcriptId: String): TranscriptLoadResult = loadResult
        override suspend fun togglePlayback(recordingId: String): RecordingPlaybackResult {
            playbackState.value = RecordingPlaybackResult.Playing
            return RecordingPlaybackResult.Playing
        }
        override suspend fun repair(
            transcriptId: String, currentText: String, manuallyEdited: Boolean, commandId: String,
        ): TranscriptRepairResult {
            repairCalls++
            repairCommands += commandId
            lastRepairText = currentText
            lastManuallyEdited = manuallyEdited
            return repairResult
        }
        override suspend fun confirm(
            transcriptId: String, confirmedText: String, commandId: String,
        ): TranscriptConfirmResult {
            this.confirmedText = confirmedText
            confirmCommands += commandId
            confirmGate?.await()
            return confirmResult
        }
    }

    companion object {
        private val transcript = RecordingTranscript("t1", "r1", "我昨天，去公园。")
        private val proposedRepair = TranscriptRepairResult.Proposed(
            repairedText = "我昨天去了公园。",
            changes = listOf(
                RepairChange("我昨天，去公园。", "我昨天去了公园。", RepairReason.CONFIRMED_DISFLUENCY),
            ),
        )
    }
}
