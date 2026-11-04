package com.reallanguage.app.feature.knowledge.presentation

import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.PatternBookRepository
import com.reallanguage.app.feature.knowledge.domain.PatternExample
import com.reallanguage.app.feature.knowledge.domain.PatternLoadResult
import com.reallanguage.app.feature.knowledge.domain.PatternRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PatternBookSearchTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun searchMatchesOnlyRealStructureOrFunctionWithoutReloadingOrMutatingKnowledge() = runTest(dispatcher) {
        val repository = Repository()
        val viewModel = PatternBookViewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(PatternBookAction.QueryChanged("  COURAGE  "))
        assertEquals(listOf("p1"), viewModel.uiState.value.visiblePatterns.map { it.patternId })
        viewModel.onAction(PatternBookAction.QueryChanged("礼貌"))
        assertEquals(listOf("p2"), viewModel.uiState.value.visiblePatterns.map { it.patternId })
        viewModel.onAction(PatternBookAction.QueryChanged("cooked"))
        assertTrue("Examples are not an invented expansion of the search field", viewModel.uiState.value.visiblePatterns.isEmpty())
        assertEquals(records, viewModel.uiState.value.patterns)
        assertEquals(1, repository.calls)
    }

    @Test fun clearingTheQueryRestoresEverySavedPatternIncludingMasteredItemsInOriginalOrder() = runTest(dispatcher) {
        val viewModel = PatternBookViewModel(Repository())
        advanceUntilIdle()
        viewModel.onAction(PatternBookAction.QueryChanged("no matching phrase"))
        assertTrue(viewModel.uiState.value.visiblePatterns.isEmpty())
        viewModel.onAction(PatternBookAction.QueryChanged(" \n "))
        assertEquals(records, viewModel.uiState.value.visiblePatterns)
        assertEquals(KnowledgeStatus.MASTERED, viewModel.uiState.value.visiblePatterns.first().writingStatus)
    }

    @Test fun hidingAnExpandedPlayingItemStopsItAndCannotLeaveAHiddenRemoveOrPlaybackTarget() = runTest(dispatcher) {
        val viewModel = PatternBookViewModel(Repository())
        val effects = mutableListOf<PatternBookEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
        advanceUntilIdle()
        viewModel.onAction(PatternBookAction.PatternSelected("p1"))
        viewModel.onAction(PatternBookAction.ExamplePronunciationRequested("p1", 0))
        advanceUntilIdle()
        assertEquals(1, effects.filterIsInstance<PatternBookEffect.PlayExample>().size)
        viewModel.onAction(PatternBookAction.QueryChanged("礼貌"))
        viewModel.onAction(PatternBookAction.RemoveRequested)
        viewModel.onAction(PatternBookAction.ExamplePronunciationRequested("p1", 0))
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.expandedPatternId)
        assertNull(viewModel.uiState.value.expandedPattern)
        assertFalse(viewModel.uiState.value.pronunciationBusy)
        assertEquals(1, effects.count { it == PatternBookEffect.StopExample })
        assertTrue(effects.none { it is PatternBookEffect.OpenRemovalConfirmation })
        assertEquals(1, effects.filterIsInstance<PatternBookEffect.PlayExample>().size)
    }

    @Test fun aStaleTapCannotExpandAnItemExcludedByTheCurrentQuery() = runTest(dispatcher) {
        val viewModel = PatternBookViewModel(Repository())
        advanceUntilIdle()
        viewModel.onAction(PatternBookAction.QueryChanged("礼貌"))
        viewModel.onAction(PatternBookAction.PatternSelected("p1"))
        assertNull(viewModel.uiState.value.expandedPattern)
        viewModel.onAction(PatternBookAction.PatternSelected("p2"))
        assertEquals("p2", viewModel.uiState.value.expandedPatternId)
    }

    @Test fun queryEnteredWhileLoadingIsAppliedToTheRealReadWithoutASecondRequest() = runTest(dispatcher) {
        val repository = Repository()
        val viewModel = PatternBookViewModel(repository)
        viewModel.onAction(PatternBookAction.QueryChanged("礼貌"))
        advanceUntilIdle()
        assertEquals("礼貌", viewModel.uiState.value.query)
        assertEquals(listOf("p2"), viewModel.uiState.value.visiblePatterns.map { it.patternId })
        assertEquals(1, repository.calls)
    }

    private class Repository : PatternBookRepository {
        var calls = 0
        override suspend fun loadAllPatterns(): PatternLoadResult {
            calls++
            return PatternLoadResult.Ready(records)
        }
    }

    companion object {
        private val records = listOf(
            PatternRecord("p1", "work up the courage to + 动词", "鼓起勇气做某事", KnowledgeStatus.MASTERED,
                KnowledgeStatus.LEARNING, 2, 4, examples = listOf(PatternExample("I cooked alone.", "我独自做饭。", "I cooked alone."))),
            PatternRecord("p2", "I'd like to...", "礼貌表达意愿", KnowledgeStatus.TENTATIVE,
                KnowledgeStatus.MASTERED, 3, 5),
        )
    }
}
