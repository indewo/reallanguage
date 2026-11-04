package com.reallanguage.app.feature.knowledge.presentation

import android.content.pm.ActivityInfo
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyEntry
import com.reallanguage.app.feature.knowledge.domain.VocabularyExample
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Isolated rendering/action fixtures, never account data, HTTP, audio or a repository.
 * The device owner supplies real 1280x720/d320/system-font2 or the original page40
 * 1829x860/d320/font1. No test density wrapper or extra scroll container is added.
 */
class VocabularyLongSenseTouchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var widthPx = 1280
    private var heightPx = 720
    private var fontScale = 2f
    private var detailMode = false
    private var touchSlop = 0f
    private var physicalGestures = 0

    @Test
    fun shortSystemDoubleFontLongSecondSenseCanReadAndOpenExactSense() {
        val state = state(longSecondSense = true)
        val sense = state.entries.first().senses[1]
        val actions = render(state)
        val node = text(sense.meaningZh)
        val paragraph = layout(node, sense.meaningZh, 16f)
        assertTrue("A genuinely long meaning must wrap under the real system font", paragraph.lineCount > 3)
        captureIfRequested("list-initial")
        val initialScroll = scrollPosition()
        repeat(paragraph.lineCount) { line -> reachLineByTouch(node, paragraph, line) }
        assertTrue("The meaning was read by moving the real production list",
            physicalGestures > 0 && scrollPosition().first > initialScroll.first)
        val button = compose.onNodeWithTag("vocabulary-sense-${sense.senseId}").assertIsEnabled()
        assertTrue("Do not shrink the existing 48dp touch target", button.fetchSemanticsNode().layoutInfo.height >= 96)
        captureIfRequested("list-tail-touch")
        val diagnostic = clickFinalVisibleGlyph(node, paragraph, button, actions)
        assertEquals("Exact physical tail-glyph action; $diagnostic",
            listOf(VocabularyBookAction.SenseSelected(sense.senseId)), actions)
        // Preserve the already passing final-punctuation hit as an independent
        // control. A valid hit near the center does not prove the same line's
        // leftmost visible character survives the actual rounded outline.
        val unchangedScroll = scrollPosition()
        captureIfRequested("list-first-glyph-touch")
        val firstDiagnostic = clickFinalVisibleGlyph(node, paragraph, button, actions, firstGlyph = true)
        assertEquals("Taps must not quietly scroll away from the inspected line", unchangedScroll, scrollPosition())
        assertEquals("Both ends of the final line must select exactly the same sense; last=$diagnostic; first=$firstDiagnostic",
            listOf(VocabularyBookAction.SenseSelected(sense.senseId), VocabularyBookAction.SenseSelected(sense.senseId)), actions)
    }

    @Test
    fun referenceShortSecondSenseRemainsReadableAndPhysicallySelectable() {
        val state = state(longSecondSense = false)
        val sense = state.entries.first().senses[1]
        val actions = render(state, reference = true)
        val node = text(sense.meaningZh)
        val paragraph = layout(node, sense.meaningZh, 16f)
        assertTrue("The complete short reference meaning is visible", geometry(node, paragraph).visible)
        val button = compose.onNodeWithTag("vocabulary-sense-${sense.senseId}").assertIsEnabled()
        captureIfRequested("reference-control")
        val diagnostic = clickFinalVisibleGlyph(node, paragraph, button, actions)
        assertEquals("Short-meaning physical control; $diagnostic",
            listOf(VocabularyBookAction.SenseSelected(sense.senseId)), actions)
        assertEquals("The normal-size control must not need scrolling", 0, physicalGestures)
    }

    @Test
    fun shortSystemDoubleFontDetailLongSecondSenseHasCompleteGlyphsInsideItsField() {
        val state = state(longSecondSense = true).copy(expandedSenseId = "run-first")
        val sense = state.entries.first().senses[1]
        val actions = render(state)
        // The first, featured/expanded meaning stays short. Only the second row in
        // the detail's existing selector is long, isolating its own height/clip.
        compose.onNodeWithTag("vocabulary-detail-sense-${sense.senseId}").assertIsEnabled()
        captureIfRequested("detail-second-sense")
        val node = text(sense.meaningZh)
        val paragraph = layout(node, sense.meaningZh, 12f)
        assertTrue("The detail fixture is a genuinely multiline meaning", paragraph.lineCount > 3)
        // Retain the original 88px-glyph/36px-clip RED as evidence, but require
        // each line to become readable, not all 150 characters on one screen.
        repeat(paragraph.lineCount) { line -> reachDetailLineByTouch(node, paragraph, line) }
        assertTrue("Reading must not silently select a different meaning", actions.isEmpty())
        assertTrue("Long detail text must be reached through actual local scrolling", physicalGestures > 0)
        val button = compose.onNodeWithTag("vocabulary-detail-sense-${sense.senseId}").assertIsEnabled()
        captureIfRequested("detail-second-sense-tail")
        val firstHit = clickDetailGlyph(node, paragraph, button, actions, firstGlyph = true)
        assertEquals("The first character of the last line selects the current exact sense; $firstHit",
            listOf(VocabularyBookAction.SenseSelected(sense.senseId)), actions)
        val lastHit = clickDetailGlyph(node, paragraph, button, actions)
        assertEquals("Both ends of the last line remain inside the real actionable area; $lastHit",
            listOf(VocabularyBookAction.SenseSelected(sense.senseId), VocabularyBookAction.SenseSelected(sense.senseId)), actions)
        val collapse = text("收回")
        val collapseParagraph = layout(collapse, "收回", 11f)
        repeat(collapseParagraph.lineCount) { line -> reachDetailLineByTouch(collapse, collapseParagraph, line) }
        assertTrue("The complete collapse label remains readable", geometry(collapse, collapseParagraph).visible)
        captureIfRequested("detail-collapse")
        val collapseHit = clickDetailGlyph(collapse, collapseParagraph,
            compose.onNodeWithTag("vocabulary-detail-collapse").assertIsEnabled(), actions)
        assertEquals("A physical collapse remains reachable after reading/selecting the sense; $collapseHit", listOf(
            VocabularyBookAction.SenseSelected(sense.senseId),
            VocabularyBookAction.SenseSelected(sense.senseId),
            VocabularyBookAction.CollapseRequested,
        ), actions)
        // The Screen collector is deliberately not a fake ViewModel/repository:
        // exact requests are verified, not persisted selection or a real collapse.
    }

    @Test
    fun referenceThreeCafeExamplesRemainCompactLocallyReadableAndExactlyActionable() {
        val state = referenceCafeState()
        val detail = requireNotNull(state.expandedSense)
        val examples = detail.examples
        val actions = render(state, reference = true)
        val positions = mutableListOf<String>()
        captureIfRequested("reference-three-initial")
        val englishParagraphs = examples.map { layout(text(it.textEn), it.textEn, 11f) }
        val chineseParagraphs = examples.map { layout(text(it.textZh), it.textZh, 9f) }
        val rowOrigins = examples.mapIndexed { index, example -> geometry(text(example.textEn), englishParagraphs[index]).origin.y }
        examples.forEachIndexed { index, example ->
            val button = compose.onNodeWithTag("vocabulary-example-${detail.senseId}-$index").assertIsEnabled()
            val english = englishParagraphs[index]
            val chinese = chineseParagraphs[index]
            val enGeometry = geometry(text(example.textEn), english)
            val zhGeometry = geometry(text(example.textZh), chinese)
            assertTrue("Example $index keeps Chinese below its own English", enGeometry.origin.y + english.size.height <= zhGeometry.origin.y + 1f)
            if (index < examples.lastIndex) {
                val naturalRowHeight = maxOf(button.fetchSemanticsNode().layoutInfo.height, english.size.height + chinese.size.height)
                // Existing 4dp column gaps surround a 1dp divider. The square
                // scene image must not space the three examples down a tall row.
                assertTrue("Reference examples stay in a compact natural stack: origins=$rowOrigins row=$index natural=$naturalRowHeight",
                    rowOrigins[index + 1] - rowOrigins[index] <= naturalRowHeight + 20f)
            }
            positions += "initial example=$index englishOrigin=${enGeometry.origin} englishSize=${english.size} englishLines=${english.lineCount} " +
                "englishClip=${enGeometry.clip} chineseOrigin=${zhGeometry.origin} chineseSize=${chinese.size} chineseLines=${chinese.lineCount} " +
                "chineseClip=${zhGeometry.clip} buttonSize=${button.fetchSemanticsNode().layoutInfo.coordinates.size}"
        }
        positions += "initial third-example ancestry ${detailCoordinateDiagnostics(text(examples.last().textEn))}"
        saveReferencePositionsIfRequested(positions)
        examples.forEachIndexed { index, example ->
            val english = englishParagraphs[index]
            val chinese = chineseParagraphs[index]
            repeat(english.lineCount) { reachDetailLineByTouch(text(example.textEn), english, it, useMovableScroll = true) }
            repeat(chinese.lineCount) { reachDetailLineByTouch(text(example.textZh), chinese, it, useMovableScroll = true) }
            val button = compose.onNodeWithTag("vocabulary-example-${detail.senseId}-$index").assertIsEnabled()
            val semantic = button.fetchSemanticsNode()
            val coordinates = semantic.layoutInfo.coordinates
            val point = coordinates.localToRoot(Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
            val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
                .intersect(semantic.boundsInRoot).intersect(content().fetchSemanticsNode().boundsInRoot).intersect(windowBounds())
            assertTrue("The example's real speaker center is visible before physical input: point=$point clip=$clip", clip.contains(point))
            val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
            compose.onRoot().performTouchInput { click(point - rootBounds.topLeft) }
            compose.waitForIdle()
            assertEquals("Only the requested stored sense/example is played", (0..index).map {
                VocabularyBookAction.ExamplePronunciationRequested(detail.senseId, it)
            }, actions)
            positions += "read example=$index english=${geometry(text(example.textEn), english)} chinese=${geometry(text(example.textZh), chinese)} " +
                "speakerPoint=$point speakerClip=$clip"
        }
        listOf("重点句出现 2 次", "非重点句出现 3 次").forEach { value ->
            val node = text(value)
            val paragraph = layout(node, value, 11f)
            repeat(paragraph.lineCount) { reachDetailLineByTouch(node, paragraph, it, useMovableScroll = true) }
            positions += "stat=$value paragraph=${paragraph.size} actual=${geometry(node, paragraph)}"
        }
        val sceneText = requireNotNull(detail.representativeScene)
        val sceneLabel = compose.onNode(hasText(sceneText) and
            hasAnyAncestor(hasTestTag("vocabulary-detail-sheet")), useUnmergedTree = true)
        val sceneParagraph = layout(sceneLabel, sceneText, 10f)
        repeat(sceneParagraph.lineCount) { reachDetailLineByTouch(sceneLabel, sceneParagraph, it, useMovableScroll = true) }
        positions += "sceneCaption=${geometry(sceneLabel, sceneParagraph)}; no trusted illustration binding in stored scene contract"
        captureIfRequested("reference-three-read")
        saveReferencePositionsIfRequested(positions)
        val collapse = text("收回")
        val collapseParagraph = layout(collapse, "收回", 11f)
        repeat(collapseParagraph.lineCount) { reachDetailLineByTouch(collapse, collapseParagraph, it, useMovableScroll = true) }
        val hit = clickDetailGlyph(collapse, collapseParagraph, compose.onNodeWithTag("vocabulary-detail-collapse").assertIsEnabled(), actions)
        assertEquals("Three exact example requests then physical collapse; $hit", listOf(
            VocabularyBookAction.ExamplePronunciationRequested(detail.senseId, 0),
            VocabularyBookAction.ExamplePronunciationRequested(detail.senseId, 1),
            VocabularyBookAction.ExamplePronunciationRequested(detail.senseId, 2),
            VocabularyBookAction.CollapseRequested,
        ), actions)
        // Only a render/action control: the saved scene text is displayed, but no TTS,
        // repository or AI is invoked. Extra featured-card fields and the missing
        // later sense rows are not claimed to match the entire original page41.
    }

    // The earlier 330px-vs-1254px image-layout regression is historical evidence,
    // not a current contract: production records have no illustration binding.
    // VocabularySceneTruthTest now rejects unconditional/keyword-based cafe art.

    private fun referenceCafeState(): VocabularyBookUiState {
        val examples = listOf(
            VocabularyExample("She runs a small café.", "她经营一家小咖啡店。", "She runs a small café."),
            VocabularyExample("He runs the business with his sister.", "他和姐姐一起经营这家公司。", "He runs the business with his sister."),
            VocabularyExample("They run the shop together.", "他们一起经营这家店。", "They run the shop together."),
        )
        val first = VocabularySenseRecord("run-first", "run", "跑步", KnowledgeStatus.MASTERED, 3, setOf("CET4"), 1)
        val detail = VocabularySenseRecord("run-second", "run", "经营", KnowledgeStatus.MASTERED, 1, setOf("CET4"), 2,
            phonetic = "/rʌn/", pronunciationText = "run", representativeScene = "经营一家小咖啡店", examples = examples,
            focusOccurrenceCount = 2, contextOccurrenceCount = 3)
        return VocabularyBookUiState("六级 + 商务英语", listOf(VocabularyEntry("run", listOf(first, detail))),
            expandedSenseId = detail.senseId, loading = false)
    }

    private fun saveReferencePositionsIfRequested(positions: List<String>, label: String = "three-example") {
        val phase = InstrumentationRegistry.getArguments().getString("vocabularySenseSnapshot") ?: return
        require(phase in setOf("before", "after") && label in setOf("three-example", "scene-aspect"))
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "visual-qa")
        check(directory.isDirectory || directory.mkdirs())
        val output = File(directory, "41-vocabulary-$label-positions-$phase.txt")
        check(output.canonicalFile.parentFile == directory.canonicalFile)
        output.writeText("renderOnly=true window=${widthPx}x$heightPx density=2 fontScale=$fontScale gestures=$physicalGestures\n" + positions.joinToString("\n"))
    }

    private fun detailScrollFor(node: SemanticsNodeInteraction, useMovableScroll: Boolean = false): SemanticsNodeInteraction? {
        check(detailMode)
        val ancestors = generateSequence(node.fetchSemanticsNode()) { it.parent }.toList()
        val detailId = content().fetchSemanticsNode().id
        assertTrue("Only the actual detail subtree may own this text", ancestors.any { it.id == detailId })
        val scroll = ancestors.takeWhile { it.id != detailId }
            .firstOrNull { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) &&
                (!useMovableScroll || it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f) } ?: return null
        return compose.onNode(SemanticsMatcher("Actual local detail scroll ${scroll.id}") { it.id == scroll.id },
            useUnmergedTree = true)
    }

    private fun detailScrollPosition(scroll: SemanticsNodeInteraction): Pair<Float, Float> {
        val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return range.value() to range.maxValue()
    }

    private fun detailCoordinateDiagnostics(node: SemanticsNodeInteraction): String {
        val semantic = node.fetchSemanticsNode()
        val semantics = generateSequence(semantic) { it.parent }.joinToString(";") { ancestor ->
            val coordinates = ancestor.layoutInfo.coordinates
            val range = if (ancestor.config.contains(SemanticsProperties.VerticalScrollAxisRange)) {
                val axis = ancestor.config[SemanticsProperties.VerticalScrollAxisRange]
                "${axis.value()}/${axis.maxValue()}"
            } else "none"
            "id=${ancestor.id},tag=${ancestor.config.firstOrNull { it.key.name == "TestTag" }?.value}," +
                "size=${coordinates.size},origin=${coordinates.localToRoot(Offset.Zero)},bounds=${ancestor.boundsInRoot}," +
                "clip=${coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)},scroll=$range"
        }
        val layouts = generateSequence(semantic.layoutInfo.coordinates) { it.parentLayoutCoordinates }.take(16)
            .joinToString(";") { "size=${it.size},origin=${it.localToRoot(Offset.Zero)}" }
        return "semantics=[$semantics] layouts=[$layouts]"
    }

    private fun reachDetailLineByTouch(node: SemanticsNodeInteraction, paragraph: TextLayoutResult, line: Int, useMovableScroll: Boolean = false) {
        var current = geometry(node, paragraph, line)
        if (current.visible) return
        // An example column may have a zero-range nested scroll. For this new
        // reference control only, use its genuinely movable ancestor within the
        // same paper; never a whole-page host or synthetic scroll container.
        val scrollCandidate = detailScrollFor(node, useMovableScroll)
        assertTrue("A clipped detail line must have a bounded local route to become readable, not a whole-page scroll: " +
            "line=$line paragraph=${paragraph.size} geometry=$current", scrollCandidate != null)
        val scroll = requireNotNull(scrollCandidate)
        val initial = detailScrollPosition(scroll)
        var gestures = 0
        var unchanged = 0
        val touchTrace = mutableListOf<String>()
        while (!current.visible && gestures < 100 && unchanged < 4) {
            val bounds = scroll.fetchSemanticsNode().boundsInRoot.intersect(content().fetchSemanticsNode().boundsInRoot).intersect(windowBounds())
            assertTrue("The real local detail viewport must allow a touch gesture", bounds.height > touchSlop + 12f)
            val top = current.boxes.minOf { it.top }
            val bottom = current.boxes.maxOf { it.bottom }
            val directionDown = bottom > bounds.bottom || top >= bounds.top
            val position = detailScrollPosition(scroll)
            if ((directionDown && position.first >= position.second - 1f) || (!directionDown && position.first <= 1f)) break
            val previous = current
            val delta = if (directionDown) bottom - bounds.bottom else bounds.top - top
            val slack = (bounds.height - (bottom - top)).coerceAtLeast(0f)
            val distance = (delta.coerceAtLeast(0f) + slack / 2f + touchSlop).coerceIn(touchSlop + 2f, bounds.height - 8f)
            val start = if (directionDown) bounds.height - 4f else 4f
            if (useMovableScroll) {
                // A partially clipped scroll node's local origin need not be its
                // visible top-left. Inject in actual root coordinates so both
                // endpoints lie in the physically visible scroll viewport.
                val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
                val from = Offset(bounds.center.x, bounds.top + start)
                val to = from + Offset(0f, if (directionDown) -distance else distance)
                assertTrue("Both real touch endpoints stay inside the visible viewport", bounds.contains(from) && bounds.contains(to))
                compose.onRoot().performTouchInput {
                    val localFrom = from - root.topLeft
                    val localTo = to - root.topLeft
                    down(localFrom)
                    // Follow the measured centering displacement slowly, then
                    // emit stationary samples before lifting. A velocity-bearing
                    // swipe overshot this short viewport and oscillated past the
                    // target; it was not evidence that the line was unreachable.
                    repeat(20) { step ->
                        val fraction = (step + 1) / 20f
                        moveTo(Offset(
                            localFrom.x + (localTo.x - localFrom.x) * fraction,
                            localFrom.y + (localTo.y - localFrom.y) * fraction,
                        ), 60L)
                    }
                    repeat(6) {
                        advanceEventTime(40L)
                        moveTo(localTo, 0L)
                    }
                    up()
                }
                if (gestures < 4 || gestures == 99 || unchanged == 3) touchTrace +=
                    "gesture=$gestures from=$from to=$to visibleBounds=$bounds scrollBefore=$position ${detailCoordinateDiagnostics(node)}"
            } else {
                // Preserve the already verified long-sense gesture behavior.
                scroll.performTouchInput {
                    swipe(Offset(bounds.width / 2f, start), Offset(bounds.width / 2f, start + if (directionDown) -distance else distance), 300)
                }
            }
            compose.waitForIdle()
            physicalGestures++; gestures++
            current = geometry(node, paragraph, line)
            unchanged = if (current == previous && detailScrollPosition(scroll) == position) unchanged + 1 else 0
        }
        val diagnostic = "line=$line paragraph=${paragraph.size} geometry=$current gestures=$gestures scroll=${detailScrollPosition(scroll)}" +
            if (useMovableScroll) " trace=$touchTrace final=${detailCoordinateDiagnostics(node)}" else ""
        if (!current.visible && gestures == 100 && unchanged < 4) {
            val position = detailScrollPosition(scroll)
            assertTrue("Gesture budget exhausted before a detail verdict: $diagnostic", position.first <= 1f || position.first >= position.second - 1f)
        }
        assertTrue("The whole detail line must be readable after real local touch: $diagnostic", current.visible)
        assertTrue("A dispatched gesture alone is not evidence of movement", gestures > 0 && detailScrollPosition(scroll) != initial)
    }

    private fun clickDetailGlyph(
        node: SemanticsNodeInteraction,
        paragraph: TextLayoutResult,
        button: SemanticsNodeInteraction,
        actions: List<VocabularyBookAction>,
        firstGlyph: Boolean = false,
    ): String {
        val visible = geometry(node, paragraph, paragraph.lineCount - 1)
        assertTrue("Select only a line already brought into view", visible.visible)
        val glyph = if (firstGlyph) visible.boxes.first() else visible.boxes.last()
        val point = glyph.center
        val action = button.fetchSemanticsNode()
        assertTrue(action.boundsInRoot.contains(point))
        assertTrue(action.config.contains(SemanticsActions.OnClick))
        val scroll = detailScrollFor(node)
        val beforeScroll = scroll?.let(::detailScrollPosition)
        val beforeCount = actions.size
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(point - rootBounds.topLeft) }
        compose.waitForIdle()
        assertEquals("Clicking must not change the inspected line's scroll position", beforeScroll, scroll?.let(::detailScrollPosition))
        return "firstGlyph=$firstGlyph point=$point glyph=$glyph clip=${visible.clip} actionBounds=${action.boundsInRoot}," +
            "scroll=$beforeScroll,actionsBefore=$beforeCount,actionsAfter=${actions.size}"
    }

    private fun state(longSecondSense: Boolean): VocabularyBookUiState {
        val first = VocabularySenseRecord("run-first", "run", "跑步", KnowledgeStatus.MASTERED, 3, setOf("CET4"), 1)
        val meaning = if (longSecondSense) {
            "在当前语境中表示经营并负责安排一项工作的日常事务，包括协调人员、安排时间和合理使用资源。" +
                "这里强调对整个过程承担组织责任，而不是表示人在道路上跑步，也不是机器正在运转。" +
                "可以用来描述负责一家商店、一个团队或者一项持续开展的活动。" +
                "概括起来，这个词在本句里的意思就是负责日常事务的组织和管理。"
        } else "经营"
        val second = VocabularySenseRecord("run-second", "run", meaning, KnowledgeStatus.LEARNING, 1, setOf("CET4"), 2)
        val other = VocabularySenseRecord("explain-first", "explain", "解释；讲清楚", KnowledgeStatus.NOT_STARTED, 0, setOf("CET4"), 3)
        return VocabularyBookUiState("六级 + 商务英语", listOf(
            VocabularyEntry("run", listOf(first, second)),
            VocabularyEntry("explain", listOf(other)),
        ), loading = false)
    }

    private fun render(state: VocabularyBookUiState, reference: Boolean = false): MutableList<VocabularyBookAction> {
        widthPx = if (reference) 1829 else 1280
        heightPx = if (reference) 860 else 720
        fontScale = if (reference) 1f else 2f
        detailMode = state.expandedSenseId != null
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.window.decorView.width == widthPx && compose.activity.window.decorView.height == heightPx }
        val actions = mutableListOf<VocabularyBookAction>()
        compose.setContent { RlTheme { RlPaperBackground {
            touchSlop = LocalViewConfiguration.current.touchSlop
            VocabularyBookScreen(state, actions::add)
        } } }
        compose.waitForIdle()
        assertActualWindow()
        content().assertIsDisplayed()
        return actions
    }

    private fun assertActualWindow() {
        val resources = compose.activity.resources
        assertEquals(320, resources.configuration.densityDpi)
        assertEquals(2f, resources.displayMetrics.density, .001f)
        assertEquals("Never substitute fake local density for system font scale", fontScale, resources.configuration.fontScale, .001f)
        assertEquals(widthPx, compose.activity.window.decorView.width)
        assertEquals(heightPx, compose.activity.window.decorView.height)
        val root = compose.onRoot().fetchSemanticsNode().layoutInfo
        assertEquals(widthPx, root.width); assertEquals(heightPx, root.height)
    }

    private fun content() = compose.onNodeWithTag(if (detailMode) "vocabulary-detail-sheet" else "vocabulary-sense-list")
    private fun text(value: String) = compose.onNodeWithText(value, useUnmergedTree = true)
    private fun windowBounds() = Rect(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
    private fun scrollPosition(): Pair<Float, Float> {
        check(!detailMode)
        val range = content().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return range.value() to range.maxValue()
    }

    private fun layout(node: SemanticsNodeInteraction, value: String, fontSp: Float): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals("Resolve one real text paragraph before any product verdict", 1, layouts.size)
        val paragraph = layouts.single()
        assertEquals(value, paragraph.layoutInput.text.text)
        assertEquals(2f, paragraph.layoutInput.density.density, .001f)
        assertEquals(fontScale, paragraph.layoutInput.density.fontScale, .001f)
        assertEquals("Do not reduce the existing text size", fontSp.sp, paragraph.layoutInput.style.fontSize)
        assertFalse(paragraph.multiParagraph.didExceedMaxLines)
        assertTrue(paragraph.lineCount > 0)
        assertEquals(value.length, paragraph.getLineEnd(paragraph.lineCount - 1))
        repeat(paragraph.lineCount) { assertFalse(paragraph.isLineEllipsized(it)) }
        return paragraph
    }

    private data class GlyphGeometry(val origin: Offset, val clip: Rect, val boxes: List<Rect>) {
        val visible get() = clip.width > 0 && clip.height > 0 && boxes.isNotEmpty() && boxes.all {
            it.left >= clip.left - 1f && it.top >= clip.top - 1f && it.right <= clip.right + 1f && it.bottom <= clip.bottom + 1f
        }
    }

    private fun geometry(node: SemanticsNodeInteraction, paragraph: TextLayoutResult, line: Int? = null): GlyphGeometry {
        val semantic = node.fetchSemanticsNode()
        val coordinates = semantic.layoutInfo.coordinates
        val origin = paragraphOrigin(paragraph, coordinates.localToRoot(Offset.Zero), coordinates.size.width)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(semantic.boundsInRoot).intersect(content().fetchSemanticsNode().boundsInRoot).intersect(windowBounds())
        val source = paragraph.layoutInput.text.text
        val rows = if (line == null) 0 until paragraph.lineCount else line..line
        val boxes = rows.flatMap { row ->
            (paragraph.getLineStart(row) until paragraph.getLineEnd(row, visibleEnd = true))
                .filter { !source[it].isWhitespace() }.map { paragraph.getBoundingBox(it).translate(origin) }
        }
        return GlyphGeometry(origin, clip, boxes)
    }

    private fun paragraphOrigin(layout: TextLayoutResult, origin: Offset, actualWidth: Int): Offset {
        val semanticWidth = layout.multiParagraph.width
        if (abs(semanticWidth - actualWidth) <= 1f) return origin
        // Same proven single-line ParagraphLayoutCache alignment correction as
        // page31/43. Never compensate vertical offsets or enlarge the real clip.
        val left = layout.getLineLeft(0)
        val right = layout.getLineRight(0)
        val diagnostic = "semanticWidth=$semanticWidth actualWidth=$actualWidth size=${layout.size} lines=${layout.lineCount} left=$left right=$right"
        assertTrue("Resolve actual paragraph coordinates before a verdict: $diagnostic",
            layout.lineCount == 1 && layout.size.width == actualWidth && semanticWidth > actualWidth &&
                ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt() == actualWidth)
        val align = layout.layoutInput.style.textAlign
        val isLeft = align == TextAlign.Left || ((align == TextAlign.Start || align == TextAlign.Unspecified) &&
            layout.layoutInput.layoutDirection == LayoutDirection.Ltr)
        if (isLeft && abs(left) <= 1f && right in 0f..(actualWidth + 1f)) return origin
        val offset = (semanticWidth - actualWidth) / 2f
        assertTrue("Only the already verified Center cache offset may be removed: $diagnostic",
            align == TextAlign.Center && abs(left - offset) <= 1f && abs(right - (offset + actualWidth)) <= 1f)
        return origin - Offset(offset, 0f)
    }

    private fun reachLineByTouch(node: SemanticsNodeInteraction, paragraph: TextLayoutResult, line: Int) {
        var current = geometry(node, paragraph, line)
        val initiallyVisible = current.visible
        val before = scrollPosition()
        var gestures = 0
        var unchanged = 0
        while (!current.visible && gestures < 100 && unchanged < 4) {
            val bounds = content().fetchSemanticsNode().boundsInRoot.intersect(windowBounds())
            assertTrue("Production list must have room for a real drag", bounds.height > touchSlop + 12f)
            val top = current.boxes.minOf { it.top }
            val bottom = current.boxes.maxOf { it.bottom }
            val directionDown = bottom > bounds.bottom || top >= bounds.top
            val position = scrollPosition()
            if ((directionDown && position.first >= position.second - 1f) || (!directionDown && position.first <= 1f)) break
            val previous = current
            val delta = if (directionDown) bottom - bounds.bottom else bounds.top - top
            val slack = (bounds.height - (bottom - top)).coerceAtLeast(0f)
            content().performTouchInput {
                val distance = (delta.coerceAtLeast(0f) + slack / 2f + touchSlop).coerceIn(touchSlop + 2f, bounds.height - 8f)
                val start = if (directionDown) bounds.height - 4f else 4f
                swipe(Offset(bounds.width / 2f, start), Offset(bounds.width / 2f, start + if (directionDown) -distance else distance), 300)
            }
            compose.waitForIdle()
            physicalGestures++; gestures++
            current = geometry(node, paragraph, line)
            unchanged = if (current == previous && scrollPosition() == position) unchanged + 1 else 0
        }
        val diagnostic = "line=$line paragraph=${paragraph.size} geometry=$current gestures=$gestures scroll=${scrollPosition()}"
        if (!current.visible && gestures == 100 && unchanged < 4) {
            val position = scrollPosition()
            assertTrue("Gesture budget exhausted before a product verdict: $diagnostic", position.first <= 1f || position.first >= position.second - 1f)
        }
        assertTrue("The whole line must become readable by real touch: $diagnostic", current.visible)
        if (!initiallyVisible) assertTrue("Dispatching a swipe is not evidence of movement", gestures > 0 && scrollPosition() != before)
    }

    private fun clickFinalVisibleGlyph(
        node: SemanticsNodeInteraction,
        paragraph: TextLayoutResult,
        button: SemanticsNodeInteraction,
        actions: List<VocabularyBookAction>,
        firstGlyph: Boolean = false,
    ): String {
        val visible = geometry(node, paragraph, paragraph.lineCount - 1)
        assertTrue(visible.visible)
        val glyph = if (firstGlyph) visible.boxes.first() else visible.boxes.last()
        val point = glyph.center
        val action = button.fetchSemanticsNode()
        assertTrue("The actual selected glyph is inside the action's rectangular bounds", action.boundsInRoot.contains(point))
        assertTrue(action.config.contains(SemanticsActions.OnClick))
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val ancestors = generateSequence(node.fetchSemanticsNode()) { it.parent }.joinToString(";") {
            val coordinates = it.layoutInfo.coordinates
            "id=${it.id},size=${coordinates.size},origin=${coordinates.localToRoot(Offset.Zero)}," +
                "clip=${coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)}," +
                "shape=${it.config.firstOrNull { entry -> entry.key.name == "Shape" }?.value}"
        }
        val beforeScroll = scrollPosition()
        val beforeCount = actions.size
        compose.onRoot().performTouchInput { click(point - rootBounds.topLeft) }
        compose.waitForIdle()
        val decor = compose.activity.window.decorView
        return "firstGlyph=$firstGlyph point=$point glyph=$glyph paragraphOrigin=${visible.origin} clip=${visible.clip}," +
            "actionBounds=${action.boundsInRoot},beforeScroll=$beforeScroll,afterScroll=${scrollPosition()}," +
            "actionsBefore=$beforeCount,actionsAfter=${actions.size},window=${decor.width}x${decor.height}," +
            "focused=${decor.hasWindowFocus()},ime=${androidx.core.view.ViewCompat.getRootWindowInsets(decor)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())}," +
            "ancestors=[$ancestors]"
    }

    private fun captureIfRequested(label: String) {
        val phase = InstrumentationRegistry.getArguments().getString("vocabularySenseSnapshot") ?: return
        require(phase in setOf("before", "after") && label in setOf("list-initial", "list-tail-touch", "list-first-glyph-touch",
            "reference-control", "detail-second-sense", "detail-second-sense-tail", "detail-collapse",
            "reference-three-initial", "reference-three-read", "reference-scene-aspect"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        assertActualWindow()
        val before = if (detailMode) null else scrollPosition()
        val decor = compose.activity.window.decorView
        val drawn = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { drawn.countDown() }
        instrumentation.runOnMainSync {
            assertTrue("Capture only the foreground isolated vocabulary window", decor.hasWindowFocus())
            decor.viewTreeObserver.addOnDrawListener(listener)
            decor.postInvalidateOnAnimation()
        }
        try { check(drawn.await(10, TimeUnit.SECONDS)) { "Actual vocabulary window did not draw" } }
        finally { instrumentation.runOnMainSync { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener) } }
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
        assertEquals(before, if (detailMode) null else scrollPosition())
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(widthPx, image.width); assertEquals(heightPx, image.height)
            var inkSamples = 0
            for (y in 0 until image.height step 16) for (x in 0 until image.width step 16) {
                val color = image.getPixel(x, y)
                if (android.graphics.Color.red(color) < 170 && android.graphics.Color.green(color) < 170 &&
                    android.graphics.Color.blue(color) < 170) inkSamples++
            }
            assertTrue("A blank frame is not evidence", inkSamples > 8)
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            val page = if (detailMode) "41" else "40"
            val output = File(directory, "$page-vocabulary-sense-$label-$phase.png")
            check(output.canonicalFile.parentFile == directory.canonicalFile)
            output.outputStream().use { check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }
}
