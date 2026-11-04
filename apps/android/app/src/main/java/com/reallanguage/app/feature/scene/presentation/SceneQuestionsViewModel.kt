package com.reallanguage.app.feature.scene.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.scene.domain.SceneAnswer
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneNextQuestionRepository
import com.reallanguage.app.feature.scene.domain.SceneQuestionRequest
import com.reallanguage.app.feature.scene.domain.SceneQuestionResult
import com.reallanguage.app.feature.scene.domain.SceneQuestionSession
import com.reallanguage.app.feature.scene.domain.SceneStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SceneQuestionsViewModel(
    private val start: SceneStart,
    private val contentType: SceneContentType,
    private val operationId: String,
    private val repository: SceneNextQuestionRepository,
    initialAnswers: List<SceneAnswer> = emptyList(),
    private val remainingContentBudget: Int = 10,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        SceneQuestionsUiState(start, contentType, confirmedAnswers = initialAnswers),
    )
    val uiState: StateFlow<SceneQuestionsUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<SceneQuestionsEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    init { requestNext() }

    fun onAction(action: SceneQuestionsAction) {
        when (action) {
            is SceneQuestionsAction.AnswerChanged -> mutableUiState.update {
                it.copy(answerText = action.answer, error = null)
            }
            SceneQuestionsAction.AnswerSubmitted -> submitAnswer()
            SceneQuestionsAction.PreviousRequested -> previous()
            SceneQuestionsAction.BackRequested -> effectChannel.trySend(SceneQuestionsEffect.ReturnToPrevious)
            SceneQuestionsAction.ExitRequested -> effectChannel.trySend(
                SceneQuestionsEffect.OpenAbandonConfirmation(session()),
            )
            SceneQuestionsAction.RetryRequested -> requestNext()
        }
    }

    private fun submitAnswer() {
        val state = mutableUiState.value
        val question = state.currentQuestion ?: return
        val text = state.answerText.trim()
        if (text.isEmpty() || state.loading) return
        val answer = SceneAnswer(question.questionId, text, question.questionZh)
        mutableUiState.update {
            it.copy(
                currentQuestion = null,
                answerText = "",
                confirmedAnswers = it.confirmedAnswers + answer,
                history = it.history + AnsweredSceneQuestion(question, answer),
            )
        }
        requestNext()
    }

    private fun previous() {
        val state = mutableUiState.value
        if (state.loading) return
        val previous = state.history.lastOrNull() ?: state.confirmedAnswers.lastOrNull()?.let { answer ->
            val question = answer.questionZh?.takeIf(String::isNotBlank)?.let {
                com.reallanguage.app.feature.scene.domain.SceneQuestion(answer.questionId, it, emptyList())
            } ?: return
            AnsweredSceneQuestion(question, answer)
        } ?: return
        mutableUiState.update {
            it.copy(
                currentQuestion = previous.question,
                answerText = previous.answer.answerZh,
                confirmedAnswers = it.confirmedAnswers.dropLast(1),
                history = if (it.history.isEmpty()) emptyList() else it.history.dropLast(1),
                error = null,
            )
        }
    }

    private fun requestNext() {
        val answers = mutableUiState.value.confirmedAnswers
        mutableUiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = try {
                repository.next(
                    SceneQuestionRequest(
                        start = start,
                        contentType = contentType,
                        confirmedAnswers = answers,
                        remainingQuestions = (10 - answers.size).coerceAtLeast(0),
                        remainingContentBudget = remainingContentBudget,
                        operationId = operationId,
                        stepIndex = answers.size,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                SceneQuestionResult.Failed
            }
            handle(result)
        }
    }

    private suspend fun handle(result: SceneQuestionResult) {
        val state = mutableUiState.value
        when (result) {
            is SceneQuestionResult.Ask -> {
                if (state.confirmedAnswers.size >= 10) {
                    mutableUiState.update { it.copy(loading = false, error = SceneQuestionsError.INVALID_RESPONSE) }
                } else {
                    mutableUiState.update {
                        it.copy(currentQuestion = result.question, answerText = "", loading = false)
                    }
                }
            }
            is SceneQuestionResult.Generate -> {
                if (state.confirmedAnswers.isEmpty()) {
                    mutableUiState.update { it.copy(loading = false, error = SceneQuestionsError.INVALID_RESPONSE) }
                } else {
                    mutableUiState.update { it.copy(loading = false) }
                    effectChannel.send(
                        SceneQuestionsEffect.OpenGeneration(
                            session(result.collectedFacts, result.missingNonBlockingFacts),
                        ),
                    )
                }
            }
            SceneQuestionResult.InsufficientInformation -> mutableUiState.update {
                it.copy(
                    loading = false,
                    currentQuestion = null,
                    error = SceneQuestionsError.INSUFFICIENT_INFORMATION,
                )
            }
            SceneQuestionResult.Failed -> mutableUiState.update {
                it.copy(loading = false, error = SceneQuestionsError.REQUEST_FAILED)
            }
        }
    }

    private fun session(
        collectedFacts: List<String> = emptyList(),
        missingFacts: List<String> = emptyList(),
    ) = SceneQuestionSession(
        start = start,
        contentType = contentType,
        answers = mutableUiState.value.confirmedAnswers,
        collectedFacts = collectedFacts,
        missingNonBlockingFacts = missingFacts,
        operationId = operationId,
    )
}
