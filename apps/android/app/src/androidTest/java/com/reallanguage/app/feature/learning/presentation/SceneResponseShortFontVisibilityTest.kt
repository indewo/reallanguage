package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.SceneDialogueLine
import com.reallanguage.app.feature.learning.domain.SceneEvaluationDecision
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Neutral, rendered UI states only. No account, database, audio, network or AI is used.
 * These tests prove visibility and action dispatch, not speech quality or persistence.
 * No extra outer scroll is added by this fixture: production must provide access itself.
 */
class SceneResponseShortFontVisibilityTest {
    @get:Rule val compose = createComposeRule()
    private var touchSlop = 0f

    @Test
    fun longBackgroundDialogueAndFeedbackExposeTheirLastCompleteLinesByLocalTouch() {
        val actions = mutableListOf<SceneResponseAction>()
        val state = fixture().copy(trainingRoundCompleted = true)
        render(state, actions)
        captureIfRequested("context-initial")
        val task = requireNotNull(state.currentTask)
        listOf(
            Triple("scene-background-scroll", requireNotNull(task.backgroundEn), "background-tail"),
            Triple("scene-dialogue-scroll", task.contextLines.joinToString("   ") { "${it.speaker}: ${it.textEn}" }, "dialogue-tail"),
            Triple("scene-feedback-scroll", requireNotNull(state.feedbackZh), "feedback-tail"),
        ).forEach { (tag, text, snapshot) ->
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
            assertTailReachableByTouch(node, text, snapshot) { node.fetchSemanticsNode().layoutInfo.coordinates }
        }
        assertEquals("Reading local content must not start recording, retry or submit", emptyList<SceneResponseAction>(), actions)
    }

    @Test
    fun longEditableTranscriptAndEntireConfirmationLabelRemainReadableAndConfirmable() {
        val actions = mutableListOf<SceneResponseAction>()
        val state = fixture().copy(transcriptReady = true, feedbackZh = null)
        render(state, actions)
        captureIfRequested("confirm-initial")
        val input = compose.onNodeWithText(state.transcript).assertIsEnabled()
        val layout = completeTextLayout(input, state.transcript)
        assertTailReachableByTouch(input, state.transcript, "confirm-transcript-tail", layout) {
            transcriptParagraphCoordinates(input, layout)
        }
        input.assertIsEnabled()
        val label = string(R.string.scene_response_confirm_transcript)
        assertButtonGlyphsVisible(label, "confirm-button")
        assertEquals(emptyList<SceneResponseAction>(), actions)
        compose.onNodeWithText(label).assertIsEnabled().performClick()
        assertEquals(listOf(SceneResponseAction.ConfirmTranscriptRequested), actions)
    }

    @Test
    fun evaluatedTranscriptAndEntireSaveRetryLabelRemainReadableWithoutRecordingAgain() {
        val actions = mutableListOf<SceneResponseAction>()
        val state = fixture().copy(roundIndex = 2, finalDecision = SceneEvaluationDecision.PASS, saveFailed = true)
        render(state, actions)
        captureIfRequested("save-retry-initial")
        val label = string(R.string.full_sentence_speaking_retry_save)
        assertButtonGlyphsVisible(label, "save-retry-button")
        val transcript = compose.onNodeWithTag("scene-transcript-scroll", useUnmergedTree = true)
        assertTailReachableByTouch(transcript, state.transcript, "save-retry-transcript-tail") {
            transcript.fetchSemanticsNode().layoutInfo.coordinates
        }
        assertButtonGlyphsVisible(label, "save-retry-button-after-scroll")
        assertEquals(emptyList<SceneResponseAction>(), actions)
        compose.onNodeWithText(label).assertIsEnabled().performClick()
        assertEquals(listOf(SceneResponseAction.RetryRequested), actions)
    }

