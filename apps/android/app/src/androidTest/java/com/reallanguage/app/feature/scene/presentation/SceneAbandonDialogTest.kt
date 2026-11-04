package com.reallanguage.app.feature.scene.presentation

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneQuestionSession
import com.reallanguage.app.feature.scene.domain.SceneStart
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SceneAbandonDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun canCancelOrConfirmAbandonment() {
        val actions = mutableListOf<SceneAbandonAction>()
        val session = SceneQuestionSession(
            SceneStart.DirectDescription("项目延期"),
            SceneContentType.DIALOGUE,
            emptyList(),
            operationId = "op",
        )
        composeRule.setContent { RlTheme { SceneAbandonDialog(SceneAbandonUiState(session), actions::add) } }
        composeRule.onNodeWithTag("scene-abandon-cancel").performClick()
        composeRule.onNodeWithTag("scene-abandon-confirm").performClick()
        assertEquals(listOf(SceneAbandonAction.ContinueRequested, SceneAbandonAction.ConfirmRequested), actions)
    }

    @Test fun showsLiveSessionSummary() {
        val session = SceneQuestionSession(
            SceneStart.DirectDescription("面试结束后的复盘"),
            SceneContentType.DIALOGUE,
            listOf(
                com.reallanguage.app.feature.scene.domain.SceneAnswer("q1", "回答一"),
                com.reallanguage.app.feature.scene.domain.SceneAnswer("q2", "回答二"),
            ),
            operationId = "op",
        )
        composeRule.setContent { RlTheme { SceneAbandonDialog(SceneAbandonUiState(session)) {} } }
        composeRule.onNodeWithText("面试结束后的复盘 · 对话 · 已回答 2 个问题").assertIsDisplayed()
    }
}
