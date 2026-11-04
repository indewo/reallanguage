package com.reallanguage.app.feature.formation.presentation

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
class FocusSelectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val units = listOf(
        SemanticUnit("u1", 0, "第一句。", "适合练习时间表达"),
        SemanticUnit("u2", 1, "第二句。"),
        SemanticUnit("u3", 2, "第三句。"),
        SemanticUnit("u4", 3, "第四句。"),
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun recommendationIsOnlyAMarkerAndDoesNotPreselectOrHideUnits() {
        val state = FocusSelectionViewModel(units).uiState.value
        assertEquals(units, state.units)
        assertTrue(state.selectedUnitIds.isEmpty())
        assertFalse(state.confirmEnabled)
    }

    @Test
    fun userCanSelectAnyOneToThreeUnitsButCannotSelectAFourth() {
        val viewModel = FocusSelectionViewModel(units)
        listOf("u2", "u3", "u4", "u1").forEach {
            viewModel.onAction(FocusSelectionAction.UnitToggled(it))
        }

        assertEquals(setOf("u2", "u3", "u4"), viewModel.uiState.value.selectedUnitIds)
        assertTrue(viewModel.uiState.value.selectionLimitReached)
        assertTrue(viewModel.uiState.value.confirmEnabled)
    }

    @Test
    fun deselectingReopensCapacity() {
        val viewModel = FocusSelectionViewModel(units)
        listOf("u1", "u2", "u3").forEach {
            viewModel.onAction(FocusSelectionAction.UnitToggled(it))
        }
        viewModel.onAction(FocusSelectionAction.UnitToggled("u2"))

        assertEquals(setOf("u1", "u3"), viewModel.uiState.value.selectedUnitIds)
        assertFalse(viewModel.uiState.value.selectionLimitReached)
    }

    @Test
    fun confirmEmitsOriginalOrderOnceAndLocksDuplicateSubmission() = runTest(dispatcher) {
        val viewModel = FocusSelectionViewModel(units)
        viewModel.onAction(FocusSelectionAction.UnitToggled("u3"))
        viewModel.onAction(FocusSelectionAction.UnitToggled("u1"))
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(FocusSelectionAction.ConfirmRequested)
        viewModel.onAction(FocusSelectionAction.ConfirmRequested)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.submitting)
        assertEquals(
            FocusSelectionEffect.OpenChapterFormation(listOf("u1", "u3")),
            effect.await(),
        )
    }

    @Test
    fun backReturnsToConfirmedUpstream() = runTest(dispatcher) {
        val viewModel = FocusSelectionViewModel(units)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(FocusSelectionAction.BackRequested)
        advanceUntilIdle()
        assertEquals(FocusSelectionEffect.ReturnUpstream, effect.await())
    }
}
