package com.reallanguage.app.feature.learning.presentation

import androidx.compose.ui.test.assertIsDisplayed
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class CompleteWritingScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun useProductionWindow() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        composeRule.waitUntil(5_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test fun silentSingleHidesCurrentAnswerUntilExplicitHelpButKeepsReadOnlyContext() {
        val state = CompleteWritingUiState(
            chapter = FixedLearningChapter("c1", 1, listOf(
                FixedLearningSentence("s1", "This is the hidden answer.", "这是当前要写的句子。", true),
                FixedLearningSentence("context", "This remains context.", "这里是非重点语境。", false))),
            lockedTargets = emptyList(), phase = CompleteWritingPhase.SILENT_SINGLE,
        )
        composeRule.setContent { RlTheme { CompleteWritingScreen(state, {}) } }
        composeRule.onNodeWithText("This is the hidden answer.").assertDoesNotExist()
        composeRule.onNodeWithText("This remains context.").assertIsDisplayed()
    }

    @Test fun threeLongAnswersCanScrollAndEditWithoutMovingSubmitAtLargeFont() {
        val chinese = "这是一个需要完整展示、不能在两行后截断的长句子。".repeat(5)
        val english = "This is a long sentence whose complete answer must remain editable. ".repeat(5)
        val actions = mutableListOf<CompleteWritingAction>()
        val state = CompleteWritingUiState(
            chapter = FixedLearningChapter("c1", 1, (1..3).map {
                FixedLearningSentence("s$it", "$it $english", "$it $chinese", true)
            }), lockedTargets = emptyList(), phase = CompleteWritingPhase.FINAL_BATCH,
            finalInputs = (1..3).associate { "s$it" to "$it $english" }, evaluationUnavailable = true,
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                RlTheme { Box(Modifier.requiredSize(760.dp, 360.dp)) { CompleteWritingScreen(state, actions::add) } }
            }
        }
        val button = composeRule.onNodeWithText("提交")
        button.assertIsDisplayed()
        val buttonBefore = button.fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("complete-writing-workspace").performScrollTo().assertIsDisplayed()
        val input = composeRule.onNodeWithTag("complete-writing-input-s3")
        input.performScrollTo().assertIsDisplayed().performTextReplacement("My preserved third answer.")
        org.junit.Assert.assertTrue(actions.contains(CompleteWritingAction.FinalInputChanged("s3", "My preserved third answer.")))
        button.performClick()
        composeRule.onNodeWithText("无法确认本次学习目标的对应位置，未记录正式结果。请检查完整句子后重新提交。")
            .performScrollTo().assertIsDisplayed()
        org.junit.Assert.assertEquals(buttonBefore, button.fetchSemanticsNode().boundsInRoot)
        org.junit.Assert.assertEquals(CompleteWritingAction.SubmitRequested, actions.last())
    }

    @Test fun unresolvedEvidenceShowsAnHonestMessageAndKeepsRetryAvailableWithoutShowingAnswers() {
        val actions = mutableListOf<CompleteWritingAction>()
        val state = CompleteWritingUiState(
            chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "Run run.", "跑两次。", true))),
            lockedTargets = emptyList(), phase = CompleteWritingPhase.FINAL_BATCH,
            finalInputs = mapOf("s1" to "Run."), evaluationUnavailable = true,
        )
        composeRule.setContent { RlTheme { CompleteWritingScreen(state, actions::add) } }
        val message = composeRule.onNodeWithText("无法确认本次学习目标的对应位置，未记录正式结果。请检查完整句子后重新提交。")
        message.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        message.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        org.junit.Assert.assertTrue(layouts.all { it.size.height <= message.fetchSemanticsNode().boundsInRoot.height + 1f })
        composeRule.onNodeWithText("Run run.").assertDoesNotExist()
        composeRule.onNodeWithText("Run.").assertIsDisplayed()
        composeRule.onNodeWithText("提交").assertIsDisplayed().performClick()
        assert(actions == listOf(CompleteWritingAction.SubmitRequested))
    }

    @Test
    fun showsFinalBatchWritingDeskAndDispatchesSubmit() {
        val actions = mutableListOf<CompleteWritingAction>()
        val state = CompleteWritingUiState(
            chapter = FixedLearningChapter(
                "c1",
                1,
                listOf(
                    FixedLearningSentence("s1", "Sentence one.", "第一句。", true),
                    FixedLearningSentence("s2", "Sentence two.", "第二句。", true),
                ),
            ),
            lockedTargets = emptyList(),
            phase = CompleteWritingPhase.FINAL_BATCH,
            finalInputs = mapOf("s1" to "", "s2" to ""),
        )

        composeRule.setContent { RlTheme { CompleteWritingScreen(state, actions::add) } }

        composeRule.onNodeWithTag("complete-writing-workspace").assertIsDisplayed()
        composeRule.onNodeWithTag("complete-writing-input-s1").assertIsDisplayed()
        val submitBefore = composeRule.onNodeWithText("提交").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("complete-writing-workspace").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("complete-writing-input-s2").performScrollTo().assertIsDisplayed()
        org.junit.Assert.assertEquals("Submit remains fixed while the second answer scrolls into view", submitBefore,
            composeRule.onNodeWithText("提交").fetchSemanticsNode().boundsInRoot)
        composeRule.onNodeWithText("Sentence one.").assertDoesNotExist()
        composeRule.onNodeWithText("提交").assertIsDisplayed().performClick()
        assert(actions == listOf(CompleteWritingAction.SubmitRequested))
    }
}
