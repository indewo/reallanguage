package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.feature.formation.domain.SemanticUnit
import org.junit.Assert.*
import org.junit.Test

class FocusSelectionRestoreViewModelTest {
    private val units = (1..4).map { SemanticUnit("u$it", it - 1, "第${it}句。", if (it == 1) "推荐" else null) }

    @Test fun returningFromAConfirmedStepRestoresOnlyTheUsersOwnFocusSelection() {
        val state = FocusSelectionViewModel(units, setOf("u2", "u4")).uiState.value
        assertEquals(setOf("u2", "u4"), state.selectedUnitIds)
        assertTrue(state.confirmEnabled)
        assertFalse(state.submitting)
        assertFalse(state.selectedUnitIds.contains("u1"))
    }

    @Test fun maximumRestoredSelectionStillBlocksAFourthWithoutLockingResubmission() {
        val viewModel = FocusSelectionViewModel(units, setOf("u2", "u3", "u4"))
        viewModel.onAction(FocusSelectionAction.UnitToggled("u1"))
        assertEquals(setOf("u2", "u3", "u4"), viewModel.uiState.value.selectedUnitIds)
        assertTrue(viewModel.uiState.value.selectionLimitReached)
        assertTrue(viewModel.uiState.value.confirmEnabled)
    }

    @Test fun corruptOrForeignRestorationDoesNotGuessAPartialSelection() {
        for (selection in listOf(setOf("u2", "other-source"), setOf("u1", "u2", "u3", "u4"))) {
            val state = FocusSelectionViewModel(units, selection).uiState.value
            assertTrue(state.selectedUnitIds.isEmpty())
            assertFalse(state.confirmEnabled)
        }
    }
}
