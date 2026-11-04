package com.reallanguage.app.feature.formation.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.click
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.collectAsState
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.LearningTargetGenerationResult
import com.reallanguage.app.feature.formation.domain.LearningTargetGenerator
import com.reallanguage.app.feature.formation.domain.QueryableExpression
import org.junit.Rule
import org.junit.Test

class LearningTargetScreenTest {
    @get:Rule val composeRule = androidx.compose.ui.test.junit4.v2.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @org.junit.Before fun landscape() {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun twoReferenceCardsKeepExamplesInsidePaperAndShareTheAvailableRow() {
        val targets = listOf(
            LearningTarget("run", "u1", "ran all the way", "一路跑；一直跑到", LearningTargetType.FIXED_EXPRESSION,
                candidateExample = com.reallanguage.app.feature.formation.domain.CandidateExample("She ran all the way home.", "她一路跑回了家。")),
            LearningTarget("platform", "u1", "platform", "站台", LearningTargetType.WORD,
                candidateExample = com.reallanguage.app.feature.formation.domain.CandidateExample("We waited on the platform.", "我们在站台上等着。")),
        )
        val state = LearningTargetUiState(
            listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "一路跑到站台，", true,
                listOf(EnglishCandidate("e1", "and I ran all the way to the platform.")))))),
            mapOf("u1" to "e1"), targets, targets.map { it.targetId }.toSet(), emptyList(),
        )
        composeRule.setContent { RlTheme { LearningTargetScreen(state, {}) } }
        targets.forEach { target ->
            val card = composeRule.onNodeWithTag("learning_target_${target.targetId}").getUnclippedBoundsInRoot()
            val example = composeRule.onNodeWithTag("learning_target_${target.targetId}_example_zh", useUnmergedTree = true)
            example.assertIsDisplayed()
            val bounds = example.getUnclippedBoundsInRoot()
            org.junit.Assert.assertTrue("Bottom example must fit entirely inside paper", bounds.bottom <= card.bottom)
        }
        val first = composeRule.onNodeWithTag("learning_target_run").fetchSemanticsNode().boundsInRoot
        val second = composeRule.onNodeWithTag("learning_target_platform").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(first.top, second.top, 1f)
        org.junit.Assert.assertEquals(first.width, second.width, 1f)
        org.junit.Assert.assertEquals(first.bottom, second.bottom, 1f)
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag("formation_chapter_badge", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        org.junit.Assert.assertEquals(1, layouts.single().lineCount)
        org.junit.Assert.assertFalse(layouts.single().hasVisualOverflow)
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "target-two-cards.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun narrowDoubleFontCategoryLabelsRemainInsideTheirIndependentRibbons() {
        val targets = LearningTargetType.entries.mapIndexed { index, type ->
            LearningTarget("ribbon$index", "u1", "Target $index", "完整释义。".repeat(index * 12 + 1), type)
        }
        val state = LearningTargetUiState(
            listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "我解释清楚了。", true,
                listOf(EnglishCandidate("e1", "I explained clearly.")))))),
            mapOf("u1" to "e1"), targets, targets.map { it.targetId }.toSet(), emptyList(),
        )
        composeRule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f),
            ) { RlTheme { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(640.dp, 360.dp)) {
                LearningTargetScreen(state, {})
            } } }
        }
        targets.forEach { target ->
            val prefix = "learning_target_${target.targetId}"
            val label = composeRule.onNodeWithTag("${prefix}_category", useUnmergedTree = true)
            label.performScrollTo().assertIsDisplayed()
            val ribbon = composeRule.onNodeWithTag("${prefix}_ribbon", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val text = label.getUnclippedBoundsInRoot()
            org.junit.Assert.assertTrue("${target.type} label must stay within its own colored ribbon",
                text.left >= ribbon.left && text.right <= ribbon.right && text.top >= ribbon.top && text.bottom <= ribbon.bottom)
            val layouts = mutableListOf<TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            org.junit.Assert.assertFalse("Category text cannot truncate at 200%", layout.multiParagraph.didExceedMaxLines)
            org.junit.Assert.assertTrue("Category line must remain complete", layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 1f)
        }
    }

    @Test fun shortScreenAtDoubleFontKeepsAllContentScrollableAndActionsReachable() {
        val english = "I want to explain this clearly. ".repeat(12)
        val chinese = "我希望能清楚解释这件事。".repeat(12)
        val state = LearningTargetUiState(
            listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", chinese, true, listOf(EnglishCandidate("e1", english)))))),
            mapOf("u1" to "e1"),
            (1..12).map { LearningTarget("t$it", "u1", "Target $it", "完整解释".repeat(10), LearningTargetType.WORD) },
            setOf("t1"), emptyList(), generationFailed = true,
        )
        composeRule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f),
            ) { RlTheme { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(640.dp, 360.dp)) {
                LearningTargetScreen(state, {})
            } } }
        }
        composeRule.waitForIdle()
        val beforeInstrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        beforeInstrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(beforeInstrumentation.targetContext.filesDir, "learning-target-double-font-before-validation.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        listOf("选择学习目标", "句子会继续学习，下面的词义与句式可以按需选择。", "全部取消", "返回", "继续").forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val measured = layouts.single()
            // Paragraph width includes unused horizontal space for wrap-content text.
            // Check actual line edges and full height, not that empty-space discrepancy.
            org.junit.Assert.assertFalse("Truncated lines at 200% font: $text", measured.multiParagraph.didExceedMaxLines)
            org.junit.Assert.assertTrue("Clipped height at 200% font: $text", measured.multiParagraph.height <= measured.size.height + 1f)
            repeat(measured.lineCount) { line ->
                org.junit.Assert.assertTrue("Clipped left edge at 200% font: $text", measured.getLineLeft(line) >= -1f)
                org.junit.Assert.assertTrue("Clipped right edge at 200% font: $text", measured.getLineRight(line) <= measured.size.width + 1f)
            }
        }
        composeRule.onNodeWithText("Target 12").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("学习目标生成失败").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("全部取消后仍保留句子级学习。").assertCountEquals(0)
        composeRule.onAllNodesWithText("本页选择范围", substring = true).assertCountEquals(0)
        listOf("全部取消", "返回", "继续").forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(instrumentation.targetContext.filesDir, "learning-target-double-font.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun tappingWordsAcrossPagesKeepsSelectionsAndDoesNotRegenerateOnToggle() {
        val calls = mutableListOf<Pair<String, String>>()
        val sentences = (1..2).map { index -> EnglishSentenceDraft(
            "u$index", "第 $index 句", true,
            listOf(EnglishCandidate("e$index", if (index == 1) "Expressed clearly." else "Expressed gently.")),
        ) }
        lateinit var viewModel: LearningTargetViewModel
        composeRule.runOnUiThread {
            viewModel = LearningTargetViewModel(
                listOf(EnglishChapterDraft("c1", sentences)),
                mapOf("u1" to "e1", "u2" to "e2"), emptyList(), emptyList(),
                LearningTargetGenerator { unitId, expressionId ->
                    calls += unitId to expressionId
                    LearningTargetGenerationResult.Added(LearningTarget("t-$unitId", unitId, "Expressed", "表达了", LearningTargetType.WORD))
                },
            )
        }
        composeRule.setContent { RlTheme { LearningTargetScreen(viewModel.uiState.collectAsState().value, viewModel::onAction) } }
        fun tapWord(text: String) {
            val layouts = mutableListOf<TextLayoutResult>()
            val node = composeRule.onNodeWithText(text)
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            node.performTouchInput { click(layouts.single().getBoundingBox(0).center) }
            composeRule.waitForIdle()
        }
        tapWord("Expressed clearly.")
        composeRule.runOnIdle { org.junit.Assert.assertEquals(setOf("t-u1"), viewModel.uiState.value.selectedTargetIds) }
        tapWord("Expressed clearly.")
        composeRule.runOnIdle { org.junit.Assert.assertTrue(viewModel.uiState.value.selectedTargetIds.isEmpty()) }
        tapWord("Expressed clearly.")
        composeRule.onNodeWithText("继续").performClick()
        tapWord("Expressed gently.")
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(listOf("u1" to "word:0", "u2" to "word:0"), calls)
            org.junit.Assert.assertEquals(setOf("t-u1", "t-u2"), viewModel.uiState.value.selectedTargetIds)
            org.junit.Assert.assertEquals(listOf("u1", "u2"), viewModel.uiState.value.targets.map { it.unitId })
        }
        composeRule.onNodeWithText("返回").performClick()
        composeRule.onNodeWithText("Expressed clearly.").assertIsDisplayed()
        composeRule.onNodeWithTag("learning_target_t-u1").assertIsDisplayed()
        composeRule.onNodeWithTag("learning_target_t-u2").assertDoesNotExist()
    }

    @Test fun targetCardsKeepReferenceHorizontalInset() {
        val state = LearningTargetUiState(
            chapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "中文", true, listOf(EnglishCandidate("e1", "English sentence.")))))),
            selectedCandidateIds = mapOf("u1" to "e1"),
            targets = listOf(LearningTarget("t1", "u1", "sentence", "句子", LearningTargetType.WORD)),
            selectedTargetIds = setOf("t1"), queryableExpressions = emptyList(),
        )
        composeRule.setContent { RlTheme { LearningTargetScreen(state, {}) } }
        val page = composeRule.onNodeWithTag("learning_targets_page").fetchSemanticsNode().boundsInRoot
        val card = composeRule.onNodeWithTag("learning_target_t1").fetchSemanticsNode().boundsInRoot
        val inset = (card.left - page.left) / page.width
        org.junit.Assert.assertTrue("Card starts too far left: $inset", inset > .10f)
        org.junit.Assert.assertTrue("Card starts too far right: $inset", inset < .13f)
        val label = composeRule.onNodeWithText("词义", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Label inherited an oversized default line height", label.height / page.height < .065f)
    }

    @Test fun longBilingualSentenceDoesNotLoseLinesInsideItsPanel() {
        val english = "I want to explain this clearly. ".repeat(30)
        val chinese = "我希望能够清楚地解释这件事情。".repeat(30)
        val state = LearningTargetUiState(
            chapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", chinese, true, listOf(EnglishCandidate("e1", english)))))),
            selectedCandidateIds = mapOf("u1" to "e1"), targets = emptyList(), selectedTargetIds = emptySet(), queryableExpressions = emptyList(),
        )
        composeRule.setContent { RlTheme { LearningTargetScreen(state, {}) } }
        listOf(english, chinese).forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            org.junit.Assert.assertFalse("Long sentence has clipped lines", layouts.single().hasVisualOverflow)
        }
        val panel = composeRule.onNodeWithTag("learning_targets_sentence").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("The sentence paper must grow with wrapped content instead of scrolling each language cell", panel.height > 76f)
    }

    @Test fun bilingualSentenceUsesReadableTypeAndIsVerticallyCenteredOnThePaper() {
        val english = "Sometimes I don't even know what to write."
        val chinese = "有时候不知道自己要写啥。"
        val state = LearningTargetUiState(
            chapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", chinese, true, listOf(EnglishCandidate("e1", english)))))),
            selectedCandidateIds = mapOf("u1" to "e1"), targets = emptyList(), selectedTargetIds = emptySet(), queryableExpressions = emptyList(),
        )
        composeRule.setContent { RlTheme { LearningTargetScreen(state, {}) } }

        val panel = composeRule.onNodeWithTag("learning_targets_sentence").fetchSemanticsNode().boundsInRoot
        listOf("learning_targets_english", "learning_targets_chinese").forEach { tag ->
            val node = composeRule.onNodeWithTag(tag)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            org.junit.Assert.assertTrue("Sentence text must not use the previous tiny 9.5sp style", layouts.single().layoutInput.style.fontSize.value >= 15f)
            org.junit.Assert.assertTrue("Sentence text must be vertically centered", kotlin.math.abs(bounds.center.y - panel.center.y) < panel.height * .2f)
        }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "learning-target-readable-reference.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test
    fun onlyCurrentSentenceTargetsAreRendered() {
        val sentences = (1..2).map { index -> EnglishSentenceDraft(
            "u$index", "第 $index 句", true,
            listOf(EnglishCandidate("e$index", "Sentence $index")),
        ) }
        val state = LearningTargetUiState(
            chapters = sentences.mapIndexed { index, sentence -> EnglishChapterDraft("c${index + 1}", listOf(sentence)) },
            selectedCandidateIds = mapOf("u1" to "e1", "u2" to "e2"),
            targets = (1..2).map { LearningTarget("t$it", "u$it", "target $it", "解释", LearningTargetType.WORD) },
            selectedTargetIds = setOf("t1", "t2"),
            queryableExpressions = emptyList(),
            focusSentenceIndex = 1,
        )
        composeRule.setContent { RlTheme { LearningTargetScreen(state, {}) } }
        composeRule.onNodeWithText("Sentence 2").assertIsDisplayed()
        composeRule.onNodeWithText("第 2 章").assertIsDisplayed()
        composeRule.onNodeWithTag("learning_target_t1").assertDoesNotExist()
        composeRule.onNodeWithTag("learning_target_t2").assertIsDisplayed()
        val sentenceWidth = composeRule.onNodeWithTag("learning_targets_sentence").fetchSemanticsNode().boundsInRoot.width
        val cardWidth = composeRule.onNodeWithTag("learning_target_t2").fetchSemanticsNode().boundsInRoot.width
        org.junit.Assert.assertTrue("One real target must retain its card width, not stretch across all three slots", cardWidth < sentenceWidth / 2)
    }

    @Test
    fun showsBilingualChapterAndUniqueTargetDetails() {
        val actions = mutableListOf<LearningTargetAction>()
        val chapter = EnglishChapterDraft(
            "c1",
            listOf(
                EnglishSentenceDraft(
                    "u1", "我想表达得更清楚。", true,
                    listOf(EnglishCandidate("e1", "I want to express myself more clearly.")),
                ),
            ),
        )
        val target = LearningTarget(
            "t1", "u1", "express oneself", "表达自己", LearningTargetType.PATTERN,
            structureParts = listOf("express", "oneself"),
            relatedVocabulary = listOf("express"),
        )
        val state = LearningTargetUiState(
            chapters = listOf(chapter),
            selectedCandidateIds = mapOf("u1" to "e1"),
            targets = listOf(target),
            selectedTargetIds = setOf("t1"),
            queryableExpressions = listOf(QueryableExpression("q1", "u1", "more clearly")),
        )
        composeRule.setContent { RlTheme { LearningTargetScreen(state, actions::add) } }
        listOf(
            "我想表达得更清楚。",
            "I want to express myself more clearly.",
            "express oneself",
            "表达自己",
            "结构：express + oneself",
            "相关生词：express",
            "其他可添加词或词组",
            "more clearly",
        ).forEach { composeRule.onNodeWithText(it).fetchSemanticsNode() }
        composeRule.onNodeWithTag("learning_targets_header").assertIsDisplayed()
        composeRule.onNodeWithTag("learning_targets_sentence").assertIsDisplayed()
        composeRule.onNodeWithTag("learning_target_t1").performClick()
        assert(actions.contains(LearningTargetAction.TargetToggled("t1")))
        val textLayouts = mutableListOf<TextLayoutResult>()
        val sentenceNode = composeRule.onNodeWithText("I want to express myself more clearly.")
        sentenceNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
        sentenceNode.performTouchInput { click(textLayouts.single().getBoundingBox(0).center) }
        org.junit.Assert.assertTrue(actions.contains(LearningTargetAction.ExpressionRequested("u1", "word:0")))
    }

    @Test
    fun additionalExpressionsExposeChineseMeaningAndClearMultiSelectState() {
        val actions = mutableListOf<LearningTargetAction>()
        val sentence = EnglishSentenceDraft("u1", "我说得很清楚。", true, listOf(EnglishCandidate("e1", "I spoke clearly.")))
        val state = LearningTargetUiState(
            chapters = listOf(EnglishChapterDraft("c1", listOf(sentence))),
            selectedCandidateIds = mapOf("u1" to "e1"),
            targets = emptyList(),
            selectedTargetIds = emptySet(),
            queryableExpressions = listOf(QueryableExpression("word:8", "u1", "clearly", "清楚地；明确地")),
        )
        composeRule.setContent { RlTheme { LearningTargetScreen(state, actions::add) } }

        composeRule.onNodeWithText("其他可添加词或词组").assertIsDisplayed()
        composeRule.onNodeWithText("多选 · 点选后自动加入并显示 ✓").assertIsDisplayed()
        composeRule.onNodeWithText("清楚地；明确地").assertIsDisplayed()
        composeRule.onNodeWithTag("learning_expression_word:8").performClick()
        org.junit.Assert.assertTrue(actions.contains(LearningTargetAction.ExpressionRequested("u1", "word:8")))
    }
}
