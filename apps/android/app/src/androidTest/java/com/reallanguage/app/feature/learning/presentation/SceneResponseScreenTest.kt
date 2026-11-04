package com.reallanguage.app.feature.learning.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsProperties
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.SceneDialogueLine
import com.reallanguage.app.feature.learning.domain.SceneEvaluationDecision
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class SceneResponseScreenTest {
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

    @Test fun longSceneDialogueFeedbackAndTranscriptRemainReachableInTheSharedPageScroll() {
        val state = SceneResponseUiState(
            FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "Target.", "目标。", true))),
            listOf(FixedSceneResponseTask("t1", "s1", "Context with important details. ".repeat(80),
                listOf(SceneDialogueLine("AI", "A long question with important details. ".repeat(80))), "Target.")),
            transcript = "A long spoken response. ".repeat(180), feedbackZh = "需要读完整的反馈。".repeat(120),
        )
        composeRule.setContent { RlTheme { SceneResponseScreen(state) {} } }
        val footer = composeRule.onNodeWithTag("scene-response-footer")
        val footerBefore = footer.fetchSemanticsNode().boundsInRoot
        val viewport = composeRule.onNodeWithTag("scene-content-scroll-viewport")
        listOf("scene-background-scroll", "scene-dialogue-scroll", "scene-feedback-scroll", "scene-transcript-scroll").forEach { tag ->
            val node = composeRule.onNodeWithTag(tag)
            node.performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            org.junit.Assert.assertTrue("$tag must retain every line", layouts.isNotEmpty() && layouts.all {
                !it.didOverflowHeight && !it.multiParagraph.didExceedMaxLines
            })
            val layout = layouts.single()
            val density = composeRule.activity.resources.displayMetrics.density
            val lastLine = layout.lineCount - 1
            val lastBottom = node.getUnclippedBoundsInRoot().top.value * density + layout.getLineBottom(lastLine)
            val scrollToLastLine = lastBottom - viewport.fetchSemanticsNode().boundsInRoot.bottom + 2f
            if (scrollToLastLine > 0f) viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, scrollToLastLine) }
            val finalTop = node.getUnclippedBoundsInRoot().top.value * density
            val visible = viewport.fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("$tag last line must actually be readable, not only laid out beyond the viewport",
                finalTop + layout.getLineTop(lastLine) >= visible.top - 1f &&
                    finalTop + layout.getLineBottom(lastLine) <= visible.bottom + 1f)
            // Shared page scrolling replaces four conflicting nested scroll areas.
            // Exercise a real gesture and keep the primary actions outside it.
            viewport.performTouchInput { swipeUp() }
            val range = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            org.junit.Assert.assertTrue("$tag must be reachable through the shared page", range.value() > 0f)
            assertEquals("The actions must not move with long content", footerBefore, footer.fetchSemanticsNode().boundsInRoot)
            composeRule.onNodeWithText("开始回应").assertIsDisplayed()
        }
        composeRule.onNodeWithText("开始回应").assertIsDisplayed()
    }

    @Test fun thirdRoundDoesNotExposeTheFixedAnswerBeforeTheUserSpeaks() {
        val state = SceneResponseUiState(
            FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "Hidden fixed answer.", "目标。", true))),
            listOf(FixedSceneResponseTask("t1", "s1", "Context", listOf(SceneDialogueLine("AI", "Question?")), "Hidden fixed answer.")),
            roundIndex = 2,
        )
        composeRule.setContent { RlTheme { SceneResponseScreen(state) {} } }
        composeRule.onNodeWithText("Hidden fixed answer.").assertDoesNotExist()
    }

    @Test fun progressFailureAndUnavailableAudioAreExplainedWithoutOfferingEvaluation() {
        val actions = mutableListOf<SceneResponseAction>()
        val state = SceneResponseUiState(
            FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "Target.", "目标。", true))),
            listOf(FixedSceneResponseTask("t1", "s1", "Context", listOf(SceneDialogueLine("AI", "Question?")), "Target.")),
            progressFailed = true, needsNewRecording = true, transcript = "Recovered text only",
        )
        composeRule.setContent { RlTheme { SceneResponseScreen(state, onAction = actions::add) } }
        composeRule.onNodeWithText("重试保存").assertIsDisplayed().performClick()
        assertEquals(listOf(SceneResponseAction.RetryRequested), actions)
    }

    @Test fun acceptedEvaluationWithSaveFailureHasExplicitSaveRetry() {
        val actions = mutableListOf<SceneResponseAction>()
        val state = SceneResponseUiState(
            FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "Target.", "目标。", true))),
            listOf(FixedSceneResponseTask("t1", "s1", "Context", listOf(SceneDialogueLine("AI", "Question?")), "Target.")),
            roundIndex = 2, finalDecision = SceneEvaluationDecision.PASS, saveFailed = true,
        )
        composeRule.setContent { RlTheme { SceneResponseScreen(state, onAction = actions::add) } }
        composeRule.onNodeWithText("重试保存").assertIsDisplayed().performClick()
        assertEquals(listOf(SceneResponseAction.RetryRequested), actions)
    }

    @Test
    fun showsThirdRoundSceneAndDispatchesFinished() {
        val actions = mutableListOf<SceneResponseAction>()
        val state = SceneResponseUiState(
            chapter = FixedLearningChapter(
                "c1",
                1,
                listOf(FixedLearningSentence("s1", "Target sentence.", "目标句。", true)),
            ),
            tasks = listOf(
                FixedSceneResponseTask(
                    "t1",
                    "s1",
                    "会议结束后，你和同事走出会议室。",
                    listOf(SceneDialogueLine("AI", "You explained your idea really clearly in there.")),
                    "Thanks. I was nervous, but I explained my idea clearly.",
                ),
            ),
            roundIndex = 2,
            recording = true,
            transcript = "Thanks. I was nervous, but I explained my idea clearly.",
            feedbackZh = "回应同事，并在正确位置说出本章目标句。",
        )

        composeRule.setContent { RlTheme { SceneResponseScreen(state, onAction = actions::add) } }

        composeRule.onNodeWithTag("scene-response-workspace").assertIsDisplayed()
        composeRule.onNodeWithText("第 3 轮 / 共 3 轮").assertIsDisplayed()
        composeRule.onNodeWithText("正在收音").assertIsDisplayed()
        val statusBefore = composeRule.onNodeWithTag("scene-response-recording-status").getUnclippedBoundsInRoot()
        composeRule.onNodeWithTag("scene-content-scroll-viewport").performTouchInput { swipeUp() }
        composeRule.onNodeWithText("正在收音").assertIsDisplayed()
        assertEquals("Recording status must stay fixed while the dialogue scrolls", statusBefore,
            composeRule.onNodeWithTag("scene-response-recording-status").getUnclippedBoundsInRoot())
        composeRule.onNodeWithText("回应完毕").assertIsDisplayed().performClick()
        assert(actions == listOf(SceneResponseAction.FinishedRequested))
    }

    @Test fun recordingStatusAndFinishStayVisibleOnShortScreenWithLargeText() {
        val actions = mutableListOf<SceneResponseAction>()
        val state = SceneResponseUiState(
            FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "Target.", "目标。", true))),
            listOf(FixedSceneResponseTask("t1", "s1", "A detailed scene. ".repeat(80),
                listOf(SceneDialogueLine("AI", "Question? ".repeat(80))), "Target.")),
            roundIndex = 2, recording = true, transcript = "An ongoing spoken response. ".repeat(60),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
                Box(Modifier.size(640.dp, 320.dp).testTag("scene-recording-short-root")) {
                    RlTheme { SceneResponseScreen(state, onAction = actions::add) }
                }
            }
        }
        composeRule.onNodeWithText("正在收音").assertIsDisplayed()
        val status = composeRule.onNodeWithTag("scene-response-recording-status")
        val before = status.getUnclippedBoundsInRoot()
        val finish = composeRule.onNodeWithText("回应完毕")
        finish.assertIsDisplayed()
        val finishBefore = finish.getUnclippedBoundsInRoot()
        composeRule.onNodeWithTag("scene-content-scroll-viewport").performTouchInput { swipeUp() }
        composeRule.onNodeWithText("正在收音").assertIsDisplayed()
        assertEquals(before, status.getUnclippedBoundsInRoot())
        assertEquals(finishBefore, finish.getUnclippedBoundsInRoot())
        val root = composeRule.onNodeWithTag("scene-recording-short-root").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue("The recording status and finish action must remain fully on the screen",
            before.top >= root.top && before.bottom <= root.bottom && finishBefore.bottom <= root.bottom)
        finish.assertIsDisplayed().performClick()
        assertEquals(listOf(SceneResponseAction.FinishedRequested), actions)
    }

    @Test fun transcribingShowsAnInPlaceCheckingStateAndLocksEveryResponseAction() {
        val actions = mutableListOf<SceneResponseAction>()
        val state = fixture(transcribing = true)

        composeRule.setContent { RlTheme { SceneResponseScreen(state, onAction = actions::add) } }

        composeRule.onNodeWithTag("scene-response-recording-status").assertIsDisplayed()
        composeRule.onAllNodesWithText("正在检查你的回应……").assertCountEquals(2)
        composeRule.onNodeWithTag("scene-response-checking-spinner").assertIsDisplayed()
        composeRule.onNodeWithText("播放上一句").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("重试").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNode(hasText("正在检查你的回应……") and isNotEnabled()).assertIsDisplayed()
        assertEquals(emptyList<SceneResponseAction>(), actions)
    }

    @Test fun evaluatingShowsTheSameInPlaceCheckingStateAndKeepsTheFooterFixed() {
        val state = fixture(submitting = true, transcript = "I can answer this now.")
        composeRule.setContent { RlTheme { SceneResponseScreen(state) {} } }

        val status = composeRule.onNodeWithTag("scene-response-recording-status")
        val before = status.getUnclippedBoundsInRoot()
        composeRule.onNodeWithTag("scene-response-recording-status").assertIsDisplayed()
        composeRule.onAllNodesWithText("正在检查你的回应……").assertCountEquals(2)
        composeRule.onNodeWithTag("scene-response-checking-spinner").assertIsDisplayed()
        composeRule.onNode(hasText("正在检查你的回应……") and isNotEnabled()).assertIsDisplayed()
        composeRule.onNodeWithTag("scene-content-scroll-viewport").performTouchInput { swipeUp() }
        assertEquals("Checking feedback must stay fixed while page content scrolls", before,
            status.getUnclippedBoundsInRoot())
    }

    private fun fixture(
        transcribing: Boolean = false,
        submitting: Boolean = false,
        transcript: String = "",
    ) = SceneResponseUiState(
        FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "Target.", "目标。", true))),
        listOf(FixedSceneResponseTask("t1", "s1", "A bright morning after the rain.",
            listOf(SceneDialogueLine("AI", "The sky is clear, and everyone seems cheerful.")), "Target.")),
        roundIndex = 2,
        transcribing = transcribing,
        submitting = submitting,
        transcript = transcript,
    )
}
