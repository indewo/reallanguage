package com.reallanguage.app.feature.scene.presentation

import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.scene.domain.SceneDraftUnit
import com.reallanguage.app.feature.scene.domain.SceneEditIssue
import com.reallanguage.app.feature.scene.domain.SceneQuestionSession
import com.reallanguage.app.feature.scene.domain.SceneGeneratedDraft

data class SceneChineseReviewUiState(
    val session: SceneQuestionSession,
    val units: List<SceneDraftUnit>,
    val changedUnitIds: Set<String> = emptySet(),
    val reviewing: Boolean = false,
    val issues: List<SceneEditIssue> = emptyList(),
    val error: SceneChineseReviewError? = null,
)

enum class SceneChineseReviewError { EMPTY_CONTENT, REVIEW_FAILED }

sealed interface SceneChineseReviewAction {
    data class UnitChanged(val unitId: String, val textZh: String) : SceneChineseReviewAction
    data object ConfirmRequested : SceneChineseReviewAction
    data object ReturnToQuestionsRequested : SceneChineseReviewAction
    data object RegenerateRequested : SceneChineseReviewAction
}

sealed interface SceneChineseReviewEffect {
    data class OpenSafetyCheck(val source: ConfirmedSource, val confirmedDraft: SceneGeneratedDraft) : SceneChineseReviewEffect
    data class ReturnToQuestions(val session: SceneQuestionSession) : SceneChineseReviewEffect
    data class OpenGeneration(val session: SceneQuestionSession, val previousUnits: List<SceneDraftUnit> = emptyList()) : SceneChineseReviewEffect
}
