package com.reallanguage.app.feature.home.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.home.domain.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Actual state must not be disguised as a formal chapter or an invented 1/9 progress. */
class IncompleteLearningFormationStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun formingEntryShowsTheActualStageWithoutInventingAChapterOrLearningProgress() {
        val entry = ordinary.copy(learningId = "source-before-fix", chapterNumber = null,
            savedStepId = "FORMATION:LEARNING_TARGET_SELECTION", savedStepLabelZh = "选择学习目标",
            kind = ResumeLearningKind.FORMATION)
        val actions = mutableListOf<IncompleteLearningAction>()
        render(IncompleteLearningUiState(listOf(entry), loading = false), actions::add)
        compose.onNodeWithText("第 null 章", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("incomplete-learning-progress").assertDoesNotExist()
        compose.onNodeWithText("停在 1 / 9 步").assertDoesNotExist()
        compose.onAllNodesWithText("选择学习目标").onFirst().assertIsDisplayed()
        compose.onNodeWithTag("incomplete-learning-resume-${entry.learningId}").performClick()
        assertEquals(listOf(IncompleteLearningAction.ResumeRequested(entry.learningId)), actions)
    }

    @Test fun unsupportedSavedModuleIsNotConvertedToFirstLearningStep() {
        render(IncompleteLearningUiState(listOf(ordinary.copy(savedStepId = "future-module", savedStepLabelZh = "待恢复")), loading = false))
        compose.onNodeWithTag("incomplete-learning-progress").assertDoesNotExist()
        compose.onNodeWithText("停在 1 / 9 步").assertDoesNotExist()
        compose.onNodeWithText("待恢复", substring = true).assertIsDisplayed()
    }

    @Test fun loadingDoesNotMisrepresentUnknownCountAsZeroConfirmedItems() {
        render(IncompleteLearningUiState(loading = true))
        compose.onNodeWithText("0", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun failedReadDoesNotMisrepresentUnknownCountAsZeroConfirmedItems() {
        render(IncompleteLearningUiState(loading = false, loadFailed = true))
        compose.onNodeWithText("0", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("重试").assertIsDisplayed()
    }

    private fun render(state: IncompleteLearningUiState, onAction: (IncompleteLearningAction) -> Unit = {}) {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.setContent { RlTheme { IncompleteLearningScreen(state, onAction) } }
    }

    private val ordinary = IncompleteLearningEntry("chapter", OrdinarySourceType.DIARY,
        "今天的日记", 1, "LR-06", "整句会说", ResumeLearningKind.ORDINARY)
}
