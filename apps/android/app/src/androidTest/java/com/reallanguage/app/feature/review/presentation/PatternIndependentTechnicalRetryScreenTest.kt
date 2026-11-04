package com.reallanguage.app.feature.review.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeExample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Controlled production-screen states and physical touch only: no audio, HTTP, receipt or AI-quality claim. */
class PatternIndependentTechnicalRetryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val pattern = LockedSpeakingPattern("pattern", "sentence", "used to + verb", "过去习惯")
    private val example = PatternPracticeExample("example", "过去的习惯", "What changed?",
        "I used to walk.", "我以前常走路。")
    private val actions = mutableListOf<PatternIndependentAction>()

    @Test fun pendingDeliveryExplainsRetainedAudioAndOnlyExplicitRetryOrReturn() {
        show(state().copy(failed = true, technicalRetryPending = true))
        message(R.string.learning_audio_retry_pending)
        compose.onNodeWithText("本次处理失败，未产生有效结果。").assertDoesNotExist()
        compose.onNodeWithText(string(R.string.full_sentence_speaking_retry_blocked)).assertDoesNotExist()
        assertNoEnabledAction("开始回应", "说完了", "继续", string(R.string.full_sentence_speaking_new_recording))
        assertTrue(actions.isEmpty())
        touch("重试")
        touch("返回回顾章节")
        assertEquals(listOf(PatternIndependentAction.RetryRequested, PatternIndependentAction.BackRequested), actions)
        assertTargetRemainsHidden()
    }

    @Test fun terminalDeliveryDisallowsRetryAndRequiresExplicitNewRecording() {
        show(state().copy(failed = true, technicalRetryBlocked = true))
        message(R.string.full_sentence_speaking_retry_blocked)
        compose.onNodeWithText(string(R.string.learning_audio_retry_pending)).assertDoesNotExist()
        assertNoEnabledAction("重试", "继续", "说完了")
        assertTrue(actions.isEmpty())
        touch(string(R.string.full_sentence_speaking_new_recording))
        touch("返回回顾章节")
        assertEquals(listOf(PatternIndependentAction.StartRequested, PatternIndependentAction.BackRequested), actions)
        assertTargetRemainsHidden()
    }

    @Test fun submittedRetryIsBusyAndCannotDispatchAnotherCaptureOrEvaluation() {
        show(state().copy(evaluating = true, technicalRetryPending = true))
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
        assertNoEnabledAction("重试", "开始回应", "说完了", "继续", string(R.string.full_sentence_speaking_new_recording))
        assertTrue("Busy must not dispatch a fresh recording or another evaluation", actions.isEmpty())
        touch("返回回顾章节")
        assertEquals(listOf(PatternIndependentAction.BackRequested), actions)
        assertTargetRemainsHidden()
    }

    private fun state() = PatternIndependentUiState(pattern, example = example, loading = false)

    private fun show(state: PatternIndependentUiState) {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.activityRule.scenario.onActivity { configureFocusedLearningWindow(it.window) }
        compose.setContent { RlTheme { RlPaperBackground {
            PatternIndependentScreen(state = state, onAction = actions::add)
        } } }
        compose.waitForIdle()
    }

    private fun message(resource: Int) {
        compose.onNodeWithText(string(resource)).performScrollTo().assertIsDisplayed()
    }

    private fun touch(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performScrollTo().assertIsDisplayed().assertIsEnabled()
            .performTouchInput { click(center) }
    }

    /** An unavailable action may be absent or disabled; never weaken it to mere semantic presence. */
    private fun assertNoEnabledAction(vararg labels: String) {
        for (label in labels) {
            val matcher = hasText(label) and hasClickAction()
            val matches = compose.onAllNodes(matcher).fetchSemanticsNodes()
            for (index in matches.indices) {
                compose.onAllNodes(matcher)[index].performScrollTo().assertIsNotEnabled()
                    .performTouchInput { click(center) }
            }
        }
    }

    private fun assertTargetRemainsHidden() {
        compose.onNodeWithText(pattern.structure).assertDoesNotExist()
        compose.onNodeWithText(example.targetResponseEn).assertDoesNotExist()
        compose.onNodeWithText(example.targetResponseZh).assertDoesNotExist()
    }

    private fun string(resource: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)
}
