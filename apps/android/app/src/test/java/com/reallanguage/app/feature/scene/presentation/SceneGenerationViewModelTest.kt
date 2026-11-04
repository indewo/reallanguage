package com.reallanguage.app.feature.scene.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.scene.domain.SceneAnswer
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneDraftUnit
import com.reallanguage.app.feature.scene.domain.SceneGeneratedDraft
import com.reallanguage.app.feature.scene.domain.SceneGenerationRepository
import com.reallanguage.app.feature.scene.domain.SceneGenerationRequest
import com.reallanguage.app.feature.scene.domain.SceneGenerationResult
import com.reallanguage.app.feature.scene.domain.SceneGenerationTask
import com.reallanguage.app.feature.scene.domain.SceneQuestionSession
import com.reallanguage.app.feature.scene.domain.SceneSpeaker
import com.reallanguage.app.feature.scene.domain.SceneStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SceneGenerationViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun dialogueUsesDialogueTurnContractWithFullConfirmedSessionAndStableIdentity() = runTest {
        val repository = FakeRepository()
        SceneGenerationViewModel(dialogueSession, "generate-1", repository)
        advanceUntilIdle()
        assertEquals(
            SceneGenerationRequest(
                task = SceneGenerationTask.DIALOGUE_TURN,
                session = dialogueSession,
                remainingContentBudget = 6,
            ),
            repository.requests.single(),
        )
        assertEquals(listOf("generate-1"), repository.commandIds)
    }

    @Test fun articleUsesArticleDraftContract() = runTest {
        val repository = FakeRepository(SceneGenerationResult.Generated(articleDraft))
        SceneGenerationViewModel(articleSession, "generate-article", repository)
        advanceUntilIdle()
        assertEquals(SceneGenerationTask.ARTICLE_DRAFT, repository.requests.single().task)
    }

    @Test fun regenerationIncludesPreviousVisibleDraft() = runTest {
        val repository = FakeRepository()
        SceneGenerationViewModel(dialogueSession, "regenerate-2", repository, previousUnits = dialogueDraft.units)
        advanceUntilIdle()
        assertEquals(dialogueDraft.units, repository.requests.single().previousUnits)
        assertEquals(listOf("regenerate-2"), repository.commandIds)
    }

    @Test fun validResultOpensChineseReviewWithoutCreatingFixedContent() = runTest {
        val viewModel = SceneGenerationViewModel(dialogueSession, "generate-1", FakeRepository())
        val effect = async { viewModel.effects.first() }
        advanceUntilIdle()
        assertEquals(SceneGenerationEffect.OpenChineseReview(dialogueSession, dialogueDraft), effect.await())
    }

    @Test fun failureKeepsSessionAndRetryReusesBusinessIdentity() = runTest {
        val repository = FakeRepository(SceneGenerationResult.Failed)
        val viewModel = SceneGenerationViewModel(dialogueSession, "generate-1", repository)
        advanceUntilIdle()
        assertEquals(SceneGenerationError.GENERATION_FAILED, viewModel.uiState.value.error)
        assertEquals(dialogueSession, viewModel.uiState.value.session)
        assertFalse(viewModel.uiState.value.generating)
        repository.result = SceneGenerationResult.Generated(dialogueDraft)
        viewModel.onAction(SceneGenerationAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(listOf("generate-1", "generate-1"), repository.commandIds)
    }

    @Test fun failedGenerationCanReturnToQuestionsWithWholeSession() = runTest {
        val viewModel = SceneGenerationViewModel(dialogueSession, "generate-1", FakeRepository(SceneGenerationResult.Failed))
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SceneGenerationAction.ReturnRequested)
        assertEquals(SceneGenerationEffect.ReturnToQuestions(dialogueSession), effect.await())
    }

    @Test fun duplicateRetryCannotStartConcurrentGeneration() = runTest {
        val repository = DelayedRepository()
        val viewModel = SceneGenerationViewModel(dialogueSession, "generate-1", repository)
        runCurrent()
        assertTrue(viewModel.uiState.value.generating)
        viewModel.onAction(SceneGenerationAction.RetryRequested)
        assertEquals(1, repository.calls)
        repository.result.complete(SceneGenerationResult.Generated(dialogueDraft))
        advanceUntilIdle()
    }

    @Test fun malformedProviderDraftsStayInFailureState() = runTest {
        val invalidDrafts = listOf(
            dialogueDraft.copy(contentType = SceneContentType.ARTICLE),
            dialogueDraft.copy(draftId = ""),
            dialogueDraft.copy(units = emptyList()),
            dialogueDraft.copy(units = List(11) { index -> dialogueDraft.units[index % 2].copy(unitId = "u$index") }),
            dialogueDraft.copy(units = listOf(dialogueDraft.units.first().copy(textZh = ""))),
            dialogueDraft.copy(units = listOf(dialogueDraft.units.first())),
            dialogueDraft.copy(units = listOf(dialogueDraft.units.last())),
            articleDraft.copy(units = listOf(articleDraft.units.first().copy(speaker = SceneSpeaker.USER))),
        )
        invalidDrafts.forEach { draft ->
            val viewModel = SceneGenerationViewModel(
                if (draft.draftId == articleDraft.draftId) articleSession else dialogueSession,
                "generate-invalid",
                FakeRepository(SceneGenerationResult.Generated(draft)),
            )
            advanceUntilIdle()
            assertEquals(SceneGenerationError.GENERATION_FAILED, viewModel.uiState.value.error)
        }
    }

    @Test fun transportExceptionPreservesSessionAsRetryableFailure() = runTest {
        val viewModel = SceneGenerationViewModel(dialogueSession, "generate-1", ThrowingRepository())
        advanceUntilIdle()
        assertEquals(SceneGenerationError.GENERATION_FAILED, viewModel.uiState.value.error)
        assertEquals(dialogueSession, viewModel.uiState.value.session)
    }

    private class FakeRepository(
        var result: SceneGenerationResult = SceneGenerationResult.Generated(dialogueDraft),
    ) : SceneGenerationRepository {
        val requests = mutableListOf<SceneGenerationRequest>()
        val commandIds = mutableListOf<String>()
        override suspend fun generate(request: SceneGenerationRequest, commandId: String): SceneGenerationResult {
            requests += request
            commandIds += commandId
            return result
        }
    }

    private class DelayedRepository : SceneGenerationRepository {
        val result = CompletableDeferred<SceneGenerationResult>()
        var calls = 0
        override suspend fun generate(request: SceneGenerationRequest, commandId: String): SceneGenerationResult {
            calls++
            return result.await()
        }
    }

    private class ThrowingRepository : SceneGenerationRepository {
        override suspend fun generate(request: SceneGenerationRequest, commandId: String): SceneGenerationResult =
            error("transport")
    }

    companion object {
        private val dialogueSession = SceneQuestionSession(
            start = SceneStart.DirectDescription("向同事解释项目延期"),
            contentType = SceneContentType.DIALOGUE,
            answers = listOf(SceneAnswer("q1", "我们是同事")),
            collectedFacts = listOf("项目需要延期", "沟通对象是同事"),
            operationId = "operation-1",
        )
        private val articleSession = dialogueSession.copy(contentType = SceneContentType.ARTICLE)
        private val dialogueDraft = SceneGeneratedDraft(
            draftId = "draft-1",
            contentType = SceneContentType.DIALOGUE,
            units = listOf(
                SceneDraftUnit("u1", SceneSpeaker.OTHER, "项目现在进展怎么样？", listOf("项目需要延期")),
                SceneDraftUnit("u2", SceneSpeaker.USER, "我想说明项目需要延期。", listOf("项目需要延期")),
            ),
        )
        private val articleDraft = SceneGeneratedDraft(
            draftId = "draft-2",
            contentType = SceneContentType.ARTICLE,
            units = listOf(SceneDraftUnit("u1", null, "项目需要延期。", listOf("项目需要延期"))),
        )
    }
}
