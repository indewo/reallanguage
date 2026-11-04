package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.formation.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LearningTargetLoadingLifecycleTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val ready = LearningTargetExtractionResult.Extracted(emptyList(), emptyList())

    @Test fun backCancelsTheRequestAndOnlyNavigatesOnce() = runTest {
        var calls = 0
        var cancelled = 0
        val model = model { calls++; try { awaitCancellation() } finally { cancelled++ } }
        val effects = mutableListOf<LearningTargetLoadingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.toList(effects) }
        runCurrent()
        repeat(2) { model.onAction(LearningTargetLoadingAction.BackRequested) }
        model.onAction(LearningTargetLoadingAction.RetryRequested)
        runCurrent()
        assertEquals(1, cancelled)
        assertEquals(1, calls)
        assertFalse(model.uiState.value.processing)
        assertEquals(listOf(LearningTargetLoadingEffect.ReturnToEnglishConfirmation), effects)
    }
    @Test fun aLateProviderResultCannotReverseAnAlreadyRequestedReturn() = runTest {
        val response = CompletableDeferred<LearningTargetExtractionResult>()
        val model = model { withContext(NonCancellable) { response.await() } }
        val effects = mutableListOf<LearningTargetLoadingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.toList(effects) }
        runCurrent()
        model.onAction(LearningTargetLoadingAction.BackRequested)
        runCurrent()
        val departed = model.uiState.value
        response.complete(ready)
        advanceUntilIdle()
        assertEquals(listOf(LearningTargetLoadingEffect.ReturnToEnglishConfirmation), effects)
        assertEquals(departed, model.uiState.value)
    }
    @Test fun unexpectedFailureReleasesTheSpinnerAndPermitsExactlyOneRetry() = runTest {
        var calls = 0
        val model = model { if (++calls == 1) error("isolated target loading failure") else ready }
        val effects = mutableListOf<LearningTargetLoadingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.toList(effects) }
        advanceUntilIdle()
        assertTrue(model.uiState.value.failed)
        assertFalse(model.uiState.value.processing)
        model.onAction(LearningTargetLoadingAction.RetryRequested)
        model.onAction(LearningTargetLoadingAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(listOf(LearningTargetLoadingEffect.OpenSelection(emptyList(), emptyList())), effects)
    }
    @Test fun deliveredSelectionCannotLaterAlsoReturnOrRegenerate() = runTest {
        var calls = 0
        val model = model { calls++; ready }
        val effects = mutableListOf<LearningTargetLoadingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.toList(effects) }
        advanceUntilIdle()
        model.onAction(LearningTargetLoadingAction.BackRequested)
        model.onAction(LearningTargetLoadingAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(listOf(LearningTargetLoadingEffect.OpenSelection(emptyList(), emptyList())), effects)
        assertFalse(model.uiState.value.processing)
    }
    private fun model(load: suspend () -> LearningTargetExtractionResult) = LearningTargetLoadingViewModel(object : LearningTargetExtractionRepository {
        override suspend fun extract() = load()
        override suspend fun generate(unitId: String, expressionId: String) = LearningTargetGenerationResult.Failed
    })
}
