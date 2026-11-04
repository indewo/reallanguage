package com.reallanguage.app.feature.learning.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class ReadSpeakScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun visibleReturnButtonEmitsBackInsteadOfOpeningAnArchivedStep() {
        val actions = mutableListOf<ReadSpeakAction>()
        val state = ReadSpeakUiState(
            FixedLearningChapter(
                "c1",
                1,
                listOf(FixedLearningSentence("s1", "It's a beautiful day today.", "今天是美好的一天。", true)),
            ),
            "s1",
        )

        composeRule.setContent { RlTheme { ReadSpeakScreen(state, actions::add) } }
        composeRule.onNodeWithText("返回").assertIsDisplayed().performClick()

        assertEquals(listOf(ReadSpeakAction.BackRequested), actions)
    }

    @Test fun savedFeedbackFailureOffersSaveRetryRatherThanAnotherRecording() {
        val actions = mutableListOf<ReadSpeakAction>()
        val state = ReadSpeakUiState(FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true))), "s1", completed = true, progressFailed = true, feedbackZh = "已有反馈")
        composeRule.setContent { RlTheme { ReadSpeakScreen(state, actions::add) } }
        composeRule.onNodeWithText("学习进度未能保存或恢复，请重试；不会重置已保存轮次。").assertIsDisplayed()
        composeRule.onNodeWithText("重试保存").assertIsDisplayed().performClick()
        assert(actions == listOf(ReadSpeakAction.ContinueRequested))
    }

    @Test fun manualFallbackRemainsReachableAfterRecordingFails() {
        val actions = mutableListOf<ReadSpeakAction>()
        val state = ReadSpeakUiState(FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true))), "s1", captureFailed = true)
        composeRule.setContent { RlTheme { ReadSpeakScreen(state, actions::add) } }
        composeRule.onNodeWithTag("read_speak_manual_finished").assertIsDisplayed().performClick()
        assert(actions.contains(ReadSpeakAction.ManualFinished))
    }

    @Test
    fun showsChapterInlineReadingCaptureAndManualFallback() {
        val actions = mutableListOf<ReadSpeakAction>()
        val state = ReadSpeakUiState(
            chapter = FixedLearningChapter(
                "c1", 1,
                listOf(
                    FixedLearningSentence(
                        "s1", "I expressed myself clearly.", "我清楚地表达了自己。", true,
                    ),
                ),
            ),
            currentFocusSentenceId = "s1",
            listening = true,
            highlightedWordCount = 2,
        )
        composeRule.setContent { RlTheme { ReadSpeakScreen(state, actions::add) } }
        composeRule.onNodeWithText("正在录音，滑完后点击“录完了”。").assertIsDisplayed()
        composeRule.onNodeWithText("录完了").assertIsDisplayed()
        composeRule.onNodeWithText("播放").assertIsDisplayed()
        composeRule.onNodeWithTag("read_speak_status").assertIsDisplayed()
        composeRule.onNodeWithTag("read_speak_manual_finished").performClick()
        assert(actions.contains(ReadSpeakAction.ManualFinished))
    }

    @Test fun idleScreenExplainsAutomaticRecordingAndOnlyEnablesFinishAfterDragging() {
        val state = ReadSpeakUiState(
            FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true))),
            "s1",
        )
        composeRule.setContent { RlTheme { ReadSpeakScreen(state) {} } }
        composeRule.onNodeWithText("触摸并沿着英文句子滑动，录音会自动开始。").assertIsDisplayed()
        composeRule.onNodeWithText("读一遍").assertDoesNotExist()
        composeRule.onNodeWithTag("read_speak_manual_finished").assertIsDisplayed()
        composeRule.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("read_speak_manual_finished")))
            .assertIsNotEnabled()
    }

    @Test fun completedManualReadingDoesNotShowTheRemovedNoAudioCopy() {
        val state = ReadSpeakUiState(
            FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "Today is a wonderful day.", "今天是美好的一天。", true))),
            "s1",
            completed = true,
            highlightedWordCount = 5,
            feedbackZh = READ_SPEAK_NO_AUDIO_COMPLETION_COPY,
        )
        composeRule.setContent { RlTheme { ReadSpeakScreen(state) {} } }
        composeRule.onNodeWithText("已读完").assertIsDisplayed()
        composeRule.onAllNodesWithText(READ_SPEAK_NO_AUDIO_COMPLETION_COPY).assertCountEquals(0)
        composeRule.onNodeWithTag("learning-round-hole-strip").assertIsDisplayed()
        val holes = composeRule.onNodeWithTag("learning-round-hole-strip").fetchSemanticsNode().boundsInRoot
        assertEquals("The dedicated source ratio keeps every punched hole circular",
            320f / 1536f, holes.width / holes.height, .02f)
        composeRule.onNodeWithTag("learning-current-trailing-arrow").assertIsDisplayed()
    }
}

