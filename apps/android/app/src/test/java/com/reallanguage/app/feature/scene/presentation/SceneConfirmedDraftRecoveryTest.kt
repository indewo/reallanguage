package com.reallanguage.app.feature.scene.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.core.app.HomeDiaryRoute
import com.reallanguage.app.core.app.toFormationRecoverySnapshot
import com.reallanguage.app.feature.formation.domain.FormationSourceOrigin
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.scene.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SceneConfirmedDraftRecoveryTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val session = SceneQuestionSession(SceneStart.DirectDescription("解释项目需要延期两天"), SceneContentType.DIALOGUE,
        listOf(SceneAnswer("fact", "需要两天")), operationId = "scene-operation")
    private val draft = SceneGeneratedDraft("scene-draft", SceneContentType.DIALOGUE, listOf(
        SceneDraftUnit("u1", SceneSpeaker.OTHER, "还需要多久？", listOf("fact")),
        SceneDraftUnit("u2", SceneSpeaker.USER, "需要延后交付。", listOf("fact")),
    ))

    @Test fun unchangedConfirmationCarriesTheExactDraftWithoutAnotherReviewRequest() = runTest {
        var calls = 0
        val vm = SceneChineseReviewViewModel(session, draft, "review", repository { _, _ -> calls++; SceneEditReviewResult.Pass })
        val effect = async { vm.effects.first() as SceneChineseReviewEffect.OpenSafetyCheck }
        vm.onAction(SceneChineseReviewAction.ConfirmRequested)
        assertEquals(draft, effect.await().confirmedDraft)
        assertEquals(0, calls)
    }

    @Test fun approvedDialogueReturnsTheSameEditedUnitsRolesFactReferencesAndDraftIdentity() = runTest {
        val vm = SceneChineseReviewViewModel(session, draft, "review", repository { _, _ -> SceneEditReviewResult.Pass })
        vm.onAction(SceneChineseReviewAction.UnitChanged("u2", "我需要再多两天。"))
        val effect = async { vm.effects.first() as SceneChineseReviewEffect.OpenSafetyCheck }
        vm.onAction(SceneChineseReviewAction.ConfirmRequested)
        advanceUntilIdle()
        val result = effect.await()
        val confirmed = draft.copy(units = draft.units.map { if (it.unitId == "u2") it.copy(textZh = "我需要再多两天。") else it })
        assertEquals(confirmed, result.confirmedDraft)
        assertEquals("对方：还需要多久？\n用户：我需要再多两天。", result.source.text)
        assertEquals("需要延后交付。", draft.units.last().textZh)
    }

    @Test fun approvedArticleKeepsItsEditedParagraphsWithoutAddingDialogueLabels() = runTest {
        val articleSession = session.copy(contentType = SceneContentType.ARTICLE)
        val article = draft.copy(contentType = SceneContentType.ARTICLE, units = listOf(SceneDraftUnit("p1", null, "原来的说明。", listOf("fact"))))
        val vm = SceneChineseReviewViewModel(articleSession, article, "review", repository { _, _ -> SceneEditReviewResult.Pass })
        vm.onAction(SceneChineseReviewAction.UnitChanged("p1", "  项目还需要两天。  "))
        val effect = async { vm.effects.first() as SceneChineseReviewEffect.OpenSafetyCheck }
        vm.onAction(SceneChineseReviewAction.ConfirmRequested)
        advanceUntilIdle()
        val result = effect.await()
        assertEquals(article.copy(units = listOf(article.units.single().copy(textZh = "  项目还需要两天。  "))), result.confirmedDraft)
        assertEquals("项目还需要两天。", result.source.text)
        assertEquals(SourceContentShape.ARTICLE, result.source.contentShape)
    }

    @Test fun onlyTheSuccessfulReviewPayloadBecomesTheReturnDraftAndBusyEditsAreIgnored() = runTest {
        val response = CompletableDeferred<SceneEditReviewResult>()
        val requests = mutableListOf<SceneEditReviewRequest>()
        val vm = SceneChineseReviewViewModel(session, draft, "review", repository { request, _ -> requests += request; response.await() })
        vm.onAction(SceneChineseReviewAction.UnitChanged("u2", "已提交审核的两天。"))
        val effect = async { vm.effects.first() as SceneChineseReviewEffect.OpenSafetyCheck }
        vm.onAction(SceneChineseReviewAction.ConfirmRequested)
        runCurrent()
        assertFalse(effect.isCompleted)
        vm.onAction(SceneChineseReviewAction.UnitChanged("u2", "未提交审核的十天。"))
        vm.onAction(SceneChineseReviewAction.ConfirmRequested)
        response.complete(SceneEditReviewResult.Pass)
        advanceUntilIdle()
        assertEquals(1, requests.size)
        assertEquals(requests.single().editedUnits, effect.await().confirmedDraft.units)
        assertFalse(effect.await().source.text.contains("十天"))
    }

    @Test fun editedConfirmedSourceKeepsItsRealSceneOriginWhenSavedForFormationRecovery() = runTest {
        val vm = SceneChineseReviewViewModel(session, draft, "review", repository { _, _ -> SceneEditReviewResult.Pass })
        vm.onAction(SceneChineseReviewAction.UnitChanged("u2", "需要延后两天。"))
        val effect = async { vm.effects.first() as SceneChineseReviewEffect.OpenSafetyCheck }
        vm.onAction(SceneChineseReviewAction.ConfirmRequested)
        advanceUntilIdle()
        val result = effect.await()
        val returnRoute = HomeDiaryRoute.SceneChineseReview(session, result.confirmedDraft)
        val route = HomeDiaryRoute.SemanticSplit(result.source.text, result.source.sourceType, result.source.contentShape, returnRoute, "accepted-scene")
        val saved = requireNotNull(route.toFormationRecoverySnapshot(0, "项目延期"))
        assertTrue("A confirmed edit must not lose its actual scene editor origin", saved.origin is FormationSourceOrigin.Scene)
        assertEquals(FormationSourceOrigin.Scene(session, result.confirmedDraft), saved.origin)
        assertEquals(result.source, saved.source)
    }

    private fun repository(review: suspend (SceneEditReviewRequest, String) -> SceneEditReviewResult) = object : SceneEditReviewRepository {
        override suspend fun review(request: SceneEditReviewRequest, commandId: String) = review.invoke(request, commandId)
    }
}
