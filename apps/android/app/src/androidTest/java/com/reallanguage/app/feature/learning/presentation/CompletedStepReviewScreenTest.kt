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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.feature.learning.domain.CompletedStepSnapshot
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class CompletedStepReviewScreenTest {
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

    @Test fun referenceExpressionKeepsPlaybackAndExplanationControlsFullyVisible() {
        val english = "But in the end, I worked up the courage to explain my idea clearly."
        val sentences = listOf(
            FixedLearningSentence("before", "I was a little nervous during today’s meeting.", "今天开会时，我本来有点紧张。", false),
            FixedLearningSentence("focus", english, "但最后还是鼓起勇气把想法讲清楚了。", true),
            FixedLearningSentence("after", "My colleague nodded after listening.", "同事听完后点了点头。", false),
        )
        val actions = mutableListOf<CompletedStepReviewAction>()
        val snapshot = CompletedStepSnapshot("LR-02", "第 1 章 · 基础讲解", english, sentences[1].textZh, "完整基础讲解",
            contextSentences = sentences, activeSentenceId = "focus",
            reviewedExpression = com.reallanguage.app.feature.learning.domain.CompletedStepExpression(
                com.reallanguage.app.feature.learning.domain.SelectableExpression("x", "worked up the courage", 18, 39),
                com.reallanguage.app.feature.learning.domain.ExpressionExplanation("x", "鼓起勇气",
                    naturalExample = com.reallanguage.app.feature.learning.domain.NaturalExpressionExample(
                        "She worked up the courage to ask.", "她鼓起勇气问了。"),
                    contextualMeaningZh = "鼓起勇气", usageZh = "常用于描述终于敢去做一件原本犹豫的事。",
                    expressionHintZh = "work up the courage to + 动词")))
        composeRule.setContent { RlTheme { androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.requiredSize(915.5.dp, 429.5.dp),
        ) { CompletedStepReviewScreen(CompletedStepReviewUiState(snapshot), actions::add) } } }
        val playback = composeRule.onNode(hasText("发音") and hasAnyAncestor(hasTestTag("completed-step-playback")), useUnmergedTree = true)
        val explanation = composeRule.onNode(hasText("查看解释") and hasAnyAncestor(hasTestTag("completed-step-explanation-toggle")), useUnmergedTree = true)
        for (node in listOf(playback, explanation)) {
            node.assertIsDisplayed()
            val height = node.fetchSemanticsNode().boundsInRoot.height
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            org.junit.Assert.assertTrue("The reference-state control label must be fully visible without scrolling",
                layouts.all { it.size.height <= height + 1f })
        }
        val hint = composeRule.onNodeWithText("work up the courage to + 动词")
        hint.assertIsDisplayed()
        val hintLayouts = mutableListOf<TextLayoutResult>()
        hint.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(hintLayouts) }
        org.junit.Assert.assertTrue("Reference hint must remain fully visible alongside fixed controls",
            hintLayouts.all { it.size.height <= hint.fetchSemanticsNode().boundsInRoot.height + 1f })
        // A shorter context frame must not hide the last original context row.
        for (sentence in listOf(sentences.first(), sentences.last())) {
            for (text in listOf(sentence.textEn, sentence.textZh)) {
                val node = composeRule.onNodeWithText(text)
                node.assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                org.junit.Assert.assertTrue("Original context text must be fully visible: $text",
                    layouts.all { it.size.height <= node.fetchSemanticsNode().boundsInRoot.height + 1f })
            }
        }
        playback.performClick()
        explanation.performClick()
        org.junit.Assert.assertEquals(listOf(CompletedStepReviewAction.AudioToggled,
            CompletedStepReviewAction.ExplanationToggled), actions)
        composeRule.onNodeWithTag("completed-step-return").assertIsDisplayed()
    }

    @Test fun structuredReadOnlyExpressionKeepsMeaningUsageAndHintSeparate() {
        val explanation = com.reallanguage.app.feature.learning.domain.ExpressionExplanation(
            "x", "旧完整词语解释", contextualMeaningZh = "平静的",
            usageZh = "形容人在当前情境中不紧张。", expressionHintZh = "stay calm：保持冷静")
        val snapshot = CompletedStepSnapshot("LR-02", "第 1 章 · 基础讲解", "I felt calm.", "我很平静。", null,
            reviewedExpression = com.reallanguage.app.feature.learning.domain.CompletedStepExpression(
                com.reallanguage.app.feature.learning.domain.SelectableExpression("x", "calm", 7, 11), explanation))
        composeRule.setContent { RlTheme { CompletedStepReviewScreen(CompletedStepReviewUiState(snapshot), {}) } }
        listOf("平静的", "形容人在当前情境中不紧张。", "stay calm：保持冷静").forEach {
            composeRule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        org.junit.Assert.assertTrue(composeRule.onAllNodesWithText("旧完整词语解释").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("completed-step-return").assertIsDisplayed()
    }

    @Test fun legacyReadOnlyExpressionIsNotDuplicatedAsIndependentUsage() {
        val snapshot = CompletedStepSnapshot("LR-02", "第 1 章 · 基础讲解", "I felt calm.", "我很平静。", null,
            reviewedExpression = com.reallanguage.app.feature.learning.domain.CompletedStepExpression(
                com.reallanguage.app.feature.learning.domain.SelectableExpression("x", "calm", 7, 11),
                com.reallanguage.app.feature.learning.domain.ExpressionExplanation("x", "旧混合解释")))
        composeRule.setContent { RlTheme { CompletedStepReviewScreen(CompletedStepReviewUiState(snapshot), {}) } }
        composeRule.onNodeWithText("此份讲解未包含独立用法说明。").performScrollTo().assertIsDisplayed()
        org.junit.Assert.assertEquals(1, composeRule.onAllNodesWithText("旧混合解释").fetchSemanticsNodes().size)
    }

    @Test fun referenceViewportKeepsExpressionPanelWithinTheOriginalContentFrame() {
        composeRule.setContent { RlTheme { androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.requiredSize(915.5.dp, 429.5.dp),
        ) { CompletedStepReviewScreen(CompletedStepReviewUiState(CompletedStepSnapshot(
            "LR-02", "第 1 章 · 基础讲解", "I felt calm.", "我很平静。", null,
        )), {}) } } }
        val bounds = composeRule.onNodeWithTag("completed-step-expression-panel").getUnclippedBoundsInRoot()
        val sheet = composeRule.onNodeWithTag("completed-step-review-sheet").getUnclippedBoundsInRoot()
        val expectedInset = sheet.left.value + (sheet.right - sheet.left).value * .105f
        // This tag measures the layout container, not the inset opaque pixels of
        // the PNG. Actual paper-edge fidelity remains a same-size screenshot check.
        org.junit.Assert.assertEquals("Archive container must preserve its 10.5% book gutter: $bounds",
            expectedInset, bounds.left.value, 1f)
        org.junit.Assert.assertTrue("Original panel width is about 621px, actual $bounds", (bounds.right - bounds.left).value in 304f..320f)
    }

    @Test fun shortScreenAtDoubleFontKeepsTitleProtectionAndReturnReadable() {
        composeRule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f),
            ) { RlTheme { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.then(
                androidx.compose.ui.Modifier.size(640.dp, 360.dp))) {
                CompletedStepReviewScreen(CompletedStepReviewUiState(CompletedStepSnapshot("LR-02", "第 3 章 · 基础讲解与三轮朗读",
                    "This is saved content. ".repeat(12), "这是已经保存的学习内容。".repeat(12), "已保存的讲解")), {})
            } } }
        }
        listOf("第 3 章 · 基础讲解与三轮朗读", "已完成·只读查看", "返回当前任务").forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val measured = layouts.single()
            org.junit.Assert.assertFalse(measured.multiParagraph.didExceedMaxLines)
            org.junit.Assert.assertTrue("Clipped height: $text", measured.multiParagraph.height <= measured.size.height + 1)
        }
        composeRule.onNodeWithTag("completed-step-protection").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("completed-step-return").assertIsDisplayed()
    }

    @Test fun savedExpressionAndExampleAreRenderedInsteadOfRepeatingTheWholeSentence() {
        val snapshot = CompletedStepSnapshot("LR-02", "基础讲解", "I felt calm.", "我很平静。", "过去时",
            reviewedExpression = com.reallanguage.app.feature.learning.domain.CompletedStepExpression(
                com.reallanguage.app.feature.learning.domain.SelectableExpression("word-1", "felt", 2, 6),
                com.reallanguage.app.feature.learning.domain.ExpressionExplanation("word-1", "感到；feel 的过去式", "/felt/",
                    com.reallanguage.app.feature.learning.domain.NaturalExpressionExample("She felt happy.", "她感到开心。"),
                    contextualMeaningZh = "感到", usageZh = "feel 的过去式")))
        composeRule.setContent { RlTheme { CompletedStepReviewScreen(CompletedStepReviewUiState(snapshot), {}) } }
        val label = composeRule.onNodeWithText("用法说明").getUnclippedBoundsInRoot()
        val explanation = composeRule.onNodeWithText("feel 的过去式").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue("Usage label and its content must share the original row",
            kotlin.math.abs((label.top + label.bottom).value - (explanation.top + explanation.bottom).value) < 8f)
        composeRule.onNodeWithText("felt").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("/felt/").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("She felt happy.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("她感到开心。").performScrollTo().assertIsDisplayed()
    }

    @Test fun realChapterContextIsVisibleButCannotChangeCompletedStep() {
        val sentences = listOf(
            FixedLearningSentence("s1", "I arrived early.", "我很早就到了。", false),
            FixedLearningSentence("s2", "I explained my idea.", "我解释了自己的想法。", true),
            FixedLearningSentence("s3", "My colleague nodded.", "同事点了点头。", false),
        )
        composeRule.setContent { RlTheme { CompletedStepReviewScreen(
            CompletedStepReviewUiState(CompletedStepSnapshot(
                "LR-02", "第 3 章 · 基础讲解", sentences[1].textEn, sentences[1].textZh,
                explanationZh = null,
                contextSentences = sentences, activeSentenceId = "s2",
            )), {},
        ) } }
        composeRule.onNodeWithText("第 3 章 · 基础讲解").assertIsDisplayed()
        listOf(sentences.first(), sentences.last()).forEach { sentence ->
            composeRule.onNodeWithText(sentence.textEn).performScrollTo().assertIsDisplayed().assertHasNoClickAction()
            composeRule.onNodeWithText(sentence.textZh).performScrollTo().assertIsDisplayed().assertHasNoClickAction()
        }
        composeRule.onNodeWithTag("completed-step-highlight-row").assertHasNoClickAction()
        composeRule.onNodeWithTag("completed-step-return").assertIsDisplayed()
    }

    @Test fun longReadOnlyContentAndExplanationCanBeReadWithoutClippingPlaybackControls() {
        val english = "This completed sentence remains available to review. ".repeat(12)
        val chinese = "这句已经完成，返回查看必须能够读全。".repeat(12)
        val explanation = "已保存的讲解内容。".repeat(30)
        composeRule.setContent { RlTheme { CompletedStepReviewScreen(
            CompletedStepReviewUiState(CompletedStepSnapshot("LR-02", "基础讲解", english, chinese, explanation), explanationVisible = true), {},
        ) } }
        listOf(english, chinese).forEach { text ->
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().indices.forEach { index ->
                val layouts = mutableListOf<TextLayoutResult>()
                composeRule.onAllNodesWithText(text)[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                org.junit.Assert.assertFalse("Read-only text is still limited to a few lines", layouts.single().multiParagraph.didExceedMaxLines)
            }
        }
        val playback = composeRule.onNodeWithTag("completed-step-playback")
        val playbackBefore = playback.fetchSemanticsNode().boundsInRoot
        // Only the expression-audio icon belongs to scrollable content; the
        // separate playback and explanation actions intentionally stay fixed.
        composeRule.onNodeWithTag("completed-step-expression-audio").performScrollTo().assertIsDisplayed()
        playback.assertIsDisplayed()
        composeRule.onNodeWithTag("completed-step-explanation-toggle").assertIsDisplayed()
        composeRule.onNodeWithText(explanation).performScrollTo().assertIsDisplayed()
        org.junit.Assert.assertEquals(playbackBefore, playback.fetchSemanticsNode().boundsInRoot)
        composeRule.onNodeWithTag("completed-step-return").assertIsDisplayed()
    }

    @Test
    fun showsReadOnlyArchiveAndReturnsToCurrentTask() {
        val actions = mutableListOf<CompletedStepReviewAction>()
        val state = CompletedStepReviewUiState(
            snapshot = CompletedStepSnapshot(
                stepId = "LR-02",
                titleZh = "第 1 章·基础讲解",
                contentEn = "But in the end, I worked up the courage to explain my idea clearly.",
                contentZh = "但最后还是鼓起勇气把想法讲清楚了。",
                explanationZh = "常用于描述终于敢去做一件原本犹豫的事。",
            ),
            explanationVisible = true,
        )

        composeRule.setContent { RlTheme { CompletedStepReviewScreen(state, actions::add) } }

        composeRule.onNodeWithTag("completed-step-review-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("completed-step-highlight-row").assertIsDisplayed()
        composeRule.onNodeWithText("第 1 章 · 基础讲解").assertIsDisplayed()
        composeRule.onNodeWithText("已完成·只读查看").assertIsDisplayed()
        composeRule.onNodeWithTag("completed-step-return").assertIsDisplayed().performClick()
        assert(actions == listOf(CompletedStepReviewAction.ReturnToCurrentTaskRequested))
    }
}
