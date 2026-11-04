package com.reallanguage.app.feature.scene.presentation

import com.reallanguage.app.feature.scene.domain.SceneAnswer
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneQuestion
import com.reallanguage.app.feature.scene.domain.SceneQuestionSession
import com.reallanguage.app.feature.scene.domain.SceneStart

data class AnsweredSceneQuestion(val question: SceneQuestion, val answer: SceneAnswer)

data class SceneQuestionsUiState(
    val start: SceneStart,
    val contentType: SceneContentType,
    val currentQuestion: SceneQuestion? = null,
    val answerText: String = "",
    val confirmedAnswers: List<SceneAnswer> = emptyList(),
    val history: List<AnsweredSceneQuestion> = emptyList(),
    val loading: Boolean = true,
    val error: SceneQuestionsError? = null,
)

enum class SceneQuestionsError { REQUEST_FAILED, INVALID_RESPONSE, INSUFFICIENT_INFORMATION }

sealed interface SceneQuestionsAction {
    data class AnswerChanged(val answer: String) : SceneQuestionsAction
    data object AnswerSubmitted : SceneQuestionsAction
    data object PreviousRequested : SceneQuestionsAction
    data object BackRequested : SceneQuestionsAction
    data object ExitRequested : SceneQuestionsAction
    data object RetryRequested : SceneQuestionsAction
}

sealed interface SceneQuestionsEffect {
    data class OpenGeneration(val session: SceneQuestionSession) : SceneQuestionsEffect
    data object ReturnToPrevious : SceneQuestionsEffect
    data class OpenAbandonConfirmation(val session: SceneQuestionSession) : SceneQuestionsEffect
}
