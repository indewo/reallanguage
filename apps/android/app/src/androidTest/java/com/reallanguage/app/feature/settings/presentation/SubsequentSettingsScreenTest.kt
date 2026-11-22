package com.reallanguage.app.feature.settings.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SubsequentSettingsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersFourTabsAndProtectsUnsavedReturn() {
        val actions = mutableListOf<SubsequentSettingsAction>()
        val original = InitialSettingsDraft(formalityLevel = 2, basicVocabulary = BasicVocabulary.CET_6)
        val draft = original.copy(formalityLevel = 3)

        composeRule.setContent {
            RlTheme {
                SubsequentSettingsScreen(
                    state = SubsequentSettingsUiState(original = original, draft = draft, loading = false),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("subsequent-settings-navigation").assertIsDisplayed()
        (0..3).forEach { composeRule.onNodeWithTag("subsequent-settings-navigation-$it").assertIsDisplayed() }
        composeRule.onNodeWithTag("subsequent-settings-save").performClick()
        composeRule.onNodeWithTag("editorial-page-back").performClick()
        composeRule.onNodeWithText("保存修改？").assertIsDisplayed()
        assertEquals(listOf(SubsequentSettingsAction.SaveRequested), actions)
        composeRule.onNodeWithText("放弃修改").performClick()

        assertEquals(
            listOf(SubsequentSettingsAction.SaveRequested, SubsequentSettingsAction.ReturnRequested),
            actions,
        )
    }
}
