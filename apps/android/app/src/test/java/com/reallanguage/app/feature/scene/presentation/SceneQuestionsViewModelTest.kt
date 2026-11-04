package com.reallanguage.app.feature.scene.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.scene.domain.SceneAnswer
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneNextQuestionRepository
import com.reallanguage.app.feature.scene.domain.SceneQuestion
import com.reallanguage.app.feature.scene.domain.SceneQuestionRequest
import com.reallanguage.app.feature.scene.domain.SceneQuestionResult
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
class SceneQuestionsViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val start = SceneStart.DirectDescription("向同事解释延期")

    @Test fun loadsExactlyOneFirstQuestionWithFullBudget() = runTest {
        val repository = FakeRepository(mutableListOf(ask("q1", "你和对方是什么关系？")))
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        assertEquals("q1", viewModel.uiState.value.currentQuestion?.questionId)
        assertEquals(10, repository.requests.single().remainingQuestions)
        assertEquals(SceneContentType.DIALOGUE, repository.requests.single().contentType)
    }

    @Test fun submittingAnswerLoadsOnlyTheNextQuestion() = runTest {
        val repository = FakeRepository(mutableListOf(ask("q1", "关系？"), ask("q2", "在哪里？")))
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(SceneQuestionsAction.AnswerChanged("同事"))
        viewModel.onAction(SceneQuestionsAction.AnswerSubmitted)
        advanceUntilIdle()
        assertEquals("q2", viewModel.uiState.value.currentQuestion?.questionId)
        assertEquals(listOf(SceneAnswer("q1", "同事", "关系？")), viewModel.uiState.value.confirmedAnswers)
        assertEquals(9, repository.requests.last().remainingQuestions)
        assertEquals("关系？", repository.requests.last().confirmedAnswers.single().questionZh)
    }

    @Test fun blankAnswerDoesNotSubmit() = runTest {
        val repository = FakeRepository(mutableListOf(ask("q1", "关系？")))
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(SceneQuestionsAction.AnswerChanged("   "))
        viewModel.onAction(SceneQuestionsAction.AnswerSubmitted)
        advanceUntilIdle()
        assertEquals(1, repository.requests.size)
    }

    @Test fun previousQuestionRestoresItsConfirmedAnswerForEditing() = runTest {
        val repository = FakeRepository(mutableListOf(ask("q1", "关系？"), ask("q2", "地点？")))
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(SceneQuestionsAction.AnswerChanged("同事"))
        viewModel.onAction(SceneQuestionsAction.AnswerSubmitted)
        advanceUntilIdle()
        viewModel.onAction(SceneQuestionsAction.PreviousRequested)
        assertEquals("q1", viewModel.uiState.value.currentQuestion?.questionId)
        assertEquals("同事", viewModel.uiState.value.answerText)
        assertTrue(viewModel.uiState.value.confirmedAnswers.isEmpty())
    }

    @Test fun generateAfterAtLeastOneAnswerOpensGeneration() = runTest {
        val repository = FakeRepository(
            mutableListOf(ask("q1", "关系？"), SceneQuestionResult.Generate(listOf("双方是同事"), emptyList())),
        )
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SceneQuestionsAction.AnswerChanged("同事"))
        viewModel.onAction(SceneQuestionsAction.AnswerSubmitted)
        advanceUntilIdle()
        val session = (effect.await() as SceneQuestionsEffect.OpenGeneration).session
        assertEquals(1, session.answers.size)
        assertEquals(listOf("双方是同事"), session.collectedFacts)
    }

    @Test fun eleventhQuestionIsRejectedByDeterministicLimit() = runTest {
        val answers = (1..10).map { SceneAnswer("old-$it", "answer-$it", "question-$it") }
        val repository = FakeRepository(mutableListOf(ask("q11", "不应出现")))
        val viewModel = SceneQuestionsViewModel(
            start, SceneContentType.DIALOGUE, "operation-1", repository, initialAnswers = answers,
        )
        advanceUntilIdle()
        assertEquals(SceneQuestionsError.INVALID_RESPONSE, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test fun tenthAnswerWithInsufficientInformationStopsAndPreservesAnswers() = runTest {
        val answers = (1..10).map { SceneAnswer("q$it", "重复回答", "问题$it") }
        val repository = FakeRepository(mutableListOf(SceneQuestionResult.InsufficientInformation))
        val viewModel = SceneQuestionsViewModel(
            start, SceneContentType.DIALOGUE, "operation-1", repository, initialAnswers = answers,
        )
        advanceUntilIdle()
        assertEquals(SceneQuestionsError.INSUFFICIENT_INFORMATION, viewModel.uiState.value.error)
        assertEquals(answers, viewModel.uiState.value.confirmedAnswers)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test fun exitOpensAbandonConfirmationWithCurrentSession() = runTest {
        val repository = FakeRepository(mutableListOf(ask("q1", "关系？")))
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SceneQuestionsAction.ExitRequested)
        assertTrue(effect.await() is SceneQuestionsEffect.OpenAbandonConfirmation)
    }

    @Test fun ordinaryBackReturnsToThePreviousSceneNodeWithoutOpeningAbandonment() = runTest {
        val repository = FakeRepository(mutableListOf(ask("q1", "关系？")))
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(SceneQuestionsAction.BackRequested)

        assertEquals(SceneQuestionsEffect.ReturnToPrevious, effect.await())
    }

    private fun viewModel(repository: FakeRepository) = SceneQuestionsViewModel(
        start, SceneContentType.DIALOGUE, "operation-1", repository,
    )

    private class FakeRepository(
        private val results: MutableList<SceneQuestionResult>,
    ) : SceneNextQuestionRepository {
        val requests = mutableListOf<SceneQuestionRequest>()
        override suspend fun next(request: SceneQuestionRequest): SceneQuestionResult {
            requests += request
            return results.removeAt(0)
        }
    }

    companion object {
        private fun ask(id: String, text: String) = SceneQuestionResult.Ask(
            SceneQuestion(id, text, options = emptyList()),
        )
    }
}
