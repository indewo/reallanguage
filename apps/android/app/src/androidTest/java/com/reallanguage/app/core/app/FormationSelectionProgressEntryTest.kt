package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.formation.presentation.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real selection screens with no persistence or provider work hidden in the observer. */
class FormationSelectionProgressEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val units = listOf(SemanticUnit("u1", 0, "我喝茶。"), SemanticUnit("u2", 1, "她工作。"))
    private val chapters = listOf(EnglishChapterDraft("c1", listOf(
        EnglishSentenceDraft("u1", units[0].textZh, true, listOf(
            EnglishCandidate("e1a", "I drink tea."), EnglishCandidate("e1b", "I have tea.", listOf("VOCABULARY:tea")))),
        EnglishSentenceDraft("u2", units[1].textZh, true, listOf(
            EnglishCandidate("e2a", "She works."), EnglishCandidate("e2b", "She is working."))),
    )))

    @Before fun landscape() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(5_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun clearingEveryFocusChoiceStillReportsTheExactUnsavedSelectionWithoutConfirming() {
        val states = mutableListOf<FocusSelectionUiState>()
        var confirms = 0
        var created = 0
        val factory = factory { created++; FocusSelectionViewModel(units, setOf("u1")) }
        rule.setContent { RlTheme {
            FocusSelectionProgressEntry(factory, "focus", { confirms++ }, {}, states::add)
        } }
        rule.onNodeWithTag("focus_sentence_u1").performClick(); rule.waitForIdle()
        assertTrue(states.any { it.selectedUnitIds.isEmpty() })
        assertEquals(units, states.last().units)
        assertFalse(states.last().confirmEnabled)
        assertEquals(0, confirms)
        assertEquals(1, created)
    }

    @Test fun partialEnglishChoiceIsObservedWithAllCandidateRefsBeforeContinueIsEnabled() {
        val states = mutableListOf<EnglishConfirmationUiState>()
        var nexts = 0
        val factory = factory { EnglishConfirmationViewModel(chapters) }
        rule.setContent { RlTheme {
            EnglishConfirmationProgressEntry(factory, "english", { _, _ -> nexts++ }, {}, states::add)
        } }
        rule.onNodeWithText("I have tea.").performClick(); rule.waitForIdle()
        assertTrue(states.any { it.selectedCandidateIds == mapOf("u1" to "e1b") })
        assertFalse(states.last().continueEnabled)
        assertEquals(chapters, states.last().chapters)
        assertEquals(listOf("VOCABULARY:tea"), states.last().chapters.single().sentences.first().candidates.last().reusedKnowledgeRefs)
        assertEquals(0, nexts)
    }

    @Test fun bothSystemBackActionsReportCurrentStateBeforeTheirOwnReturnWithoutFallingThroughParent() {
        val screen = mutableStateOf(0)
        var parentBack = 0
        var forwards = 0
        val returns = mutableListOf<String>()
        val focusStates = mutableListOf<FocusSelectionUiState>()
        val englishStates = mutableListOf<EnglishConfirmationUiState>()
        val focusFactory = factory { FocusSelectionViewModel(units, setOf("u1")) }
        val englishFactory = factory { EnglishConfirmationViewModel(chapters, mapOf("u1" to "e1b")) }
        rule.setContent { RlTheme {
            BackHandler { parentBack++ }
            if (screen.value == 0) FocusSelectionProgressEntry(focusFactory, "focus", { forwards++ }, {
                assertEquals(setOf("u1"), focusStates.last().selectedUnitIds)
                returns += "focus"; screen.value = 1
            }, focusStates::add)
            else EnglishConfirmationProgressEntry(englishFactory, "english", { _, _ -> forwards++ }, {
                assertEquals(mapOf("u1" to "e1b"), englishStates.last().selectedCandidateIds)
                returns += "english"
            }, englishStates::add)
        } }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        assertEquals(listOf("focus"), returns)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        assertEquals(listOf("focus", "english"), returns)
        assertEquals(0, parentBack)
        assertEquals(0, forwards)
    }

    @Test fun sameKeysAfterLeavingRecreateBothSelectionViewModelsInsteadOfRetainingSubmittedState() {
        val visible = mutableStateOf(true)
        val screen = mutableStateOf(0)
        var focusCreated = 0
        var englishCreated = 0
        var forwards = 0
        val focusFactory = factory { focusCreated++; FocusSelectionViewModel(units, setOf("u1")) }
        val englishFactory = factory { englishCreated++; EnglishConfirmationViewModel(chapters, mapOf("u1" to "e1b", "u2" to "e2a")) }
        rule.setContent { RlTheme {
            if (visible.value) {
                if (screen.value == 0) FocusSelectionProgressEntry(focusFactory, "same-focus", { forwards++ }, {})
                else EnglishConfirmationProgressEntry(englishFactory, "same-english", { _, _ -> forwards++ }, {})
            }
        } }
        rule.onNodeWithTag("focus_confirm").performClick(); rule.waitForIdle()
        reenter(visible)
        assertEquals(2, focusCreated)
        rule.onNodeWithTag("focus_confirm").assertIsEnabled()
        rule.runOnIdle { screen.value = 1 }; rule.waitForIdle()
        rule.onNodeWithText("继续选择学习目标").performClick(); rule.waitForIdle()
        reenter(visible)
        assertEquals(2, englishCreated)
        rule.onNodeWithText("继续选择学习目标").assertIsEnabled()
        assertEquals(2, forwards)
    }

    private fun reenter(visible: androidx.compose.runtime.MutableState<Boolean>) {
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        rule.runOnIdle { visible.value = true }; rule.waitForIdle()
    }
    private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
}
