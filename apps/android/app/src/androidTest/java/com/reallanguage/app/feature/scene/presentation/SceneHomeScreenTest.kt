package com.reallanguage.app.feature.scene.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.scene.domain.RecentSceneRecommendation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SceneHomeScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersRecommendationsTemplatesAndDirectDescriptionEntry() {
        val actions = mutableListOf<SceneHomeAction>()
        composeRule.setContent {
            RlTheme {
                SceneHomeScreen(
                    SceneHomeUiState(
                        loadingRecommendations = false,
                        recommendations = listOf(
                            RecentSceneRecommendation("rec-1", "英语 App 宣传", "近期内容", listOf("s1")),
                        ),
                        directDescription = "向同事解释项目延期",
                    ),
                    actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("scene-home-header").assertIsDisplayed()
        composeRule.onNodeWithTag("scene-home-artwork").assertIsDisplayed()
        composeRule.onNodeWithTag("scene-home-recommendations").assertIsDisplayed()
        composeRule.onNodeWithTag("scene-home-templates").assertIsDisplayed()
        composeRule.onNodeWithTag("scene-home-direct-entry", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("scene-home-direct-panel").performClick()

        assertEquals(listOf(SceneHomeAction.DirectDescriptionRequested), actions)
    }
}
