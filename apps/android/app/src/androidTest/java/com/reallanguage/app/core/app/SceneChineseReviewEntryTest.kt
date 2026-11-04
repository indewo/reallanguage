package com.reallanguage.app.core.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.scene.domain.*
import com.reallanguage.app.feature.scene.presentation.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SceneChineseReviewEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val session = SceneQuestionSession(SceneStart.DirectDescription("说明延期两天"), SceneContentType.DIALOGUE,
        listOf(SceneAnswer("fact", "两天")), operationId = "scene-operation")
    private val draft = SceneGeneratedDraft("scene-draft", SceneContentType.DIALOGUE,
        listOf(SceneDraftUnit("u1", SceneSpeaker.USER, "需要延期。", listOf("fact"))))

    @Test fun approvedEditCarriesMatchingSourceAndDraftThroughTheCurrentCallback() {
        val version = mutableStateOf(0)
        val response = CompletableDeferred<SceneEditReviewResult>()
        val captures = mutableListOf<Triple<Int, ConfirmedSource, SceneGeneratedDraft>>()
        lateinit var vm: SceneChineseReviewViewModel
        val factory = factory { SceneChineseReviewViewModel(session, draft, "review", repository { _, _ -> response.await() }).also { vm = it } }
        rule.setContent { RlTheme {
            val callbackVersion = version.value
            SceneChineseReviewEntry(factory, "scene-entry", { source, confirmed -> captures += Triple(callbackVersion, source, confirmed) }, {}, { _, _ -> })
        } }
        rule.runOnIdle {
            vm.onAction(SceneChineseReviewAction.UnitChanged("u1", "需要延后两天。"))
            vm.onAction(SceneChineseReviewAction.ConfirmRequested)
        }
        rule.runOnIdle { version.value = 1 }; rule.waitForIdle()
        response.complete(SceneEditReviewResult.Pass)
        rule.waitUntil(5_000) { captures.isNotEmpty() }
        assertEquals(1, captures.size)
        assertEquals(1, captures.single().first)
        assertEquals("用户：需要延后两天。", captures.single().second.text)
        assertEquals(draft.copy(units = listOf(draft.units.single().copy(textZh = "需要延后两天。"))), captures.single().third)
    }

    @Test fun leavingCancelsThisReviewAndSameEntryRecreatesAUsableViewModel() {
        val visible = mutableStateOf(true)
        var requests = 0
        var cancelled = 0
        var safety = 0
        val created = mutableListOf<SceneChineseReviewViewModel>()
        val factory = factory { SceneChineseReviewViewModel(session, draft, "review", repository { _, _ ->
            requests++
            try { awaitCancellation() } finally { cancelled++ }
        }).also(created::add) }
        rule.setContent { RlTheme {
            if (visible.value) SceneChineseReviewEntry(factory, "same-scene", { _, _ -> safety++ }, {}, { _, _ -> })
        } }
        rule.runOnIdle {
            created.single().onAction(SceneChineseReviewAction.UnitChanged("u1", "需要延后两天。"))
            created.single().onAction(SceneChineseReviewAction.ConfirmRequested)
        }
        rule.waitUntil(5_000) { requests == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        assertEquals(1, cancelled)
        rule.runOnIdle { visible.value = true }; rule.waitForIdle()
        assertEquals(2, created.size)
        assertFalse(created.last().uiState.value.reviewing)
        assertEquals(0, safety)
    }

    @Test fun systemBackReturnsThroughTheReviewViewModelOnlyOnceWithoutFallingThroughParent() {
        var parent = 0
        var returns = 0
        var safety = 0
        val factory = factory { SceneChineseReviewViewModel(session, draft, "review", repository { _, _ -> error("Back does not review") }) }
        rule.setContent { RlTheme {
            BackHandler { parent++ }
            SceneChineseReviewEntry(factory, "scene-back", { _, _ -> safety++ }, { assertEquals(session, it); returns++ }, { _, _ -> })
        } }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        assertEquals(1, returns)
        assertEquals(0, parent)
        assertEquals(0, safety)
    }

    private fun repository(review: suspend (SceneEditReviewRequest, String) -> SceneEditReviewResult) = object : SceneEditReviewRepository {
        override suspend fun review(request: SceneEditReviewRequest, commandId: String) = review.invoke(request, commandId)
    }
    private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
}