    private fun render(state: SceneResponseUiState, actions: MutableList<SceneResponseAction>) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
                RlTheme {
                    touchSlop = LocalViewConfiguration.current.touchSlop
                    Box(Modifier.requiredSize(640.dp, 360.dp).testTag(VIEWPORT)) {
                        // Named parameters keep this test independent of the optional header callback.
                        SceneResponseScreen(state = state, onAction = actions::add)
                    }
                }
            }
        }
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().layoutInfo
        assertEquals("The test must use the requested width, not a wider host", 1280, viewport.width)
        assertEquals("The test must use the requested height, not a taller host", 720, viewport.height)
    }

    private fun completeTextLayout(node: SemanticsNodeInteraction, text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue("The real production Text/TextField must expose its text layout", it(layouts))
        }
        assertEquals("One actual paragraph is required for coordinate inspection", 1, layouts.size)
        val layout = layouts.single()
        assertEquals(text, layout.layoutInput.text.text)
        assertTrue("The paragraph must contain at least one line", layout.lineCount > 0)
        assertFalse("No original lines may be discarded", layout.multiParagraph.didExceedMaxLines)
        assertEquals("The complete last line must exist", text.length, layout.getLineEnd(layout.lineCount - 1))
        repeat(layout.lineCount) { line ->
            assertFalse("A complete line must not be replaced by ellipsis: line=$line", layout.isLineEllipsized(line))
            assertTrue("Every line must fit horizontally: line=$line width=${layout.size.width}",
                layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
        }
        return layout
    }

    private data class TailGeometry(val origin: Offset, val clip: Rect, val glyphs: List<Rect>) {
        val visible get() = clip.width > 0f && clip.height > 0f && glyphs.isNotEmpty() && glyphs.all { fits(it, clip) }
        val remainingDistance get() = (glyphs.maxOfOrNull { it.bottom } ?: Float.POSITIVE_INFINITY) - clip.bottom
        val slack get() = (clip.height - (glyphs.maxOfOrNull { it.height } ?: clip.height)).coerceAtLeast(0f)
    }

    private fun assertTailReachableByTouch(
        region: SemanticsNodeInteraction,
        text: String,
        snapshot: String,
        layout: TextLayoutResult = completeTextLayout(region, text),
        coordinates: () -> LayoutCoordinates,
    ) {
        assertTrue("Long content must genuinely wrap", layout.lineCount > 3)
        // The short-screen production body can itself scroll between the existing regions.
        // Reveal this region first; all remaining text must still be reached with real local
        // gestures and checked against the actual glyph clip, not just ScrollTo completion.
        region.performScrollTo()
        compose.waitForIdle()
        val tail = layout.lineCount - 1
        fun geometry(): TailGeometry {
            val actual = coordinates()
            val origin = actual.localToRoot(Offset.Zero)
            val clip = actual.findRootCoordinates().localBoundingBoxOf(actual, clipBounds = true)
                .intersect(region.fetchSemanticsNode().boundsInRoot)
                .intersect(compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot)
            val boxes = (layout.getLineStart(tail) until layout.getLineEnd(tail, visibleEnd = true))
                .filter { !text[it].isWhitespace() }.map { layout.getBoundingBox(it).translate(origin) }
            return TailGeometry(origin, clip, boxes)
        }
        fun scrollPosition(): Pair<Float, Float>? {
            val config = region.fetchSemanticsNode().config
            if (!config.contains(SemanticsProperties.VerticalScrollAxisRange)) return null
            val range = config[SemanticsProperties.VerticalScrollAxisRange]
            return range.value() to range.maxValue()
        }
        val original = geometry()
        val originalScroll = scrollPosition()
        var current = original
        var gestures = 0
        var unchanged = 0
        // All swipes occur in the real production region, not a test-added scrolling host.
        // TextField may not publish ScrollAxisRange; in that case its actual paragraph
        // origin and clipped glyphs, never a synthetic offset, determine reachability.
        while (!current.visible && gestures < 100 && unchanged < 4) {
            val localBounds = region.fetchSemanticsNode().boundsInRoot
            val bounds = localBounds
                .intersect(compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot)
            val range = scrollPosition()
            if (bounds.width <= 0f || bounds.height <= touchSlop + 4f ||
                (range != null && range.first >= range.second - 1f)) break
            val previous = current
            val previousScroll = range
            region.performTouchInput {
                val top = bounds.top - localBounds.top
                val left = bounds.left - localBounds.left
                val bottom = bounds.bottom - localBounds.top
                val startY = bottom - 2f
                val maximum = (bounds.height - 4f).coerceAtLeast(touchSlop + 2f)
                val distance = (current.remainingDistance + current.slack / 2f + touchSlop)
                    .coerceIn(touchSlop + 2f, maximum)
                swipe(Offset(left + bounds.width / 2f, startY),
                    Offset(left + bounds.width / 2f, (startY - distance).coerceAtLeast(top + 2f)),
                    durationMillis = 300)
            }
            compose.waitForIdle()
            gestures += 1
            current = geometry()
            unchanged = if (current == previous && scrollPosition() == previousScroll) unchanged + 1 else 0
        }
        captureIfRequested(snapshot)
        val diagnostic = "snapshot=$snapshot paragraph=${layout.size} lines=${layout.lineCount} " +
            "original=$original actual=$current gestures=$gestures scroll=${scrollPosition()}"
        if (!current.visible && gestures == 100 && unchanged < 4) {
            val range = scrollPosition()
            assertTrue("Fixture gesture budget exhausted before a clipping verdict: $diagnostic",
                range != null && range.first >= range.second - 1f)
        }
        assertTrue("The complete final line's real glyph boxes must fit the local clipped paper: $diagnostic", current.visible)
        if (!original.visible) {
            assertTrue("Actual touch must move content, not merely report a dispatched swipe: $diagnostic",
                gestures > 0 && (current.origin != original.origin || scrollPosition() != originalScroll))
        }
    }

    private fun transcriptParagraphCoordinates(input: SemanticsNodeInteraction, layout: TextLayoutResult): LayoutCoordinates {
        val fieldId = input.fetchSemanticsNode().id
        val field = compose.onNode(SemanticsMatcher("the same unmerged transcript field") { it.id == fieldId },
            useUnmergedTree = true).fetchSemanticsNode()
        fun descendants(node: SemanticsNode): Sequence<SemanticsNode> =
            node.children.asSequence().flatMap { sequenceOf(it) + descendants(it) }
        val measured = (sequenceOf(field) + descendants(field)).toList()
        val layers = measured.drop(1).filter {
            // Proven by the LR06 test: a clipped paragraph can be much shorter than
            // TextLayoutResult. Never reject the real drawing layer for its height.
            it.children.isEmpty() && it.layoutInfo.width == layout.size.width &&
                !it.config.contains(SemanticsProperties.Text)
        }
        val diagnostic = measured.joinToString("; ") {
            "id=${it.id},size=${it.layoutInfo.width}x${it.layoutInfo.height},children=${it.children.size}," +
                "keys=${it.config.map { entry -> entry.key.name }},bounds=${it.boundsInRoot}"
        }
        assertEquals("Resolve the actual paragraph before making a product clipping verdict: paragraph=${layout.size}; $diagnostic",
            1, layers.size)
        return layers.single().layoutInfo.coordinates
    }

    private fun assertButtonGlyphsVisible(label: String, snapshot: String) {
        val button = compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled()
        val text = compose.onNodeWithText(label, useUnmergedTree = true)
        val layout = completeTextLayout(text, label)
        val coordinates = text.fetchSemanticsNode().layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(button.fetchSemanticsNode().boundsInRoot)
            .intersect(compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot)
        val glyphs = label.indices.filter { !label[it].isWhitespace() }
            .map { layout.getBoundingBox(it).translate(origin) }
        captureIfRequested(snapshot)
        assertTrue("Every main-action glyph must fit the real button, including wrapped final lines: " +
            "snapshot=$snapshot lines=${layout.lineCount} clip=$clip glyphs=$glyphs",
            clip.width > 0f && clip.height > 0f && glyphs.isNotEmpty() && glyphs.all { fits(it, clip) })
    }

    private fun captureIfRequested(suffix: String) {
        val phase = InstrumentationRegistry.getArguments().getString("sceneContentSnapshot") ?: return
        require(phase in setOf("before", "after"))
        require(suffix.matches(Regex("[a-z-]+")))
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(VIEWPORT).captureToImage().asAndroidBitmap()
        try {
            assertEquals(1280, bitmap.width)
            assertEquals(720, bitmap.height)
            val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "visual-qa")
            check(folder.isDirectory || folder.mkdirs())
            val output = File(folder, "30-scene-content-$suffix-$phase.png")
            check(output.canonicalFile.parentFile == folder.canonicalFile)
            output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun string(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun fixture(): SceneResponseUiState {
        val source = "I asked for help and explained my idea clearly."
        val background = "You are discussing a small project with a colleague. " +
            "Explain what you have already finished and ask which detail needs attention. " +
            "You have time to check the plan together before the next meeting. " +
            "The final detail is the agreed time for another conversation."
        val lines = listOf(
            SceneDialogueLine("Colleague", "We can review the draft together after lunch. " +
                "Please tell me which part you would like to check and why it matters."),
            SceneDialogueLine("You", "I have written the main points and marked two questions. " +
                "The final dialogue detail is that I will bring the notes to our meeting."),
        )
        val feedback = "你已经回应了对方的问题，并说明了目前的进展。接下来请保留已经说清楚的事实，" +
            "把需要对方帮助确认的细节表达完整。不要为了缩短回答而遗漏已经约定的时间。" +
            "这段反馈的最后一句也需要能够完整读到。"
        val transcript = "I have written the main points and checked the plan carefully. " +
            "I would like your help with the final detail before our next meeting. " +
            "I can bring my notes after lunch so that we can read them together. " +
            "The last sentence is still part of my own answer."
        return SceneResponseUiState(
            chapter = FixedLearningChapter("render-only-chapter", 1, listOf(
                FixedLearningSentence("render-only-focus", source, "我请求了帮助，并清楚地解释了自己的想法。", true),
            )),
            tasks = listOf(FixedSceneResponseTask("render-only-task", "render-only-focus", background, lines, source)),
            roundIndex = 1,
            transcript = transcript,
            feedbackZh = feedback,
        )
    }

    private companion object {
        const val VIEWPORT = "scene-short-font-visibility-viewport"
        fun fits(box: Rect, clip: Rect) = box.top >= clip.top - 1f && box.bottom <= clip.bottom + 1f &&
            box.left >= clip.left - 1f && box.right <= clip.right + 1f
    }
}
