package com.reallanguage.app.feature.knowledge.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyBookRepository
import com.reallanguage.app.feature.knowledge.domain.VocabularyLoadResult
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Isolated dispatch/state tests; no real player, audio, HTTP or pronunciation assessment. */
@OptIn(ExperimentalCoroutinesApi::class)
class VocabularyGroupPronunciationViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test fun nonFirstGroupPlaysItsExactStoredWordWithoutExpandingOrChangingLearningRecords() = runTest {
        val (vm, effects) = observedViewModel()
        val before = vm.uiState.value.entries
        assertEquals(listOf("run", "CALM", "steady", "silent-null", "silent-empty", "silent-blank"), before.map { it.englishForm })

        vm.onAction(VocabularyBookAction.SensePronunciationRequested("calm-state"))
        advanceUntilIdle()

        assertEquals(listOf(VocabularyBookEffect.PlayPronunciation("calm", 0)), effects)
        assertTrue(vm.uiState.value.pronunciationBusy)
        assertFalse(vm.uiState.value.pronunciationFailed)
        assertNull(vm.uiState.value.expandedSenseId)
        assertEquals(before, vm.uiState.value.entries)
    }

    @Test fun unknownAndScopeHiddenSenseIdsDoNotProducePlaybackOrFailure() = runTest {
        val (vm, effects) = observedViewModel()
        val before = vm.uiState.value
        assertFalse(before.entries.any { entry -> entry.senses.any { it.senseId == "hidden-sense" } })

        listOf("missing-sense", "hidden-sense", "").forEach { id ->
            vm.onAction(VocabularyBookAction.SensePronunciationRequested(id))
        }
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
        assertEquals(before, vm.uiState.value)
    }

    @Test fun nullEmptyAndWhitespaceStoredPronunciationDoNotFallBackToDisplayOrFirstWord() = runTest {
        val (vm, effects) = observedViewModel()
        val before = vm.uiState.value

        listOf("silent-null", "silent-empty", "silent-blank").forEach { id ->
            vm.onAction(VocabularyBookAction.SensePronunciationRequested(id))
        }
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
        assertEquals(before, vm.uiState.value)
    }

    @Test fun busyRepeatedGroupRequestIsSingleAndFailedPlaybackRetriesTheSameStoredWord() = runTest {
        val (vm, effects) = observedViewModel()
        repeat(2) { vm.onAction(VocabularyBookAction.SensePronunciationRequested("calm-state")) }
        advanceUntilIdle()
        assertEquals(listOf(VocabularyBookEffect.PlayPronunciation("calm", 0)), effects)

        vm.onAction(VocabularyBookAction.PronunciationFinished(0, false))
        assertFalse(vm.uiState.value.pronunciationBusy)
        assertTrue(vm.uiState.value.pronunciationFailed)
        repeat(2) { vm.onAction(VocabularyBookAction.RetryPronunciationRequested) }
        advanceUntilIdle()
        assertEquals(listOf(
            VocabularyBookEffect.PlayPronunciation("calm", 0),
            VocabularyBookEffect.PlayPronunciation("calm", 1),
        ), effects)
        assertTrue(vm.uiState.value.pronunciationBusy)
        assertFalse(vm.uiState.value.pronunciationFailed)

        vm.onAction(VocabularyBookAction.PronunciationFinished(0, true))
        assertTrue(vm.uiState.value.pronunciationBusy)
        vm.onAction(VocabularyBookAction.PronunciationFinished(1, true))
        assertFalse(vm.uiState.value.pronunciationBusy)
        assertFalse(vm.uiState.value.pronunciationFailed)
    }

    @Test fun newGroupRequestOwnsCompletionAndLatePriorFailureCannotMarkItFailedOrEnableRetry() = runTest {
        val (vm, effects) = observedViewModel()
        vm.onAction(VocabularyBookAction.SensePronunciationRequested("calm-state"))
        vm.onAction(VocabularyBookAction.SensePronunciationRequested("steady-state"))
        advanceUntilIdle()
        assertEquals(listOf(
            VocabularyBookEffect.PlayPronunciation("calm", 0),
            VocabularyBookEffect.PlayPronunciation("steady", 1),
        ), effects)

        vm.onAction(VocabularyBookAction.PronunciationFinished(0, false))
        vm.onAction(VocabularyBookAction.RetryPronunciationRequested)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pronunciationBusy)
        assertFalse(vm.uiState.value.pronunciationFailed)
        assertEquals(2, effects.size)

        vm.onAction(VocabularyBookAction.PronunciationFinished(1, true))
        assertFalse(vm.uiState.value.pronunciationBusy)
        assertFalse(vm.uiState.value.pronunciationFailed)
        assertNull(vm.uiState.value.expandedSenseId)
    }

    @Test fun rejectedGroupRequestCannotReplaceAnActiveValidRequestOrItsRetryIdentity() = runTest {
        val (vm, effects) = observedViewModel()
        vm.onAction(VocabularyBookAction.SensePronunciationRequested("calm-state"))
        advanceUntilIdle()
        assertEquals(listOf(VocabularyBookEffect.PlayPronunciation("calm", 0)), effects)
        listOf("missing-sense", "hidden-sense", "silent-blank").forEach { id ->
            vm.onAction(VocabularyBookAction.SensePronunciationRequested(id))
        }
        advanceUntilIdle()
        assertEquals(1, effects.size)
        assertTrue(vm.uiState.value.pronunciationBusy)

        vm.onAction(VocabularyBookAction.PronunciationFinished(0, false))
        vm.onAction(VocabularyBookAction.RetryPronunciationRequested)
        advanceUntilIdle()
        assertEquals(VocabularyBookEffect.PlayPronunciation("calm", 1), effects.last())
        assertEquals(2, effects.size)
    }

    private suspend fun TestScope.observedViewModel(): Pair<VocabularyBookViewModel, MutableList<VocabularyBookEffect>> {
        val vm = VocabularyBookViewModel(object : VocabularyBookRepository {
            override suspend fun loadAllRecords() = VocabularyLoadResult.Ready(setOf("CET4"), records)
        })
        val effects = mutableListOf<VocabularyBookEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect(effects::add) }
        advanceUntilIdle()
        return vm to effects
    }

    companion object {
        private val records = listOf(
            record("run-business", "run", "经营", "run", 1),
            record("calm-state", "CALM", "平静", "calm", 2),
            record("steady-state", "steady", "稳定", "steady", 3),
            record("silent-null", "silent-null", "隔离空值", null, 4),
            record("silent-empty", "silent-empty", "隔离空文", "", 5),
            record("silent-blank", "silent-blank", "隔离空白", " \t ", 6),
            record("hidden-sense", "hidden", "隔离不可见", "hidden", 7).copy(learningScopes = setOf("CET6")),
        )

        private fun record(id: String, english: String, meaning: String, pronunciation: String?, order: Long) =
            VocabularySenseRecord(
                senseId = id,
                englishForm = english,
                meaningZh = meaning,
                status = KnowledgeStatus.MASTERED,
                effectiveLearnedCount = 2,
                learningScopes = setOf("CET4"),
                enteredOrder = order,
                pronunciationText = pronunciation,
                focusOccurrenceCount = 3,
                contextOccurrenceCount = 4,
            )
    }
}
