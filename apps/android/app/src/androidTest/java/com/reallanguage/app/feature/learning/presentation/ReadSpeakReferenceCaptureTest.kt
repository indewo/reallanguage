package com.reallanguage.app.feature.learning.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Original 24's exact meeting content and listening display state, in the actual focused window.
 * This is an isolated Screen fixture: no ProductionAppGraph, microphone, audio, HTTP, or AI.
 * PRODUCT_CONTEXT 7.1/7.3 takes precedence over the original image's duplicated lower sentence.
 * No IPA, waveform, feedback, or pronunciation score is invented to resemble the reference.
 */
class ReadSpeakReferenceCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun originalMeetingContentAndListeningStateCaptureAtTheActualReferenceViewport() {
        renderAndCapture()
    }

    @Test
    fun originalMeetingCurrentSentenceFitsItsReferenceEnglishRowWithoutArtificialWordGaps() {
        renderAndCapture()
        val row = compose.onNodeWithTag("learning-english-meeting-2").fetchSemanticsNode()
        val range = row.config[SemanticsProperties.HorizontalScrollAxisRange]
        compose.runOnIdle {
            assertEquals("The original reference's complete current sentence fits this baseline row", 0f,
                range.maxValue(), .5f)
        }
    }

    @Test
    fun passedWordsHaveAVisiblePaperHighlightRatherThanTheSameColorAsTheRow() {
        renderAndCapture()
        val node = compose.onNodeWithText("But", useUnmergedTree = true).fetchSemanticsNode()
        val coordinates = node.layoutInfo.coordinates
        val origin = coordinates.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
        val x = (origin.x + coordinates.size.width / 2f).toInt()
        val gapX = (origin.x + coordinates.size.width + 2f).toInt()
        val y = (origin.y - 4f).toInt() // Actual word padding, above the glyph ink.
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val selected = bitmap.getPixel(x, y)
            val row = bitmap.getPixel(gapX, y)
            val distance = kotlin.math.abs(android.graphics.Color.red(selected) - android.graphics.Color.red(row)) +
                kotlin.math.abs(android.graphics.Color.green(selected) - android.graphics.Color.green(row)) +
                kotlin.math.abs(android.graphics.Color.blue(selected) - android.graphics.Color.blue(row))
            assertTrue("Passed-word paper must remain visibly distinct from its parent row: selected=${Integer.toHexString(selected)} row=${Integer.toHexString(row)}",
                distance >= 30)
        } finally { bitmap.recycle() }
    }

    @Test
    fun allThreeOriginalContextRowsHaveUnclippedEnglishAndChineseGlyphsAtBaseline() {
        renderAndCapture()
        for (id in listOf("meeting-1", "meeting-3")) {
            for (language in listOf("english", "chinese")) {
                val node = compose.onNodeWithTag("learning-$language-$id").fetchSemanticsNode()
                val results = mutableListOf<TextLayoutResult>()
                compose.runOnIdle {
                    checkNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)(results)
                    val coordinates = node.layoutInfo.coordinates
                    val origin = coordinates.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
                    val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
                    val text = results.single()
                    text.layoutInput.text.text.forEachIndexed { index, char ->
                        if (!char.isWhitespace()) {
                            val glyph = text.getBoundingBox(index).translate(origin)
                            assertTrue("Reference context $language/$id character $index is clipped: glyph=$glyph clip=$clip",
                                glyph.left >= clip.left - 1f && glyph.right <= clip.right + 1f &&
                                    glyph.top >= clip.top - 1f && glyph.bottom <= clip.bottom + 1f)
                        }
                    }
                }
            }
        }
    }

    private fun renderAndCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val actions = mutableListOf<ReadSpeakAction>()
        lateinit var decor: View
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
            decor = it.window.decorView
        }
        val sentences = listOf(
            FixedLearningSentence("meeting-1", "I was a little nervous during today’s meeting.",
                "今天开会时，我本来有点紧张。", false),
            FixedLearningSentence("meeting-2", "But in the end, I worked up the courage to explain my idea clearly.",
                "但最后还是鼓起勇气把想法讲清楚了。", true),
            FixedLearningSentence("meeting-3", "My colleague nodded after listening.",
                "同事听完后点了点头。", false),
        )
        val current = sentences[1]
        val state = ReadSpeakUiState(
            chapter = FixedLearningChapter("reference-meeting-chapter", 1, sentences),
            currentFocusSentenceId = current.sentenceId,
            listening = true,
            // Passed through "worked up the courage": the current prefix model contains 9 words.
            highlightedWordCount = 9,
        )
        var renderedDensity = 0f
        var renderedFontScale = 0f
        compose.setContent {
            val density = LocalDensity.current
            SideEffect { renderedDensity = density.density; renderedFontScale = density.fontScale }
            RlTheme { RlPaperBackground {
                Box(Modifier.fillMaxSize().testTag(VIEWPORT)) {
                    ReadSpeakScreen(state, actions::add)
                }
            } }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("The isolated Activity must own the real focused window", decor.hasWindowFocus())
            assertEquals("Actual window width; no screenshot resizing", WIDTH, decor.width)
            assertEquals("Actual window height; no virtual taller host", HEIGHT, decor.height)
            assertEquals(320, decor.resources.configuration.densityDpi)
            assertEquals(2f, decor.resources.displayMetrics.density, .001f)
            assertEquals(1f, decor.resources.configuration.fontScale, .001f)
            assertEquals(2f, renderedDensity, .001f)
            assertEquals(1f, renderedFontScale, .001f)
        }
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
        assertEquals(0f, viewport.left, .5f)
        assertEquals(0f, viewport.top, .5f)
        assertEquals(WIDTH.toFloat(), viewport.width, .5f)
        assertEquals(HEIGHT.toFloat(), viewport.height, .5f)

        val focusTag = "learning-english-${current.sentenceId}"
        sentences.forEach { sentence ->
            val englishTag = "learning-english-${sentence.sentenceId}"
            val english = compose.onNodeWithTag(englishTag, useUnmergedTree = true)
            english.assertIsDisplayed()
            assertEquals("Exact original English, including punctuation", sentence.textEn,
                textIn(english.fetchSemanticsNode()).joinToString(" "))
            compose.onNodeWithTag("learning-chinese-${sentence.sentenceId}")
                .assertTextEquals(sentence.textZh).assertIsDisplayed()
            if (!sentence.isFocus) {
                compose.onAllNodes(hasClickAction() and
                    (hasTestTag(englishTag) or hasAnyAncestor(hasTestTag(englishTag))),
                    useUnmergedTree = true).assertCountEquals(0)
                assertFalse("Context-only English cannot become a tracking region",
                    english.fetchSemanticsNode().config.contains(SemanticsProperties.HorizontalScrollAxisRange))
            }
        }
        val words = current.textEn.split(" ")
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(focusTag)),
            useUnmergedTree = true).assertCountEquals(words.size).assertAll(isEnabled())
        assertTrue("Only the current English row offers horizontal reading",
            compose.onNodeWithTag(focusTag).fetchSemanticsNode().config
                .contains(SemanticsProperties.HorizontalScrollAxisRange))
        compose.onNodeWithText(instrumentation.targetContext.getString(R.string.read_speak_listening))
            .assertIsDisplayed()
        compose.onNodeWithTag("read_speak_manual_finished").assertIsDisplayed().assertIsEnabled()

        // This deliberately does not reproduce the original bitmap's lower whole-sentence repeat.
        compose.onAllNodes(hasText(current.textEn, substring = true) and
            !hasAnyAncestor(hasTestTag(focusTag)), useUnmergedTree = true).assertCountEquals(0)
        val lower = compose.onNodeWithTag("learning-module-paper", useUnmergedTree = true)
        assertFalse(textIn(lower.fetchSemanticsNode()).joinToString(" ").contains(current.textEn))
        compose.runOnIdle { assertTrue("Display setup cannot start any learning action", actions.isEmpty()) }

        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals("Uncropped whole-screen PNG width", WIDTH, bitmap.width)
            assertEquals("Uncropped whole-screen PNG height", HEIGHT, bitmap.height)
            val output = File(instrumentation.targetContext.cacheDir, CAPTURE_NAME)
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue("Reference evidence must be a nonempty PNG", output.length() > 0L)
        } finally { bitmap.recycle() }
    }

    private fun textIn(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.children.flatMap(::textIn)

    private companion object {
        const val WIDTH = 1831
        const val HEIGHT = 859
        const val VIEWPORT = "read-reference-viewport"
        const val CAPTURE_NAME = "24-read-reference-meeting-listening.png"
    }
}
