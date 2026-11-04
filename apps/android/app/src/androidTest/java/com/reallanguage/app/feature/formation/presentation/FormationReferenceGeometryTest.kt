package com.reallanguage.app.feature.formation.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Narrow visual checks from immutable 20-learning-targets.png / 21-final-fix.png,
 * both 1846×852. They deliberately do not assert missing paper/stamp/font fidelity.
 * The isolated screen data is not evidence of AI extraction or successful persistence.
 */
class FormationReferenceGeometryTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun landscape() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
        composeRule.waitUntil(5_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test fun targetContinueUsesTheOriginalVisibleButtonRegionWithoutShrinkingItsTextOrLosingTheAction() {
        val actions = mutableListOf<LearningTargetAction>()
        render { LearningTargetScreen(targetState(), actions::add) }
        val label = composeRule.onNodeWithText("继续", useUnmergedTree = true)
        assertButtonLabel(label, "继续")
        val actual = primaryButtonInk(capture())
        // Inclusive source ink x1417..1665/y731..805. Measure the painted body,
        // not the larger minimum-interactive/touch region required by Android.
        assertInkBounds("20 Continue", Rect(1417f, 731f, 1666f, 806f), actual)
        label.assertIsDisplayed().performClick()
        // A smaller painted body must retain a larger usable touch target. This
        // physical tap is above the paint but inside the required 48dp target.
        composeRule.onNodeWithTag(VIEWPORT).performTouchInput { click(Offset(1541.5f, 726f)) }
        assertEquals(List(2) { LearningTargetAction.ContinueRequested }, actions)
    }

    @Test fun finalConfirmationUsesEqualAlignedActionsWithoutShrinkingTextOrLosingTheAction() {
        val actions = mutableListOf<FinalFixAction>()
        render { FinalFixScreen(finalState(), actions::add) }
        val label = composeRule.onNode(
            hasText("确认并固定") and hasAnyAncestor(hasTestTag("final_fix_confirm")), useUnmergedTree = true,
        )
        assertButtonLabel(label, "确认并固定")
        val back = composeRule.onNodeWithTag("final_fix_back").fetchSemanticsNode().boundsInRoot
        val confirm = composeRule.onNodeWithTag("final_fix_confirm").fetchSemanticsNode().boundsInRoot
        assertEquals("Paired actions use equal widths", back.width, confirm.width, 1f)
        assertEquals("Paired actions use equal heights", back.height, confirm.height, 1f)
        assertEquals("Paired actions share one top baseline", back.top, confirm.top, 1f)
        composeRule.onNodeWithTag("final_fix_confirm").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("final_fix_confirm").performClick()
        assertEquals(List(2) { FinalFixAction.ConfirmRequested }, actions)
    }

    @Test fun targetPageDoesNotAddTheGenericRedTopStripMissingFromItsOriginal() {
        val actions = mutableListOf<LearningTargetAction>()
        render { LearningTargetScreen(targetState(), actions::add) }
        assertNoRedAccent("20 outer paper", capture(), Rect(1542f, 20f, 1674f, 40f))
        composeRule.onNodeWithText("全部取消").assertIsDisplayed().performClick()
        assertEquals(listOf(LearningTargetAction.ClearAllRequested), actions)
    }

    @Test fun finalPageAndBothDocumentsDoNotAddGenericRedTopStripsMissingFromTheOriginal() {
        val actions = mutableListOf<FinalFixAction>()
        render { FinalFixScreen(finalState(), actions::add) }
        val image = capture()
        assertNoRedAccent("21 outer paper", image, Rect(1542f, 20f, 1674f, 40f))
        val viewport = composeRule.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
        listOf("final_fix_sentences", "final_fix_targets").forEach { tag ->
            val document = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            // The old 76dp accent is inset 32dp. Sample only its interior, well away
            // from existing green labels, English emphasis and the paper's beige tape.
            assertNoRedAccent(tag, image, Rect(
                document.right - viewport.left - 208f, document.top - viewport.top + 8f,
                document.right - viewport.left - 76f, document.top - viewport.top + 28f,
            ))
        }
        composeRule.onNodeWithText("返回修改").assertIsDisplayed().performClick()
        assertEquals(listOf(FinalFixAction.BackRequested), actions)
    }

    @Test fun finalSummaryEmphasizesBothActualCountsAndLeavesItsWordingInInk() {
        val chapters = (1..3).map { chapter -> EnglishChapterDraft("c$chapter", (1..4).map { sentence ->
            val id = "$chapter-$sentence"
            EnglishSentenceDraft("u$id", "确认过的第 $id 句。", true,
                listOf(EnglishCandidate("e$id", "Confirmed sentence $id.")))
        }) }
        val state = FinalFixUiState(chapters,
            chapters.flatMap { it.sentences }.associate { it.unitId to it.candidates.single().candidateId }, emptyList())
        render { FinalFixScreen(state, {}) }
        val text = "12 个重点句  ·  3 个章节"
        val layout = textLayout(composeRule.onNodeWithText(text, useUnmergedTree = true))
        assertEquals(text, layout.layoutInput.text.text)
        val countIndices = setOf(0, 1, text.indexOf('3'))
        text.indices.forEach { index ->
            val explicitColor = layout.layoutInput.text.spanStyles.lastOrNull {
                index >= it.start && index < it.end && it.item.color != Color.Unspecified
            }?.item?.color ?: layout.layoutInput.style.color
            assertEquals("Only the two actual counts are emphasized, character $index in [$text]",
                if (index in countIndices) RlColor.ActionVivid else RlColor.Ink, explicitColor)
        }
        assertEquals("Numeric emphasis must not reduce the established summary type size", 18f,
            layout.layoutInput.style.fontSize.value, .01f)
    }

    private fun assertButtonLabel(node: SemanticsNodeInteraction, text: String) {
        val layout = textLayout(node)
        assertEquals(text, layout.layoutInput.text.text)
        assertEquals("Do not shrink the established 16sp button text to hit source geometry", 16f,
            layout.layoutInput.style.fontSize.value, .01f)
        assertFalse("All button characters must be laid out", layout.multiParagraph.didExceedMaxLines)
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue("Button text height must be readable", layout.multiParagraph.height <= layout.size.height + 1f)
        repeat(layout.lineCount) {
            assertFalse(layout.isLineEllipsized(it))
            assertTrue(layout.getLineLeft(it) >= -1f)
            assertTrue(layout.getLineRight(it) <= layout.size.width + 1f)
        }
    }

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }

    private fun assertInkBounds(name: String, expected: Rect, actual: Rect) {
        // Three raster pixels cover anti-aliased torn/rounded edges, not a layout shift.
        assertEquals("$name visible left: $actual", expected.left, actual.left, 3f)
        assertEquals("$name visible top: $actual", expected.top, actual.top, 3f)
        assertEquals("$name visible right: $actual", expected.right, actual.right, 3f)
        assertEquals("$name visible bottom: $actual", expected.bottom, actual.bottom, 3f)
    }

    private fun primaryButtonInk(image: ImageBitmap): Rect {
        val pixels = image.toPixelMap()
        var left = image.width; var top = image.height; var right = -1; var bottom = -1
        // Only the primary action lives in this lower-right ROI. This broad hue test
        // accepts both the existing and source terracotta; it does not sneak in a color redesign.
        for (y in 710 until 830) for (x in 1300 until 1760) {
            if (isRed(pixels[x, y])) {
                left = minOf(left, x); top = minOf(top, y)
                right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        assertTrue("The real rendered button must have a non-empty painted body", right >= left && bottom >= top)
        return Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
    }

    private fun assertNoRedAccent(name: String, image: ImageBitmap, region: Rect) {
        val pixels = image.toPixelMap()
        var red = 0; var total = 0
        for (y in region.top.toInt() until region.bottom.toInt()) {
            for (x in region.left.toInt() until region.right.toInt()) {
                if (isRed(pixels[x, y])) red += 1
                total += 1
            }
        }
        assertTrue("$name must not add an unapproved red strip at $region; red=$red/$total",
            total > 0 && red.toFloat() / total < .01f)
    }

    private fun isRed(color: Color) = color.red > .55f && color.green < .48f && color.blue < .42f &&
        color.red > color.green * 1.7f

    private fun capture(): ImageBitmap = composeRule.onNodeWithTag(VIEWPORT).captureToImage().also {
        assertEquals("Run at the source viewport, without screenshot scaling", 1846, it.width)
        assertEquals(852, it.height)
    }

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                RlTheme {
                    Box(Modifier.requiredSize(923.dp, 426.dp).testTag(VIEWPORT)) {
                        RlPaperBackground { content() }
                    }
                }
            }
        }
    }

    private fun targetState() = LearningTargetUiState(
        chapters = listOf(EnglishChapterDraft("c1", listOf(secondSentence("u1", "e1")))),
        selectedCandidateIds = mapOf("u1" to "e1"), targets = sourceTargets("u1"),
        selectedTargetIds = setOf("t1", "t2", "t3"), queryableExpressions = emptyList(),
    )

    private fun finalState() = FinalFixUiState(
        chapters = listOf(
            EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "今天开会时，我本来有点紧张。", true,
                listOf(EnglishCandidate("e1", "I was a little nervous during today’s meeting."))))),
            EnglishChapterDraft("c2", listOf(secondSentence("u2", "e2"))),
        ), selectedCandidateIds = mapOf("u1" to "e1", "u2" to "e2"), selectedTargets = sourceTargets("u2"),
    )

    private fun secondSentence(unitId: String, candidateId: String) = EnglishSentenceDraft(unitId,
        "但最后还是鼓起勇气把想法讲清楚了。", true,
        listOf(EnglishCandidate(candidateId, "But in the end, I worked up the courage to explain my idea clearly.")))

    // These reproduce the provided visual sample, not the production extraction contract.
    // Its historical WORD/FIXED_EXPRESSION classification must not become an algorithm assertion.
    private fun sourceTargets(unitId: String) = listOf(
        LearningTarget("t1", unitId, "work up the courage", "鼓起勇气", LearningTargetType.WORD),
        LearningTarget("t2", unitId, "explain", "解释；讲清楚", LearningTargetType.FIXED_EXPRESSION),
        LearningTarget("t3", unitId, "work up the courage to + 动词", "鼓起勇气做某事", LearningTargetType.PATTERN,
            structureParts = listOf("work up the courage to", "动词"), relatedVocabulary = listOf("courage")),
    )

    private companion object { const val VIEWPORT = "formation-reference-viewport" }
}
