package com.reallanguage.app.feature.learning.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Rule
import org.junit.Test

class ChapterAbandonScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun showsRemovalScopeAndDispatchesExplicitConfirmation() {
        val actions = mutableListOf<ChapterAbandonAction>()
        val state = ChapterAbandonUiState("chapter-2", "delete-chapter-2")

        composeRule.setContent { RlTheme { ChapterAbandonScreen(state, actions::add) } }

        composeRule.onNodeWithTag("chapter-abandon-sheet").assertIsDisplayed()
        composeRule.onNodeWithText("确认放弃第 2 章？").assertIsDisplayed()
        composeRule.onNodeWithText("本章固定句").assertIsDisplayed()
        composeRule.onNodeWithText("本章不会进入知识本、回顾或统计。").assertIsDisplayed()
        composeRule.onNodeWithTag("chapter-abandon-confirm").assertIsDisplayed().performClick()
        assert(actions == listOf(ChapterAbandonAction.ConfirmAbandonRequested))
    }
}
