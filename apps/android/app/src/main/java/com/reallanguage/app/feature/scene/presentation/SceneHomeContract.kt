package com.reallanguage.app.feature.scene.presentation

import com.reallanguage.app.feature.scene.domain.PublicSceneTemplate
import com.reallanguage.app.feature.scene.domain.PublicSceneTemplates
import com.reallanguage.app.feature.scene.domain.RecentSceneRecommendation
import com.reallanguage.app.feature.scene.domain.SceneStart

data class SceneHomeUiState(
    val loadingRecommendations: Boolean = true,
    val recommendations: List<RecentSceneRecommendation> = emptyList(),
    val templates: List<PublicSceneTemplate> = PublicSceneTemplates,
    val directDescription: String = "",
) {
    val showRecentSection: Boolean get() = recommendations.isNotEmpty()
    val directDescriptionAvailable: Boolean get() = true
}

sealed interface SceneHomeAction {
    data class RecommendationSelected(val recommendationId: String) : SceneHomeAction
    data class TemplateSelected(val templateId: String) : SceneHomeAction
    data object DirectDescriptionRequested : SceneHomeAction
    @Deprecated("The dedicated description page owns scene text editing")
    data class DescriptionChanged(val description: String) : SceneHomeAction
    @Deprecated("Use DirectDescriptionRequested")
    data object DirectDescriptionSubmitted : SceneHomeAction
}

sealed interface SceneHomeEffect {
    data class OpenTypeSelection(val start: SceneStart) : SceneHomeEffect
    data class OpenDirectDescription(val initialText: String) : SceneHomeEffect
}
