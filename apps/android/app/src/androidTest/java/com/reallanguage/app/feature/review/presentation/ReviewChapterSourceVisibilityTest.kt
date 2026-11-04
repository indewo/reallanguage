package com.reallanguage.app.feature.review.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.review.domain.ReviewAbility
import com.reallanguage.app.feature.review.domain.ReviewAbilityModule
import com.reallanguage.app.feature.review.domain.ReviewAbilityResultDraft
import com.reallanguage.app.feature.review.domain.ReviewAbilityStatus
import com.reallanguage.app.feature.review.domain.ReviewChapterSnapshot
import com.reallanguage.app.feature.review.domain.ReviewContextSentence
import com.reallanguage.app.feature.review.domain.ReviewSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Original-source display in the existing review chapter state, never a PATTERN independent-answer fixture. */
class ReviewChapterSourceVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var expectedWidth = 1829
    private var expectedHeight = 860

    @Before fun landscape() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun equalEnglishTextsKeepTheirOwnChineseInTheSameLeftRightRowAndLegacyDoesNotInventTranslation() {
        val state = render()
        capture("pairing")
        sentences.forEach { sentence ->
            val chinese = sentenceText(sentence, english = false)
            chinese.assertExists()
            compose.onNodeWithTag("review-context-${sentence.sentenceId}").performScrollTo()
            val englishBounds = sentenceText(sentence, english = true).getUnclippedBoundsInRoot()
            val chineseBounds = chinese.getUnclippedBoundsInRoot()
            assertTrue("Each fixed sentence is English-left / Chinese-right, not two unrelated lists",
                englishBounds.right <= chineseBounds.left &&
                    abs(englishBounds.top.value - chineseBounds.top.value) <= 1f)
            assertFirstLineVisible(sentenceText(sentence, english = true))
            assertFirstLineVisible(chinese)
            val otherChinese = sentences.first { it.sentenceId != sentence.sentenceId }.textZh
            compose.onNode(hasText(otherChinese) and hasAnyAncestor(hasTestTag("review-context-${sentence.sentenceId}")),
                useUnmergedTree = true).assertDoesNotExist()
        }
        compose.runOnIdle {
            state.value = state.value.copy(chapter = requireNotNull(state.value.chapter).copy(originalSentences = null))
        }
        sentences.forEach { compose.onNodeWithText(it.textZh, useUnmergedTree = true).assertDoesNotExist() }
        compose.onNodeWithTag("review-chapter-continue").assertIsDisplayed()
    }

    @Test fun enteringAndChangingCurrentSentenceIdRevealItsFirstCompleteBilingualLineWithoutTestScrolling() {
        val state = render()
        capture("entry")
        assertCurrentSentenceVisible(sentences.last())
        compose.runOnIdle {
            state.value = state.value.copy(resultDrafts = listOf(ReviewAbilityResultDraft("ability-focus-two", "isolated-draft")))
        }
        compose.waitForIdle()
        capture("switched")
        assertCurrentSentenceVisible(sentences[1])
    }

    @Test fun recomposingTheSameCurrentSentenceDoesNotUndoARealUserScrollGesture() {
        val state = render()
        val scroll = sourceScroll()
        val before = scrollPosition(scroll)
        scroll.performTouchInput { swipeDown(durationMillis = 350) }
        compose.waitForIdle()
        val manuallySelectedPosition = scrollPosition(scroll)
        assertTrue("The real source gesture must move its actual viewport", abs(before - manuallySelectedPosition) > 1f)
        compose.runOnIdle {
            val chapter = requireNotNull(state.value.chapter)
            state.value = state.value.copy(chapter = chapter.copy(sourceTitle = "已保存的日记 · 已同步"))
        }
        compose.waitForIdle()
        assertEquals("An unchanged current sentence must not recenter after ordinary source metadata recomposition",
            manuallySelectedPosition, scrollPosition(sourceScroll()), 1f)
        compose.onNodeWithTag("review-context-focus-two").assertIsSelected()
        capture("manual-scroll")
    }

    @Test fun shortDoubleFontPreservesLongBilingualTextLocalScrollingAndEveryExistingAction() {
        val longSentence = sentences.last().copy(
            textEn = "I discussed the travel plan with my friend and explained what we need to prepare before leaving. ".repeat(7).trim(),
            textZh = "我和朋友讨论了旅行计划，并解释了出发之前需要准备的事情。".repeat(3),
        )
        val source = sentences.dropLast(1) + longSentence
        val actions = mutableListOf<ReviewChapterAction>()
        render(source, width = 640.dp, height = 360.dp, fontScale = 2f, onAction = actions::add, onlyCurrentAbility = true)
        capture("short-initial")
        assertCurrentSentenceVisible(longSentence)
        val english = sentenceText(longSentence, english = true)
        val layout = textLayout(english)
        textLayout(sentenceText(longSentence, english = false))
        assertTrue("Exercise genuinely long paragraphs rather than a one-line fixture", layout.lineCount > 4)
        sourceScroll().performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 100_000f)) }
        compose.waitForIdle()
        capture("short-source-end")
        assertLineVisible(english, layout, layout.lineCount - 1)
        val headerBefore = compose.onNodeWithText("统一回顾 · 第 2 章").getUnclippedBoundsInRoot()
        compose.onNodeWithText("整句会说", useUnmergedTree = true).performScrollTo()
        assertFirstLineVisible(compose.onNodeWithText("整句会说", useUnmergedTree = true))
        listOf("继续当前能力", "退出统一回顾？").forEach { text ->
            val node = compose.onNodeWithText(text, useUnmergedTree = true)
            val buttonText = textLayout(node)
            if (node.fetchSemanticsNode().boundsInRoot.height + 1f < buttonText.multiParagraph.height) node.performScrollTo()
            repeat(buttonText.lineCount) { assertLineVisible(node, buttonText, it) }
            node.assertIsDisplayed().performClick()
        }
        assertEquals(headerBefore, compose.onNodeWithText("统一回顾 · 第 2 章").getUnclippedBoundsInRoot())
        assertEquals(listOf(ReviewChapterAction.OpenCurrentModuleRequested, ReviewChapterAction.ExitRequested), actions)
        capture("short-actions")
    }

    private fun assertCurrentSentenceVisible(sentence: ReviewContextSentence) {
        compose.waitForIdle()
        compose.onNodeWithTag("review-context-${sentence.sentenceId}").assertIsSelected()
        assertFirstLineVisible(sentenceText(sentence, english = true))
        assertFirstLineVisible(sentenceText(sentence, english = false))
    }

    private fun sentenceText(sentence: ReviewContextSentence, english: Boolean) = compose.onNode(
        hasText(if (english) sentence.textEn else sentence.textZh) and
            hasAnyAncestor(hasTestTag("review-context-${sentence.sentenceId}")), useUnmergedTree = true,
    )

    private fun sourceScroll() = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("review-context-focus-two")))

    private fun scrollPosition(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        val diagnostic = "${layout.layoutInput.text}: size=${layout.size}, lines=${layout.lineCount}, paragraphHeight=${layout.multiParagraph.height}"
        assertFalse(diagnostic, layout.multiParagraph.didExceedMaxLines)
        assertEquals(diagnostic, layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue(diagnostic, layout.multiParagraph.height <= layout.size.height + 1f)
        repeat(layout.lineCount) { line ->
            assertFalse(diagnostic, layout.isLineEllipsized(line))
            assertTrue(diagnostic, layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
        }
        return layout
    }

    private fun assertFirstLineVisible(node: SemanticsNodeInteraction) = assertLineVisible(node, textLayout(node), 0)

    private fun assertLineVisible(node: SemanticsNodeInteraction, layout: TextLayoutResult, line: Int) {
        val visible = node.fetchSemanticsNode().boundsInRoot
        val original = node.getUnclippedBoundsInRoot()
        val top = original.top.value * 2f
        val left = original.left.value * 2f
        assertTrue("A complete requested line must be visible in its actual local clip: visible=$visible, original=$original, line=$line",
            top + layout.getLineTop(line) >= visible.top - 1f && top + layout.getLineBottom(line) <= visible.bottom + 1f &&
                left + layout.getLineLeft(line) >= visible.left - 1f && left + layout.getLineRight(line) <= visible.right + 1f)
    }

    private fun render(source: List<ReviewContextSentence> = sentences, width: Dp = 914.5.dp, height: Dp = 430.dp,
        fontScale: Float = 1f, onAction: (ReviewChapterAction) -> Unit = {}, onlyCurrentAbility: Boolean = false): MutableState<ReviewChapterUiState> {
        val abilities = if (onlyCurrentAbility) listOf(ability("focus-two")) else listOf(ability("focus-two"), ability("focus-one"))
        val snapshot = ReviewChapterSnapshot("review", "0a70c048-ad7b-4fa2-a4f0-8a00c877fd19", ReviewSourceType.DIARY,
            "已保存的日记", source.map { it.textEn }, source[1].textEn,
            abilities, originalChapterNumber = 2, originalSentences = source)
        val state = mutableStateOf(ReviewChapterUiState("review", "command", snapshot, loading = false))
        expectedWidth = (width.value * 2f).toInt()
        expectedHeight = (height.value * 2f).toInt()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                RlTheme {
                    Box(Modifier.requiredSize(width, height).testTag("review-source-visibility-viewport")) {
                        RlPaperBackground { ReviewChapterScreen(state.value, onAction) }
                    }
                }
            }
        }
        compose.waitForIdle()
        return state
    }

    private fun capture(stage: String) {
        val phase = InstrumentationRegistry.getArguments().getString("reviewSourceVisibilityCapture") ?: return
        require(phase in setOf("before", "after"))
        val bitmap = compose.onNodeWithTag("review-source-visibility-viewport").captureToImage().asAndroidBitmap()
        try {
            check(bitmap.width == expectedWidth && bitmap.height == expectedHeight)
            val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "visual-qa")
            check(folder.isDirectory || folder.mkdirs())
            File(folder, "37-review-source-$stage-$phase.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun ability(sentenceId: String) = ReviewAbility("ability-$sentenceId", ReviewAbilityModule.FULL_SENTENCE_SPEAKING,
        ReviewAbilityStatus.LEARNING, sentenceId = sentenceId)

    private val sentences = listOf(
        ReviewContextSentence("context-before", "The same words.", "前面的非重点上下文。", false, 0),
        ReviewContextSentence("focus-one", "The same words.", "第一处已确认的中文意思。", true, 1),
        ReviewContextSentence("context-between", "The same words.", "两句之间的非重点上下文。", false, 2),
        ReviewContextSentence("focus-two", "The same words.", "第二处不同的已确认中文意思。", true, 3),
    )
}
