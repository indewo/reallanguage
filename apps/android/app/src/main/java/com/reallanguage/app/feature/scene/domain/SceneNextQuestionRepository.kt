package com.reallanguage.app.feature.scene.domain

data class SceneQuestion(
    val questionId: String,
    val questionZh: String,
    val options: List<String>,
)

data class SceneAnswer(
    val questionId: String,
    val answerZh: String,
    val questionZh: String? = null,
)

data class SceneQuestionRequest(
    val start: SceneStart,
    val contentType: SceneContentType,
    val confirmedAnswers: List<SceneAnswer>,
    val remainingQuestions: Int,
    val remainingContentBudget: Int,
    val operationId: String,
    val stepIndex: Int,
)

sealed interface SceneQuestionResult {
    data class Ask(val question: SceneQuestion) : SceneQuestionResult
    data class Generate(
        val collectedFacts: List<String>,
        val missingNonBlockingFacts: List<String>,
    ) : SceneQuestionResult
    data object InsufficientInformation : SceneQuestionResult
    data object Failed : SceneQuestionResult
}

data class SceneQuestionSession(
    val start: SceneStart,
    val contentType: SceneContentType,
    val answers: List<SceneAnswer>,
    val collectedFacts: List<String> = emptyList(),
    val missingNonBlockingFacts: List<String> = emptyList(),
    val operationId: String,
)

interface SceneNextQuestionRepository {
    suspend fun next(request: SceneQuestionRequest): SceneQuestionResult
}
