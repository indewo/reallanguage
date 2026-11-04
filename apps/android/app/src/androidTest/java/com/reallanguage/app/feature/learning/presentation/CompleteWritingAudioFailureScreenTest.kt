package com.reallanguage.app.feature.learning.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CompleteWritingAudioFailureScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun audioFailureIsVisibleAndRetryableWithoutRevealingTheAnswerOrClaimingAResult() {
        val answer = "I write every day."
        val actions = mutableListOf<CompleteWritingAction>()
        val state = CompleteWritingUiState(
            FixedLearningChapter("audio-failure", 1, listOf(FixedLearningSentence("s1", answer, "我每天写字。", true))),
            emptyList(), audioPlaying = false, audioFailed = true,
        )
        rule.setContent { RlTheme { CompleteWritingScreen(state, actions::add) } }
        rule.onNodeWithText(rule.activity.getString(R.string.learning_audio_failed)).assertIsDisplayed()
        rule.onAllNodesWithText(answer).assertCountEquals(0)
        rule.onNodeWithText(rule.activity.getString(R.string.learning_pronounce)).performClick()
        assertEquals(listOf(CompleteWritingAction.AudioToggled), actions)
    }
}
