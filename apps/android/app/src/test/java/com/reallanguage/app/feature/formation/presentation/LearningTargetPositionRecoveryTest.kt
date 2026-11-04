package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.feature.formation.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LearningTargetPositionRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    private val sentences = listOf(
        EnglishSentenceDraft("focus-one", "第一句。", true, listOf(EnglishCandidate("e1", "I drink tea."))),
        EnglishSentenceDraft("context", "上下文。", false, listOf(EnglishCandidate("ec", "It is warm."))),
        EnglishSentenceDraft("focus-two", "第二句。", true, listOf(EnglishCandidate("e2", "I read books."))),
        EnglishSentenceDraft("focus-three", "第三句。", true, listOf(EnglishCandidate("e3", "I work today."))),
    )
    private val chapters = listOf(EnglishChapterDraft("chapter-one", sentences))
    private val selections = sentences.associate { it.unitId to it.candidates.single().candidateId }
    private val targets = listOf(
        LearningTarget("tea", "focus-one", "tea", "茶", LearningTargetType.WORD),
        LearningTarget("books", "focus-two", "books", "书", LearningTargetType.WORD),
        LearningTarget("work", "focus-three", "work", "工作", LearningTargetType.WORD),
    )
    private var generationCalls = 0

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `restores the second focus sentence rather than the second source unit without regenerating`() = runTest(dispatcher) {
        val viewModel = viewModel(1, setOf("tea", "work"))
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(1, state.focusSentenceIndex)
        assertEquals("focus-two", state.currentSentence?.unitId)
        assertEquals(listOf("books"), state.currentTargets.map { it.targetId })
        assertEquals(setOf("tea", "work"), state.selectedTargetIds)
        assertFalse(state.recoveryUnavailable)
        assertEquals(0, generationCalls)
        viewModel.onAction(LearningTargetAction.TargetToggled("books"))
        assertEquals(setOf("tea", "books", "work"), viewModel.uiState.value.selectedTargetIds)
    }

    @Test fun `continuing the restored last sentence fixes the exact saved selection only once`() = runTest(dispatcher) {
        val viewModel = viewModel(2, setOf("books"))
        val effects = mutableListOf<LearningTargetEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect { effects += it } }
        repeat(2) { viewModel.onAction(LearningTargetAction.ContinueRequested) }
        advanceUntilIdle()
        assertEquals(listOf(LearningTargetEffect.OpenFinalConfirmation(setOf("books"), targets)), effects)
        assertTrue(viewModel.uiState.value.submitting)
        assertEquals(0, generationCalls)
    }

    @Test fun `returning from restored sentence preserves selections and uses existing previous sentence behavior`() = runTest(dispatcher) {
        val viewModel = viewModel(1, emptySet())
        val effects = mutableListOf<LearningTargetEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect { effects += it } }
        viewModel.onAction(LearningTargetAction.BackRequested)
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.focusSentenceIndex)
        assertEquals("focus-one", viewModel.uiState.value.currentSentence?.unitId)
        assertTrue(viewModel.uiState.value.selectedTargetIds.isEmpty())
        assertTrue(effects.isEmpty())
        viewModel.onAction(LearningTargetAction.BackRequested)
        advanceUntilIdle()
        assertEquals(listOf(LearningTargetEffect.ReturnToEnglishConfirmation), effects)
    }

    @Test fun `invalid focus positions remain unavailable with no edits generation or accidental fixed submission`() = runTest(dispatcher) {
        // There are four source units, but only three focus sentences. Index 3 is invalid.
        for (index in listOf(-1, 3)) {
            val viewModel = viewModel(index, setOf("books"))
            val effects = mutableListOf<LearningTargetEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect { effects += it } }
            assertTrue(viewModel.uiState.value.recoveryUnavailable)
            assertEquals(index, viewModel.uiState.value.focusSentenceIndex)
            assertNull(viewModel.uiState.value.currentSentence)
            assertFalse(viewModel.uiState.value.continueEnabled)
            viewModel.onAction(LearningTargetAction.ClearAllRequested)
            viewModel.onAction(LearningTargetAction.TargetToggled("tea"))
            viewModel.onAction(LearningTargetAction.ExpressionRequested("focus-one", "word:8"))
            viewModel.onAction(LearningTargetAction.ContinueRequested)
            advanceUntilIdle()
            assertEquals(setOf("books"), viewModel.uiState.value.selectedTargetIds)
            assertTrue(effects.isEmpty())
            repeat(2) { viewModel.onAction(LearningTargetAction.BackRequested) }
            advanceUntilIdle()
            assertEquals(listOf(LearningTargetEffect.ReturnToEnglishConfirmation), effects)
            assertEquals(index, viewModel.uiState.value.focusSentenceIndex)
        }
        assertEquals(0, generationCalls)
    }

    private fun viewModel(index: Int, selected: Set<String>) = LearningTargetViewModel(
        chapters, selections, targets, emptyList(),
        LearningTargetGenerator { _, _ -> generationCalls++; LearningTargetGenerationResult.Failed },
        initialSelectedTargetIds = selected, initialSentenceIndex = index,
    )
}
