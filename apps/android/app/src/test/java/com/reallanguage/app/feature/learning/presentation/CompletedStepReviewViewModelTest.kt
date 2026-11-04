package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.CompletedStepSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompletedStepReviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val snapshot = CompletedStepSnapshot(
        "LR-02",
        "基础讲解与朗读",
        "I wrote a diary entry.",
        "我写了一篇日记。",
        "这句使用一般过去时。",
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun completedSnapshotRemainsImmutableWhileAudioToggles() {
        val viewModel = CompletedStepReviewViewModel(snapshot) { CompletableDeferred<Boolean>().await() }
        viewModel.onAction(CompletedStepReviewAction.AudioToggled)
        assertTrue(viewModel.uiState.value.audioPlaying)
        assertEquals(snapshot, viewModel.uiState.value.snapshot)
        viewModel.onAction(CompletedStepReviewAction.AudioToggled)
        assertFalse(viewModel.uiState.value.audioPlaying)
    }

    @Test
    fun explanationCanBeViewedWithoutChangingCompletedContent() {
        val viewModel = CompletedStepReviewViewModel(snapshot) { CompletableDeferred<Boolean>().await() }
        viewModel.onAction(CompletedStepReviewAction.ExplanationToggled)
        assertTrue(viewModel.uiState.value.explanationVisible)
        assertEquals(snapshot.contentEn, viewModel.uiState.value.snapshot.contentEn)
    }

    @Test
    fun returnRestoresCurrentTaskRoute() = runTest(dispatcher) {
        val viewModel = CompletedStepReviewViewModel(snapshot) { CompletableDeferred<Boolean>().await() }
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(CompletedStepReviewAction.ReturnToCurrentTaskRequested)
        advanceUntilIdle()
        assertEquals(CompletedStepReviewEffect.ReturnToCurrentTask, effect.await())
    }

    @Test fun audioStopsWhenTheRealCompletionSignalArrives() = runTest(dispatcher) {
        val finished = CompletableDeferred<Boolean>()
        val spoken = mutableListOf<String>()
        val viewModel = CompletedStepReviewViewModel(snapshot) { text -> spoken += text; finished.await() }
        viewModel.onAction(CompletedStepReviewAction.AudioToggled)
        advanceUntilIdle()
        assertEquals(listOf(snapshot.contentEn), spoken)
        assertTrue(viewModel.uiState.value.audioPlaying)
        finished.complete(true)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.audioPlaying)
        assertFalse(viewModel.uiState.value.audioFailed)
        assertEquals(snapshot, viewModel.uiState.value.snapshot)
    }

    @Test fun rejectedPlaybackCanBeRetriedInsteadOfStayingMutedForever() = runTest(dispatcher) {
        var calls = 0
        val viewModel = CompletedStepReviewViewModel(snapshot) { calls++; false }
        viewModel.onAction(CompletedStepReviewAction.AudioToggled)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.audioPlaying)
        assertTrue(viewModel.uiState.value.audioFailed)
        viewModel.onAction(CompletedStepReviewAction.AudioToggled)
        advanceUntilIdle()
        assertEquals(2, calls)
    }

    @Test fun expressionPronunciationUsesTheSavedExpressionNotTheWholeSentence() = runTest(dispatcher) {
        val reviewed = snapshot.copy(reviewedExpression = com.reallanguage.app.feature.learning.domain.CompletedStepExpression(
            com.reallanguage.app.feature.learning.domain.SelectableExpression("word-1", "wrote", 2, 7),
            com.reallanguage.app.feature.learning.domain.ExpressionExplanation("word-1", "写了", null, null)))
        val spoken = mutableListOf<String>()
        val viewModel = CompletedStepReviewViewModel(reviewed) { spoken += it; true }
        viewModel.onAction(CompletedStepReviewAction.ExpressionAudioToggled)
        advanceUntilIdle()
        assertEquals(listOf("wrote"), spoken)
    }

    @Test fun returningStopsAudioAndOnlyRestoresCurrentTaskOnce() = runTest(dispatcher) {
        val viewModel = CompletedStepReviewViewModel(snapshot) { CompletableDeferred<Boolean>().await() }
        val effects = mutableListOf<CompletedStepReviewEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect { effects += it } }
        viewModel.onAction(CompletedStepReviewAction.AudioToggled)
        advanceUntilIdle()
        repeat(2) { viewModel.onAction(CompletedStepReviewAction.ReturnToCurrentTaskRequested) }
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.audioPlaying)
        assertEquals(listOf(CompletedStepReviewEffect.ReturnToCurrentTask), effects)
        viewModel.onAction(CompletedStepReviewAction.AudioToggled)
        assertFalse(viewModel.uiState.value.audioPlaying)
    }

    @Test fun savedContentLoadsOnceAndReturningCancelsLateDelivery() = runTest(dispatcher) {
        val delivery = CompletableDeferred<CompletedStepSnapshot>()
        var calls = 0
        val viewModel = CompletedStepReviewViewModel(snapshot, loadSnapshot = { calls++; delivery.await() }) { false }
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loading)
        viewModel.onAction(CompletedStepReviewAction.RetryLoadRequested)
        viewModel.onAction(CompletedStepReviewAction.ReturnToCurrentTaskRequested)
        delivery.complete(snapshot.copy(explanationZh = "迟到内容"))
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(snapshot, viewModel.uiState.value.snapshot)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test fun failedReadIsExplicitAndRetryOnlyReadsSavedContent() = runTest(dispatcher) {
        var reads = 0
        val loaded = snapshot.copy(explanationZh = "已保存内容")
        val viewModel = CompletedStepReviewViewModel(snapshot, loadSnapshot = {
            reads++; if (reads == 1) error("local read failure") else loaded
        }) { false }
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadingFailed)
        viewModel.onAction(CompletedStepReviewAction.RetryLoadRequested)
        advanceUntilIdle()
        assertEquals(2, reads)
        assertEquals(loaded, viewModel.uiState.value.snapshot)
        assertFalse(viewModel.uiState.value.loadingFailed)
    }

    @Test fun aSceneWithoutVerifiedSavedTasksCannotSpeakTheRouteAnswer() = runTest(dispatcher) {
        val spoken = mutableListOf<String>()
        val viewModel = CompletedStepReviewViewModel(snapshot.copy(stepId = "LR-09")) { spoken += it; true }
        viewModel.onAction(CompletedStepReviewAction.AudioToggled)
        advanceUntilIdle()
        viewModel.onAction(CompletedStepReviewAction.ExpressionAudioToggled)
        advanceUntilIdle()
        assertTrue(spoken.isEmpty())
        assertFalse(viewModel.uiState.value.audioPlaying)
    }
}
