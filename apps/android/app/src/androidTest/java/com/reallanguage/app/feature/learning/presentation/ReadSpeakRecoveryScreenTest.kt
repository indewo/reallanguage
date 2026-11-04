package com.reallanguage.app.feature.learning.presentation

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.ReadSpeakCompletionSignal
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackRecovery
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackResult
import com.reallanguage.app.feature.learning.domain.ReadSpeakRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class ReadSpeakRecoveryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun focusedLearningWindow() { compose.activityRule.scenario.onActivity {
        it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        configureFocusedLearningWindow(it.window)
    } }
    private fun state(busy: Boolean = false) = ReadSpeakUiState(FixedLearningChapter("isolated-lr03", 1,
        listOf(FixedLearningSentence("isolated-sentence", "I drink green tea.", "我喝绿茶。", true))),
        "isolated-sentence", captureFailed = true, pendingFeedbackRetry = true, submitting = busy)

    @Test fun originalRequestRecoveryIsNotReportedAsMicrophoneFailure() {
        val actions = mutableListOf<ReadSpeakAction>()
        compose.setContent { RlTheme { ReadSpeakScreen(state(), actions::add) } }
        compose.waitForIdle()
        val name = InstrumentationRegistry.getArguments().getString("readRecoveryCapture")
        if (name != null) {
            require(name.matches(Regex("24-read-recovery-[a-z-]+\\.png")))
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.waitForIdle(200, 5_000)
            val image = automation.takeScreenshot()
            try { File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name).outputStream().use {
                check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            } } finally { image.recycle() }
        }
        compose.onNodeWithText("上次请求尚未取得结果。录音已保留，重试将继续同一次请求。").assertIsDisplayed()
        compose.onNodeWithText("麦克风权限或识别未完成，本步骤仍保留。").assertDoesNotExist()
        compose.onNodeWithText("重试").assertIsEnabled().performClick()
        assertEquals(listOf(ReadSpeakAction.RetryRequested), actions)
    }

    @Test fun pendingRequestDoesNotAllowRepeatedRetryWhileSubmitting() {
        val actions = mutableListOf<ReadSpeakAction>()
        compose.setContent { RlTheme { ReadSpeakScreen(state(true), actions::add) } }
        compose.onNodeWithText("重试").assertIsNotEnabled()
        // The tag belongs to the decorative wrapper; verify the real Button's
        // enabled semantics and prove that attempting the action dispatches nothing.
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("read_speak_manual_finished")),
            useUnmergedTree = true).assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(
            com.reallanguage.app.R.string.read_speak_processing)).assertIsDisplayed()
        assertEquals(emptyList<ReadSpeakAction>(), actions)
    }

    @Test fun completedTrainingShowsCompletionInsteadOfAnotherReadingInstruction() {
        compose.setContent { RlTheme { ReadSpeakScreen(state().copy(completed = true, captureFailed = false,
            pendingFeedbackRetry = false, feedbackZh = "已保存的隔离反馈")) {} } }
        compose.onNodeWithText("已读完").assertIsDisplayed()
        compose.onNodeWithText("沿着英文句子滑动并同时朗读。").assertDoesNotExist()
        compose.onNodeWithText("进入手写抄写").assertIsEnabled()
    }

    @Test fun restoredTerminalRequestExplainsTheBlockAndExplicitlyStartsANewRecording() {
        val actions = mutableListOf<ReadSpeakAction>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = object : ReadSpeakRepository {
            override val requiresFeedbackRecovery = true
            override suspend fun restoreFeedback() = ReadSpeakFeedbackRecovery.Blocked
            override suspend fun feedback(sentenceId: String, completionSignal: ReadSpeakCompletionSignal): ReadSpeakFeedbackResult =
                error("This isolated display test must not submit feedback")
        }
        compose.setContent {
            val initial = remember { state() }
            val model = remember { ReadSpeakViewModel(initial.chapter, initial.currentFocusSentenceId, repository) }
            val owner = remember { ViewModelStore().apply { put("isolated-blocked-read", model) } }
            DisposableEffect(owner) { onDispose { owner.clear() } }
            val restored by model.uiState.collectAsState()
            RlTheme { ReadSpeakScreen(restored, actions::add) }
        }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.full_sentence_speaking_retry_blocked)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.learning_audio_retry_pending)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.read_speak_retry)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.full_sentence_speaking_new_recording))
            .assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf(ReadSpeakAction.StartRequested), actions)
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("read_speak_manual_finished")),
            useUnmergedTree = true).assertIsEnabled()
    }
}
