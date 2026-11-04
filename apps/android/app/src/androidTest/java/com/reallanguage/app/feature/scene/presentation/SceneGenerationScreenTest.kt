package com.reallanguage.app.feature.scene.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneQuestionSession
import com.reallanguage.app.feature.scene.domain.SceneStart
import org.junit.Rule
import org.junit.Test

class SceneGenerationScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun rendersGenerationWorkspaceAndLiveProgress() {
        val session = SceneQuestionSession(
            SceneStart.DirectDescription("项目延期"), SceneContentType.DIALOGUE, emptyList(), operationId = "op",
        )
        composeRule.setContent {
            RlTheme { SceneGenerationScreen(SceneGenerationUiState(session, generating = true), {}) }
        }
        composeRule.onNodeWithTag("scene-generation-workspace").assertIsDisplayed()
        composeRule.onNodeWithTag("scene-generation-progress").assertIsDisplayed()
    }
}
