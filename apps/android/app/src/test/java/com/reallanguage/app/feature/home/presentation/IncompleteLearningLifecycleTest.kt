package com.reallanguage.app.feature.home.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.home.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncompleteLearningLifecycleTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun repeatedResumeEmitsOneNavigationAndNeverASecondHomeNavigation() = runTest {
        val model = IncompleteLearningViewModel { IncompleteLearningLoadResult.Ready(listOf(entry)) }
        advanceUntilIdle()
        val effects = mutableListOf<IncompleteLearningEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.collect { effects += it } }
        repeat(2) { model.onAction(IncompleteLearningAction.ResumeRequested(entry.learningId)) }
        model.onAction(IncompleteLearningAction.ReturnHomeRequested)
        assertEquals(listOf(IncompleteLearningEffect.Resume(entry.learningId, "LR-03")), effects)
    }

    @Test fun leavingCancelsPendingReadAndClearsSensitiveEntries() = runTest {
        val pending = CompletableDeferred<IncompleteLearningLoadResult>()
        var cancelled = false
        val model = IncompleteLearningViewModel {
            try { pending.await() } finally { cancelled = true }
        }
        runCurrent()
        val effects = mutableListOf<IncompleteLearningEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.collect { effects += it } }
        model.onAction(IncompleteLearningAction.ReturnHomeRequested)
        runCurrent()
        assertTrue("Leaving must cancel the pending account data read", cancelled)
        pending.complete(IncompleteLearningLoadResult.Ready(listOf(entry)))
        advanceUntilIdle()
        assertTrue(model.uiState.value.entries.isEmpty())
        assertEquals(listOf(IncompleteLearningEffect.OpenHome), effects)
    }

    @Test fun retryDuringAnActiveReadDoesNotStartAnotherRead() = runTest {
        val pending = CompletableDeferred<IncompleteLearningLoadResult>()
        var reads = 0
        val model = IncompleteLearningViewModel { reads++; pending.await() }
        runCurrent()
        repeat(3) { model.onAction(IncompleteLearningAction.RetryRequested) }
        runCurrent()
        assertEquals(1, reads)
        pending.complete(IncompleteLearningLoadResult.Ready(emptyList()))
        advanceUntilIdle()
    }

    @Test fun retryAndUnknownResumeAfterReturningCannotReadOrNavigateAgain() = runTest {
        var reads = 0
        val model = IncompleteLearningViewModel { reads++; IncompleteLearningLoadResult.Ready(listOf(entry)) }
        advanceUntilIdle()
        val effects = mutableListOf<IncompleteLearningEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.collect { effects += it } }
        model.onAction(IncompleteLearningAction.ReturnHomeRequested)
        model.onAction(IncompleteLearningAction.RetryRequested)
        model.onAction(IncompleteLearningAction.ResumeRequested(entry.learningId))
        model.onAction(IncompleteLearningAction.ReturnHomeRequested)
        advanceUntilIdle()
        assertEquals(1, reads)
        assertEquals(listOf(IncompleteLearningEffect.OpenHome), effects)
        assertTrue(model.uiState.value.entries.isEmpty())
    }

    companion object {
        private val entry = IncompleteLearningEntry("chapter-current", OrdinarySourceType.DIARY,
            "已确认日记", 1, "LR-03", "读＋说", ResumeLearningKind.ORDINARY)
    }
}
