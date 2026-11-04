package com.reallanguage.app.feature.knowledge.presentation

import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.PatternBookRepository
import com.reallanguage.app.feature.knowledge.domain.PatternLoadResult
import com.reallanguage.app.feature.knowledge.domain.PatternRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PatternBookViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun loadsAllPatternsWithoutVocabularyScopeFilter() = runTest(dispatcher) {
        val repository = Fake(PatternLoadResult.Ready(patterns))
        val viewModel = PatternBookViewModel(repository)
        advanceUntilIdle()
        assertEquals(patterns, viewModel.uiState.value.patterns)
        assertEquals(1, repository.calls)
    }

    @Test fun showsIndependentWritingAndSpeakingResults() = runTest(dispatcher) {
        val viewModel = PatternBookViewModel(Fake(PatternLoadResult.Ready(patterns)))
        advanceUntilIdle()
        assertEquals(KnowledgeStatus.MASTERED, viewModel.uiState.value.patterns.first().writingStatus)
        assertEquals(KnowledgeStatus.LEARNING, viewModel.uiState.value.patterns.first().speakingStatus)
    }

    @Test fun selectionExpandsOnePatternInlineAndSwitchesDirectly() = runTest(dispatcher) {
        val viewModel = PatternBookViewModel(Fake(PatternLoadResult.Ready(patterns)))
        advanceUntilIdle()
        viewModel.onAction(PatternBookAction.PatternSelected("p1"))
        assertEquals("p1", viewModel.uiState.value.expandedPatternId)
        viewModel.onAction(PatternBookAction.PatternSelected("p2"))
        assertEquals("p2", viewModel.uiState.value.expandedPatternId)
        viewModel.onAction(PatternBookAction.CollapseRequested)
        assertNull(viewModel.uiState.value.expandedPatternId)
    }

    @Test fun failureRetriesAndReturnHomeIsExplicit() = runTest(dispatcher) {
        val repository = Fake(PatternLoadResult.Failed)
        val viewModel = PatternBookViewModel(repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadFailed)
        repository.result = PatternLoadResult.Ready(patterns)
        viewModel.onAction(PatternBookAction.RetryRequested)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.loadFailed)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(PatternBookAction.ReturnHomeRequested)
        advanceUntilIdle()
        assertEquals(PatternBookEffect.ReturnHome, effect.await())
    }

    private class Fake(var result: PatternLoadResult) : PatternBookRepository {
        var calls = 0
        override suspend fun loadAllPatterns(): PatternLoadResult { calls++; return result }
    }

    @Test fun repeatedRetryCannotIssueOverlappingReads() = runTest(dispatcher) {
        val repository = Fake(PatternLoadResult.Ready(patterns))
        val viewModel = PatternBookViewModel(repository)
        repeat(3) { viewModel.onAction(PatternBookAction.RetryRequested) }
        advanceUntilIdle()
        assertEquals(1, repository.calls)
    }

    @Test fun thrownReadIsRecoverableAndDoesNotLeaveLoadingActive() = runTest(dispatcher) {
        var calls = 0
        val viewModel = PatternBookViewModel(object : PatternBookRepository {
            override suspend fun loadAllPatterns(): PatternLoadResult {
                if (++calls == 1) error("isolated read failure")
                return PatternLoadResult.Ready(patterns)
            }
        })
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.loading)
        assertTrue(viewModel.uiState.value.loadFailed)
        viewModel.onAction(PatternBookAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(patterns, viewModel.uiState.value.patterns)
    }
    companion object {
        private val patterns = listOf(
            PatternRecord("p1", "for the first time", "表示第一次经历", KnowledgeStatus.MASTERED, KnowledgeStatus.LEARNING, 2, 5),
            PatternRecord("p2", "used to + verb", "表示过去经常发生", KnowledgeStatus.TENTATIVE, KnowledgeStatus.TENTATIVE, 0, 3),
        )
    }
}
