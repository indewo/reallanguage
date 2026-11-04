package com.reallanguage.app.feature.scene.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.scene.domain.SceneAbandonRepository
import com.reallanguage.app.feature.scene.domain.SceneAbandonResult
import com.reallanguage.app.feature.scene.domain.SceneAnswer
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneQuestionSession
import com.reallanguage.app.feature.scene.domain.SceneStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SceneAbandonViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun continueReturnsToQuestionsWithoutDeletingAnything() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SceneAbandonAction.ContinueRequested)
        assertEquals(SceneAbandonEffect.ReturnToQuestions(session), effect.await())
        assertEquals(0, repository.calls)
    }

    @Test fun confirmDeletesWholeUnfixedSessionWithStableCommandAndReturnsHome() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SceneAbandonAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals(session, repository.session)
        assertEquals("abandon-1", repository.commandId)
        assertEquals(SceneAbandonEffect.OpenHome, effect.await())
    }

    @Test fun duplicateTapCannotRunAbandonTwice() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.onAction(SceneAbandonAction.ConfirmRequested)
        viewModel.onAction(SceneAbandonAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals(1, repository.calls)
    }

    @Test fun failureKeepsSessionAndAllowsSameCommandRetry() = runTest {
        val repository = FakeRepository(SceneAbandonResult.Failed)
        val viewModel = viewModel(repository)
        viewModel.onAction(SceneAbandonAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals(SceneAbandonError.DELETE_FAILED, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.deleting)
        repository.result = SceneAbandonResult.Abandoned
        viewModel.onAction(SceneAbandonAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals("abandon-1", repository.commandId)
    }

    private fun viewModel(repository: FakeRepository) = SceneAbandonViewModel(
        session, "abandon-1", repository,
    )

    private class FakeRepository(var result: SceneAbandonResult = SceneAbandonResult.Abandoned) : SceneAbandonRepository {
        var calls = 0
        var session: SceneQuestionSession? = null
        var commandId: String? = null
        override suspend fun abandon(session: SceneQuestionSession, commandId: String): SceneAbandonResult {
            calls++
            this.session = session
            this.commandId = commandId
            return result
        }
    }

    companion object {
        private val session = SceneQuestionSession(
            start = SceneStart.DirectDescription("向同事解释延期"),
            contentType = SceneContentType.DIALOGUE,
            answers = listOf(SceneAnswer("q1", "同事")),
            operationId = "operation-1",
        )
    }
}
