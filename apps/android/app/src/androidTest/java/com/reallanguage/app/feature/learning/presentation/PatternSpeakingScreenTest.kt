package com.reallanguage.app.feature.learning.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeExample
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class PatternSpeakingScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun useProductionLandscapeOrientation() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitUntil(5_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test fun savedPracticeFailureShowsTargetsAndOffersOnlyProgressRetry() {
        val actions = mutableListOf<PatternSpeakingAction>()
        val state = PatternSpeakingUiState(
            FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "Original sentence.", "原句。", true))),
            "s1", listOf(LockedSpeakingPattern("p1", "s1", "structure", "说明")),
            stage = PatternSpeakingStage.RESPONDING,
            examples = listOf(PatternPracticeExample("e1", "具体场景", "Question?", "Visible target answer.", "固定回答中文。")),
            progressFailed = true,
        )
        composeRule.setContent { RlTheme { PatternSpeakingScreen(state, actions::add) } }
        composeRule.onNodeWithText("Visible target answer.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("固定回答中文。").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("重试保存").assertIsDisplayed().performClick()
        assertEquals(listOf(PatternSpeakingAction.RetryProgressRequested), actions)
    }

    @Test
    fun recordingLastSceneKeepsTheRealSkipActionAvailable() {
        val actions = mutableListOf<PatternSpeakingAction>()
        val state = PatternSpeakingUiState(
            FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "Original sentence.", "原句。", true))),
            "s1", listOf(LockedSpeakingPattern("p1", "s1", "structure", "说明")),
            stage = PatternSpeakingStage.RESPONDING,
            examples = listOf(PatternPracticeExample("e1", "最后一个场景", "Question?", "Visible target answer.", "固定回答中文。")),
            recording = true,
        )
        composeRule.setContent { RlTheme { PatternSpeakingScreen(state, actions::add) } }
        composeRule.onNodeWithText("正在收音").assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(com.reallanguage.app.R.string.pattern_speaking_skip_scene))
            .assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf(PatternSpeakingAction.SkipSceneRequested), actions)
    }

    @Test
    fun showsSceneResponseWorkspaceAndDispatchesFinish() {
        val actions = mutableListOf<PatternSpeakingAction>()
        val examples = listOf(
            PatternPracticeExample("e1", "场景一", "Question one?", "Answer one.", "回答一。"),
            PatternPracticeExample(
                "e2",
                "你一直想向主管提出一个建议，今天终于准备开口。",
                "What did you finally decide to do?",
                "I worked up the courage to share my suggestion.",
                "我鼓起勇气分享了自己的建议。",
            ),
            PatternPracticeExample("e3", "场景三", "Question three?", "Answer three.", "回答三。"),
        )
        val state = PatternSpeakingUiState(
            chapter = FixedLearningChapter(
                "c1",
                1,
                listOf(
                    FixedLearningSentence(
                        "s1",
                        "But in the end, I worked up the courage to explain my idea clearly.",
                        "但最后还是鼓起勇气把想法讲清楚了。",
                        true,
                    ),
                ),
            ),
            currentFocusSentenceId = "s1",
            lockedPatterns = listOf(
                LockedSpeakingPattern(
                    "p1",
                    "s1",
                    "work up the courage to + 动词",
                    "鼓起勇气做某事",
                ),
            ),
            stage = PatternSpeakingStage.RESPONDING,
            examples = examples,
            responseIndex = 1,
            recording = true,
            transcript = "I worked up the courage to share my suggestion.",
        )

        composeRule.setContent { RlTheme { PatternSpeakingScreen(state, actions::add) } }

        composeRule.onNodeWithTag("pattern-speaking-workspace").assertIsDisplayed()
        composeRule.onNodeWithTag("pattern-speaking-scene-progress").assertIsDisplayed()
        composeRule.onNodeWithText("What did you finally decide to do?").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("正在收音").assertIsDisplayed()
        composeRule.onNodeWithText("回应完毕").assertIsDisplayed().performClick()
        assert(actions == listOf(PatternSpeakingAction.FinishResponseRequested))
    }
}
