package com.reallanguage.app.feature.learning.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Guards the user-requested writing-first layout. */
class CompleteWritingArtworkReadabilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun useProductionWindow() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test fun silentWritingUsesThePageForWritingInsteadOfDecorationOrRedundantLabels() {
        render(singleState(CompleteWritingPhase.SILENT_SINGLE))

        compose.onNodeWithTag("chapter-writing-workspace-artwork").assertDoesNotExist()
        compose.onNodeWithText("无声书写").assertDoesNotExist()
        compose.onNodeWithText("不自动发音，根据中文键盘写出英文。").assertDoesNotExist()
        compose.onNodeWithText("固定标点（点击输入）").assertDoesNotExist()
        compose.onNodeWithTag("audio-dictation-punctuation").assertDoesNotExist()
        compose.onNodeWithText("根据中文写出当前重点句。").assertIsDisplayed()
        compose.onNodeWithTag("complete-writing-input-single").assertIsDisplayed().assertHeightIsAtLeast(140.dp)
        compose.onNodeWithTag("complete-writing-scroll-content").assertIsDisplayed()
        compose.onNodeWithTag("learning-current-leading-arrow").assertIsDisplayed()
        compose.onNodeWithTag("learning-current-trailing-arrow").assertIsDisplayed()
    }

    @Test fun audibleWritingUsesTheSameWritingFirstLayoutAsSilentWriting() {
        render(singleState(CompleteWritingPhase.AUDIBLE_SINGLE))

        compose.onNodeWithTag("chapter-writing-workspace-artwork").assertDoesNotExist()
        compose.onNodeWithText("有声书写").assertDoesNotExist()
        compose.onNodeWithText("听完整句发音，键盘写出英文。").assertDoesNotExist()
        compose.onNodeWithText("固定标点（点击输入）").assertDoesNotExist()
        compose.onNodeWithTag("audio-dictation-punctuation").assertDoesNotExist()
        compose.onNodeWithText("根据中文写出当前重点句。").assertIsDisplayed()
        compose.onNodeWithTag("complete-writing-input-single").assertIsDisplayed().assertHeightIsAtLeast(140.dp)
        compose.onNodeWithTag("complete-writing-scroll-content").assertIsDisplayed()
        compose.onNodeWithTag("learning-current-leading-arrow").assertIsDisplayed()
        compose.onNodeWithTag("learning-current-trailing-arrow").assertIsDisplayed()
    }

    @Test fun longFinalWritingRemainsReachableInsideTheExpandedWritingPaper() {
        val longChinese = "这是需要完整展示并允许继续向下滚动的重点句。".repeat(4)
        val sentences = (1..4).map {
            FixedLearningSentence("s$it", "A complete English sentence number $it.", "$it $longChinese", true)
        }
        render(CompleteWritingUiState(
            chapter = FixedLearningChapter("c1", 1, sentences),
            lockedTargets = emptyList(),
            phase = CompleteWritingPhase.FINAL_BATCH,
            finalInputs = sentences.associate { it.sentenceId to "" },
        ))

        val scroll = compose.onNodeWithTag("complete-writing-scroll-content")
        repeat(6) {
            scroll.performTouchInput { swipeUp(durationMillis = 220) }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("complete-writing-input-s4").assertIsDisplayed()
        compose.onNodeWithText("提交").assertIsDisplayed()
    }

    private fun render(state: CompleteWritingUiState) {
        compose.setContent { RlTheme { RlPaperBackground { CompleteWritingScreen(state, {}) } } }
        compose.waitForIdle()
    }

    private fun singleState(phase: CompleteWritingPhase) = CompleteWritingUiState(
        chapter = FixedLearningChapter("c1", 1, listOf(
            FixedLearningSentence("s1", "It is a beautiful day today.", "今天是美好的一天。", true),
        )),
        lockedTargets = emptyList(),
        phase = phase,
    )
}
