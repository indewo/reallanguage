package com.reallanguage.app.feature.knowledge.presentation

import androidx.compose.ui.test.assertIsDisplayed
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.PatternExample
import com.reallanguage.app.feature.knowledge.domain.PatternRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class PatternDetailScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun useProductionLandscapeWindow() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
    }

    @Test
    fun expandedPatternShowsRealExamplesCountsAndWorkingActions() {
        val actions = mutableListOf<PatternBookAction>()
        val detail = PatternRecord(
            patternId = "p1",
            structureEn = "for the first time",
            functionZh = "表示第一次经历",
            writingStatus = KnowledgeStatus.MASTERED,
            speakingStatus = KnowledgeStatus.LEARNING,
            consecutiveSuccessCount = 2,
            occurrenceCount = 5,
            representativeScene = "第一次独立做某件事",
            examples = listOf(
                PatternExample("I cooked alone for the first time.", "我第一次独自做饭。", "example-1"),
                PatternExample("She traveled alone for the first time.", "她第一次独自旅行。", "example-2"),
            ),
            writingConsecutiveSuccessCount = 2,
            speakingConsecutiveSuccessCount = 1,
        )
        val state = PatternBookUiState(listOf(detail), "p1", loading = false)

        composeRule.setContent { RlTheme { PatternBookScreen(state, actions::add) } }

        composeRule.onNodeWithTag("pattern-detail-sheet").assertIsDisplayed()
        composeRule.onNodeWithText("典型例句").assertIsDisplayed()
        composeRule.onNodeWithText("I cooked alone for the first time.").assertIsDisplayed()
        composeRule.onNodeWithText("连续会写 2 次").assertIsDisplayed()
        composeRule.onNodeWithText("连续会说 1 次").assertIsDisplayed()
        composeRule.onNodeWithTag("pattern-example-p1-0").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("pattern-detail-collapse").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("pattern-detail-remove").assertIsDisplayed().performClick()
        assertEquals(
            listOf(
                PatternBookAction.ExamplePronunciationRequested("p1", 0),
                PatternBookAction.CollapseRequested,
                PatternBookAction.RemoveRequested,
            ),
            actions,
        )
    }
}
