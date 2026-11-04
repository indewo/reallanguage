package com.reallanguage.app.feature.knowledge.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PatternRemovalDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun showsRemovalBoundaryAndDispatchesBothRealChoices() {
        val actions = mutableListOf<PatternRemovalAction>()
        val state = PatternRemovalUiState("p1", "command-p1")

        composeRule.setContent {
            RlTheme {
                PatternRemovalDialog(state, "work up the courage to + 动词", actions::add)
            }
        }

        composeRule.onNodeWithTag("pattern-removal-sheet").assertIsDisplayed()
        composeRule.onNodeWithText("移出后将删除此句式的状态、代表场景、例句和未来任务。").assertIsDisplayed()
        composeRule.onNodeWithText("原来已经固定的句子不会被修改。").assertIsDisplayed()
        composeRule.onNodeWithTag("pattern-removal-cancel").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("pattern-removal-confirm").assertIsDisplayed().performClick()
        assertEquals(
            listOf(PatternRemovalAction.CancelRequested, PatternRemovalAction.ConfirmRequested),
            actions,
        )
    }
}
