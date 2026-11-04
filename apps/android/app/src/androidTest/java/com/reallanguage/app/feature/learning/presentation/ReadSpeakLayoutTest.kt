package com.reallanguage.app.feature.learning.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual focused-learning window; isolated content, not microphone or AI quality evidence. */
class ReadSpeakLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val chapter = FixedLearningChapter("isolated-layout", 1,
        listOf(FixedLearningSentence("s1", "I drink green tea.", "我喝绿茶。", true)))
    private val actions = mutableListOf<ReadSpeakAction>()
    @Before fun window() { compose.activityRule.scenario.onActivity {
        it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        configureFocusedLearningWindow(it.window)
    } }

    private fun render() {
        compose.setContent { RlTheme { RlPaperBackground {
            Box(Modifier.fillMaxSize().testTag("read-layout-viewport")) {
                ReadSpeakScreen(ReadSpeakUiState(chapter, "s1", captureFailed = true,
                    feedbackRetryBlocked = true), actions::add)
            }
        } } }
        compose.waitForIdle()
    }

    @Test fun readingModuleUsesTheFullWidthPaperRatherThanAnUnspecifiedArtworkSidebar() {
        render()
        capture("structure")
        val viewport = compose.onNodeWithTag("read-layout-viewport").fetchSemanticsNode().boundsInRoot
        val paper = compose.onNodeWithTag("learning-module-paper").fetchSemanticsNode().boundsInRoot
        assertTrue("Original 24's lower paper spans the content width: viewport=$viewport paper=$paper",
            paper.width >= viewport.width * .85f)
        compose.onNodeWithText("把熟悉的内容，变成真正会用的英语。").assertDoesNotExist()
    }

    @Test fun terminalMessageCanBeReadToItsLastLineAndAllActionGlyphsRemainReachable() {
        render()
        val message = context.getString(R.string.full_sentence_speaking_retry_blocked)
        val text = compose.onNodeWithText(message, useUnmergedTree = true)
        val layout = layout(text)
        val paper = compose.onNodeWithTag("learning-module-paper")
        fun tailFits(): Boolean {
            val line = layout.lineCount - 1
            val clip = clip(text).intersect(paper.fetchSemanticsNode().boundsInRoot)
            val origin = text.fetchSemanticsNode().layoutInfo.coordinates.localToRoot(Offset.Zero)
            return (layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true))
                .filter { !message[it].isWhitespace() }.all { fits(layout.getBoundingBox(it).translate(origin), clip) }
        }
        repeat(8) {
            if (!tailFits()) {
                // Touch the visible text's real scroll hit region, not the decorative paper margin.
                val visible = clip(text).intersect(paper.fetchSemanticsNode().boundsInRoot)
                val node = text.fetchSemanticsNode().boundsInRoot
                if (visible.height > 8f) text.performTouchInput {
                    swipe(Offset(visible.center.x - node.left, visible.bottom - node.top - 3f),
                        Offset(visible.center.x - node.left, visible.top - node.top + 3f), durationMillis = 300)
                }
            }
            compose.waitForIdle()
        }
        capture("terminal-tail")
        assertTrue("The actual last line must be fully readable after bounded local touch scrolling", tailFits())
        listOf(R.string.formation_back, R.string.read_speak_play, R.string.read_speak_manual_finished,
            R.string.full_sentence_speaking_new_recording).forEach { id ->
            val label = context.getString(id)
            val button = compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled()
            val words = compose.onNodeWithText(label, useUnmergedTree = true)
            val result = layout(words)
            val bounds = clip(words).intersect(button.fetchSemanticsNode().boundsInRoot)
            val ink = unionBounds(label.indices.map(result::getBoundingBox))
            assertTrue("Every action glyph must fit: $label clip=$bounds ink=$ink",
                bounds.width > 0f && bounds.height > 0f &&
                    ink.width <= bounds.width + 1f && ink.height <= bounds.height + 1f)
        }
        compose.onNodeWithText(context.getString(R.string.full_sentence_speaking_new_recording))
            .performTouchInput { click(center) }
        assertEquals(listOf(ReadSpeakAction.StartRequested), actions)
    }

    @Test fun chapterTitleFitsItsMeasuredHeightAtTheSelectedFontScale() {
        render()
        val title = "第 1 章 · ${context.getString(R.string.read_speak_title)}"
        val node = compose.onNodeWithText(title, useUnmergedTree = true)
        val result = layout(node)
        val bounds = clip(node)
        capture("header")
        val ink = title.indices.filter { !title[it].isWhitespace() }
            .map(result::getBoundingBox).let(::unionBounds)
        assertTrue("Chapter title must not be clipped by a fixed height: clip=$bounds layout=${result.size} ink=$ink",
            bounds.width > 0f && bounds.height > 0f &&
                ink.width <= bounds.width + 1f && ink.height <= bounds.height + 1f)
    }

    @Test fun focusSentenceUsesThePaperForLargeCenteredFingerReading() {
        render()
        val paper = compose.onNodeWithTag("learning-context-paper").fetchSemanticsNode().boundsInRoot
        val focus = compose.onNodeWithTag("learning-focus-s1").fetchSemanticsNode().boundsInRoot
        val track = compose.onNodeWithTag("learning-english-s1", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val word = compose.onNodeWithText("I", useUnmergedTree = true)
        val text = layout(word)
        val topGap = focus.top - paper.top
        val bottomGap = paper.bottom - focus.bottom

        capture("finger-reading")
        assertTrue("The focus card must occupy a useful share of its paper: paper=$paper focus=$focus",
            focus.height >= paper.height * .55f)
        assertTrue("The focus card must be vertically centered: top=$topGap bottom=$bottomGap",
            kotlin.math.abs(topGap - bottomGap) <= paper.height * .12f)
        assertTrue("The tracking row must provide a large pointing target: track=$track focus=$focus",
            track.height >= focus.height * .45f)
        assertTrue("Finger-reading text must not reuse the old 13sp body size: ${text.layoutInput.style.fontSize}",
            text.layoutInput.style.fontSize.value >= 17f)
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }
    private fun clip(node: SemanticsNodeInteraction): Rect {
        val coordinates = node.fetchSemanticsNode().layoutInfo.coordinates
        return coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(compose.onNodeWithTag("read-layout-viewport").fetchSemanticsNode().boundsInRoot)
    }
    private fun fits(glyph: Rect, bounds: Rect) = bounds.width > 0 && bounds.height > 0 &&
        glyph.left >= bounds.left - 1 && glyph.right <= bounds.right + 1 &&
        glyph.top >= bounds.top - 1 && glyph.bottom <= bounds.bottom + 1
    private fun unionBounds(rects: List<Rect>): Rect = Rect(
        left = rects.minOf(Rect::left),
        top = rects.minOf(Rect::top),
        right = rects.maxOf(Rect::right),
        bottom = rects.maxOf(Rect::bottom),
    )
    private fun capture(suffix: String) {
        val phase = InstrumentationRegistry.getArguments().getString("readLayoutCapture") ?: return
        require(phase in setOf("before", "after", "final-short", "final-reference"))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 5_000)
        val bitmap = automation.takeScreenshot()
        try { File(context.cacheDir, "24-read-layout-$suffix-$phase.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }
}
