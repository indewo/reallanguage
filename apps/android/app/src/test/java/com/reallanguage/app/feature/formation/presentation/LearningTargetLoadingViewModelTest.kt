package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetExtractionRepository
import com.reallanguage.app.feature.formation.domain.LearningTargetExtractionResult
import com.reallanguage.app.feature.formation.domain.LearningTargetGenerationResult
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LearningTargetLoadingViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    @Test fun `opens selection after extracting focus targets`() = runTest {
        val target = LearningTarget("t1", "u1", "happy", "高兴的", LearningTargetType.WORD)
        val repository = object : LearningTargetExtractionRepository {
            override suspend fun extract() = LearningTargetExtractionResult.Extracted(listOf(target), emptyList())
            override suspend fun generate(unitId: String, expressionId: String) = LearningTargetGenerationResult.Failed
        }
        val viewModel = LearningTargetLoadingViewModel(repository)
        val effect = async { viewModel.effects.first() }
        dispatcher.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(LearningTargetLoadingEffect.OpenSelection(listOf(target), emptyList()), effect.await())
    }
    @Test fun `keeps the confirmed content recoverable after extraction failure`() = runTest {
        val repository = object : LearningTargetExtractionRepository {
            override suspend fun extract() = LearningTargetExtractionResult.Failed
            override suspend fun generate(unitId: String, expressionId: String) = LearningTargetGenerationResult.Failed
        }
        val viewModel = LearningTargetLoadingViewModel(repository)
        dispatcher.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.failed)
    }
}
