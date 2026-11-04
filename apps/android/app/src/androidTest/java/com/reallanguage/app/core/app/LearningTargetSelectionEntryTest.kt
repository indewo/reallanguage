package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.formation.presentation.*
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual entry/Compose lifecycle with isolated generators, never a real AI acceptance claim. */
class LearningTargetSelectionEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val sentences = listOf(
        EnglishSentenceDraft("u1", "我喝茶。", true, listOf(EnglishCandidate("e1", "I drink tea."))),
        EnglishSentenceDraft("u2", "她工作。", true, listOf(EnglishCandidate("e2", "She works."))),
    )
    private val chapters = listOf(EnglishChapterDraft("c1", sentences))
    private val selected = mapOf("u1" to "e1", "u2" to "e2")
    private val savedTargets = listOf(LearningTarget("work", "u2", "works", "工作", LearningTargetType.WORD))

    @Before fun landscape() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(5_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun leavingCancelsTheExactPendingWordRequestWithoutSubmittingOrReturning() {
        val visible = mutableStateOf(true)
        var calls = 0
        var cancellations = 0
        var navigations = 0
        val factory = factory {
            viewModel(generator = LearningTargetGenerator { _, _ ->
                calls++
                try { awaitCancellation() } finally { cancellations++ }
            })
        }
        rule.setContent { RlTheme {
            if (visible.value) LearningTargetSelectionEntry(factory, "isolated-entry", { _, _ -> navigations++ }, { navigations++ })
        } }
        tapTea()
        rule.waitUntil(5_000) { calls == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        assertEquals(1, cancellations)
        assertEquals(0, navigations)
    }

    @Test fun reopeningSameEntryCreatesFreshViewModelFromSavedLastSentenceAndSelection() {
        val visible = mutableStateOf(true)
        var created = 0
        val results = mutableListOf<Set<String>>()
        val factory = factory { created++; viewModel(index = 1) }
        rule.setContent { RlTheme {
            if (visible.value) LearningTargetSelectionEntry(factory, "same-entry", { chosen, _ -> results += chosen }, {})
        } }
        rule.onNodeWithText("She works.").assertIsDisplayed()
        rule.onNodeWithText("继续").performClick()
        rule.waitUntil(5_000) { results.size == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        rule.runOnIdle { visible.value = true }; rule.waitForIdle()
        assertEquals(2, created)
        rule.onNodeWithText("She works.").assertIsDisplayed()
        rule.onNodeWithText("继续").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { results.size == 2 }
        assertEquals(listOf(setOf("work"), setOf("work")), results)
    }

    @Test fun systemBackUsesPreviousSentenceThenEnglishReturnNeverParentNavigationOrFix() {
        val visible = mutableStateOf(true)
        var parentBack = 0
        var returns = 0
        var fixes = 0
        val factory = factory { viewModel(index = 1) }
        rule.setContent { RlTheme {
            BackHandler { parentBack++ }
            if (visible.value) LearningTargetSelectionEntry(factory, "back-entry", { _, _ -> fixes++ }, { returns++; visible.value = false })
        } }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        rule.onNodeWithText("I drink tea.").assertIsDisplayed()
        assertEquals(0, returns)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        assertEquals(1, returns)
        assertEquals(0, fixes)
        assertEquals(0, parentBack)
    }

    @Test fun stateObserverReceivesNewActualTargetDeselectionAndCurrentSentenceBeforeFinalCallback() {
        val observed = mutableListOf<LearningTargetUiState>()
        var checkedFinal = false
        val tea = LearningTarget("tea", "u1", "tea", "茶", LearningTargetType.WORD)
        val factory = factory { viewModel(generator = LearningTargetGenerator { _, _ -> LearningTargetGenerationResult.Added(tea) }) }
        rule.setContent { RlTheme {
            LearningTargetSelectionEntry(factory, "state-entry", { chosen, targets ->
                assertEquals(targets, observed.last().targets)
                assertEquals(chosen, observed.last().selectedTargetIds)
                assertEquals(1, observed.last().focusSentenceIndex)
                checkedFinal = true
            }, {}, observed::add)
        } }
        tapTea()
        rule.waitForIdle()
        assertTrue(observed.any { tea in it.targets && "tea" in it.selectedTargetIds })
        rule.onNodeWithText("全部取消").performClick(); rule.waitForIdle()
        assertTrue(observed.last().selectedTargetIds.isEmpty())
        rule.onNodeWithText("继续").performClick(); rule.waitForIdle()
        assertEquals(1, observed.last().focusSentenceIndex)
        rule.onNodeWithText("继续").performClick(); rule.waitForIdle()
        assertTrue(checkedFinal)
    }

    @Test fun unavailablePositionShowsHonestExistingFailureLayoutAndOnlyEnglishReturn() {
        var returns = 0
        var fixes = 0
        val factory = factory { viewModel(index = 2) }
        rule.setContent { RlTheme {
            LearningTargetSelectionEntry(factory, "unavailable-entry", { _, _ -> fixes++ }, { returns++ })
        } }
        rule.onNodeWithText("学习目标位置恢复失败").assertIsDisplayed()
        rule.onNodeWithText("已固定内容不受影响。").assertDoesNotExist()
        rule.onNodeWithText("返回章节").assertDoesNotExist()
        rule.onNodeWithText("重试").assertDoesNotExist()
        rule.onNodeWithText("继续").assertDoesNotExist()
        rule.onNodeWithText("返回英语表达").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(1, returns)
        assertEquals(0, fixes)
    }

    private fun tapTea() {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = rule.onNodeWithText("I drink tea.")
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        node.performTouchInput { click(layouts.single().getBoundingBox(8).center) }
        rule.waitForIdle()
    }

    private fun viewModel(index: Int = 0, generator: LearningTargetGenerator = LearningTargetGenerator { _, _ -> LearningTargetGenerationResult.Failed }) =
        LearningTargetViewModel(chapters, selected, savedTargets, emptyList(), generator,
            initialSelectedTargetIds = setOf("work"), initialSentenceIndex = index)

    private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
}
