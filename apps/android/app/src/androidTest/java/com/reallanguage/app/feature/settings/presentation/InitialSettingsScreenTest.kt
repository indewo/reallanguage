package com.reallanguage.app.feature.settings.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import org.junit.Before
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import com.reallanguage.app.feature.settings.domain.selectableVocabularyPools
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InitialSettingsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun useProductionWindow() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test
    fun stepOneShowsOnlyConfirmedSettingsAndNoSkip() {
        composeRule.setContent {
            RlTheme {
                InitialSettingsScreen(
                    state = InitialSettingsUiState(),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("正式／随意程度").assertIsDisplayed()
        composeRule.onNodeWithText("俚语").assertIsDisplayed()
        composeRule.onNodeWithText("学习单词池").assertIsDisplayed()
        composeRule.onNodeWithText("自然平衡").assertIsDisplayed()
        composeRule.onNodeWithText("不使用").assertIsDisplayed()
        composeRule.onNodeWithText("适量使用").assertIsDisplayed()
        composeRule.onNodeWithText("I don’t know.").assertIsDisplayed()
        composeRule.onNodeWithText("I have no idea.\nThat’s awesome!").assertIsDisplayed()
        selectableVocabularyPools.forEach { pool ->
            composeRule.onNodeWithTag("settings-vocabulary-${pool.name.lowercase()}").assertIsDisplayed()
        }
        composeRule.onNodeWithText("基础词汇难度").assertDoesNotExist()
        composeRule.onNodeWithText("专八").assertDoesNotExist()
        composeRule.onNodeWithText("关闭应用后，本次未完成设置不会保存。").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-editorial-intro").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-expression-paper-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-formality-scale").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-slang-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-vocabulary-cards").assertIsDisplayed()
        composeRule.onNodeWithText("继续").assertIsNotEnabled()
        composeRule.onNodeWithText("跳过").assertDoesNotExist()
    }

    @Test
    fun voiceOffDisablesAutoPlayAndShowsNoVoiceSelection() {
        val actions = mutableListOf<InitialSettingsAction>()
        composeRule.setContent {
            RlTheme {
                InitialSettingsScreen(
                    state = InitialSettingsUiState(
                        step = InitialSettingsStep.VOICE_SETTINGS,
                        draft = InitialSettingsDraft(voiceEnabled = false),
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("settings-voice-editorial-intro").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-voice-toggle").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-autoplay-toggle").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithTag("settings-speech-speed").assertIsDisplayed()
        composeRule.onNodeWithText("语音").assertIsDisplayed()
        composeRule.onNodeWithText("关闭后仍保留全部文字内容。").assertIsDisplayed()
        composeRule.onNodeWithText("自动播放").assertIsDisplayed()
        composeRule.onNodeWithText("进入讲解或练习时自动播放。").assertIsDisplayed()
        composeRule.onNodeWithText("更慢").assertIsDisplayed()
        composeRule.onNodeWithText("标准").assertIsDisplayed()
        composeRule.onNodeWithText("更快").assertIsDisplayed()
        composeRule.onNodeWithText("默认使用美式英语声音，当前不提供声线选择。").assertIsDisplayed()
        composeRule.onNodeWithText("声线选择").assertDoesNotExist()
        composeRule.onNodeWithText("完成设置").assertIsNotEnabled()
        composeRule.onNodeWithTag("settings-voice-toggle").performClick()
        assertEquals(InitialSettingsAction.VoiceChanged(true), actions.single())
    }

    @Test
    fun expressionBasicsControlsDispatchRealSettingsActions() {
        val actions = mutableListOf<InitialSettingsAction>()
        composeRule.setContent {
            RlTheme {
                InitialSettingsScreen(
                    state = InitialSettingsUiState(),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("settings-slang-enabled").performClick()
        composeRule.onNodeWithTag("settings-vocabulary-cet_6").assertIsDisplayed().performClick()

        assertEquals(
            listOf(
                InitialSettingsAction.SlangChanged(true),
                InitialSettingsAction.BasicVocabularyChanged(BasicVocabulary.CET_6),
            ),
            actions,
        )
    }

    @Test
    fun stepTwoMatchesConfirmedPreferenceStructureAndDispatchesSelection() {
        val actions = mutableListOf<InitialSettingsAction>()
        composeRule.setContent {
            RlTheme {
                InitialSettingsScreen(
                    state = InitialSettingsUiState(
                        step = InitialSettingsStep.USAGE_PREFERENCES,
                        draft = InitialSettingsDraft(
                            basicVocabulary = BasicVocabulary.CET_6,
                            styleReference = "清晰、自然、有分寸，像一位耐心的同事。",
                        ),
                        continueEnabled = true,
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("settings-preferences-editorial-intro").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-selected-vocabulary-pool").assertIsDisplayed()
        composeRule.onNodeWithText("当前学习单词池").assertIsDisplayed()
        composeRule.onNodeWithText("六级").assertIsDisplayed()
        composeRule.onNodeWithText("8 个词表平级单选，随时可以切换。").assertIsDisplayed()
        composeRule.onNodeWithText("当前池的词义会优先用于内容生成和学习目标标注。").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-purpose-cards").assertDoesNotExist()
        composeRule.onNodeWithText("Business English").assertDoesNotExist()
        composeRule.onNodeWithText("清晰、自然、有分寸，像一位耐心的同事。").assertIsDisplayed()
        composeRule.onNodeWithText("只保存你明确填写的设置，不自动建立长期画像。").assertIsDisplayed()

        composeRule.onNodeWithText("返回").assertIsDisplayed().performClick()
        assertEquals(
            InitialSettingsAction.BackRequested,
            actions.single(),
        )
        actions.clear()
        composeRule.onNodeWithTag("settings-style-reference-input")
            .performTextReplacement("温暖、直接")
        assertEquals(
            InitialSettingsAction.StyleReferenceChanged("温暖、直接"),
            actions.last(),
        )
    }
}
