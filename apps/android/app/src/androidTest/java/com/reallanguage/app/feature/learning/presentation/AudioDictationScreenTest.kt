package com.reallanguage.app.feature.learning.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import org.junit.Rule
import org.junit.Test

class AudioDictationScreenTest {
    @get:Rule val composeRule = androidx.compose.ui.test.junit4.v2.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @org.junit.Before fun productionWindow() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            com.reallanguage.app.core.app.configureFocusedLearningWindow(it.window)
        }
    }

    @Test fun playbackFailureKeepsRetryAndInputReachable() {
        val actions = mutableListOf<AudioDictationAction>()
        val state = AudioDictationUiState(FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true))), "s1", input = "I felt", audioLooping = false, lockedHintItems = emptyList(), audioFailed = true)
        composeRule.setContent { RlTheme { AudioDictationScreen(state, actions::add) } }
        composeRule.onNodeWithText(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(com.reallanguage.app.R.string.dictation_audio_failed)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("I felt").assertIsDisplayed()
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("captureVisualEvidence") == "true") {
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val output = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa/dictation-audio-failed.png")
            output.parentFile!!.mkdirs()
            requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
                output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
        }
        composeRule.onNodeWithText("重新生成发音\n（重新计费）").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("I felt").performScrollTo().assertIsDisplayed()
        assert(actions == listOf(AudioDictationAction.RegenerateAudioRequested))
    }

    @Test fun savedCorrectAnswerShowsAContinueActionRatherThanAnotherSubmit() {
        val actions = mutableListOf<AudioDictationAction>()
        val state = AudioDictationUiState(FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true))), "s1", input = "I felt calm.", audioLooping = false, lockedHintItems = emptyList(), correct = true)
        composeRule.setContent { RlTheme { AudioDictationScreen(state, actions::add) } }
        composeRule.onNodeWithText("继续").assertIsDisplayed().performClick()
        assert(actions == listOf(AudioDictationAction.ContinueRequested))
    }

    @Test
    fun showsSeparatedContinuousInputAndDispatchesLoopControl() {
        val actions = mutableListOf<AudioDictationAction>()
        val state = AudioDictationUiState(
            chapter = FixedLearningChapter(
                "c1", 1,
                listOf(
                    FixedLearningSentence(
                        "s1", "I expressed myself clearly.", "我清楚地表达了自己。", true,
                    ),
                ),
            ),
            currentFocusSentenceId = "s1",
            audioLooping = true,
            lockedHintItems = listOf("express oneself：表达自己"),
        )
        composeRule.setContent { RlTheme { AudioDictationScreen(state, actions::add) } }
        composeRule.onNodeWithText("点击播放，听写当前句").assertIsDisplayed()
        composeRule.onNodeWithTag("audio-dictation-loop-status").assertIsDisplayed()
        composeRule.onNodeWithTag("audio-dictation-input-panel").assertIsDisplayed()
        composeRule.onNodeWithText("请输入完整英文").assertIsDisplayed()
        composeRule.onNodeWithText("静音").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("提示").assertIsDisplayed()
        composeRule.onNodeWithText("提交").assertIsDisplayed()
        assert(actions == listOf(AudioDictationAction.AudioToggled))
    }

    @Test
    fun expandedHintIsVisibleBeforeTheInputAndOnlyShowsLockedLearningContent() {
        val state = AudioDictationUiState(
            chapter = FixedLearningChapter(
                "c1", 1,
                listOf(FixedLearningSentence("s1", "I went out shopping today.", "我今天出去逛街。", true)),
            ),
            currentFocusSentenceId = "s1",
            hintExpanded = true,
            lockedHintItems = listOf("go out shopping：出去购物", "two pieces of clothing：两件衣服"),
        )
        composeRule.setContent { RlTheme { AudioDictationScreen(state) {} } }

        composeRule.onNodeWithTag("audio-dictation-hint-panel").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("本句已选学习内容").assertIsDisplayed()
        composeRule.onNodeWithText("• go out shopping：出去购物").assertIsDisplayed()
        composeRule.onNodeWithText("• two pieces of clothing：两件衣服").assertIsDisplayed()
        composeRule.onNodeWithText("收起提示").assertIsDisplayed()
    }

    @Test
    fun expandedHintExplainsWhenTheSentenceHasNoLockedTargets() {
        val state = AudioDictationUiState(
            chapter = FixedLearningChapter(
                "c1", 1,
                listOf(FixedLearningSentence("s1", "I felt happy.", "我很开心。", true)),
            ),
            currentFocusSentenceId = "s1",
            hintExpanded = true,
            lockedHintItems = emptyList(),
        )
        composeRule.setContent { RlTheme { AudioDictationScreen(state) {} } }

        composeRule.onNodeWithText("本句没有已选择的重点词义、固定搭配或句式。请继续听音输入；提示不会临时增加学习目标。")
            .performScrollTo().assertIsDisplayed()
    }
    @Test fun longInputRemainsEditableWithKeyboardWithoutSubmitting() {
        val text = "Please share the timeline for the next phase, including key milestones and deliverables."
        val actions = mutableListOf<AudioDictationAction>()
        val state = androidx.compose.runtime.mutableStateOf(AudioDictationUiState(
            FixedLearningChapter("layout-only", 1, listOf(FixedLearningSentence("s1", text, "请分享下一阶段时间表。", true))),
            "s1", audioLooping = false, lockedHintItems = emptyList()))
        composeRule.setContent { RlTheme { AudioDictationScreen(state.value) { action ->
            actions += action
            if (action is AudioDictationAction.InputChanged) state.value = state.value.copy(input = action.value)
        } } }
        composeRule.onNodeWithText("请输入完整英文").performScrollTo().performTextInput(text)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text).assertIsDisplayed()
        check(state.value.input == text)
        check(actions.all { it is AudioDictationAction.InputChanged })
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()!!.let { bitmap ->
            java.io.File(instrumentation.targetContext.filesDir, "dictation-keyboard-long.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("提交").assertIsDisplayed()
        check(state.value.input == text)
    }
}
