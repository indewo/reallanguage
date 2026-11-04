package com.reallanguage.app.feature.review.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Rule
import org.junit.Test

class ReviewExitDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun unknownCompletedCountDoesNotDisplayAFabricatedZeroOrNegativeCount() {
        composeRule.setContent {
            RlTheme { ReviewExitDialog(ReviewExitUiState("r2", -1)) {} }
        }
        composeRule.onNodeWithText("当前章本次进度不会保存，下次会从章首开始。此前完整完成的回顾章节会保留，当前章不占今天的重点句名额。").assertIsDisplayed()
        composeRule.onNodeWithTag("review-exit-confirm").assertIsDisplayed()
    }

    @Test
    fun explainsSaveBoundaryAndConfirmsExit() {
        val actions = mutableListOf<ReviewExitAction>()
        composeRule.setContent {
            RlTheme {
                ReviewExitDialog(
                    state = ReviewExitUiState("review-2", completedChapterCount = 1),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("review-exit-sheet").assertIsDisplayed()
        composeRule.onNodeWithText("要退出本次回顾吗？").assertIsDisplayed()
        composeRule.onNodeWithTag("review-exit-confirm").assertIsDisplayed().performClick()
        assert(actions == listOf(ReviewExitAction.ConfirmExitRequested))
    }
}
