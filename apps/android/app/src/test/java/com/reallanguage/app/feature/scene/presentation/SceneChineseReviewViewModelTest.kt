package com.reallanguage.app.feature.scene.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import com.reallanguage.app.feature.scene.domain.SceneAnswer
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneDraftUnit
import com.reallanguage.app.feature.scene.domain.SceneEditIssue
import com.reallanguage.app.feature.scene.domain.SceneEditIssueReason
import com.reallanguage.app.feature.scene.domain.SceneEditReviewRepository
import com.reallanguage.app.feature.scene.domain.SceneEditReviewRequest
import com.reallanguage.app.feature.scene.domain.SceneEditReviewResult
import com.reallanguage.app.feature.scene.domain.SceneGeneratedDraft
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
class SceneChineseReviewViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun changingTextAfterFailedReviewDoesNotReuseAnOldRequestIdentity() = runTest {
        val repository = FakeRepository(SceneEditReviewResult.Failed)
        val viewModel = viewModel(repository)
        viewModel.onAction(SceneChineseReviewAction.UnitChanged("u2", "我需要多两天时间。"))
        viewModel.onAction(SceneChineseReviewAction.ConfirmRequested)
        advanceUntilIdle()
        viewModel.onAction(SceneChineseReviewAction.UnitChanged("u2", "需要把交付时间延后两天。"))
        viewModel.onAction(SceneChineseReviewAction.ConfirmRequested)
        advanceUntilIdle()
        org.junit.Assert.assertNotEquals(repository.calls[0].second, repository.calls[1].second)
    }

    @Test fun manualEditUpdatesOnlySelectedUnitAndTracksItsLocation() = runTest {
        val viewModel = viewModel(FakeRepository())
        viewModel.onAction(SceneChineseReviewAction.UnitChanged("u2", "我需要多两天时间。"))
        assertEquals("我需要多两天时间。", viewModel.uiState.value.units[1].textZh)
        assertEquals(setOf("u2"), viewModel.uiState.value.changedUnitIds)
    }

    @Test fun unchangedContentPassesDirectlyWithoutAnotherAiCall() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SceneChineseReviewAction.ConfirmRequested)
        assertEquals(SceneChineseReviewEffect.OpenSafetyCheck(expectedSource, draft), effect.await())
        assertEquals(0, repository.calls.size)
    }

    @Test fun editedContentUsesSceneEditReviewContractAndStableIdentity() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.onAction(SceneChineseReviewAction.UnitChanged("u2", "我需要多两天时间。"))
        viewModel.onAction(SceneChineseReviewAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals(
            SceneEditReviewRequest(
                originalDraft = draft,
                editedUnits = draft.units.map {
                    if (it.unitId == "u2") it.copy(textZh = "我需要多两天时间。") else it
                },
                changedUnitIds = setOf("u2"),
                contentType = SceneContentType.DIALOGUE,
            ),
            repository.calls.single().first,
        )
        assertEquals("review-1", repository.calls.single().second)
    }

    @Test fun reviewIssuesAreSpecificAndNeverOverwriteUserText() = runTest {
        val issue = SceneEditIssue(
            unitId = "u2",
            startOffset = 0,
            endOffset = 3,
            reason = SceneEditIssueReason.REQUIRED_INFORMATION_MISSING,
            explanationZh = "需要说明延期多久。",
        )
        val repository = FakeRepository(SceneEditReviewResult.NeedsUserEdit(listOf(issue)))
        val viewModel = viewModel(repository)
        viewModel.onAction(SceneChineseReviewAction.UnitChanged("u2", "需要延期。"))
        viewModel.onAction(SceneChineseReviewAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals(listOf(issue), viewModel.uiState.value.issues)
        assertEquals("需要延期。", viewModel.uiState.value.units[1].textZh)
    }

    @Test fun passedEditedContentOpensUnifiedSafetyCheckWithCurrentVisibleText() = runTest {
        val viewModel = viewModel(FakeRepository())
        viewModel.onAction(SceneChineseReviewAction.UnitChanged("u2", "我需要多两天时间。"))
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SceneChineseReviewAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals(
            SceneChineseReviewEffect.OpenSafetyCheck(
                expectedSource.copy(text = "对方：项目现在进展怎么样？\n用户：我需要多两天时间。"),
                draft.copy(units = draft.units.map { if (it.unitId == "u2") it.copy(textZh = "我需要多两天时间。") else it }),
            ),
            effect.await(),
        )
    }

    @Test fun failedReviewKeepsEditsAndAllowsSameCommandRetry() = runTest {
        val repository = FakeRepository(SceneEditReviewResult.Failed)
        val viewModel = viewModel(repository)
        viewModel.onAction(SceneChineseReviewAction.UnitChanged("u2", "我需要多两天时间。"))
        viewModel.onAction(SceneChineseReviewAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals(SceneChineseReviewError.REVIEW_FAILED, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.reviewing)
        repository.result = SceneEditReviewResult.Pass
        viewModel.onAction(SceneChineseReviewAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals(listOf("review-1", "review-1"), repository.calls.map { it.second })
    }

    @Test fun returnAndRegeneratePreserveWholeUpstreamSession() = runTest {
        val viewModel = viewModel(FakeRepository())
        var effect = async { viewModel.effects.first() }
        viewModel.onAction(SceneChineseReviewAction.ReturnToQuestionsRequested)
        assertEquals(SceneChineseReviewEffect.ReturnToQuestions(session), effect.await())
        effect = async { viewModel.effects.first() }
        viewModel.onAction(SceneChineseReviewAction.RegenerateRequested)
        assertEquals(SceneChineseReviewEffect.OpenGeneration(session, draft.units), effect.await())
    }

    @Test fun blankUnitCannotBeConfirmedAndConcurrentReviewCannotDuplicateCall() = runTest {
        val viewModel = viewModel(FakeRepository())
        viewModel.onAction(SceneChineseReviewAction.UnitChanged("u2", "  "))
        viewModel.onAction(SceneChineseReviewAction.ConfirmRequested)
        assertEquals(SceneChineseReviewError.EMPTY_CONTENT, viewModel.uiState.value.error)

        val delayed = DelayedRepository()
        val delayedViewModel = viewModel(delayed)
        delayedViewModel.onAction(SceneChineseReviewAction.UnitChanged("u2", "我需要多两天时间。"))
        delayedViewModel.onAction(SceneChineseReviewAction.ConfirmRequested)
        runCurrent()
        assertTrue(delayedViewModel.uiState.value.reviewing)
        delayedViewModel.onAction(SceneChineseReviewAction.ConfirmRequested)
        assertEquals(1, delayed.calls)
        delayed.result.complete(SceneEditReviewResult.Pass)
        advanceUntilIdle()
    }

    private fun viewModel(repository: SceneEditReviewRepository) = SceneChineseReviewViewModel(
        session = session,
        draft = draft,
        commandId = "review-1",
        repository = repository,
    )

    private class FakeRepository(
        var result: SceneEditReviewResult = SceneEditReviewResult.Pass,
    ) : SceneEditReviewRepository {
        val calls = mutableListOf<Pair<SceneEditReviewRequest, String>>()
        override suspend fun review(request: SceneEditReviewRequest, commandId: String): SceneEditReviewResult {
            calls += request to commandId
            return result
        }
    }

    private class DelayedRepository : SceneEditReviewRepository {
        val result = CompletableDeferred<SceneEditReviewResult>()
        var calls = 0
        override suspend fun review(request: SceneEditReviewRequest, commandId: String): SceneEditReviewResult {
            calls++
            return result.await()
        }
    }

    companion object {
        private val session = SceneQuestionSession(
            start = SceneStart.DirectDescription("向同事解释项目延期"),
            contentType = SceneContentType.DIALOGUE,
            answers = listOf(SceneAnswer("q1", "我们是同事")),
            operationId = "operation-1",
        )
        private val draft = SceneGeneratedDraft(
            draftId = "draft-1",
            contentType = SceneContentType.DIALOGUE,
            units = listOf(
                SceneDraftUnit("u1", SceneSpeaker.OTHER, "项目现在进展怎么样？", emptyList()),
                SceneDraftUnit("u2", SceneSpeaker.USER, "我想说明项目需要延期。", emptyList()),
            ),
        )
        private val expectedSource = ConfirmedSource(
            text = "对方：项目现在进展怎么样？\n用户：我想说明项目需要延期。",
            sourceType = SourceType.AI_SCENE,
            contentShape = SourceContentShape.DIALOGUE,
        )
    }
}
