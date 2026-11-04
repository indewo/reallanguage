package com.reallanguage.app.feature.scene.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneStart
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SceneTypeScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun selectsArticleAndContinues() {
        val actions = mutableListOf<SceneTypeAction>()
        composeRule.setContent {
            RlTheme {
                SceneTypeScreen(
                    SceneTypeUiState(
                        start = SceneStart.DirectDescription("向同事解释项目延期"),
                        selectedType = SceneContentType.DIALOGUE,
                    ),
                    actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("scene-type-header").assertIsDisplayed()
        composeRule.onNodeWithText("之后会通过几个问题补全细节").assertDoesNotExist()
        composeRule.onNodeWithTag("scene-type-dialogue").assertIsDisplayed()
        composeRule.onNodeWithTag("scene-type-article").performClick()
        composeRule.onNodeWithTag("scene-type-continue").performClick()

        assertEquals(
            listOf(
                SceneTypeAction.TypeSelected(SceneContentType.ARTICLE),
                SceneTypeAction.ContinueRequested,
            ),
            actions,
        )
    }
}
