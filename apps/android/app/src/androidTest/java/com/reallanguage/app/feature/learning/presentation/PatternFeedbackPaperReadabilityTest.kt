package com.reallanguage.app.feature.learning.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Rendering and actual touch only; no account, API, microphone or AI. Original artwork is unchanged. */
class PatternFeedbackPaperReadabilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun window() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }
    @Test fun pendingReadingRetainsWholeLastLineOnPaperAndItsRealRetryAction() = verify(false)
    @Test fun terminalReadingRetainsWholeLastLineOnPaperAndExplicitNewRecording() = verify(true)
    @Test fun scrollingMustClipAtThePaperBodyNotPaintAcrossTapeAndTornEdges() = verify(false, checkViewport = true)

    private fun verify(blocked: Boolean, checkViewport: Boolean = false) {
        val actions = mutableListOf<PatternSpeakingAction>()
        val state = PatternSpeakingUiState(
            FixedLearningChapter("isolated", 1, listOf(FixedLearningSentence("s1", "I drink green tea.", "我喝绿茶。", true))),
            "s1", listOf(LockedSpeakingPattern("p1", "s1", "drink + noun", "说明喝的饮品")),
            stage = PatternSpeakingStage.READING,
            examples = listOf(PatternPracticeExample("e1", "聊饮品", "What do you drink?", "I drink black tea.", ZH)),
            failureMessageVisible = true, technicalRetryPending = !blocked, technicalRetryBlocked = blocked,
            feedbackExampleId = "e1",
        )
        compose.setContent { RlTheme { PatternSpeakingScreen(state, actions::add) } }
        val footer = compose.onNodeWithText(compose.activity.getString(R.string.pattern_speaking_start_responses))
        val footerBefore = footer.fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithText(ZH, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals(ZH, layout.layoutInput.text.text)
        assertEquals(17.sp, layout.layoutInput.style.fontSize)
        assertFalse(layout.multiParagraph.didExceedMaxLines)
        val scroll = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("pattern-speaking-workspace")))
        if (checkViewport) {
            val paper = compose.onNodeWithTag("learning-module-paper").fetchSemanticsNode().layoutInfo.coordinates
            val origin = paper.localToRoot(Offset.Zero)
            val coordinate = scroll.fetchSemanticsNode().layoutInfo.coordinates
            val visible = coordinate.findRootCoordinates().localBoundingBoxOf(coordinate, true)
            assertTrue("Scrollable content must be clipped below the paper's tape/background", visible.top >= origin.y + paper.size.height * 90f / 630 - 1)
            assertTrue("Scrollable content must stop before the lower torn edge", visible.bottom <= origin.y + paper.size.height * 540f / 630 + 1)
        }
        fun position() = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        var gestures = 0
        while (!onPaper(text, layout) && gestures < 12) {
            val previous = position()
            scroll.performTouchInput { swipe(Offset(width * .6f, height * .8f), Offset(width * .6f, height * .2f), 300) }
            compose.waitForIdle()
            gestures++
            if (previous == position()) break
        }
        assertTrue("Every final Chinese glyph must be reachable within the paper, not in its torn edge: gestures=$gestures position=${position()}", onPaper(text, layout))
        assertEquals("Only the inner content can scroll", footerBefore, footer.fetchSemanticsNode().boundsInRoot)
        assertTrue(actions.isEmpty())
        val label = compose.activity.getString(if (blocked) R.string.full_sentence_speaking_new_recording else R.string.read_speak_retry)
        compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
        assertEquals(listOf(PatternSpeakingAction.StartReadingRequested("e1")), actions)
    }

    private fun onPaper(node: SemanticsNodeInteraction, layout: TextLayoutResult): Boolean {
        val coordinate = node.fetchSemanticsNode().layoutInfo.coordinates
        val origin = coordinate.localToRoot(Offset.Zero)
        val paper = compose.onNodeWithTag("learning-module-paper").fetchSemanticsNode().layoutInfo.coordinates
        val p = paper.localToRoot(Offset.Zero)
        // Unchanged 1700x630 torn note: the accepted usable paper ROI excludes tape and torn shadow.
        val safe = Rect(p.x + paper.size.width * 45f / 1700, p.y + paper.size.height * 90f / 630,
            p.x + paper.size.width * 1655f / 1700, p.y + paper.size.height * 540f / 630)
            .intersect(coordinate.findRootCoordinates().localBoundingBoxOf(coordinate, true))
        val line = layout.lineCount - 1
        return (layout.getLineStart(line) until layout.getLineEnd(line, true)).filter { !ZH[it].isWhitespace() }.all {
            val glyph = layout.getBoundingBox(it).translate(origin)
            glyph.left >= safe.left - 1 && glyph.right <= safe.right + 1 && glyph.top >= safe.top - 1 && glyph.bottom <= safe.bottom + 1
        }
    }
    companion object { private const val ZH = "我喝红茶。" }
}
