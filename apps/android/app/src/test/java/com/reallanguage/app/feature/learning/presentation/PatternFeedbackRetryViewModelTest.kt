package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.feature.learning.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** VM retry/admission contract only. The fake advertises ownership of an existing audio attempt;
 * this is not HTTP/command persistence, billing, microphone or real pronunciation evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class PatternFeedbackRetryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    @Before fun prepare() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() { owners.forEach(ViewModelStore::clear); Dispatchers.resetMain() }

    @Test fun pendingReadingIsBoundToItsExampleAndRetriesFeedbackWithoutAnotherCapture() = runTest(dispatcher) {
        val (vm, repository) = failedAttempt(Mode.READING, Outcome.UNKNOWN)
        vm.onAction(PatternSpeakingAction.StartReadingRequested("e2"))
        assertEquals("Another example cannot replace the retained attempt", 1, repository.starts)
        assertPending(vm, "e1")
        assertEquals(examples, vm.uiState.value.examples)
        repository.outcome = Outcome.READY
        val gate = CompletableDeferred<Unit>().also { repository.gate = it }
        try {
            repeat(3) { vm.onAction(PatternSpeakingAction.StartReadingRequested("e1")) }
            assertTrue("Retry must occupy the request before it is dispatched", vm.uiState.value.submitting)
            advanceUntilIdle()
            vm.onAction(PatternSpeakingAction.StartReadingRequested("e2"))
            vm.onAction(PatternSpeakingAction.ReadingFinished("e1"))
            advanceUntilIdle()
            assertEquals(listOf("e1", "e1"), repository.readingIds)
            assertEquals(1, repository.starts)
            assertEquals(1, repository.generations)
            assertFalse(vm.uiState.value.recording)
            assertTrue(vm.uiState.value.completedReadingExampleIds.isEmpty())
        } finally { gate.complete(Unit) }
        advanceUntilIdle()
        assertEquals(setOf("e1"), vm.uiState.value.completedReadingExampleIds)
        assertEquals(FEEDBACK, vm.uiState.value.feedbackZh)
        assertFalse(vm.uiState.value.technicalRetryPending)
        assertFalse(vm.uiState.value.technicalRetryBlocked)
        assertTrue(repository.responseIds.isEmpty())
    }

    @Test fun pendingResponseRetryActionReusesFeedbackAndRejectsRapidDuplicateActions() = runTest(dispatcher) {
        retryResponse(PatternSpeakingAction.RetryResponseRequested)
    }

    @Test fun pendingResponseStartActionReusesFeedbackAndRejectsRapidDuplicateActions() = runTest(dispatcher) {
        retryResponse(PatternSpeakingAction.StartResponseRequested)
    }

    @Test fun terminalFeedbackCannotTechnicallyRetryButExplicitStartMayCreateANewAttempt() = runTest(dispatcher) {
        Mode.entries.forEach { mode ->
            val (vm, repository) = failedAttempt(mode, Outcome.TERMINAL)
            val starts = repository.starts
            val calls = repository.calls.toList()
            assertFalse(vm.uiState.value.technicalRetryPending)
            assertTrue("Terminal status must remain explicit", vm.uiState.value.technicalRetryBlocked)
            assertEquals("e1", vm.uiState.value.feedbackExampleId)
            vm.onAction(PatternSpeakingAction.RetryResponseRequested)
            advanceUntilIdle()
            assertEquals(calls, repository.calls)
            assertEquals(starts, repository.starts)
            assertTrue(vm.uiState.value.technicalRetryBlocked)
            assertFalse(vm.uiState.value.recording)
            start(vm, mode)
            assertEquals(starts + 1, repository.starts)
            assertTrue(vm.uiState.value.recording)
            assertFalse(vm.uiState.value.technicalRetryPending)
            assertFalse(vm.uiState.value.technicalRetryBlocked)
            assertEquals("Starting new recording is not replaying the old feedback", calls, repository.calls)
            assertEquals(1, repository.generations)
        }
    }

    @Test fun deliveredInconclusiveUsesExistingExplicitPracticeFlowNotTechnicalReplay() = runTest(dispatcher) {
        Mode.entries.forEach { mode ->
            val (vm, repository) = failedAttempt(mode, Outcome.INCONCLUSIVE)
            val starts = repository.starts
            val calls = repository.calls.toList()
            assertTrue(vm.uiState.value.failureMessageVisible)
            assertFalse(vm.uiState.value.technicalRetryPending)
            assertFalse(vm.uiState.value.technicalRetryBlocked)
            if (mode == Mode.RESPONSE) {
                vm.onAction(PatternSpeakingAction.RetryResponseRequested)
                advanceUntilIdle()
                assertEquals(starts, repository.starts)
                assertEquals(calls, repository.calls)
            }
            repository.outcome = Outcome.READY
            start(vm, mode)
            assertEquals("Delivered inconclusive requires a new explicit recording", starts + 1, repository.starts)
            assertTrue(vm.uiState.value.recording)
            assertEquals(calls, repository.calls)
            finish(vm, mode)
            advanceUntilIdle()
            assertEquals(calls + Call(mode, pattern.patternId, "e1"), repository.calls)
            assertEquals(FEEDBACK, vm.uiState.value.feedbackZh)
            assertEquals(1, repository.generations)
            assertFalse(vm.uiState.value.technicalRetryPending)
            if (mode == Mode.READING) assertEquals(setOf("e1"), vm.uiState.value.completedReadingExampleIds)
            else assertTrue(vm.uiState.value.currentResponseCompleted)
        }
    }

    private suspend fun TestScope.retryResponse(action: PatternSpeakingAction) {
        val (vm, repository) = failedAttempt(Mode.RESPONSE, Outcome.UNKNOWN)
        val starts = repository.starts
        assertPending(vm, "e1")
        repository.outcome = Outcome.READY
        val gate = CompletableDeferred<Unit>().also { repository.gate = it }
        try {
            repeat(3) { vm.onAction(action) }
            assertTrue("The first retry must synchronously exclude a second", vm.uiState.value.submitting)
            advanceUntilIdle()
            vm.onAction(PatternSpeakingAction.StartResponseRequested)
            vm.onAction(PatternSpeakingAction.RetryResponseRequested)
            vm.onAction(PatternSpeakingAction.FinishResponseRequested)
            advanceUntilIdle()
            assertEquals(listOf("e1", "e1"), repository.responseIds)
            assertEquals(listOf("e1", "e2"), repository.readingIds)
            assertEquals(starts, repository.starts)
            assertEquals(1, repository.generations)
            assertFalse(vm.uiState.value.recording)
            assertFalse(vm.uiState.value.currentResponseCompleted)
            assertEquals(0, vm.uiState.value.responseIndex)
        } finally { gate.complete(Unit) }
        advanceUntilIdle()
        assertTrue(vm.uiState.value.currentResponseCompleted)
        assertEquals(examples.first().targetResponseEn, vm.uiState.value.transcript)
        assertEquals(FEEDBACK, vm.uiState.value.feedbackZh)
        assertEquals(setOf("e1", "e2"), vm.uiState.value.completedReadingExampleIds)
        assertFalse(vm.uiState.value.technicalRetryPending)
        assertFalse(vm.uiState.value.technicalRetryBlocked)
        assertEquals(examples, vm.uiState.value.examples)
    }

    private suspend fun TestScope.failedAttempt(mode: Mode, result: Outcome): Pair<PatternSpeakingViewModel, RetryRepository> {
        val repository = RetryRepository()
        val vm = PatternSpeakingViewModel(chapter, "s1", listOf(pattern), repository)
        owners += ViewModelStore().also { it.put("pattern", vm) }
        advanceUntilIdle()
        if (mode == Mode.RESPONSE) {
            examples.forEach { example ->
                vm.onAction(PatternSpeakingAction.StartReadingRequested(example.exampleId))
                vm.onAction(PatternSpeakingAction.ReadingFinished(example.exampleId))
                advanceUntilIdle()
            }
            vm.onAction(PatternSpeakingAction.StartResponsesRequested)
            assertEquals(PatternSpeakingStage.RESPONDING, vm.uiState.value.stage)
        }
        repository.outcome = result
        start(vm, mode)
        finish(vm, mode)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.failureMessageVisible)
        assertFalse(vm.uiState.value.recording)
        assertFalse(vm.uiState.value.submitting)
        return vm to repository
    }
    private fun assertPending(vm: PatternSpeakingViewModel, exampleId: String) {
        assertTrue(vm.uiState.value.technicalRetryPending)
        assertFalse(vm.uiState.value.technicalRetryBlocked)
        assertEquals(exampleId, vm.uiState.value.feedbackExampleId)
    }
    private fun start(vm: PatternSpeakingViewModel, mode: Mode) = vm.onAction(
        if (mode == Mode.READING) PatternSpeakingAction.StartReadingRequested("e1") else PatternSpeakingAction.StartResponseRequested)
    private fun finish(vm: PatternSpeakingViewModel, mode: Mode) = vm.onAction(
        if (mode == Mode.READING) PatternSpeakingAction.ReadingFinished("e1") else PatternSpeakingAction.FinishResponseRequested)

    private enum class Mode { READING, RESPONSE }
    private enum class Outcome { READY, UNKNOWN, TERMINAL, INCONCLUSIVE }
    private data class Call(val mode: Mode, val patternId: String, val exampleId: String)
    private class RetryRepository : PatternSpeakingRepository {
        var starts = 0
        var generations = 0
        var outcome = Outcome.READY
        var gate: CompletableDeferred<Unit>? = null
        private var pending = false
        private var blocked = false
        val calls = mutableListOf<Call>()
        val readingIds get() = calls.filter { it.mode == Mode.READING }.map { it.exampleId }
        val responseIds get() = calls.filter { it.mode == Mode.RESPONSE }.map { it.exampleId }
        override fun startCapture(): Boolean { starts++; pending = false; blocked = false; return true }
        override fun cancelCapture() { pending = false; blocked = false }
        override fun hasPendingFeedbackRetry() = pending
        override fun technicalRetryBlocked() = blocked
        override suspend fun generate(pattern: LockedSpeakingPattern): PatternPracticeGenerationResult {
            generations++
            check(pattern == PatternFeedbackRetryViewModelTest.pattern)
            return PatternPracticeGenerationResult.Ready(PatternPracticeSet(pattern.patternId, examples))
        }
        override suspend fun readingFeedback(patternId: String, exampleId: String) = feedback(Mode.READING, patternId, exampleId)
        override suspend fun responseFeedback(patternId: String, exampleId: String) = feedback(Mode.RESPONSE, patternId, exampleId)
        private suspend fun feedback(mode: Mode, patternId: String, exampleId: String): PatternPracticeFeedbackResult {
            check(patternId == pattern.patternId && examples.any { it.exampleId == exampleId })
            calls += Call(mode, patternId, exampleId)
            gate?.await()
            pending = outcome == Outcome.UNKNOWN
            blocked = outcome == Outcome.TERMINAL
            return when (outcome) {
                Outcome.UNKNOWN, Outcome.TERMINAL -> PatternPracticeFeedbackResult.Failed
                Outcome.INCONCLUSIVE -> PatternPracticeFeedbackResult.Inconclusive
                Outcome.READY -> PatternPracticeFeedbackResult.Ready(PatternPracticeFeedback(
                    FEEDBACK, false, examples.single { it.exampleId == exampleId }.targetResponseEn))
            }
        }
    }
    private companion object {
        const val FEEDBACK = "隔离句式练习反馈。"
        val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I used to walk to school.", "我过去常走路上学。", true)))
        val pattern = LockedSpeakingPattern("p1", "s1", "used to + verb", "说明过去常做的事情。")
        val examples = listOf(
            PatternPracticeExample("e1", "谈上学方式", "How did you get to school?", "I used to walk to school.", "我过去常走路上学。"),
            PatternPracticeExample("e2", "谈周末习惯", "What did you do on weekends?", "I used to visit my grandparents.", "我过去常看望祖父母。"),
        )
    }
}
