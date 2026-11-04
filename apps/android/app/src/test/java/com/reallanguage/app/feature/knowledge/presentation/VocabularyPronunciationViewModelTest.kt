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
class VocabularyPronunciationViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun defaultLeftCardPlaysItsActualWordWithoutRequiringAnInvisibleExpandedSense() = runTest {
        val vm = viewModel(); val effects = mutableListOf<VocabularyBookEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect(effects::add) }
        advanceUntilIdle()
        vm.onAction(VocabularyBookAction.PronunciationRequested); advanceUntilIdle()
        assertEquals(listOf(VocabularyBookEffect.PlayPronunciation("run", 0)), effects)
        assertTrue(vm.uiState.value.pronunciationBusy)
    }

    @Test fun currentExampleUsesStoredPronunciationAndDoubleClickDoesNotStartDuplicatePlayback() = runTest {
        val vm = viewModel(); val effects = mutableListOf<VocabularyBookEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect(effects::add) }; advanceUntilIdle()
        vm.onAction(VocabularyBookAction.SenseSelected("run-business"))
        repeat(2) { vm.onAction(VocabularyBookAction.ExamplePronunciationRequested("run-business", 0)) }; advanceUntilIdle()
        assertEquals(listOf(VocabularyBookEffect.PlayPronunciation("She runs a café.", 0)), effects)
        assertTrue(vm.uiState.value.pronunciationBusy)
        assertEquals(KnowledgeStatus.MASTERED, vm.uiState.value.expandedSense?.status)
        assertEquals(2, vm.uiState.value.expandedSense?.effectiveLearnedCount)
    }

    @Test fun hiddenSenseAndOutOfRangeOrBlankExampleCannotBePlayed() = runTest {
        val vm = viewModel(); val effects = mutableListOf<VocabularyBookEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect(effects::add) }; advanceUntilIdle()
        vm.onAction(VocabularyBookAction.SenseSelected("run-business"))
        vm.onAction(VocabularyBookAction.ExamplePronunciationRequested("calm-state", 0))
        vm.onAction(VocabularyBookAction.ExamplePronunciationRequested("run-business", 7))
        vm.onAction(VocabularyBookAction.ExamplePronunciationRequested("run-business", 1)); advanceUntilIdle()
        assertTrue(effects.isEmpty())
        assertFalse(vm.uiState.value.pronunciationBusy)
        vm.onAction(VocabularyBookAction.ExamplePronunciationRequested("run-business", 0)); advanceUntilIdle()
        assertEquals(1, effects.size)
    }

    @Test fun realPlaybackFailureKeepsTextAndCanRetryButStaleCallbacksCannotEndNewRequest() = runTest {
        val vm = viewModel(); val effects = mutableListOf<VocabularyBookEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect(effects::add) }; advanceUntilIdle()
        vm.onAction(VocabularyBookAction.SenseSelected("run-business"))
        vm.onAction(VocabularyBookAction.ExamplePronunciationRequested("run-business", 0)); advanceUntilIdle()
        vm.onAction(VocabularyBookAction.PronunciationFinished(0, false)); advanceUntilIdle()
        assertTrue(vm.uiState.value.pronunciationFailed)
        assertFalse(vm.uiState.value.pronunciationBusy)
        assertEquals("She runs a cafe.", vm.uiState.value.expandedSense?.examples?.first()?.textEn)
        vm.onAction(VocabularyBookAction.RetryPronunciationRequested); advanceUntilIdle()
        assertEquals(VocabularyBookEffect.PlayPronunciation("She runs a café.", 1), effects.last())
        vm.onAction(VocabularyBookAction.PronunciationFinished(0, true)); advanceUntilIdle()
        assertTrue(vm.uiState.value.pronunciationBusy)
        vm.onAction(VocabularyBookAction.PronunciationFinished(1, true)); advanceUntilIdle()
        assertFalse(vm.uiState.value.pronunciationBusy)
        assertFalse(vm.uiState.value.pronunciationFailed)
    }

    @Test fun switchingSenseStopsAudioAndMakesLateFailureOrRetryInapplicableToOtherWord() = runTest {
        val vm = viewModel(); val effects = mutableListOf<VocabularyBookEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect(effects::add) }; advanceUntilIdle()
        vm.onAction(VocabularyBookAction.SenseSelected("run-business"))
        vm.onAction(VocabularyBookAction.ExamplePronunciationRequested("run-business", 0)); advanceUntilIdle()
        vm.onAction(VocabularyBookAction.SenseSelected("calm-state")); advanceUntilIdle()
        assertEquals(VocabularyBookEffect.StopPronunciation, effects.last())
        vm.onAction(VocabularyBookAction.PronunciationFinished(0, false))
        vm.onAction(VocabularyBookAction.RetryPronunciationRequested); advanceUntilIdle()
        assertEquals(2, effects.size)
        assertFalse(vm.uiState.value.pronunciationBusy)
        assertFalse(vm.uiState.value.pronunciationFailed)
    }

    @Test fun collapseAndReloadStopPlaybackAndDoNotLeaveFailureAttachedToAnOldSense() = runTest {
        val vm = viewModel(); val effects = mutableListOf<VocabularyBookEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect(effects::add) }; advanceUntilIdle()
        vm.onAction(VocabularyBookAction.SenseSelected("run-business"))
        vm.onAction(VocabularyBookAction.ExamplePronunciationRequested("run-business", 0)); advanceUntilIdle()
        vm.onAction(VocabularyBookAction.CollapseRequested); advanceUntilIdle()
        assertEquals(VocabularyBookEffect.StopPronunciation, effects.last())
        assertFalse(vm.uiState.value.pronunciationBusy)
        vm.onAction(VocabularyBookAction.PronunciationRequested); advanceUntilIdle()
        vm.onAction(VocabularyBookAction.RetryRequested); advanceUntilIdle()
        assertEquals(VocabularyBookEffect.StopPronunciation, effects.last())
        vm.onAction(VocabularyBookAction.PronunciationFinished(1, false)); advanceUntilIdle()
        assertFalse(vm.uiState.value.pronunciationFailed)
    }

    @Test fun activityStopInterruptsPlaybackWithoutLosingSelectedMeaningOrTreatingItAsSuccess() = runTest {
        val vm = viewModel(); val effects = mutableListOf<VocabularyBookEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect(effects::add) }; advanceUntilIdle()
        vm.onAction(VocabularyBookAction.SenseSelected("run-business"))
        vm.onAction(VocabularyBookAction.ExamplePronunciationRequested("run-business", 0)); advanceUntilIdle()
        assertTrue(vm.uiState.value.pronunciationBusy)
        vm.onAction(VocabularyBookAction.PronunciationInterrupted); advanceUntilIdle()
        assertEquals(VocabularyBookEffect.StopPronunciation, effects.last())
        assertEquals("run-business", vm.uiState.value.expandedSenseId)
        assertFalse(vm.uiState.value.pronunciationBusy)
        assertFalse(vm.uiState.value.pronunciationFailed)
        vm.onAction(VocabularyBookAction.PronunciationFinished(0, true)); advanceUntilIdle()
        assertEquals(2, vm.uiState.value.expandedSense?.effectiveLearnedCount)
        vm.onAction(VocabularyBookAction.ExamplePronunciationRequested("run-business", 0)); advanceUntilIdle()
        assertEquals(VocabularyBookEffect.PlayPronunciation("She runs a café.", 1), effects.last())
        assertTrue(vm.uiState.value.pronunciationBusy)
    }

    private fun viewModel() = VocabularyBookViewModel(object : VocabularyBookRepository {
        override suspend fun loadAllRecords() = VocabularyLoadResult.Ready(setOf("CET4"), records)
    })
    companion object {
        private val records = listOf(
            VocabularySenseRecord("run-business", "run", "经营", KnowledgeStatus.MASTERED, 2, setOf("CET4"), 1,
                pronunciationText = "run", examples = listOf(
                    VocabularyExample("She runs a cafe.", "她经营一家咖啡店。", "She runs a café."),
                    VocabularyExample("A legacy example.", "旧例句。", ""),
                )),
            VocabularySenseRecord("calm-state", "calm", "平静", KnowledgeStatus.LEARNING, 0, setOf("CET4"), 2,
                pronunciationText = "calm", examples = listOf(VocabularyExample("I stayed calm.", "我保持平静。", "I stayed calm."))),
        )
    }
}
