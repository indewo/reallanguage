package com.reallanguage.app.feature.formation.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.formation.domain.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

/** Isolated UI lifecycle, not a real AI generation result. */
class LearningTargetLoadingFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun expressionClickShowsProgressAndFailureReleasesControls() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        val state = mutableStateOf(LearningTargetUiState(
            chapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "我想表达清楚。", true,
                listOf(EnglishCandidate("e1", "I want to explain more clearly.")))))),
            selectedCandidateIds = mapOf("u1" to "e1"), targets = emptyList(), selectedTargetIds = emptySet(),
            queryableExpressions = listOf(QueryableExpression("q1", "u1", "more clearly")),
        ))
        val requests = mutableListOf<LearningTargetAction.ExpressionRequested>()
        compose.setContent { RlTheme { LearningTargetScreen(state.value, onAction = { action ->
            if (action is LearningTargetAction.ExpressionRequested) {
                requests += action
                state.value = state.value.copy(generating = true, generationFailed = false)
            }
        }) } }
        compose.onNodeWithText("more clearly").performScrollTo().performClick()
        compose.onNodeWithTag("learning-target-generation-loading").assertIsDisplayed()
        compose.onNodeWithText("正在生成学习目标…").assertIsDisplayed()
        compose.onNodeWithText("继续").assertIsNotEnabled()
        val inst = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.takeScreenshot()!!.let { bitmap ->
            java.io.File(inst.targetContext.filesDir, "target-generation-loading.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.runOnIdle { state.value = state.value.copy(generating = false, generationFailed = true) }
        compose.onNodeWithTag("learning-target-generation-loading").assertDoesNotExist()
        compose.onNodeWithText("学习目标生成失败").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("继续").assertIsEnabled()
        compose.onNodeWithText("more clearly").performScrollTo().performClick()
        assertEquals(2, requests.size)
        compose.onNodeWithTag("learning-target-generation-loading").assertIsDisplayed()
    }
}
