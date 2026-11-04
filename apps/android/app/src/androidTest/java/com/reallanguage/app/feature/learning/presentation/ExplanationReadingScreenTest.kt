package com.reallanguage.app.feature.learning.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.SelectableExpression
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class ExplanationReadingScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun assertVisibleOrScrollTo(text: String) {
        val node = composeRule.onNodeWithText(text)
        try {
            node.assertIsDisplayed()
        } catch (_: AssertionError) {
            node.performScrollTo().assertIsDisplayed()
        }
    }

    @Before fun useProductionLandscapeOrientation() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            // Match MainActivity's immersive window: system bars must not shrink
            // the original-image viewport in this instrumentation host.
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it.window, false)
            androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test fun currentExpressionMeaningUsageAndHintAreNotSentenceExplanationOrRoundHint() {
        val details = com.reallanguage.app.feature.learning.domain.ExpressionExplanation(
            "x", "旧完整词语解释", contextualMeaningZh = "平静的",
            usageZh = "形容人在当前情境中不紧张。", expressionHintZh = "stay calm：保持冷静",
            naturalExample = com.reallanguage.app.feature.learning.domain.NaturalExpressionExample("She stayed calm.", "她保持冷静。"))
        val state = ExplanationReadingUiState(FixedLearningChapter("c", 1, listOf(
            FixedLearningSentence("s", "I felt calm.", "我很平静。", true),
        )), "s", "整句基础讲解必须继续保留。", currentIncrementalHint = "本轮只注意 felt 的读音。",
            expressions = listOf(SelectableExpression("x", "calm", 7, 11)), selectedExpression = details)
        composeRule.setContent { RlTheme { ExplanationReadingScreen(state, {}) } }
        listOf("平静的", "形容人在当前情境中不紧张。", "stay calm：保持冷静", "She stayed calm.", "她保持冷静。", "整句基础讲解必须继续保留。")
            .forEach(::assertVisibleOrScrollTo)
        composeRule.onAllNodesWithText("旧完整词语解释").assertCountEquals(0)
        assertVisibleOrScrollTo("本轮只注意 felt 的读音。")
        org.junit.Assert.assertTrue("Only the current round hint belongs at the top of the explanation area",
            composeRule.onNodeWithText("本轮只注意 felt 的读音。").fetchSemanticsNode().boundsInRoot.top <=
                composeRule.onNodeWithText("形容人在当前情境中不紧张。").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun legacyExpressionDoesNotPretendTheSentenceExplanationIsItsUsage() {
        val state = ExplanationReadingUiState(FixedLearningChapter("c", 1, listOf(
            FixedLearningSentence("s", "I felt calm.", "我很平静。", true),
        )), "s", "整句原讲解", expressions = listOf(SelectableExpression("x", "calm", 7, 11)),
            selectedExpression = com.reallanguage.app.feature.learning.domain.ExpressionExplanation("x", "旧词语混合解释"))
        composeRule.setContent { RlTheme { ExplanationReadingScreen(state, {}) } }
        assertVisibleOrScrollTo("此份讲解未包含独立用法说明。")
        composeRule.onAllNodesWithText("旧词语混合解释").assertCountEquals(1)
        assertVisibleOrScrollTo("整句原讲解")
    }

    @Test fun longSelectedExplanationCanScrollToSelfAssessmentWithoutMovingFooter() {
        val state = ExplanationReadingUiState(FixedLearningChapter("c", 1, listOf(
            FixedLearningSentence("s", "I felt calm.", "我很平静。", true),
        )), "s", "基础讲解", expressions = listOf(SelectableExpression("x", "calm", 7, 11)),
            selectedExpression = com.reallanguage.app.feature.learning.domain.ExpressionExplanation("x", "这里说明当前语境的含义。".repeat(20)))
        val target = com.reallanguage.app.feature.learning.domain.SelfAssessmentTarget("t", "s", "calm", "平静",
            setOf(com.reallanguage.app.feature.learning.domain.SelfAssessmentAbility.MEANING_WRITING))
        val actions = mutableListOf<TargetSelfAssessmentAction>()
        composeRule.setContent { RlTheme { Box(Modifier.size(820.dp, 340.dp)) {
            ExplanationReadingScreen(state, {}, targetAssessment = { display ->
                TargetSelfAssessmentControls(TargetSelfAssessmentUiState(listOf(target)), actions::add,
                    display = display, cardAction = true)
            })
        } } }
        composeRule.onNodeWithText("会了").assertIsDisplayed().performClick()
        assertVisibleOrScrollTo("用法说明")
        val visibleHeight = composeRule.onNodeWithText("用法说明").fetchSemanticsNode().boundsInRoot.height
        composeRule.onNodeWithText("用法说明").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { getLayout ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            org.junit.Assert.assertTrue(getLayout(layouts))
            org.junit.Assert.assertTrue("The content viewport must show a complete text row, not a sliver", layouts.all { it.size.height <= visibleHeight + 1f })
        }
        val output = java.io.File(composeRule.activity.getExternalFilesDir(null), "explanation-short-footer.png")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        composeRule.onNodeWithText("我理解了").assertIsDisplayed()
        org.junit.Assert.assertEquals(1, actions.size)
    }

    @Test fun shortExplanationKeepsPageAndModuleFixedAndPinsStyledAssessmentToCardCorner() {
        val state = ExplanationReadingUiState(FixedLearningChapter("c", 1, listOf(
            FixedLearningSentence("s", "It's a beautiful day today.", "今天是美好的一天。", true),
        )), "s", "这句是很自然的日常说法。", expressions = listOf(
            SelectableExpression("x", "beautiful", 7, 16),
        ), selectedExpression = com.reallanguage.app.feature.learning.domain.ExpressionExplanation(
            "x", "美好的；漂亮的", "/ˈbjuːtɪfl/", contextualMeaningZh = "美好的；漂亮的",
            usageZh = "这里用来形容 day，表达天气很好。",
            naturalExample = com.reallanguage.app.feature.learning.domain.NaturalExpressionExample(
                "It's a beautiful morning.", "这是一个美好的早晨。",
            ),
        ))
        val target = com.reallanguage.app.feature.learning.domain.SelfAssessmentTarget(
            "t", "s", "beautiful", "美好的；漂亮的",
            setOf(com.reallanguage.app.feature.learning.domain.SelfAssessmentAbility.MEANING_WRITING),
        )
        composeRule.setContent { RlTheme { Box(Modifier.size(845.5.dp, 465.dp)) {
            ExplanationReadingScreen(state, {}, targetAssessment = { display ->
                TargetSelfAssessmentControls(
                    TargetSelfAssessmentUiState(listOf(target)), {}, display = display, cardAction = true,
                )
            })
        } } }
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("learning-page-scroll-viewport").and(hasScrollAction()))
            .assertCountEquals(0)
        composeRule.onAllNodes(hasTestTag("learning-module-scroll-content").and(hasScrollAction()))
            .assertCountEquals(0)
        composeRule.onAllNodesWithText("美好的；漂亮的").assertCountEquals(1)
        val card = composeRule.onNodeWithTag("explanation_expression_card_content", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val action = composeRule.onNodeWithTag("expression_assessment_action", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("assessment must be anchored at the expression card's right edge", card.right - action.right <= 2f)
        org.junit.Assert.assertTrue("assessment must be anchored at the expression card's bottom edge", card.bottom - action.bottom <= 2f)
    }

    @Test fun onlyOverflowingExplanationGetsAnInternalStyledScrollWindow() {
        val longExplanation = "这是较长的基础讲解，用来确认内容只在解释窗口内部滚动。".repeat(30) + "OVERFLOW_END"
        val state = ExplanationReadingUiState(FixedLearningChapter("c", 1, listOf(
            FixedLearningSentence("s", "I felt calm.", "我很平静。", true),
        )), "s", longExplanation, expressions = listOf(SelectableExpression("x", "calm", 7, 11)))
        composeRule.setContent { RlTheme { Box(Modifier.size(845.5.dp, 465.dp)) {
            ExplanationReadingScreen(state, {})
        } } }
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasTestTag("learning-page-scroll-viewport").and(hasScrollAction()))
            .assertCountEquals(0)
        composeRule.onNodeWithTag("learning-module-overflow-scrollbar", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("OVERFLOW_END", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("我理解了").assertIsDisplayed()
    }

    @Test fun selectedExpressionTitleMatchesItsExplanationInsteadOfAlwaysUsingFirstWord() {
        val state = ExplanationReadingUiState(FixedLearningChapter("c1", 1, listOf(
            FixedLearningSentence("s1", "I felt calm.", "我很平静。", true),
        )), "s1", "基础讲解", expressions = listOf(
            SelectableExpression("x1", "felt", 2, 6), SelectableExpression("x2", "calm", 7, 11),
        ), selectedExpression = com.reallanguage.app.feature.learning.domain.ExpressionExplanation("x2", "这里表示平静。"))
        composeRule.setContent { RlTheme { ExplanationReadingScreen(state) {} } }
        composeRule.onNodeWithText("calm").assertIsDisplayed()
        composeRule.onAllNodesWithText("felt").assertCountEquals(0)
    }

    @Test fun naturalExampleShowsItsOwnEnglishAndChineseInsteadOfRepeatingTheMeaning() {
        val example = com.reallanguage.app.feature.learning.domain.NaturalExpressionExample("She felt calm after the walk.", "散步后她感到平静。")
        val state = ExplanationReadingUiState(FixedLearningChapter("c", 1, listOf(
            FixedLearningSentence("s", "I felt calm.", "我很平静。", true),
        )), "s", "基础讲解", expressions = listOf(SelectableExpression("x", "calm", 7, 11)),
            selectedExpression = com.reallanguage.app.feature.learning.domain.ExpressionExplanation("x", "平静的", naturalExample = example))
        composeRule.setContent { RlTheme { ExplanationReadingScreen(state, {}) } }
        composeRule.onNodeWithText(example.textEn).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(example.textZh).performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("平静的").assertCountEquals(1)
    }

    @Test fun referenceStateKeepsExampleBeforeHintAndFullyVisibleWithoutScrolling() {
        val english = "But in the end, I worked up the courage to explain my idea clearly."
        val example = com.reallanguage.app.feature.learning.domain.NaturalExpressionExample(
            "She worked up the courage to ask.", "她鼓起勇气问了。")
        val hint = "work up the courage to + 动词"
        val state = ExplanationReadingUiState(FixedLearningChapter("reference", 1, listOf(
            FixedLearningSentence("before", "I was a little nervous during today’s meeting.", "今天开会时，我本来有点紧张。", false),
            FixedLearningSentence("focus", english, "但最后还是鼓起勇气把想法讲清楚了。", true),
            FixedLearningSentence("after", "My colleague nodded after listening.", "同事听完后点了点头。", false),
        )), "focus", "常用于描述终于敢去做一件原本犹豫的事。",
            currentIncrementalHint = null, completedReadCount = 3,
            expressions = listOf(SelectableExpression("phrase", "work up the courage", 18, 39)),
            selectedExpression = com.reallanguage.app.feature.learning.domain.ExpressionExplanation("phrase", "鼓起勇气", naturalExample = example,
                contextualMeaningZh = "鼓起勇气", usageZh = "常用于描述终于敢去做一件原本犹豫的事。", expressionHintZh = hint))
        composeRule.setContent { RlTheme { Box(Modifier.size(845.5.dp, 465.dp).testTag("explanation-reference-host")) {
            ExplanationReadingScreen(state, {})
        } } }
        composeRule.waitForIdle()
        val diagnostics = buildString {
            append("density=${composeRule.activity.resources.displayMetrics.density}; ")
            append("fontScale=${composeRule.activity.resources.configuration.fontScale}; ")
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
            append("cutout=${insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout())}; ")
            append("navigation=${insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())}; ")
            for (tag in listOf("explanation-reference-host", "learning-page-scroll-viewport", "learning-page-heading",
                "learning-context-paper", "learning-page-workspace", "explanation_content", "learning-page-footer")) {
                val semantics = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
                append("$tag bounds=${semantics.boundsInRoot} measured=${semantics.size}; ")
            }
        }
        val screenshot = java.io.File(composeRule.activity.getExternalFilesDir(null), "explanation-reference-layout.png")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            screenshot.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        for (text in listOf(example.textEn, example.textZh, hint)) {
            val node = composeRule.onNodeWithText(text)
            assertVisibleOrScrollTo(text)
            val textNode = node.fetchSemanticsNode()
            val visibleHeight = textNode.boundsInRoot.height
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { getLayout ->
                org.junit.Assert.assertTrue(getLayout(layouts))
            }
            val layoutDescription = layouts.joinToString { "size=${it.size}, lines=${it.lineCount}, lastLineBottom=${if (it.lineCount > 0) it.getLineBottom(it.lineCount - 1) else 0f}, didOverflowHeight=${it.didOverflowHeight}" }
            android.util.Log.i("ExplanationLayout", "$text bounds=${textNode.boundsInRoot}; $layoutDescription; $diagnostics")
            org.junit.Assert.assertTrue("Reference content must not be clipped: $text; bounds=${textNode.boundsInRoot}; $layoutDescription; $diagnostics; screenshot=${screenshot.absolutePath}",
                layouts.all { it.size.height <= visibleHeight + 1f })
        }
        org.junit.Assert.assertTrue(composeRule.onNodeWithText(example.textZh).fetchSemanticsNode().boundsInRoot.bottom <=
            composeRule.onNodeWithText(hint).fetchSemanticsNode().boundsInRoot.top)
        composeRule.onNodeWithText("我理解了").assertIsDisplayed()
    }

    @Test
    fun sentenceUsesAvailableTopWidthAndBottomControlsStayOneEqualHeightRow() {
        val state = ExplanationReadingUiState(FixedLearningChapter("c", 1, listOf(
            FixedLearningSentence("s", "I studied at the library today.", "我今天在图书馆学习。", true),
        )), "s", "说明", expressions = emptyList(), audioPlaying = false)
        composeRule.setContent { RlTheme { Box(Modifier.size(820.dp, 340.dp)) {
            ExplanationReadingScreen(state, {})
        } } }

        val back = composeRule.onNodeWithText("返回").fetchSemanticsNode().boundsInRoot
        val audio = composeRule.onNodeWithTag("explanation_audio_toggle").fetchSemanticsNode().boundsInRoot
        val understood = composeRule.onNodeWithTag("explanation_understood").fetchSemanticsNode().boundsInRoot
        val focus = composeRule.onNodeWithTag("learning-focus-s").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(back.top, audio.top, 1f)
        org.junit.Assert.assertEquals(audio.top, understood.top, 1f)
        org.junit.Assert.assertEquals(back.height, audio.height, 1f)
        org.junit.Assert.assertEquals(audio.height, understood.height, 1f)
        org.junit.Assert.assertTrue("The active sentence must not remain a flat strip", focus.height >= 52f)
        composeRule.onAllNodesWithText("继续朗读").assertCountEquals(0)
    }

    @Test
    fun keepsSentenceInChapterAndShowsOnlyCurrentHintWithNoRoundProgress() {
        val actions = mutableListOf<ExplanationReadingAction>()
        val english = "I expressed myself more clearly."
        val state = ExplanationReadingUiState(
            chapter = FixedLearningChapter(
                "c1", 1,
                listOf(FixedLearningSentence("s1", english, "我表达得更清楚了。", true)),
            ),
            currentFocusSentenceId = "s1",
            baseExplanationZh = "用 express oneself 表达自己。",
            currentIncrementalHint = "本轮新提示：注意 clearly 的重音。",
            expressions = listOf(SelectableExpression("x1", "expressed", 2, 11)),
            explaining = false,
        )
        composeRule.setContent { RlTheme { ExplanationReadingScreen(state, actions::add) } }
        composeRule.onAllNodesWithText(english).assertCountEquals(1)
        assertVisibleOrScrollTo("用 express oneself 表达自己。")
        assertVisibleOrScrollTo("本轮新提示：注意 clearly 的重音。")
        composeRule.onAllNodesWithText("第 1 轮", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("返回").assertIsDisplayed()
        composeRule.onNodeWithText("静音").assertIsDisplayed()
        composeRule.onNodeWithText("我理解了").assertIsDisplayed()
        composeRule.onAllNodesWithText("继续朗读").assertCountEquals(0)
        composeRule.onNodeWithTag("explanation_content").assertIsDisplayed()
        composeRule.onNodeWithTag("explanation_understood").performClick()
        assert(actions.contains(ExplanationReadingAction.UnderstoodRequested))
    }
}
