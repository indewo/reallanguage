package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.feature.learning.domain.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Pure VM recovery contract: all audio, requests, progress and candidates are in memory. */
@OptIn(ExperimentalCoroutinesApi::class)
class FullSpeakingRecoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()

    @Before fun prepare() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() {
        owners.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    @Test fun everyTrainingRoundRestoresTheOriginalRetryWithoutStartingOrSendingAutomatically() = runTest(dispatcher) {
        for (round in 0..3) {
            val events = mutableListOf<String>()
            val repo = RecoveryRepository(events).apply { recovery[round] = FullSpeakingRecovery.TrainingPending }
            val vm = model(repo, MemoryProgress(snapshot(round), events))
            runCurrent()
            assertEquals(listOf("read", "restore:$round"), events.take(2))
            assertEquals(round, vm.uiState.value.attemptIndex)
            assertTrue("round $round", vm.uiState.value.technicalRetryPending)
            assertFalse(vm.uiState.value.recording)
            assertEquals(0, repo.starts)
            assertTrue(repo.trainingModes.isEmpty())
            vm.onAction(FullSentenceSpeakingAction.StartRequested)
            runCurrent()
            assertEquals(0, repo.starts)
            vm.onAction(FullSentenceSpeakingAction.RetryRequested)
            runCurrent()
            assertEquals(listOf(if (round < 2) SpeakingTrainingMode.BILINGUAL_FOLLOW else SpeakingTrainingMode.ENGLISH_READING), repo.trainingModes)
            assertTrue(vm.uiState.value.trainingCanContinue)
            assertEquals(FEEDBACK.feedbackZh, vm.uiState.value.feedbackZh)
            assertEquals(0, repo.starts)
        }
    }

    @Test fun oneRetryTapDuringJournalRestoreRunsTheSavedRequestImmediatelyAfterRestore() = runTest(dispatcher) {
        val releaseRestore = CompletableDeferred<Unit>()
        val repo = RecoveryRepository().apply {
            recovery[2] = FullSpeakingRecovery.TrainingPending
            beforeRestore = { releaseRestore.await() }
        }
        val vm = model(repo, MemoryProgress(snapshot(2)))
        runCurrent()
        vm.onAction(FullSentenceSpeakingAction.RetryRequested)
        runCurrent()
        assertTrue(repo.trainingModes.isEmpty())
        releaseRestore.complete(Unit)
        runCurrent()
        assertEquals(listOf(SpeakingTrainingMode.ENGLISH_READING), repo.trainingModes)
        assertTrue(vm.uiState.value.trainingCanContinue)
        assertEquals(0, repo.starts)
    }

    @Test fun durableProductionRecoveryPollsTheSamePendingRequestUntilAResultArrives() = runTest(dispatcher) {
        val repo = RecoveryRepository(automaticallyResumePendingRequests = true).apply {
            recovery[2] = FullSpeakingRecovery.TrainingPending
            trainingResults += ReadingFeedbackResult.Failed
            trainingResults += ReadingFeedbackResult.Ready(FEEDBACK)
        }
        val vm = model(repo, MemoryProgress(snapshot(2)))
        runCurrent()
        assertTrue(vm.uiState.value.technicalRetryPending)
        assertTrue(repo.trainingModes.isEmpty())

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, repo.trainingModes.size)
        assertTrue(vm.uiState.value.technicalRetryPending)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(SpeakingTrainingMode.ENGLISH_READING, SpeakingTrainingMode.ENGLISH_READING), repo.trainingModes)
        assertTrue(vm.uiState.value.trainingCanContinue)
        assertFalse(vm.uiState.value.technicalRetryPending)
        assertEquals(0, repo.starts)
    }

    @Test fun deliveredTrainingContinuesOnlyAfterSavingTheNewRoundThenRestoresThatExactRound() = runTest(dispatcher) {
        val events = mutableListOf<String>()
        val repo = RecoveryRepository(events).apply {
            recovery[0] = FullSpeakingRecovery.TrainingReady(FEEDBACK)
            recovery[1] = FullSpeakingRecovery.TrainingPending
        }
        val progress = MemoryProgress(snapshot(0), events)
        val releaseWrite = CompletableDeferred<Unit>()
        val vm = model(repo, progress)
        try {
            runCurrent()
            assertTrue(vm.uiState.value.trainingCanContinue)
            assertEquals(FEEDBACK.feedbackZh, vm.uiState.value.feedbackZh)
            assertTrue(repo.trainingModes.isEmpty())
            progress.beforeWrite = { if (it["attemptIndex"] == "1") releaseWrite.await() }
            vm.onAction(FullSentenceSpeakingAction.ContinueTrainingRequested)
            runCurrent()
            assertEquals(0, vm.uiState.value.attemptIndex)
            assertFalse(events.contains("restore:1"))
            releaseWrite.complete(Unit)
            runCurrent()
            assertEquals(1, vm.uiState.value.attemptIndex)
            assertTrue(vm.uiState.value.technicalRetryPending)
            assertTrue(events.indexOf("write:1") < events.indexOf("restore:1"))
            assertTrue(repo.trainingModes.isEmpty())
            assertEquals(0, repo.starts)
            vm.onAction(FullSentenceSpeakingAction.RetryRequested)
            runCurrent()
            assertEquals(listOf(SpeakingTrainingMode.BILINGUAL_FOLLOW), repo.trainingModes)
        } finally {
            releaseWrite.complete(Unit)
            runCurrent()
        }
    }

    @Test fun pendingAsrRequiresAnExplicitOriginalRequestRetryAndNoNewCapture() = runTest(dispatcher) {
        val repo = RecoveryRepository().apply { recovery[4] = FullSpeakingRecovery.TranscriptionPending }
        val vm = model(repo, MemoryProgress(snapshot(4)))
        runCurrent()
        assertTrue(vm.uiState.value.technicalRetryPending)
        assertEquals(0, repo.transcriptions)
        vm.onAction(FullSentenceSpeakingAction.RetryRequested)
        runCurrent()
        assertEquals(1, repo.transcriptions)
        assertEquals(0, repo.starts)
        assertEquals(ASR.transcript, vm.uiState.value.transcript)
        assertEquals(ASR.lowConfidenceSegments, vm.uiState.value.lowConfidenceSegments)
        assertTrue(vm.uiState.value.transcriptReadyForConfirmation)
        assertTrue(repo.evaluations.isEmpty())
    }

    @Test fun recoveredAsrOwnsItsTranscriptAndLowConfidenceSegmentsInsteadOfAnUnboundOldEdit() = runTest(dispatcher) {
        val repo = RecoveryRepository().apply { recovery[4] = FullSpeakingRecovery.TranscriptReady(ASR) }
        val vm = model(repo, MemoryProgress(snapshot(4) + ("transcript" to "edit from an unrelated old recording")))
        runCurrent()
        assertEquals(ASR.transcript, vm.uiState.value.transcript)
        assertEquals(ASR.lowConfidenceSegments, vm.uiState.value.lowConfidenceSegments)
        assertTrue(vm.uiState.value.transcriptReadyForConfirmation)
        assertFalse(vm.uiState.value.needsNewRecording)
        assertEquals(0, repo.starts)
        assertEquals(0, repo.transcriptions)
        assertTrue(repo.evaluations.isEmpty())
        vm.onAction(FullSentenceSpeakingAction.TranscriptChanged("fresh confirmed edit"))
        vm.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        runCurrent()
        assertEquals(listOf("fresh confirmed edit" to false), repo.evaluations)
    }

    @Test fun pendingEvaluationLocksRecoveredFactsUntilExplicitRetryOfThatEvaluation() = runTest(dispatcher) {
        val repo = RecoveryRepository().apply {
            recovery[4] = FullSpeakingRecovery.EvaluationPending("already confirmed", false)
        }
        val vm = model(repo, MemoryProgress(snapshot(4) + ("transcript" to "stale edit")))
        runCurrent()
        assertTrue(vm.uiState.value.technicalRetryPending)
        assertTrue(vm.uiState.value.transcriptReadyForConfirmation)
        assertTrue(repo.evaluations.isEmpty())
        vm.onAction(FullSentenceSpeakingAction.TranscriptChanged("replacement"))
        vm.onAction(FullSentenceSpeakingAction.AnswerAudioRequested)
        vm.onAction(FullSentenceSpeakingAction.StartRequested)
        assertEquals("already confirmed", vm.uiState.value.transcript)
        assertFalse(vm.uiState.value.answerAudioPlayed)
        vm.onAction(FullSentenceSpeakingAction.RetryRequested)
        runCurrent()
        assertEquals(listOf("already confirmed" to false), repo.evaluations)
        assertEquals(0, repo.starts)
        assertEquals(0, repo.transcriptions)
        assertEquals(listOf(SpeakingEvaluationDecision.PASS), repo.saved)
    }

    @Test fun deliveredCandidateCanRetryOnlyLocalSaveAndHeardAnswerStillDisqualifiesPass() = runTest(dispatcher) {
        val repo = RecoveryRepository().apply {
            recovery[4] = FullSpeakingRecovery.EvaluationReady(CANDIDATE, "delivered transcript", true)
            saveResult = SpeakingDecisionSaveResult.Failed
        }
        val vm = model(repo, MemoryProgress(snapshot(4)))
        runCurrent()
        assertTrue(vm.uiState.value.evaluationReceived)
        assertEquals("delivered transcript", vm.uiState.value.transcript)
        assertTrue(vm.uiState.value.answerAudioPlayed)
        assertTrue(repo.evaluations.isEmpty())
        vm.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        runCurrent()
        assertTrue(vm.uiState.value.decisionSaveFailed)
        assertTrue(repo.saved.isNotEmpty())
        assertTrue(repo.saved.all { it == SpeakingEvaluationDecision.FAIL })
        repo.saveResult = SpeakingDecisionSaveResult.Saved
        vm.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        runCurrent()
        assertEquals(SpeakingEvaluationDecision.FAIL, vm.uiState.value.finalDecision)
        assertTrue(repo.evaluations.isEmpty())
        assertEquals(0, repo.transcriptions)
        assertEquals(0, repo.starts)
    }

    @Test fun unavailableRecoveryCannotCaptureOrAdvanceEvenWhenOldProgressSaysTrainingFinished() = runTest(dispatcher) {
        val repo = RecoveryRepository().apply { recovery[0] = FullSpeakingRecovery.Unavailable }
        val vm = model(repo, MemoryProgress(snapshot(0) + ("trainingCanContinue" to "true")))
        runCurrent()
        assertTrue(vm.uiState.value.progressFailed)
        vm.onAction(FullSentenceSpeakingAction.ContinueTrainingRequested)
        vm.onAction(FullSentenceSpeakingAction.StartRequested)
        runCurrent()
        assertEquals(0, vm.uiState.value.attemptIndex)
        assertEquals(0, repo.starts)
        assertTrue(repo.prepared.isEmpty())
        assertTrue(repo.trainingModes.isEmpty())
        assertTrue(vm.uiState.value.progressFailed)
    }

    @Test fun terminalRecoveryCannotRetryButExplicitStartPreparesANewAttempt() = runTest(dispatcher) {
        val repo = RecoveryRepository().apply { recovery[4] = FullSpeakingRecovery.Blocked }
        val vm = model(repo, MemoryProgress(snapshot(4)))
        runCurrent()
        assertTrue(vm.uiState.value.technicalRetryBlocked)
        vm.onAction(FullSentenceSpeakingAction.RetryRequested)
        runCurrent()
        assertEquals(0, repo.starts)
        assertTrue(repo.evaluations.isEmpty())
        vm.onAction(FullSentenceSpeakingAction.StartRequested)
        runCurrent()
        assertEquals(listOf("s1" to 4), repo.prepared)
        assertEquals(1, repo.starts)
        assertTrue(vm.uiState.value.recording)
        assertFalse(vm.uiState.value.technicalRetryBlocked)
    }

    @Test fun startWaitsForTheCurrentProgressAcknowledgementThenPreparationBeforeOpeningMic() = runTest(dispatcher) {
        val repo = RecoveryRepository()
        val progress = MemoryProgress(snapshot(4))
        val writeGate = CompletableDeferred<Unit>()
        val prepareGate = CompletableDeferred<Boolean>()
        repo.prepareHook = { prepareGate.await() }
        val vm = model(repo, progress)
        try {
            runCurrent()
            progress.beforeWrite = { writeGate.await() }
            vm.onAction(FullSentenceSpeakingAction.AnswerAudioRequested)
            runCurrent()
            vm.onAction(FullSentenceSpeakingAction.StartRequested)
            runCurrent()
            assertEquals(0, repo.starts)
            assertTrue(repo.prepared.isEmpty())
            writeGate.complete(Unit)
            runCurrent()
            assertEquals("true", progress.payload?.get("answerAudioPlayed"))
            assertEquals(listOf("s1" to 4), repo.prepared)
            assertEquals(0, repo.starts)
            prepareGate.complete(true)
            runCurrent()
            assertEquals(1, repo.starts)
            assertTrue(vm.uiState.value.recording)
        } finally {
            writeGate.complete(Unit)
            prepareGate.complete(true)
            runCurrent()
        }
    }

    @Test fun failedProgressOrRejectedPreparationNeverOpensTheMicrophone() = runTest(dispatcher) {
        for (rejectProgress in listOf(true, false)) {
            val repo = RecoveryRepository().apply { prepareHook = { false } }
            val progress = MemoryProgress(if (rejectProgress) null else snapshot(0)).apply { writable = !rejectProgress }
            val vm = model(repo, progress)
            runCurrent()
            vm.onAction(FullSentenceSpeakingAction.StartRequested)
            runCurrent()
            assertEquals(0, repo.starts)
            assertEquals(if (rejectProgress) 0 else 1, repo.prepared.size)
            assertFalse(vm.uiState.value.recording)
            assertTrue(vm.uiState.value.progressFailed || vm.uiState.value.captureFailed)
        }
    }

    @Test fun interruptOrClearDuringNonCooperativePreparationCannotLateStartTheMicrophone() = runTest(dispatcher) {
        for (clear in listOf(false, true)) {
            var callback: Continuation<Boolean>? = null
            val repo = RecoveryRepository().apply {
                prepareHook = { suspendCoroutine { callback = it } }
            }
            val vm = model(repo, MemoryProgress(snapshot(0)))
            try {
                runCurrent()
                vm.onAction(FullSentenceSpeakingAction.StartRequested)
                runCurrent()
                assertNotNull("The asynchronous preparation must actually have started", callback)
                assertEquals(0, repo.starts)
                if (clear) owners.last().clear() else vm.interruptUnsubmittedCapture()
                val old = callback
                callback = null
                old!!.resume(true)
                runCurrent()
                assertEquals("clear=$clear", 0, repo.starts)
                assertFalse(vm.uiState.value.recording)
                if (!clear) {
                    repo.prepareHook = { true }
                    vm.onAction(FullSentenceSpeakingAction.StartRequested)
                    runCurrent()
                    assertEquals("A later explicit Start remains available", 1, repo.starts)
                }
            } finally {
                callback?.resume(false)
                callback = null
                runCurrent()
            }
        }
    }

    @Test fun optInRecoveryAlsoRunsWithoutAProgressStore() = runTest(dispatcher) {
        val durable = RecoveryRepository().apply { recovery[0] = FullSpeakingRecovery.TrainingReady(FEEDBACK) }
        val recovered = model(durable, null)
        runCurrent()
        assertEquals(listOf(0), durable.restored)
        assertTrue(recovered.uiState.value.trainingCanContinue)
        assertEquals(0, durable.starts)
    }

    @Test fun legacyRepositoriesRemainSynchronousAndNeverUseTheRecoveryHooks() = runTest(dispatcher) {
        val legacy = RecoveryRepository(requiresAttemptRecovery = false)
        val unchanged = model(legacy, null)
        unchanged.onAction(FullSentenceSpeakingAction.StartRequested)
        assertTrue("Default-false review/fakes retain synchronous Start", unchanged.uiState.value.recording)
        assertEquals(1, legacy.starts)
        assertTrue(legacy.restored.isEmpty())
        assertTrue(legacy.prepared.isEmpty())
    }

    @Test fun matchingRecordingIdentityPreservesConfirmedDraftEditsIncludingAnEmptyEdit() = runTest(dispatcher) {
        for (edit in listOf("my careful edit", "")) {
            val repo = RecoveryRepository().apply {
                recordingId = "owned-recording-A"
                recovery[4] = FullSpeakingRecovery.TranscriptReady(ASR, recordingId)
            }
            val progress = MemoryProgress(snapshot(4) + mapOf("recordingId" to "owned-recording-A", "transcript" to edit))
            val vm = model(repo, progress)
            runCurrent()
            assertEquals("A persisted edit belongs only to this exact original recording", edit, vm.uiState.value.transcript)
            assertEquals(ASR.lowConfidenceSegments, vm.uiState.value.lowConfidenceSegments)
            assertTrue(vm.uiState.value.transcriptReadyForConfirmation)
            assertEquals("owned-recording-A", progress.payload?.get("recordingId"))
            assertEquals(0, repo.transcriptions)
            assertTrue(repo.evaluations.isEmpty())
        }
    }

    @Test fun missingOrDifferentEditIdentityUsesOriginalAsrAndFreshAsrRecordsOnlyItsRepositoryIdentity() = runTest(dispatcher) {
        for (oldId in listOf(null, "old-recording-A")) {
            val repo = RecoveryRepository().apply {
                recordingId = "current-recording-B"
                recovery[4] = FullSpeakingRecovery.TranscriptReady(ASR, recordingId)
            }
            val old = snapshot(4) + ("transcript" to "unbound old edit") +
                (oldId?.let { mapOf("recordingId" to it) } ?: emptyMap())
            val progress = MemoryProgress(old)
            val vm = model(repo, progress)
            runCurrent()
            assertEquals(ASR.transcript, vm.uiState.value.transcript)
            assertEquals(ASR.lowConfidenceSegments, vm.uiState.value.lowConfidenceSegments)
            assertEquals("current-recording-B", progress.payload?.get("recordingId"))
            assertEquals(0, repo.starts)
            assertEquals(0, repo.transcriptions)
        }
        val repo = RecoveryRepository().apply { recordingId = "fresh-recording-C" }
        val progress = MemoryProgress(snapshot(4))
        val vm = model(repo, progress)
        runCurrent()
        vm.onAction(FullSentenceSpeakingAction.StartRequested)
        runCurrent()
        vm.onAction(FullSentenceSpeakingAction.FinishedRequested)
        runCurrent()
        assertEquals(1, repo.transcriptions)
        assertEquals("fresh-recording-C", progress.payload?.get("recordingId"))
        vm.onAction(FullSentenceSpeakingAction.TranscriptChanged("fresh edit for C"))
        runCurrent()
        assertEquals("fresh edit for C", progress.payload?.get("transcript"))
        assertEquals("fresh-recording-C", progress.payload?.get("recordingId"))
    }

    @Test fun liveRepeatForClarityKeepsTheRoundAndRequiresAnExplicitNewRecordingWithoutFormalFailure() = runTest(dispatcher) {
        for (round in 0..3) {
            val repeat = ReadingFeedback("这次证据不足，请清楚地再读一遍。", ReadingFeedbackAction.REPEAT_FOR_CLARITY)
            val repo = RecoveryRepository().apply { trainingResponse = repeat }
            val vm = model(repo, MemoryProgress(snapshot(round)))
            runCurrent()
            vm.onAction(FullSentenceSpeakingAction.StartRequested)
            runCurrent()
            vm.onAction(FullSentenceSpeakingAction.FinishedRequested)
            runCurrent()
            assertEquals(repeat.feedbackZh, vm.uiState.value.feedbackZh)
            assertFalse("Uncertain feedback cannot consume round $round", vm.uiState.value.trainingCanContinue)
            assertNull(vm.uiState.value.finalDecision)
            assertFalse(vm.uiState.value.captureFailed)
            assertFalse(vm.uiState.value.technicalRetryPending)
            assertEquals(1, repo.starts)
            vm.onAction(FullSentenceSpeakingAction.ContinueTrainingRequested)
            runCurrent()
            assertEquals(round, vm.uiState.value.attemptIndex)
            assertEquals(1, repo.starts)
            vm.onAction(FullSentenceSpeakingAction.StartRequested)
            runCurrent()
            assertEquals(2, repo.starts)
            assertTrue(vm.uiState.value.recording)
            assertEquals(listOf("s1" to round, "s1" to round), repo.prepared)
            assertEquals(1, repo.trainingModes.size)
            assertTrue(repo.saved.isEmpty())
            assertTrue(repo.evaluations.isEmpty())
        }
    }

    @Test fun recoveredRepeatForClarityKeepsFeedbackButCannotAdvanceOrAutomaticallyRecordAgain() = runTest(dispatcher) {
        for (round in 0..3) {
            val repeat = ReadingFeedback("请重读，让关键音更清楚。", ReadingFeedbackAction.REPEAT_FOR_CLARITY)
            val repo = RecoveryRepository().apply { recovery[round] = FullSpeakingRecovery.TrainingReady(repeat) }
            val vm = model(repo, MemoryProgress(snapshot(round)))
            runCurrent()
            assertEquals(repeat.feedbackZh, vm.uiState.value.feedbackZh)
            assertFalse(vm.uiState.value.trainingCanContinue)
            assertNull(vm.uiState.value.finalDecision)
            assertFalse(vm.uiState.value.captureFailed)
            assertFalse(vm.uiState.value.technicalRetryPending)
            assertEquals(0, repo.starts)
            vm.onAction(FullSentenceSpeakingAction.ContinueTrainingRequested)
            runCurrent()
            assertEquals(round, vm.uiState.value.attemptIndex)
            assertEquals(0, repo.starts)
            vm.onAction(FullSentenceSpeakingAction.StartRequested)
            runCurrent()
            assertEquals(1, repo.starts)
            assertTrue(vm.uiState.value.recording)
            assertEquals(listOf("s1" to round), repo.prepared)
            assertTrue(repo.trainingModes.isEmpty())
            assertTrue(repo.saved.isEmpty())
            assertTrue(repo.evaluations.isEmpty())
        }
    }

    private fun model(repo: RecoveryRepository, progress: LearningProgressStore?): FullSentenceSpeakingViewModel {
        val vm = FullSentenceSpeakingViewModel(CHAPTER, "s1", repo, progress)
        owners += ViewModelStore().also { it.put("full-recovery", vm) }
        return vm
    }

    private class MemoryProgress(
        var payload: Map<String, String>?,
        private val events: MutableList<String> = mutableListOf(),
    ) : LearningProgressStore {
        var writable = true
        var beforeWrite: suspend (Map<String, String>) -> Unit = {}
        override suspend fun read(): Map<String, String>? {
            events += "read"
            return payload?.toMap()
        }
        override suspend fun write(values: Map<String, String>): Boolean {
            beforeWrite(values)
            if (!writable) return false
            payload = values.toMap()
            events += "write:${values.getValue("attemptIndex")}"
            return true
        }
    }

    private class RecoveryRepository(
        private val events: MutableList<String> = mutableListOf(),
        override val requiresAttemptRecovery: Boolean = true,
        override val automaticallyResumePendingRequests: Boolean = false,
    ) : FullSentenceSpeakingRepository {
        val recovery = mutableMapOf<Int, FullSpeakingRecovery>()
        val restored = mutableListOf<Int>()
        val prepared = mutableListOf<Pair<String, Int>>()
        val trainingModes = mutableListOf<SpeakingTrainingMode>()
        val evaluations = mutableListOf<Pair<String, Boolean>>()
        val saved = mutableListOf<SpeakingEvaluationDecision>()
        var starts = 0
        var transcriptions = 0
        var current: FullSpeakingRecovery = FullSpeakingRecovery.None
        var recordingId: String? = null
        var prepareHook: suspend () -> Boolean = { true }
        var beforeRestore: suspend () -> Unit = {}
        var saveResult: SpeakingDecisionSaveResult = SpeakingDecisionSaveResult.Saved
        var trainingResponse: ReadingFeedback = FEEDBACK
        val trainingResults = ArrayDeque<ReadingFeedbackResult>()
        override suspend fun restoreAttempt(sentenceId: String, attemptIndex: Int): FullSpeakingRecovery {
            assertEquals("s1", sentenceId)
            beforeRestore()
            restored += attemptIndex
            events += "restore:$attemptIndex"
            return (recovery[attemptIndex] ?: FullSpeakingRecovery.None).also { current = it }
        }
        override suspend fun prepareCapture(sentenceId: String, attemptIndex: Int): Boolean {
            prepared += sentenceId to attemptIndex
            events += "prepare:$attemptIndex"
            return prepareHook()
        }
        override fun startCapture(): Boolean { starts++; current = FullSpeakingRecovery.None; return true }
        override fun cancelCapture() = Unit
        override fun currentRecordingId() = recordingId
        override fun hasPendingTrainingRetry() = current == FullSpeakingRecovery.TrainingPending
        override fun hasPendingTranscriptionRetry() = current == FullSpeakingRecovery.TranscriptionPending
        override fun hasPendingEvaluationRetry() = current is FullSpeakingRecovery.EvaluationPending
        override fun technicalRetryBlocked() = current == FullSpeakingRecovery.Blocked
        override suspend fun trainingFeedback(sentenceId: String, mode: SpeakingTrainingMode): ReadingFeedbackResult {
            trainingModes += mode
            val result = if (trainingResults.isEmpty()) ReadingFeedbackResult.Ready(trainingResponse)
            else trainingResults.removeFirst()
            if (result is ReadingFeedbackResult.Ready) current = FullSpeakingRecovery.TrainingReady(result.feedback)
            return result
        }
        override suspend fun transcribeIndependentRetelling(sentenceId: String): SpeakingTranscriptionResult {
            transcriptions++
            current = FullSpeakingRecovery.TranscriptReady(ASR)
            return SpeakingTranscriptionResult.Ready(ASR)
        }
        override suspend fun evaluateIndependentRetelling(sentenceId: String, confirmedTranscript: String,
            answerAudioPlayed: Boolean): SpeakingEvaluationResult {
            evaluations += confirmedTranscript to answerAudioPlayed
            current = FullSpeakingRecovery.EvaluationReady(CANDIDATE, confirmedTranscript, answerAudioPlayed)
            return SpeakingEvaluationResult.Ready(CANDIDATE)
        }
        override suspend fun saveIndependentDecision(sentenceId: String, decision: SpeakingEvaluationDecision): SpeakingDecisionSaveResult {
            saved += decision
            return saveResult
        }
    }

    private companion object {
        val CHAPTER = FixedLearningChapter("c1", 1,
            listOf(FixedLearningSentence("s1", "I expressed myself clearly.", "我清楚地表达了自己。", true)))
        val FEEDBACK = ReadingFeedback("内容完整，节奏清楚。", ReadingFeedbackAction.CONTINUE)
        val ASR = SpeakingTranscription("I expressed myself clearly.", listOf("expressed"), 0.65)
        val CANDIDATE = SpeakingEvaluationCandidate(SpeakingEvaluationDecision.PASS, 90, true, true, "内容完整且发音清楚。")
        fun snapshot(round: Int) = mapOf("version" to "1", "attemptIndex" to round.toString(),
            "answerAudioPlayed" to "false", "transcript" to "", "feedbackZh" to "",
            "trainingCanContinue" to "false", "finalDecision" to "")
    }
}
