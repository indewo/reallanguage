package com.reallanguage.app.feature.knowledge.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.PatternExample
import com.reallanguage.app.feature.knowledge.domain.PatternRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Fixed 1774x887/d320/font1 reference fixture; production physical Insets are verified separately. */
class PatternBookLayoutTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun landscape() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun actualListOccupiesTheOriginalLedgerRegionWithoutAnInventedLeftContentColumn() {
        render()
        composeRule.onNodeWithText("句式档案").assertDoesNotExist()
        val root = referenceBounds()
        val list = composeRule.onNodeWithTag("pattern-book-list").fetchSemanticsNode().boundsInRoot
        // Reference 42 keeps the architectural sketch in its left margin;
        // the collapsed entries start at x489, below the caption at y264.
        assertTrue("Collapsed rows preserve the reference's left illustration margin: $list",
            list.left - root.left in 480f..500f)
        assertTrue("Original ledger list starts around y264: $list", list.top - root.top in 250f..280f)
        assertTrue("Original table reaches x≈1615: $list", list.right - root.left in 1590f..1635f)
    }

    @Test fun selectedDetailsBelongToThatExactRowBeforeTheFollowingPattern() {
        render(expanded = "p1")
        referenceBounds()
        composeRule.onNode(hasTestTag("pattern-detail-sheet") and hasAnyAncestor(hasTestTag("pattern-item-p1")))
            .assertIsDisplayed()
        val detail = composeRule.onNodeWithTag("pattern-detail-sheet").fetchSemanticsNode()
        val next = composeRule.onNodeWithTag("pattern-row-p2").fetchSemanticsNode()
        assertTrue("Details must be inserted immediately below p1, not after every other row",
            detail.positionInRoot.y + detail.size.height <= next.positionInRoot.y + 1f)
        composeRule.onNode(hasTestTag("pattern-detail-sheet") and hasAnyAncestor(hasTestTag("pattern-item-p2")))
            .assertDoesNotExist()
    }

    @Test fun searchIsARealInputInTheOriginalHeaderRegionAndFiltersOnlyCurrentSavedRows() {
        render()
        val root = referenceBounds()
        val search = composeRule.onNodeWithTag("pattern-book-search").assertIsDisplayed()
        val bounds = search.fetchSemanticsNode().boundsInRoot
        assertTrue("Search belongs in the right header: $bounds", bounds.left - root.left in 1200f..1245f)
        assertTrue("Search stays above the ledger: $bounds", bounds.top - root.top in 52f..84f)
        // Source 42 spans x≈1217..1569. The previous 330px input was visibly
        // narrower than that paper; keep the measured reference width, not the old UI.
        assertEquals("100% search follows the measured reference paper width", 354f, bounds.width, 1f)
        search.performTextReplacement(" COURAGE ")
        composeRule.onNodeWithTag("pattern-row-p1").assertIsDisplayed()
        composeRule.onNodeWithTag("pattern-row-p2").assertDoesNotExist()
        search.performTextReplacement("礼貌")
        composeRule.onNodeWithTag("pattern-row-p1").assertDoesNotExist()
        composeRule.onNodeWithTag("pattern-row-p2").assertIsDisplayed()
        search.performTextReplacement("")
        composeRule.onNodeWithTag("pattern-row-p1").assertIsDisplayed()
        composeRule.onNodeWithTag("pattern-row-p2").assertIsDisplayed()
    }

    @Test fun noSearchMatchIsNotMistakenForAnEmptySavedPatternBook() {
        render()
        composeRule.onNodeWithTag("pattern-book-search").performTextReplacement("absent pattern")
        composeRule.onNodeWithText("没有匹配的句式或功能").assertIsDisplayed()
        composeRule.onNodeWithTag("pattern-row-p1").assertDoesNotExist()
        composeRule.onNodeWithTag("pattern-book-search").performTextReplacement("")
        composeRule.onNodeWithText("没有匹配的句式或功能").assertDoesNotExist()
        composeRule.onNodeWithTag("pattern-row-p1").assertIsDisplayed()
    }

    @Test fun structureBreakdownUsesTheSavedOrderedPartsWithoutInventingPerPartTranslations() {
        val parts = listOf("work", "up", "the courage", "to", "+ 动词")
        render(expanded = "p1", patterns = listOf(records.first().copy(structureParts = parts)))
        composeRule.onNodeWithTag("pattern-structure-parts-p1").assertIsDisplayed()
        parts.forEach { part -> composeRule.onNodeWithText(part).assertIsDisplayed() }
        // These independent translations exist in the raster but not in the stored parts.
        composeRule.onNodeWithText("努力").assertDoesNotExist()
        composeRule.onNodeWithText("加强").assertDoesNotExist()
    }

    @Test fun anOldPatternWithoutSavedPartsKeepsItsRealStructureButDoesNotFakeABreakdown() {
        render(expanded = "p1", patterns = listOf(records.first()))
        composeRule.onNodeWithTag("pattern-detail-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("pattern-structure-parts-p1").assertDoesNotExist()
        composeRule.onNodeWithText("work").assertDoesNotExist()
        composeRule.onNodeWithText("加强").assertDoesNotExist()
    }

    @Test fun realSearchFocusAndImeActionKeepInputUsableAndDismissTheKeyboard() {
        render(referenceInsets = false)
        val input = composeRule.onNodeWithTag("pattern-book-search")
        input.performClick().assertIsFocused()
        composeRule.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        input.performTextReplacement("COURAGE")
        input.performImeAction()
        input.assertIsNotFocused()
        composeRule.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        composeRule.onNodeWithTag("pattern-row-p1").assertIsDisplayed()
        composeRule.onNodeWithTag("pattern-row-p2").assertDoesNotExist()
    }

    @Test fun shortDoubleFontPreservesLongBilingualTextAndCanScrollToActualDetailActions() {
        val english = "After many months of careful preparation and encouragement from friends, I finally worked up the courage to apply for the scholarship that would let me continue my studies abroad."
        val chinese = "经过数月的认真准备并得到朋友们的鼓励，我终于鼓起勇气申请了那项能够让我继续出国深造的奖学金；此前我一直很犹豫，担心自己没有做好充分准备。"
        val actions = mutableListOf<PatternBookAction>()
        render("p1", listOf(records.first().copy(examples = listOf(PatternExample(english, chinese, english)))),
            width = 640.dp, height = 360.dp, fontScale = 2f, referenceInsets = false, observedActions = actions)
        val viewport = composeRule.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("pattern-detail-sheet")))
        val viewportBounds = viewport.fetchSemanticsNode().boundsInRoot
        assertTrue("The reading viewport must remain usable at 200% font", viewportBounds.height >= 64f)
        listOf(english, chinese).forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertTrue("Long content must lay out completely, not be maxLines-clipped", layouts.isNotEmpty() && layouts.all { layout ->
                !layout.didOverflowHeight && (0 until layout.lineCount).all { line ->
                    !layout.isLineEllipsized(line) && layout.getLineLeft(line) >= -.5f &&
                        layout.getLineRight(line) <= layout.size.width + .5f
                }
            })
        }
        composeRule.onNodeWithTag("pattern-detail-collapse").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("pattern-detail-remove").assertIsDisplayed().performClick()
        assertEquals(listOf(PatternBookAction.CollapseRequested, PatternBookAction.RemoveRequested), actions)
    }

    @Test fun shortDoubleFontShowsTheWholeSearchPurposeWithoutOverlappingOtherHeaderContent() {
        render(width = 640.dp, height = 360.dp, fontScale = 2f, referenceInsets = false)
        val placeholder = composeRule.onNodeWithText("搜索句式或功能", useUnmergedTree = true)
        placeholder.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        placeholder.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val placeholderBounds = placeholder.fetchSemanticsNode().boundsInRoot
        assertTrue("Search purpose must be fully readable, not the observed clipped 搜索句: " +
            layouts.joinToString { "size=${it.size}, overflow=${it.hasVisualOverflow}, lastEnd=${it.getLineEnd(it.lineCount - 1)}" },
            layouts.isNotEmpty() && layouts.all { layout ->
                !layout.didOverflowHeight && layout.size.height <= placeholderBounds.height + 1f &&
                    layout.getLineEnd(layout.lineCount - 1) == "搜索句式或功能".length &&
                    (0 until layout.layoutInput.text.length).all { index ->
                        val glyph = layout.getBoundingBox(index)
                        glyph.left >= -1f && glyph.right <= layout.size.width + 1f &&
                            glyph.top >= -1f && glyph.bottom <= placeholderBounds.height + 1f
                    }
            })
        val search = composeRule.onNodeWithTag("pattern-book-search").fetchSemanticsNode().boundsInRoot
        val title = composeRule.onNodeWithText("句式本", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val subtitle = composeRule.onNodeWithText("展示全部已收录句式，不受当前词汇范围过滤", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val list = composeRule.onNodeWithTag("pattern-book-list").fetchSemanticsNode().boundsInRoot
        val viewport = composeRule.onNodeWithTag("pattern-reference-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Search must not collide with the actual title", !search.overlaps(title))
        assertTrue("Search must stay within the real viewport", search.left >= viewport.left && search.right <= viewport.right + 1f)
        assertTrue("Measured caption/first-row gap must stay valid", subtitle.bottom <= list.top + 1f)
    }

    private fun render(expanded: String? = null, patterns: List<PatternRecord> = records,
        width: Dp = 887.dp, height: Dp = 443.5.dp, fontScale: Float = 1f, referenceInsets: Boolean = true,
        observedActions: MutableList<PatternBookAction>? = null,
    ) {
        var state by mutableStateOf(PatternBookUiState(patterns, expanded, loading = false))
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                RlTheme {
                    Box(Modifier.requiredSize(width, height)
                        .then(if (referenceInsets) Modifier.consumeWindowInsets(WindowInsets.safeDrawing) else Modifier)
                        .testTag("pattern-reference-viewport")) {
                        PatternBookScreen(state) { action ->
                            observedActions?.add(action)
                            if (action is PatternBookAction.QueryChanged) state = state.copy(query = action.query)
                        }
                    }
                }
            }
        }
    }

    private fun referenceBounds(): androidx.compose.ui.geometry.Rect {
        val bounds = composeRule.onNodeWithTag("pattern-reference-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(1774f, bounds.width, 1f)
        assertEquals(887f, bounds.height, 1f)
        return bounds
    }

    companion object {
        private val records = listOf(
            PatternRecord("p1", "work up the courage to + 动词", "鼓起勇气做某事", KnowledgeStatus.MASTERED,
                KnowledgeStatus.LEARNING, 2, 4, "终于决定去做一直犹豫的事",
                listOf(PatternExample("I worked up the courage to apply for the scholarship.", "我鼓起勇气申请了那项奖学金。", "I worked up the courage to apply for the scholarship."))),
            PatternRecord("p2", "I'd like to...", "礼貌表达意愿", KnowledgeStatus.MASTERED, KnowledgeStatus.MASTERED, 3, 4),
            PatternRecord("p3", "It took me...to...", "表达完成某事所花时间", KnowledgeStatus.LEARNING, KnowledgeStatus.TENTATIVE, 0, 2),
        )
    }
}
