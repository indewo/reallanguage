package com.reallanguage.app.feature.knowledge.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.knowledge.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PatternPronunciationViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun currentExampleUsesExactStoredSpeechAndDoubleClickCannotReplayOrChangeLearningCounts() = runTest {
        val vm = viewModel(); val effects = mutableListOf<PatternBookEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect(effects::add) }; advanceUntilIdle()
        vm.onAction(PatternBookAction.PatternSelected("p1"))
        repeat(2) { vm.onAction(PatternBookAction.ExamplePronunciationRequested("p1", 0)) }; advanceUntilIdle()
        assertEquals(listOf(PatternBookEffect.PlayExample("She ran a café for the first time.", 0)), effects)
        assertTrue(vm.uiState.value.pronunciationBusy)
        assertEquals(patterns, vm.uiState.value.patterns)
    }

    @Test fun blankMissingOrOtherPatternExamplesNeverProduceAudioOrReadAbstractStructure() = runTest {
        val vm = viewModel(); val effects = mutableListOf<PatternBookEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect(effects::add) }; advanceUntilIdle()
        vm.onAction(PatternBookAction.ExamplePronunciationRequested("p1", 0))
        vm.onAction(PatternBookAction.PatternSelected("p1"))
        vm.onAction(PatternBookAction.ExamplePronunciationRequested("p2", 0))
        vm.onAction(PatternBookAction.ExamplePronunciationRequested("p1", -1))
        vm.onAction(PatternBookAction.ExamplePronunciationRequested("p1", 99))
        vm.onAction(PatternBookAction.ExamplePronunciationRequested("p1", 1)); advanceUntilIdle()
        assertTrue(effects.isEmpty())
        assertFalse(vm.uiState.value.pronunciationBusy)
        assertEquals(patterns, vm.uiState.value.patterns)
    }

    @Test fun actualFailureIsRetryableAndLateCallbackCannotFinishNewPlayback() = runTest {
        val vm = viewModel(); val effects = mutableListOf<PatternBookEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect(effects::add) }; advanceUntilIdle()
        vm.onAction(PatternBookAction.PatternSelected("p1"))
        vm.onAction(PatternBookAction.ExamplePronunciationRequested("p1", 0)); advanceUntilIdle()
        vm.onAction(PatternBookAction.PronunciationFinished(0, false)); advanceUntilIdle()
        assertTrue(vm.uiState.value.pronunciationFailed)
        assertFalse(vm.uiState.value.pronunciationBusy)
        vm.onAction(PatternBookAction.RetryPronunciationRequested); advanceUntilIdle()
        assertEquals(PatternBookEffect.PlayExample("She ran a café for the first time.", 1), effects.last())
        vm.onAction(PatternBookAction.PronunciationFinished(0, true)); advanceUntilIdle()
        assertTrue(vm.uiState.value.pronunciationBusy)
        vm.onAction(PatternBookAction.PronunciationFinished(1, true)); advanceUntilIdle()
        assertFalse(vm.uiState.value.pronunciationBusy)
        assertFalse(vm.uiState.value.pronunciationFailed)
        assertEquals(patterns, vm.uiState.value.patterns)
    }

    @Test fun switchingPatternStopsOldPlaybackAndRejectsOldExampleOrFailure() = runTest {
        val vm = viewModel(); val effects = mutableListOf<PatternBookEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect(effects::add) }; advanceUntilIdle()
        vm.onAction(PatternBookAction.PatternSelected("p1"))
        vm.onAction(PatternBookAction.ExamplePronunciationRequested("p1", 0)); advanceUntilIdle()
        vm.onAction(PatternBookAction.PatternSelected("p2")); advanceUntilIdle()
        assertEquals(PatternBookEffect.StopExample, effects.last())
        vm.onAction(PatternBookAction.PronunciationFinished(0, false))
        vm.onAction(PatternBookAction.ExamplePronunciationRequested("p1", 0))
        vm.onAction(PatternBookAction.RetryPronunciationRequested); advanceUntilIdle()
        assertEquals(2, effects.size)
        assertEquals("p2", vm.uiState.value.expandedPatternId)
        assertFalse(vm.uiState.value.pronunciationFailed)
    }

    @Test fun removalConfirmationStopsPlaybackWithoutRemovingOrChangingThePattern() = runTest {
        val vm = viewModel(); val effects = mutableListOf<PatternBookEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect(effects::add) }; advanceUntilIdle()
        vm.onAction(PatternBookAction.PatternSelected("p1"))
        vm.onAction(PatternBookAction.ExamplePronunciationRequested("p1", 0)); advanceUntilIdle()
        vm.onAction(PatternBookAction.RemoveRequested); advanceUntilIdle()
        assertEquals(listOf(PatternBookEffect.StopExample, PatternBookEffect.OpenRemovalConfirmation("p1")), effects.takeLast(2))
        assertFalse(vm.uiState.value.pronunciationBusy)
        assertEquals(patterns, vm.uiState.value.patterns)
    }

    @Test fun collapseReloadAndHomeStopAudioInsteadOfLeavingItInAnotherScreen() = runTest {
        val vm = viewModel(); val effects = mutableListOf<PatternBookEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect(effects::add) }; advanceUntilIdle()
        vm.onAction(PatternBookAction.PatternSelected("p1"))
        vm.onAction(PatternBookAction.ExamplePronunciationRequested("p1", 0)); advanceUntilIdle()
        vm.onAction(PatternBookAction.CollapseRequested); advanceUntilIdle()
        assertEquals(PatternBookEffect.StopExample, effects.last())
        assertNull(vm.uiState.value.expandedPatternId)
        vm.onAction(PatternBookAction.PatternSelected("p1"))
        vm.onAction(PatternBookAction.ExamplePronunciationRequested("p1", 0)); advanceUntilIdle()
        vm.onAction(PatternBookAction.RetryRequested); advanceUntilIdle()
        assertEquals(PatternBookEffect.StopExample, effects.last())
        vm.onAction(PatternBookAction.ExamplePronunciationRequested("p1", 0)); advanceUntilIdle()
        vm.onAction(PatternBookAction.ReturnHomeRequested); advanceUntilIdle()
        assertEquals(listOf(PatternBookEffect.StopExample, PatternBookEffect.ReturnHome), effects.takeLast(2))
        assertFalse(vm.uiState.value.pronunciationBusy)
    }

    @Test fun activityInterruptionKeepsCurrentPatternButDoesNotHangOrClaimLearningSuccess() = runTest {
        val vm = viewModel(); val effects = mutableListOf<PatternBookEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect(effects::add) }; advanceUntilIdle()
        vm.onAction(PatternBookAction.PatternSelected("p1"))
        vm.onAction(PatternBookAction.ExamplePronunciationRequested("p1", 0)); advanceUntilIdle()
        vm.onAction(PatternBookAction.PronunciationInterrupted); advanceUntilIdle()
        assertEquals(PatternBookEffect.StopExample, effects.last())
        assertFalse(vm.uiState.value.pronunciationBusy)
        assertEquals("p1", vm.uiState.value.expandedPatternId)
        vm.onAction(PatternBookAction.PronunciationFinished(0, true)); advanceUntilIdle()
        assertEquals(patterns, vm.uiState.value.patterns)
        vm.onAction(PatternBookAction.ExamplePronunciationRequested("p1", 0)); advanceUntilIdle()
        assertEquals(PatternBookEffect.PlayExample("She ran a café for the first time.", 1), effects.last())
        assertTrue(vm.uiState.value.pronunciationBusy)
    }

    private fun viewModel() = PatternBookViewModel(object : PatternBookRepository {
        override suspend fun loadAllPatterns() = PatternLoadResult.Ready(patterns)
    })
    companion object {
        private val patterns = listOf(
            PatternRecord("p1", "for the first time", "表示第一次经历", KnowledgeStatus.MASTERED, KnowledgeStatus.LEARNING, 2, 5,
                examples = listOf(
                    PatternExample("She ran a cafe for the first time.", "她第一次经营一家咖啡店。", "She ran a café for the first time."),
                    PatternExample("A legacy full example.", "旧的完整例句。", ""),
                ), writingConsecutiveSuccessCount = 2, speakingConsecutiveSuccessCount = 1),
            PatternRecord("p2", "used to + verb", "表示过去经常发生", KnowledgeStatus.TENTATIVE, KnowledgeStatus.TENTATIVE, 0, 3,
                examples = listOf(PatternExample("I used to read every day.", "我过去每天读书。", "I used to read every day."))),
        )
    }
}
