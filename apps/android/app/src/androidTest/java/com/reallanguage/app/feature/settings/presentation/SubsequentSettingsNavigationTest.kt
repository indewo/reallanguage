package com.reallanguage.app.feature.settings.presentation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SubsequentSettingsNavigationTest {
    @get:Rule val composeRule = createComposeRule()
    private val draft = InitialSettingsDraft(formalityLevel = 3, slangEnabled = false,
        basicVocabulary = BasicVocabulary.CET_6, voiceEnabled = true, autoPlayEnabled = false,
        speechSpeedLevel = 3)
    private fun ready() = SubsequentSettingsUiState(original = draft, draft = draft, loading = false)

    @Test fun sidebarClickScrollsToActualGroupWithoutSavingOrMovingFooter() {
        val actions = mutableListOf<SubsequentSettingsAction>()
        composeRule.setContent { RlTheme { SubsequentSettingsScreen(ready(), actions::add) } }
        val footerBefore = composeRule.onNodeWithTag("subsequent-settings-return").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("subsequent-settings-navigation-1").performClick()
        // Vocabulary choices are now one shared pool selection; this section is
        // expression style, not the obsolete second vocabulary-preference axis.
        composeRule.onNodeWithText("②  表达风格").assertIsDisplayed()
        val scrollNode = composeRule.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("subsequent-settings-group")))
            .fetchSemanticsNode()
        val range = scrollNode.config[SemanticsProperties.VerticalScrollAxisRange]
        val groups = composeRule.onAllNodesWithTag("subsequent-settings-group", true).fetchSemanticsNodes()
        assertTrue("Group 1 must remain selected after its actual header is reached; scroll=${range.value()}/${range.maxValue()}, " +
            "viewport=${scrollNode.boundsInRoot}, groups=${groups.map { it.boundsInRoot }}",
            composeRule.onNodeWithTag("subsequent-settings-navigation-1").fetchSemanticsNode().config[SemanticsProperties.Selected])
        composeRule.onNodeWithTag("subsequent-settings-navigation-0").assertIsNotSelected()
        composeRule.onNodeWithTag("subsequent-settings-navigation-2").performClick()
        composeRule.onNodeWithText("③  语音设置").assertIsDisplayed()
        composeRule.onNodeWithTag("subsequent-settings-navigation-2").assertIsSelected()
        composeRule.onNodeWithTag("subsequent-settings-navigation-0").performClick()
        composeRule.onNodeWithText("①  表达基础").assertIsDisplayed()
        composeRule.onNodeWithTag("subsequent-settings-navigation-0").assertIsSelected()
        assertEquals(footerBefore, composeRule.onNodeWithTag("subsequent-settings-return").fetchSemanticsNode().boundsInRoot)
        assertTrue("Navigation must not mutate a draft or submit settings", actions.isEmpty())
    }

    @Test fun selectionTracksManualContentScrollInsteadOfTheLastClickedCard() {
        composeRule.setContent { RlTheme { SubsequentSettingsScreen(ready(), {}) } }
        composeRule.onNodeWithTag("subsequent-settings-navigation-1").performClick()
        composeRule.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("subsequent-settings-group")))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100_000f) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("subsequent-settings-navigation-0").assertIsSelected()
        composeRule.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("subsequent-settings-group")))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        composeRule.waitForIdle()
        // In production landscape the last group can exceed the viewport. Its final
        // real field, not a non-sticky heading, proves that scrolling reached the end.
        composeRule.onNodeWithText("默认使用美式英语声音").assertIsDisplayed()
        composeRule.onNodeWithTag("subsequent-settings-navigation-2").assertIsSelected()
        composeRule.onNodeWithTag("subsequent-settings-navigation-0").assertIsNotSelected()
        composeRule.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("subsequent-settings-group")))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100_000f) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("①  表达基础").assertIsDisplayed()
        composeRule.onNodeWithTag("subsequent-settings-navigation-0").assertIsSelected()
    }

    @Test fun groupsWithoutLoadedContentCannotPretendToNavigate() {
        val current = mutableStateOf(SubsequentSettingsUiState())
        composeRule.setContent { RlTheme { SubsequentSettingsScreen(current.value, {}) } }
        repeat(3) { composeRule.onNodeWithTag("subsequent-settings-navigation-$it").assertIsNotEnabled() }
        composeRule.runOnIdle { current.value = ready().copy(saving = true) }
        repeat(3) { composeRule.onNodeWithTag("subsequent-settings-navigation-$it").assertIsNotEnabled() }
    }
}
