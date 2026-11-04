package com.reallanguage.app.feature.learning.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import org.junit.Rule
import org.junit.Test

class LearningExitDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun showsThreeExitChoicesAndKeepsContinueFunctional() {
        val actions = mutableListOf<LearningExitAction>()
        composeRule.setContent {
            RlTheme {
                LearningExitDialog(
                    state = LearningExitUiState(
                        checkpoint = OrdinaryLearningCheckpoint("c1", "LR-09", "round-2"),
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("learning-exit-continue").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("learning-exit-pause").assertIsDisplayed()
        composeRule.onNodeWithTag("learning-exit-abandon").assertIsDisplayed()
        assert(actions == listOf(LearningExitAction.ContinueLearningRequested))
    }
}
