package com.reallanguage.app.feature.diary.presentation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.reallanguage.app.core.designsystem.RlTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DiaryInputScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyDiaryShowsConfirmedInputAndDisabledSubmit() {
        composeRule.setContent {
            RlTheme { DiaryInputScreen(DiaryInputUiState(), onAction = {}) }
        }

        composeRule.onNodeWithText("写日记").assertIsDisplayed()
        composeRule.onNodeWithText("最多 10 句").assertIsDisplayed()
        composeRule.onAllNodesWithText("今天你想表达什么？").assertCountEquals(1)
        composeRule.onNodeWithText("在此写下你想表达的内容").assertIsDisplayed()
        composeRule.onNodeWithText("用中文写下你想表达的内容，我们会用AI帮你形成真正你想表达的英语。")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("diary-open-notebook").assertIsDisplayed()
        composeRule.onNodeWithTag("diary-date-ticket").assertIsDisplayed()
        composeRule.onNodeWithTag("diary-prompt-note").assertIsDisplayed()
        composeRule.onNodeWithTag("diary-privacy-note").assertIsDisplayed()
        composeRule.onNodeWithText("按住听写").assertIsDisplayed()
        composeRule.onNodeWithText("0 / 300").assertIsDisplayed()
        composeRule.onNodeWithText("提交").assertIsDisplayed().assertIsNotEnabled()
        val viewport = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val sidebar = composeRule.onNodeWithTag("diary-sidebar").fetchSemanticsNode().boundsInRoot
        val submit = composeRule.onNodeWithTag("diary-submit").fetchSemanticsNode().boundsInRoot
        assertTrue("Submit must remain fully inside the visible viewport: $viewport / $sidebar / $submit",
            submit.bottom <= viewport.bottom)
        composeRule.onNodeWithText("保存草稿").assertDoesNotExist()
    }

    @Test
    fun promptCopyKeepsAReadableLowerMarginInsideItsPaper() {
        composeRule.setContent {
            RlTheme { DiaryInputScreen(DiaryInputUiState(), onAction = {}) }
        }

        val paper = composeRule.onNodeWithTag("diary-prompt-note").fetchSemanticsNode().boundsInRoot
        val copy = composeRule.onNodeWithText(
            "用中文写下你想表达的内容，我们会用AI帮你形成真正你想表达的英语。",
        ).fetchSemanticsNode().boundsInRoot

        assertTrue(
            "Prompt copy must not touch the lower torn edge: $paper / $copy",
            copy.bottom <= paper.bottom - paper.height * .08f,
        )
    }

    @Test
    fun heldDictationUsesTheSameActiveRecordingStructureAsHome() {
        composeRule.setContent {
            RlTheme {
                DiaryInputScreen(
                    DiaryInputUiState(
                        dictationState = DiaryDictationState.RECORDING,
                        dictationElapsedSeconds = 3,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("正在听写").assertIsDisplayed()
        composeRule.onNodeWithText("00:03").assertIsDisplayed()
        composeRule.onNodeWithText("松开识别").assertIsDisplayed()
        composeRule.onNodeWithTag("diary-dictation-active").assertIsDisplayed()
    }

    @Test
    fun holdingAndReleasingDispatchesOneCompleteDictationGesture() {
        val actions = mutableListOf<DiaryInputAction>()
        composeRule.setContent {
            RlTheme { DiaryInputScreen(DiaryInputUiState(), onAction = actions::add) }
        }

        composeRule.onNodeWithTag("diary-dictation-control").performTouchInput {
            down(center)
            advanceEventTime(250)
            up()
        }

        assertEquals(
            listOf(
                DiaryInputAction.DictationHoldStarted,
                DiaryInputAction.DictationReleased,
            ),
            actions,
        )
    }

    @Test
    fun recognizingAndFailureStatesAreExplicitAndDoNotPretendToSubmit() {
        var state by mutableStateOf(DiaryInputUiState(dictationState = DiaryDictationState.RECOGNIZING))
        composeRule.setContent { RlTheme { DiaryInputScreen(state, onAction = {}) } }
        composeRule.onNodeWithText("正在识别…").assertIsDisplayed()
        composeRule.onNodeWithText("提交").assertIsNotEnabled()

        state = state.copy(dictationState = DiaryDictationState.FAILED)
        composeRule.onNodeWithText("听写识别失败，原有内容已保留，请重新按住听写。").assertIsDisplayed()
        composeRule.onNodeWithText("按住听写").assertIsDisplayed()
    }

    @Test
    fun notebookInputAndSubmitRemainRealControls() {
        val actions = mutableListOf<DiaryInputAction>()
        var state by mutableStateOf(DiaryInputUiState(text = "原内容", submitEnabled = true))
        composeRule.setContent {
            RlTheme {
                DiaryInputScreen(
                    state = state,
                    onAction = { action ->
                        actions += action
                        if (action is DiaryInputAction.TextChanged) {
                            state = state.copy(text = action.value, submitEnabled = true)
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag("diary-submit").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.onNodeWithTag("diary-text-input").performTextReplacement("今天开会。")

        assertEquals(
            listOf(
                DiaryInputAction.SubmitRequested,
                DiaryInputAction.TextChanged("今天开会。"),
            ),
            actions,
        )
    }

    @Test
    fun discardDialogMakesContinueEditingTheSafePrimaryChoice() {
        composeRule.setContent {
            RlTheme {
                DiaryInputScreen(
                    state = DiaryInputUiState(
                        text = "今天发生了一件事。",
                        submitEnabled = true,
                        showDiscardConfirmation = true,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("放弃本次日记？").assertIsDisplayed()
        composeRule.onNodeWithText("继续编辑").assertIsDisplayed()
        composeRule.onNodeWithText("确认放弃").assertIsDisplayed()
        composeRule.onNodeWithText("保存草稿").assertDoesNotExist()
    }

    @Test
    fun privacyCopyStaysInsideTheVisibleBandOfTheUnchangedPaperAsset() {
        composeRule.setContent { RlTheme { DiaryInputScreen(DiaryInputUiState(), {}) } }
        val paper = composeRule.onNodeWithTag("diary-privacy-note").fetchSemanticsNode().boundsInRoot
        val text = composeRule.onNodeWithText("请不要填写密码、证件号等敏感信息。").fetchSemanticsNode().boundsInRoot
        // rl_torn_heading_label has transparent margins; its paper band is y=0.24..0.74.
        assertTrue("Privacy copy must stay below the paper's upper torn edge: $paper / $text",
            text.top >= paper.top + paper.height * .24f)
        assertTrue("Privacy copy must stay above the paper's lower torn edge: $paper / $text",
            text.bottom <= paper.top + paper.height * .74f)
    }
}
