package com.reallanguage.app.feature.scene.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneQuestionSession
import com.reallanguage.app.feature.scene.domain.SceneStart
import org.junit.Rule
import org.junit.Test

class SceneGenerationScreenTest {
    @get:Rule val composeRule = androidx.compose.ui.test.junit4.v2.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @org.junit.Before fun productionWindow() {
        composeRule.activityRule.scenario.onActivity {
            com.reallanguage.app.core.app.configureFocusedLearningWindow(it.window)
            it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun rendersGenerationWorkspaceAndLiveProgress() {
        val session = SceneQuestionSession(
            SceneStart.DirectDescription("项目延期"), SceneContentType.DIALOGUE, emptyList(), operationId = "op",
        )
        composeRule.setContent {
            RlTheme { SceneGenerationScreen(SceneGenerationUiState(session, generating = true), {}) }
        }
        composeRule.onNodeWithTag("scene-generation-workspace").assertIsDisplayed()
        composeRule.onNodeWithTag("scene-generation-progress").assertIsDisplayed()
        java.io.File(composeRule.activity.filesDir, "scene-generation-layout.png").outputStream().use {
            composeRule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        listOf("整理场景信息", "编排中文场景", "生成练习内容").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
    }
}
