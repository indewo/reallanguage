package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.feature.formation.domain.ChapterDraft
import com.reallanguage.app.feature.formation.domain.ChapterFormationRepository
import com.reallanguage.app.feature.formation.domain.ChapterFormationResult
import com.reallanguage.app.feature.formation.domain.SemanticUnit
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterFormationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val units = (1..4).map { SemanticUnit("u$it", it - 1, "第${it}句。") }
    private val selected = listOf("u1", "u3")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun startsImmediatelyAndOpensEnglishConfirmationOnSuccess() = runTest(dispatcher) {
        val chapters = listOf(ChapterDraft("c1", listOf("u1", "u2", "u3")))
        val repository = RecordingRepository { ChapterFormationResult.Formed(chapters) }
        val viewModel = ChapterFormationViewModel(units, selected, repository)
        val effect = async { viewModel.effects.first() }

        assertTrue(viewModel.uiState.value.processing)
        advanceUntilIdle()

        assertEquals(listOf(Request(units, selected)), repository.requests)
        assertEquals(ChapterFormationEffect.OpenEnglishConfirmation(chapters), effect.await())
    }

    @Test
    fun failureKeepsSelectionForTheFlowToReturnToFocusSelection() = runTest(dispatcher) {
        val repository = RecordingRepository { ChapterFormationResult.Failed }
        val viewModel = ChapterFormationViewModel(units, selected, repository)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.processing)
        assertTrue(viewModel.uiState.value.failed)
        assertEquals(selected, viewModel.uiState.value.selectedUnitIds)
    }

    private data class Request(val units: List<SemanticUnit>, val selected: List<String>)

    private class RecordingRepository(
        private val result: suspend () -> ChapterFormationResult,
    ) : ChapterFormationRepository {
        val requests = mutableListOf<Request>()
        override suspend fun formChapters(
            units: List<SemanticUnit>,
            selectedUnitIds: List<String>,
        ): ChapterFormationResult {
            requests += Request(units, selectedUnitIds)
            return result()
        }
    }
}
