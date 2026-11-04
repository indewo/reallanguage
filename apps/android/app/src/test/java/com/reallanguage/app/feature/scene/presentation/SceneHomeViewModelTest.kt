package com.reallanguage.app.feature.scene.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.scene.domain.RecentSceneRecommendation
import com.reallanguage.app.feature.scene.domain.SceneHomeRepository
import com.reallanguage.app.feature.scene.domain.SceneRecommendationResult
import com.reallanguage.app.feature.scene.domain.SceneStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SceneHomeViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun loadsAtMostFourRecentRecommendations() = runTest {
        val viewModel = SceneHomeViewModel(FakeRepository(recommendations(5)))
        advanceUntilIdle()
        assertEquals(4, viewModel.uiState.value.recommendations.size)
    }

    @Test fun emptyRecommendationsHideOnlyRecentSection() = runTest {
        val viewModel = SceneHomeViewModel(FakeRepository(emptyList()))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.showRecentSection)
        assertEquals(8, viewModel.uiState.value.templates.size)
        assertTrue(viewModel.uiState.value.directDescriptionAvailable)
    }

    @Test fun unavailableRecommendationsStillLeaveTemplatesAndDirectInput() = runTest {
        val viewModel = SceneHomeViewModel(FakeRepository(result = SceneRecommendationResult.Unavailable))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.showRecentSection)
        assertEquals(8, viewModel.uiState.value.templates.size)
    }

    @Test fun cachedRecommendationsRenderBeforeChangedSourceRefreshCompletes() = runTest {
        val cached = recommendations(1)
        val refreshed = RecentSceneRecommendation("new", "最新主题", "新固定内容", listOf("new-source"))
        val repository = FakeRepository(cached, refresh = SceneRecommendationResult.Ready(listOf(refreshed)))
        val viewModel = SceneHomeViewModel(repository)
        advanceUntilIdle()
        assertEquals(listOf("最新主题"), viewModel.uiState.value.recommendations.map { it.shortTopicZh })
        assertEquals(1, repository.refreshCalls)
        assertFalse(viewModel.uiState.value.loadingRecommendations)
    }

    @Test fun selectingRecommendationOpensTypeSelectionWithRecommendationStart() = runTest {
        val recommendation = recommendations(1).single()
        val viewModel = SceneHomeViewModel(FakeRepository(listOf(recommendation)))
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SceneHomeAction.RecommendationSelected(recommendation.recommendationId))
        assertEquals(SceneHomeEffect.OpenTypeSelection(SceneStart.Recommendation(recommendation)), effect.await())
    }

    @Test fun selectingFixedTemplateOpensTypeSelection() = runTest {
        val viewModel = SceneHomeViewModel(FakeRepository(emptyList()))
        advanceUntilIdle()
        val template = viewModel.uiState.value.templates.first()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SceneHomeAction.TemplateSelected(template.templateId))
        assertEquals(SceneHomeEffect.OpenTypeSelection(SceneStart.Template(template)), effect.await())
    }

    @Test fun directDescriptionEntryOpensDedicatedEditorWithPreservedText() = runTest {
        val viewModel = SceneHomeViewModel(
            FakeRepository(emptyList()),
            SceneStart.DirectDescription("我想练习向同事解释延期"),
        )
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SceneHomeAction.DirectDescriptionRequested)
        assertEquals(
            SceneHomeEffect.OpenDirectDescription("我想练习向同事解释延期"),
            effect.await(),
        )
    }

    private class FakeRepository(
        private val values: List<RecentSceneRecommendation> = emptyList(),
        private val result: SceneRecommendationResult = SceneRecommendationResult.Ready(values),
        private val refresh: SceneRecommendationResult? = null,
    ) : SceneHomeRepository {
        var refreshCalls = 0
        override suspend fun loadRecommendations(): SceneRecommendationResult = result
        override suspend fun refreshRecommendationsIfNeeded(): SceneRecommendationResult? {
            refreshCalls++
            return refresh
        }
    }

    companion object {
        private fun recommendations(count: Int) = (1..count).map {
            RecentSceneRecommendation("rec-$it", "近期主题 $it", "与近期内容有关", listOf("source-$it"))
        }
    }
}
