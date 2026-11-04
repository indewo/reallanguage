package com.reallanguage.app.feature.home.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.home.domain.IncompleteLearningEntry
import com.reallanguage.app.feature.home.domain.IncompleteLearningLoadResult
import com.reallanguage.app.feature.home.domain.IncompleteLearningRepository
import com.reallanguage.app.feature.home.domain.OrdinarySourceType
import com.reallanguage.app.feature.home.domain.ResumeLearningKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncompleteLearningViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun loadsOnlyOrdinaryLearningEntriesWithSourceChapterAndCheckpoint() = runTest {
        val review = entry.copy(learningId = "review-1", kind = ResumeLearningKind.UNIFIED_REVIEW)
        val viewModel = viewModel(FakeRepository(IncompleteLearningLoadResult.Ready(listOf(entry, review))))
        advanceUntilIdle()
        assertEquals(listOf(entry), viewModel.uiState.value.entries)
        assertTrue(viewModel.uiState.value.entries.none { it.kind == ResumeLearningKind.UNIFIED_REVIEW })
    }

    @Test fun selectingKnownEntryResumesItsSavedStep() = runTest {
        val viewModel = viewModel(FakeRepository(IncompleteLearningLoadResult.Ready(listOf(entry))))
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(IncompleteLearningAction.ResumeRequested("learning-1"))
        assertEquals(IncompleteLearningEffect.Resume("learning-1", "LR-06"), effect.await())
    }

    @Test fun acceptedSourceBeforeFixRemainsVisibleAndResumesItsExactSavedFormationStage() = runTest {
        val forming = entry.copy(learningId = "source-before-fix", chapterNumber = null,
            savedStepId = "FORMATION:LEARNING_TARGET_SELECTION", savedStepLabelZh = "选择学习目标",
            kind = ResumeLearningKind.FORMATION)
        val review = entry.copy(learningId = "review", kind = ResumeLearningKind.UNIFIED_REVIEW)
        val viewModel = viewModel(FakeRepository(IncompleteLearningLoadResult.Ready(listOf(forming, entry, review))))
        advanceUntilIdle()
        assertEquals(listOf(forming, entry), viewModel.uiState.value.entries)
        viewModel.onAction(IncompleteLearningAction.ResumeRequested(forming.learningId))
        assertEquals(IncompleteLearningEffect.Resume(forming.learningId, forming.savedStepId), viewModel.effects.first())
    }

    @Test fun unknownEntryCannotInventResumeTargetAndReturnOpensHome() = runTest {
        val viewModel = viewModel(FakeRepository(IncompleteLearningLoadResult.Ready(listOf(entry))))
        advanceUntilIdle()
        viewModel.onAction(IncompleteLearningAction.ResumeRequested("missing"))
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(IncompleteLearningAction.ReturnHomeRequested)
        assertEquals(IncompleteLearningEffect.OpenHome, effect.await())
    }

    @Test fun emptyAndFailedStatesAreExplicitAndFailureCanRetry() = runTest {
        val repository = FakeRepository(IncompleteLearningLoadResult.Failed)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadFailed)
        repository.result = IncompleteLearningLoadResult.Ready(emptyList())
        viewModel.onAction(IncompleteLearningAction.RetryRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.entries.isEmpty())
        assertTrue(!viewModel.uiState.value.loadFailed)
    }

    private fun viewModel(repository: FakeRepository) = IncompleteLearningViewModel(repository)

    private class FakeRepository(var result: IncompleteLearningLoadResult) : IncompleteLearningRepository {
        override suspend fun loadOrdinaryLearning(): IncompleteLearningLoadResult = result
    }

    companion object {
        private val entry = IncompleteLearningEntry(
            learningId = "learning-1",
            sourceType = OrdinarySourceType.DIARY,
            sourceLabelZh = "昨晚的日记",
            chapterNumber = 2,
            savedStepId = "LR-06",
            savedStepLabelZh = "整句会说",
            kind = ResumeLearningKind.ORDINARY,
        )
    }
}
