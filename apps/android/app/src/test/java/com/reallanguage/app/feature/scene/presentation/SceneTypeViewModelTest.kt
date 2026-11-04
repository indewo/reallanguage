package com.reallanguage.app.feature.scene.presentation

import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SceneTypeViewModelTest {
    private val start = SceneStart.DirectDescription("向同事解释项目延期")

    @Test fun opensWithOriginalStartAndNoAutomaticType() {
        val viewModel = SceneTypeViewModel(start)
        assertEquals(start, viewModel.uiState.value.start)
        assertNull(viewModel.uiState.value.selectedType)
    }

    @Test fun userCanSelectDialogueOrArticle() {
        val viewModel = SceneTypeViewModel(start)
        viewModel.onAction(SceneTypeAction.TypeSelected(SceneContentType.DIALOGUE))
        assertEquals(SceneContentType.DIALOGUE, viewModel.uiState.value.selectedType)
        viewModel.onAction(SceneTypeAction.TypeSelected(SceneContentType.ARTICLE))
        assertEquals(SceneContentType.ARTICLE, viewModel.uiState.value.selectedType)
    }

    @Test fun continueOpensQuestionsWithStartAndChosenType() = runTest {
        val viewModel = SceneTypeViewModel(start)
        viewModel.onAction(SceneTypeAction.TypeSelected(SceneContentType.ARTICLE))
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SceneTypeAction.ContinueRequested)
        assertEquals(SceneTypeEffect.OpenQuestions(start, SceneContentType.ARTICLE), effect.await())
    }

    @Test fun backReturnsToSceneHomeWithOriginalStartPreserved() = runTest {
        val viewModel = SceneTypeViewModel(start)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SceneTypeAction.BackRequested)
        assertEquals(SceneTypeEffect.OpenSceneHome(start), effect.await())
    }
}
