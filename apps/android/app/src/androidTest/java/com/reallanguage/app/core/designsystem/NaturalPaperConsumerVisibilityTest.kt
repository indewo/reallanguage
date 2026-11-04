package com.reallanguage.app.core.designsystem

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.reallanguage.app.R
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.feature.home.presentation.AccountDeletionAction
import com.reallanguage.app.feature.home.presentation.AccountDeletionDialog
import com.reallanguage.app.feature.home.presentation.AccountDeletionUiState
import com.reallanguage.app.feature.review.presentation.ReviewChapterAction
import com.reallanguage.app.feature.review.presentation.ReviewChapterScreen
import com.reallanguage.app.feature.review.presentation.ReviewChapterUiState
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real natural-height paper consumers, at root-controlled 1829x860 / d320 / font 1.
 * Only UI state and callback lists are used: no Graph, VM, repository, database,
 * account, HTTP, recording or actual deletion. No scrolling or density override.
 */
class NaturalPaperConsumerVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val canvasSize = AtomicReference(IntSize.Zero)

    @Test
    fun reviewLoadingThenFailureKeepsWholeMessageAndRetryVisible() {
        val state = mutableStateOf(ReviewChapterUiState("render-only-review", "render-only-command"))
        val actions = mutableListOf<ReviewChapterAction>()
        mount { ReviewChapterScreen(state.value, actions::add) }

        assertWholeText(string(R.string.review_chapter_title))
        assertWholeText(string(R.string.today_review_badge))
        assertWholeNode(progress())
        compose.onNodeWithText(string(R.string.review_chapter_load_failed)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.diary_retry)).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(actions.isEmpty())
            state.value = state.value.copy(loading = false, loadFailed = true)
        }

        progress().assertDoesNotExist()
        assertWholeText(string(R.string.review_chapter_title))
        assertWholeText(string(R.string.review_chapter_load_failed))
        assertWholeText(string(R.string.diary_retry))
        touchEnabledButton(string(R.string.diary_retry))
        compose.runOnIdle {
            assertEquals(listOf(ReviewChapterAction.RetryLoadRequested), actions)
            // The caller supplies the next loading state; no repository is simulated.
            state.value = state.value.copy(loading = true, loadFailed = false)
        }
        assertWholeNode(progress())
        compose.onNodeWithText(string(R.string.review_chapter_load_failed)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.diary_retry)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(ReviewChapterAction.RetryLoadRequested), actions) }
    }

    @Test
    fun accountNormalAndFailedCopyIsWholeAndBothActionsDispatch() {
        val state = mutableStateOf(AccountDeletionUiState())
        val actions = mutableListOf<AccountDeletionAction>()
        mount { AccountDeletionDialog(state.value, actions::add) }

        assertAccountCopy(R.string.account_delete_confirm_body, R.string.account_delete_confirm)
        touchEnabledButton(string(R.string.account_delete_cancel))
        touchEnabledButton(string(R.string.account_delete_confirm))
        compose.runOnIdle {
            assertEquals(listOf(AccountDeletionAction.CancelRequested, AccountDeletionAction.ConfirmRequested), actions)
            state.value = AccountDeletionUiState(failed = true)
        }

        compose.onNodeWithText(string(R.string.account_delete_confirm_body)).assertDoesNotExist()
        assertAccountCopy(R.string.account_delete_failed, R.string.account_delete_confirm)
        touchEnabledButton(string(R.string.account_delete_cancel))
        touchEnabledButton(string(R.string.account_delete_confirm))
        compose.runOnIdle {
            assertEquals(
                listOf(AccountDeletionAction.CancelRequested, AccountDeletionAction.ConfirmRequested,
                    AccountDeletionAction.CancelRequested, AccountDeletionAction.ConfirmRequested),
                actions,
            )
        }
    }

    @Test
    fun accountDeletingKeepsWholeCopyAndDisablesBothActions() {
        val actions = mutableListOf<AccountDeletionAction>()
        mount { AccountDeletionDialog(AccountDeletionUiState(deleting = true), actions::add) }
        assertAccountCopy(R.string.account_delete_confirm_body, R.string.account_delete_deleting)
        compose.onNodeWithText(string(R.string.account_delete_confirm)).assertDoesNotExist()
        listOf(R.string.account_delete_cancel, R.string.account_delete_deleting).forEach { resource ->
            val button = compose.onNodeWithText(string(resource))
            assertWholeNode(button)
            button.assertIsNotEnabled().performTouchInput { click() }
        }
        compose.runOnIdle { assertTrue("Disabled controls cannot dispatch a deletion or cancellation", actions.isEmpty()) }
    }

    private fun assertAccountCopy(body: Int, primary: Int) {
        assertWholeText(string(R.string.account_delete_confirm_title))
        assertWholeText("需要你的确认")
        assertWholeText(string(body))
        assertWholeText(string(R.string.account_delete_cancel))
        assertWholeText(string(primary))
    }

    private fun touchEnabledButton(text: String) {
        val button = compose.onNodeWithText(text)
        assertWholeNode(button)
        button.assertIsEnabled().performTouchInput { click() }
    }

    private fun progress() = compose.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
    )

    private fun assertWholeNode(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val measured = node.fetchSemanticsNode()
        val coordinates = measured.layoutInfo.coordinates
        val root = coordinates.findRootCoordinates()
        assertEquals("Use the actual baseline window, including Dialog's own root", CANVAS, root.size)
        val origin = coordinates.localToRoot(Offset.Zero)
        val full = Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
        val clip = root.localBoundingBoxOf(coordinates, clipBounds = true).intersect(measured.boundsInRoot)
        assertTrue("The real control must be wholly visible without scrolling: full=$full clip=$clip", fits(full, clip))
    }

    private fun assertWholeText(expected: String) {
        // Unmerged resolves the actual Text, not the enclosing button's larger bounds.
        val node = compose.onNodeWithText(expected, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        assertEquals(expected, layout.layoutInput.text.text)
        assertEquals("Real density, including the separate Dialog owner", 2f, layout.layoutInput.density.density, .001f)
        assertEquals("Do not shrink the actual system font to make copy fit", 1f, layout.layoutInput.density.fontScale, .001f)
        assertTrue(layout.lineCount > 0)
        assertFalse(layout.multiParagraph.didExceedMaxLines)
        assertEquals("The original final character must remain laid out", expected.length, layout.getLineEnd(layout.lineCount - 1))
        // These production Text nodes are Start/Left aligned. Compose 1.11.4 can
        // expose a wider semantic MultiParagraph than its drawn intrinsic node;
        // didOverflowWidth alone is not clipping, nor an extra centering offset.
        val alignment = layout.layoutInput.style.textAlign
        assertTrue("Only known Start/Left paragraph coordinates are used",
            alignment == TextAlign.Left || ((alignment == TextAlign.Start || alignment == TextAlign.Unspecified) &&
                layout.layoutInput.layoutDirection == LayoutDirection.Ltr))
        val measured = node.fetchSemanticsNode()
        val coordinates = measured.layoutInfo.coordinates
        val root = coordinates.findRootCoordinates()
        assertEquals(CANVAS, root.size)
        val origin = coordinates.localToRoot(Offset.Zero)
        val clip = root.localBoundingBoxOf(coordinates, clipBounds = true).intersect(measured.boundsInRoot)
        repeat(layout.lineCount) { line ->
            assertFalse("No original line may be ellipsized", layout.isLineEllipsized(line))
            val glyphs = (layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true))
                .filter { !expected[it].isWhitespace() }.map { layout.getBoundingBox(it).translate(origin) }
            assertTrue("Every real glyph must fit the ancestor clip without scrolling: text=$expected line=$line clip=$clip glyphs=$glyphs",
                glyphs.isNotEmpty() && glyphs.all { fits(it, clip) })
        }
    }

    private fun mount(content: @Composable () -> Unit) {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.setContent {
            RlTheme { Box(Modifier.fillMaxSize().onSizeChanged { canvasSize.set(it) }) { content() } }
        }
        compose.waitUntil(10_000) { canvasSize.get() == CANVAS }
    }

    private fun string(id: Int) = compose.activity.getString(id)

    private companion object {
        val CANVAS = IntSize(1829, 860)
        fun fits(box: Rect, clip: Rect) = clip.width > 0f && clip.height > 0f &&
            box.left >= clip.left - 1f && box.top >= clip.top - 1f &&
            box.right <= clip.right + 1f && box.bottom <= clip.bottom + 1f
    }
}
