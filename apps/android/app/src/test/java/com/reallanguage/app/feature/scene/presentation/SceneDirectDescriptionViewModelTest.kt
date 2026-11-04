package com.reallanguage.app.feature.scene.presentation

import com.reallanguage.app.MainDispatcherRule
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SceneDirectDescriptionViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun editedDescriptionAndCaretArePreservedThenTrimmedForNextStep() = runTest {
        val viewModel = SceneDirectDescriptionViewModel("初始场景")
        viewModel.onAction(SceneDirectDescriptionAction.TextChanged("  和同事确认项目截止时间  ", 8, 8))
        assertEquals(8, viewModel.uiState.value.selectionStart)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SceneDirectDescriptionAction.ContinueRequested)
        assertEquals(
            SceneDirectDescriptionEffect.OpenTypeSelection("和同事确认项目截止时间"),
            effect.await(),
        )
    }

    @Test fun nonEmptyBackRequiresExplicitDiscardAndBlankBackReturnsDirectly() = runTest {
        val edited = SceneDirectDescriptionViewModel("待保留场景")
        edited.onAction(SceneDirectDescriptionAction.BackRequested)
        assertEquals(true, edited.uiState.value.showDiscardConfirmation)
        val discard = async { edited.effects.first() }
        edited.onAction(SceneDirectDescriptionAction.DiscardConfirmed)
        assertEquals(SceneDirectDescriptionEffect.OpenSceneHome, discard.await())

        val blank = SceneDirectDescriptionViewModel()
        val back = async { blank.effects.first() }
        blank.onAction(SceneDirectDescriptionAction.BackRequested)
        assertEquals(SceneDirectDescriptionEffect.OpenSceneHome, back.await())
    }
}
