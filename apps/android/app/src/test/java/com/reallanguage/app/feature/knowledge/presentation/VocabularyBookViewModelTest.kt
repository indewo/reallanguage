package com.reallanguage.app.feature.knowledge.presentation

import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyBookRepository
import com.reallanguage.app.feature.knowledge.domain.VocabularyLoadResult
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VocabularyBookViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun filtersCurrentScopeWithoutMutatingRepositoryRecords() = runTest(dispatcher) {
        val repository = FakeRepository(VocabularyLoadResult.Ready(setOf("CET4"), sampleRecords))
        val viewModel = VocabularyBookViewModel(repository)
        advanceUntilIdle()
        assertEquals(listOf("run-business", "run-move"), viewModel.uiState.value.entries.single().senses.map { it.senseId })
        assertEquals(3, repository.records.size)
        assertEquals("CET4", viewModel.uiState.value.currentScope)
    }

    @Test fun groupsSameEnglishFormAndKeepsEachMeaningOnItsOwnRow() = runTest(dispatcher) {
        val viewModel = VocabularyBookViewModel(FakeRepository(VocabularyLoadResult.Ready(setOf("CET4"), sampleRecords)))
        advanceUntilIdle()
        val entry = viewModel.uiState.value.entries.single()
        assertEquals("run", entry.englishForm)
        assertEquals(listOf("经营业务", "跑步"), entry.senses.map { it.meaningZh })
    }

    @Test fun keepsThePoolWideLongestHeadwordAsTheStableLayoutReference() = runTest(dispatcher) {
        val viewModel = VocabularyBookViewModel(
            FakeRepository(
                VocabularyLoadResult.Ready(
                    currentScopes = setOf("CET_6"),
                    records = sampleRecords,
                    longestHeadword = "notwithstanding",
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals("notwithstanding", viewModel.uiState.value.longestHeadword)
    }

    @Test fun learnedSenseRemainsVisibleWithIndependentEffectiveCount() = runTest(dispatcher) {
        val viewModel = VocabularyBookViewModel(FakeRepository(VocabularyLoadResult.Ready(setOf("CET4"), sampleRecords)))
        advanceUntilIdle()
        val learned = viewModel.uiState.value.entries.single().senses.first()
        assertEquals(KnowledgeStatus.MASTERED, learned.status)
        assertEquals(2, learned.effectiveLearnedCount)
    }

    @Test fun selectingAnotherMeaningReplacesInlineExpansionAndCollapseKeepsList() = runTest(dispatcher) {
        val viewModel = VocabularyBookViewModel(FakeRepository(VocabularyLoadResult.Ready(setOf("CET4"), sampleRecords)))
        advanceUntilIdle()
        viewModel.onAction(VocabularyBookAction.SenseSelected("run-business"))
        assertEquals("run-business", viewModel.uiState.value.expandedSenseId)
        viewModel.onAction(VocabularyBookAction.SenseSelected("run-move"))
        assertEquals("run-move", viewModel.uiState.value.expandedSenseId)
        viewModel.onAction(VocabularyBookAction.CollapseRequested)
        assertNull(viewModel.uiState.value.expandedSenseId)
        assertFalse(viewModel.uiState.value.entries.isEmpty())
    }

    @Test fun loadFailureRetriesAndReturnHomeIsExplicit() = runTest(dispatcher) {
        val repository = FakeRepository(VocabularyLoadResult.Failed)
        val viewModel = VocabularyBookViewModel(repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadFailed)
        repository.result = VocabularyLoadResult.Ready(setOf("CET4"), sampleRecords)
        viewModel.onAction(VocabularyBookAction.RetryRequested)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.loadFailed)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(VocabularyBookAction.ReturnHomeRequested)
        advanceUntilIdle()
        assertEquals(VocabularyBookEffect.ReturnHome, effect.await())
    }

    private class FakeRepository(var result: VocabularyLoadResult) : VocabularyBookRepository {
        val records = sampleRecords.toMutableList()
        var calls = 0
        override suspend fun loadAllRecords(): VocabularyLoadResult {
            calls++
            return result
        }
    }

    @Test fun repeatedRetryDoesNotStartConcurrentScopeAndRecordReads() = runTest(dispatcher) {
        val repository = FakeRepository(VocabularyLoadResult.Ready(setOf("CET4"), sampleRecords))
        val viewModel = VocabularyBookViewModel(repository)
        repeat(3) { viewModel.onAction(VocabularyBookAction.RetryRequested) }
        advanceUntilIdle()
        assertEquals(1, repository.calls)
    }

    @Test fun thrownReadBecomesVisibleFailureAndCanRetry() = runTest(dispatcher) {
        var calls = 0
        val viewModel = VocabularyBookViewModel(object : VocabularyBookRepository {
            override suspend fun loadAllRecords(): VocabularyLoadResult {
                if (++calls == 1) error("isolated read failure")
                return VocabularyLoadResult.Ready(setOf("CET4"), sampleRecords)
            }
        })
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.loading)
        assertTrue(viewModel.uiState.value.loadFailed)
        viewModel.onAction(VocabularyBookAction.RetryRequested)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.loadFailed)
        assertEquals(1, viewModel.uiState.value.entries.size)
    }

    @Test fun searchAndClearUseOfflinePagesAndLoadMoreAppendsTheNextWordBatch() = runTest(dispatcher) {
        val calls = mutableListOf<Triple<String, Int, Int>>()
        val first = VocabularySenseRecord("ability", "ability", "能力", KnowledgeStatus.NOT_STARTED, 0, setOf("CET_4"), 1, partOfSpeech = "n.")
        val second = VocabularySenseRecord("able", "able", "能够的", KnowledgeStatus.NOT_STARTED, 0, setOf("CET_4"), 2, partOfSpeech = "adj.")
        val repository = object : VocabularyBookRepository {
            override suspend fun loadAllRecords() = VocabularyLoadResult.Ready(setOf("CET_4"), listOf(first))
            override suspend fun loadPage(query: String, limit: Int, offset: Int): VocabularyLoadResult {
                calls += Triple(query, limit, offset)
                return when {
                    query == "ab" && offset == 0 -> VocabularyLoadResult.Ready(setOf("CET_4"), listOf(first), hasMore = true)
                    offset == 1 -> VocabularyLoadResult.Ready(setOf("CET_4"), listOf(second), hasMore = false)
                    else -> VocabularyLoadResult.Ready(setOf("CET_4"), listOf(first), hasMore = false)
                }
            }
        }
        val viewModel = VocabularyBookViewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(VocabularyBookAction.QueryChanged("ab"))
        viewModel.onAction(VocabularyBookAction.SearchRequested)
        advanceUntilIdle()
        assertEquals("ab", viewModel.uiState.value.appliedQuery)
        assertTrue(viewModel.uiState.value.hasMore)
        viewModel.onAction(VocabularyBookAction.LoadMoreRequested)
        advanceUntilIdle()
        assertEquals(listOf("ability", "able"), viewModel.uiState.value.entries.map { it.englishForm })
        assertEquals(Triple("ab", 200, 1), calls.last())
        viewModel.onAction(VocabularyBookAction.ClearSearchRequested)
        advanceUntilIdle()
        assertEquals("", viewModel.uiState.value.query)
        assertEquals("", viewModel.uiState.value.appliedQuery)
    }

    @Test fun selectingInitialUsesIndexedOfflinePageAndClearsSearch() = runTest(dispatcher) {
        var requestedInitial: Char? = null
        val record = VocabularySenseRecord(
            "research", "research", "研究", KnowledgeStatus.NOT_STARTED, 0,
            setOf("CET4"), 1, partOfSpeech = "n.",
        )
        val repository = object : VocabularyBookRepository {
            override suspend fun loadAllRecords() = VocabularyLoadResult.Ready(setOf("CET4"), sampleRecords)
            override suspend fun loadFromInitial(initial: Char, limit: Int, offset: Int): VocabularyLoadResult {
                requestedInitial = initial
                return VocabularyLoadResult.Ready(setOf("CET4"), listOf(record))
            }
        }
        val viewModel = VocabularyBookViewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(VocabularyBookAction.QueryChanged("old"))
        viewModel.onAction(VocabularyBookAction.InitialSelected('r'))
        advanceUntilIdle()

        assertEquals('R', requestedInitial)
        assertEquals('R', viewModel.uiState.value.selectedInitial)
        assertEquals("", viewModel.uiState.value.query)
        assertEquals(listOf("research"), viewModel.uiState.value.entries.map { it.englishForm })
    }

    companion object {
        private val sampleRecords = listOf(
            VocabularySenseRecord("run-business", "run", "经营业务", KnowledgeStatus.MASTERED, 2, setOf("CET4"), 1),
            VocabularySenseRecord("run-move", "run", "跑步", KnowledgeStatus.LEARNING, 0, setOf("CET4", "CET6"), 2),
            VocabularySenseRecord("allocate", "allocate", "分配", KnowledgeStatus.NOT_STARTED, 0, setOf("CET6"), 3),
        )
    }
}
