package com.reallanguage.app.feature.home.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.home.domain.AppMessage
import com.reallanguage.app.feature.home.domain.AppMessageTarget
import com.reallanguage.app.feature.home.domain.MessageKind
import com.reallanguage.app.feature.home.domain.MessageTargetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Regional checks from immutable 57-messages.png (1831×859). This fixture has no
 * cutout; the real production device's ~50px inset must not be removed to match it.
 * These assertions do not certify unavailable fonts, wax seals or paper textures.
 * Isolated display data is not evidence of a server-side message/read operation.
 */
class MessagesReferenceLayoutTest {
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

    @Test fun fourReferenceMessagesOccupyTheListRegionAndRemainWholeAboveTheFooter() {
        val actions = mutableListOf<MessagesAction>()
        render(actions::add)
        val root = rootBounds()
        composeRule.waitForIdle()
        captureMessagesVisualEvidence("57-reference-no-cutout") {
            composeRule.onNodeWithTag(VIEWPORT).captureToImage().asAndroidBitmap()
        }
        val rows = composeRule.onAllNodesWithTag("message-row", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(4, rows.size)
        val bounds = rows.map { it.boundsInRoot.relativeTo(root) }
        assertTrue("The reference list starts near x670, not in the inbox region: $bounds",
            bounds.all { it.left in 650f..690f && it.right in 1750f..1790f })
        assertTrue("The no-cutout reference first row begins near y163: $bounds", bounds.first().top in 150f..178f)
        assertTrue("The four reference-height rows must be complete, not individually compressed: $bounds",
            bounds.all { it.height in 120f..148f })
        val footerTop = minOf(
            composeRule.onNodeWithTag("messages-mark-all-read").fetchSemanticsNode().boundsInRoot.top,
            composeRule.onNodeWithTag("messages-return-home").fetchSemanticsNode().boundsInRoot.top,
        ) - root.top
        assertTrue("The last complete row ends near y707 and does not slide underneath actions: $bounds, footer=$footerTop",
            bounds.last().bottom in 695f..724f && bounds.last().bottom <= footerTop - 8f)
        messages.forEach { message ->
            composeRule.onNodeWithText(message.summaryZh, useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithText(message.relatedContentLabelZh!!, useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("messages-open-${message.messageId}").assertIsDisplayed().performClick()
        }
        assertEquals(messages.map { MessagesAction.MessageRequested(it.messageId) }, actions)
    }

    @Test fun footerActionsKeepTheReferenceWidthsAndPositionsInsteadOfStretchingAcrossTheWholeList() {
        val actions = mutableListOf<MessagesAction>()
        render(actions::add)
        val root = rootBounds()
        val markRead = composeRule.onNodeWithTag("messages-mark-all-read").assertIsDisplayed()
        val home = composeRule.onNodeWithTag("messages-return-home").assertIsDisplayed()
        val markBounds = markRead.fetchSemanticsNode().boundsInRoot.relativeTo(root)
        val homeBounds = home.fetchSemanticsNode().boundsInRoot.relativeTo(root)
        // Source paint is x700..1114 and x1154..1527. Semantics may include the
        // required 48dp touch area, so this measures width/center, not paint height.
        assertTrue("Mark-read action must retain the original left/width: $markBounds",
            markBounds.left in 688f..712f && markBounds.width in 400f..428f)
        assertTrue("Return-home action must retain the original left/width: $homeBounds",
            homeBounds.left in 1142f..1166f && homeBounds.width in 359f..387f)
        listOf(markBounds, homeBounds).forEach {
            assertTrue("Actions stay at the reference footer without touching the bottom edge: $it",
                it.center.y in 768f..792f && it.bottom <= 843f)
        }
        markRead.performClick()
        home.performClick()
        assertEquals(listOf(MessagesAction.MarkAllReadRequested, MessagesAction.ReturnHomeRequested), actions)
    }

    @Test fun inboxTitleAndLiveUnreadCountUseTheActualBlankPaperRegionsOfTheUnchangedAsset() {
        render(onAction = {})
        val artwork = composeRule.onNodeWithTag("messages-inbox-artwork").fetchSemanticsNode()
        val bitmap = BitmapFactory.decodeResource(composeRule.activity.resources, R.drawable.rl_messages_inbox_collage)
        try {
            assertEquals(1199, bitmap.width)
            assertEquals(1312, bitmap.height)
            // Image is Fit inside this Box; use actual source coordinates rather
            // than fixed offsets from a differently sized parent Column.
            val scale = min(artwork.size.width.toFloat() / bitmap.width, artwork.size.height.toFloat() / bitmap.height)
            val imageX = artwork.positionInRoot.x + (artwork.size.width - bitmap.width * scale) / 2f
            val imageY = artwork.positionInRoot.y + (artwork.size.height - bitmap.height * scale) / 2f
            fun assertOnBlankRegion(text: String, region: Rect) {
                val actual = composeRule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot
                val onSource = Rect((actual.left - imageX) / scale, (actual.top - imageY) / scale,
                    (actual.right - imageX) / scale, (actual.bottom - imageY) / scale)
                assertTrue("[$text] must remain within its actual empty blue-paper region $region, not the envelope/stamps: $onSource",
                    onSource.left >= region.left && onSource.right <= region.right &&
                        onSource.top >= region.top && onSource.bottom <= region.bottom)
            }
            // Title: below the clip and above the envelope. Count: the blue ticket
            // above the postage stamps. These are separate frozen-asset regions;
            // the asset is not identical to the original page's envelope collage.
            assertOnBlankRegion("我的收件箱", Rect(290f, 205f, 1060f, 328f))
            assertOnBlankRegion("3 条新消息", Rect(450f, 788f, 1040f, 951f))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun realSystemInsetsKeepFourShortReferenceMessagesInHorizontalRowsAboveFooter() {
        composeRule.activityRule.scenario.onActivity {
            WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        val actions = mutableListOf<MessagesAction>()
        render(actions::add, consumeInsets = false)
        composeRule.waitForIdle()
        captureMessagesVisualEvidence("57-reference-four-real-system-bars")
        composeRule.runOnIdle {
            val actualInsets = WindowInsetsCompat.toWindowInsetsCompat(composeRule.activity.window.decorView.rootWindowInsets)
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            assertTrue("This is the real-bars/cutout control, not the zero-inset reference fixture: $actualInsets",
                actualInsets.top > 0 && actualInsets.bottom > 0)
        }

        val rows = composeRule.onAllNodesWithTag("message-row", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(4, rows.size)
        val footerTop = composeRule.onNodeWithTag("messages-mark-all-read").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        val list = composeRule.onNodeWithTag("messages-scroll-list").fetchSemanticsNode().boundsInRoot
        messages.forEachIndexed { index, message ->
            val title = composeRule.onNodeWithText(message.summaryZh, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val detail = composeRule.onNodeWithText(message.relatedContentLabelZh!!, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val time = composeRule.onNodeWithText(listOf("今天 14:32", "今天 11:08", "昨天 19:46", "昨天 08:00")[index],
                useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val status = composeRule.onNodeWithText(listOf("已通过", "可学习", "需修改", "去查看")[index],
                useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Wide landscape must retain the original title/detail beside time/status, not stack a new metadata row: " +
                "title=$title, detail=$detail, time=$time, status=$status",
                time.left >= detail.right && time.center.y >= title.top && time.center.y <= detail.bottom &&
                    status.left >= time.right && status.center.y >= title.top && status.center.y <= detail.bottom)
            val row = rows[index]
            assertTrue("All four short reference rows must be whole above the persistent footer with real insets: " +
                "row=${row.boundsInRoot}, actualSize=${row.size}, list=$list, footer=$footerTop",
                row.boundsInRoot.height + 1f >= row.size.height && row.boundsInRoot.top >= list.top - 1f &&
                    row.boundsInRoot.bottom <= footerTop - 8f)
            assertWholeVisibleText(message.summaryZh)
            assertWholeVisibleText(message.relatedContentLabelZh!!)
            composeRule.onNodeWithTag("messages-open-${message.messageId}").assertIsDisplayed().performClick()
        }
        composeRule.onNodeWithTag("messages-return-home").assertIsDisplayed().performClick()
        assertEquals(messages.map { MessagesAction.MessageRequested(it.messageId) } + MessagesAction.ReturnHomeRequested, actions)
    }

    @Test fun referenceMessageCardsHaveRealWarmPaperPixelsInsteadOfFlatLibrarySurfaces() {
        render(onAction = {})
        messages.forEach { message ->
            val card = composeRule.onNode(hasTestTag("message-row") and hasAnyDescendant(hasText(message.summaryZh)),
                useUnmergedTree = true).captureToImage().toPixelMap()
            // A blank interior strip: below the top edge, above all live text,
            // far from icons and shadows. A flat white Surface cannot pass.
            val colors = buildList {
                for (y in 8..13) for (x in (card.width * .35f).roundToInt()..(card.width * .62f).roundToInt()) add(card[x, y])
            }
            val distinctPaperPixels = colors.map { it.toArgb() and 0x00ffffff }.toSet().size
            val averageWarmth = colors.sumOf { (it.red - it.blue).toDouble() } / colors.size
            assertTrue("${message.messageId}: the source card is textured warm paper, not a flat white UI-library surface; " +
                "distinct=$distinctPaperPixels, redMinusBlue=$averageWarmth",
                distinctPaperPixels >= 4 && averageWarmth >= 8.0 / 255.0)
        }
    }

    @Test fun realSystemInsetsKeepEveryMessageGlyphInsideTheVisiblePaperAboveItsShadow() {
        composeRule.activityRule.scenario.onActivity {
            WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        val actions = mutableListOf<MessagesAction>()
        render(actions::add, consumeInsets = false)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val insets = WindowInsetsCompat.toWindowInsetsCompat(composeRule.activity.window.decorView.rootWindowInsets)
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            assertTrue("This regression requires the real reduced safe canvas: $insets", insets.top > 0 && insets.bottom > 0)
        }
        val viewport = composeRule.onNodeWithTag(VIEWPORT)
        val viewportBounds = viewport.fetchSemanticsNode().boundsInRoot
        // Capture the viewport, not just each row's semantics rectangle: a real
        // paper shadow may legitimately paint into the existing inter-row gap.
        val pixels = viewport.captureToImage().toPixelMap()
        val rows = composeRule.onAllNodesWithTag("message-row", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(4, rows.size)
        val footer = composeRule.onNodeWithTag("messages-mark-all-read").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.relativeTo(viewportBounds)
        val times = listOf("今天 14:32", "今天 11:08", "昨天 19:46", "昨天 08:00")
        messages.forEachIndexed { index, message ->
            val row = rows[index].boundsInRoot.relativeTo(viewportBounds)
            val title = composeRule.onNodeWithText(message.summaryZh, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.relativeTo(viewportBounds)
            val detail = composeRule.onNodeWithText(message.relatedContentLabelZh!!, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.relativeTo(viewportBounds)
            val time = composeRule.onNodeWithText(times[index], useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.relativeTo(viewportBounds)
            assertTrue("The original four-row arrangement must remain above the fixed actions: row=$row footer=$footer",
                row.height + 1f >= rows[index].size.height && row.bottom <= footer.top - 8f)

            // This clear strip lies in the existing title/time gap, before its
            // divider. Measure actual rendered paper/shadow pixels, not a source
            // crop constant or a test tag that can drift with the implementation.
            val stripLeft = title.right.roundToInt() + 3
            val stripRight = stripLeft + 3
            assertTrue("The paper probe must not include text or the divider: title=$title time=$time",
                stripRight < (title.right + time.left) / 2f - 1f)
            fun stripColor(y: Int): Triple<Float, Float, Float> {
                val colors = (stripLeft..stripRight).map { x -> pixels[x, y] }
                return Triple(colors.map { it.red }.average().toFloat(),
                    colors.map { it.green }.average().toFloat(), colors.map { it.blue }.average().toFloat())
            }
            val nextTop = if (index < rows.lastIndex) rows[index + 1].boundsInRoot.top - viewportBounds.top else footer.top
            val searchTop = (row.top + row.height * .72f).roundToInt()
            val searchBottom = minOf((row.bottom + 8f).roundToInt(), nextTop.roundToInt() - 1, pixels.height - 1)
            val shadowY = (searchTop..searchBottom).minBy { stripColor(it).first }
            val shadow = stripColor(shadowY)
            assertTrue("The unchanged asset's real lower shadow must be present, not clipped away: " +
                "${message.messageId}, y=$shadowY rgb=$shadow", shadow.first < .92f)
            val paperBottom = (shadowY downTo searchTop).firstOrNull { y ->
                val color = stripColor(y)
                color.first >= .94f && color.second >= .90f && color.third >= .84f
            } ?: error("No warm paper ink boundary above the actual shadow for ${message.messageId}")

            listOf(message.summaryZh to title, message.relatedContentLabelZh!! to detail).forEach { (text, bounds) ->
                assertWholeVisibleText(text)
                val glyphRows = (bounds.top.roundToInt() until bounds.bottom.roundToInt()).filter { y ->
                    (bounds.left.roundToInt() until bounds.right.roundToInt()).any { x ->
                        val pixel = pixels[x, y]
                        pixel.red < .5f && pixel.green < .5f && pixel.blue < .55f
                    }
                }
                assertTrue("The live text must have actual visible ink: [$text]", glyphRows.isNotEmpty())
                assertTrue("[$text] needs at least 2dp of real paper around its glyphs, not only a large row hit box: " +
                    "glyphY=${glyphRows.first()}..${glyphRows.last()}, paperBottom=$paperBottom, shadowY=$shadowY, row=$row",
                    glyphRows.first() >= row.top + 4f && glyphRows.last() + 4 <= paperBottom)
            }
            composeRule.onNodeWithTag("messages-open-${message.messageId}").assertIsDisplayed().performClick()
        }
        composeRule.onNodeWithTag("messages-return-home").assertIsDisplayed().performClick()
        assertEquals(messages.map { MessagesAction.MessageRequested(it.messageId) } + MessagesAction.ReturnHomeRequested, actions)
    }

    @Test fun referenceTimeColumnsHaveTheExistingDesignsFineVerticalDividerWithoutMovingText() {
        render(onAction = {})
        val times = listOf("今天 14:32", "今天 11:08", "昨天 19:46", "昨天 08:00")
        messages.forEachIndexed { index, message ->
            val row = composeRule.onNode(hasTestTag("message-row") and hasAnyDescendant(hasText(message.summaryZh)),
                useUnmergedTree = true)
            val rowBounds = row.fetchSemanticsNode().boundsInRoot
            val pixels = row.captureToImage().toPixelMap()
            val text = composeRule.onNodeWithText(message.summaryZh, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val time = composeRule.onNodeWithText(times[index], useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val dividerX = ((text.right + time.left) / 2f - rowBounds.left).roundToInt()
            val centerY = (time.center.y - rowBounds.top).roundToInt()
            val strokeRows = (centerY - 28..centerY + 28).count { y ->
                (dividerX - 1..dividerX + 1).any { x ->
                    val pixel = pixels[x, y]
                    pixel.red < .86f && pixel.green < .82f && pixel.blue < .77f
                }
            }
            assertTrue("${message.messageId}: the fine divider belongs in the existing text/time gap, not over content; " +
                "x=$dividerX, visibleStrokeRows=$strokeRows", strokeRows >= 36)
        }
    }

    @Test fun referenceFooterRetainsItsInnerFineBordersAndExistingCheckHomeIcons() {
        render(onAction = {})
        listOf("messages-mark-all-read", "messages-return-home").forEach { tag ->
            val pixels = composeRule.onNodeWithTag(tag).assertIsDisplayed().captureToImage().toPixelMap()
            val start = (pixels.height * .25f).roundToInt()
            val end = (pixels.height * .75f).roundToInt()
            // Source footer has a second inset outline. Sample only its vertical
            // edge, away from text/icons, preserving the current hit-box geometry.
            val innerStrokeRows = (start..end).count { y ->
                (5..8).any { x ->
                    val pixel = pixels[x, y]
                    if (tag == "messages-mark-all-read") pixel.red < .86f && pixel.green < .82f && pixel.blue < .77f
                    else pixel.red > .7f && pixel.green > .7f && pixel.blue > .7f
                }
            }
            assertTrue("$tag: source inner fine outline is missing; strokeRows=$innerStrokeRows",
                innerStrokeRows >= (end - start + 1) * .8f)
        }
        composeRule.onNodeWithTag("messages-read-check-circle-icon", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("messages-home-icon", useUnmergedTree = true).assertIsDisplayed()
    }

    /** Read-only diagnostic, not a visual-acceptance assertion or replacement for the ROI test. */
    @Test fun diagnoseNormalArtworkActualTextMeasurementsAndCanvasConstraints() {
        lateinit var measurer: TextMeasurer
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                RlTheme {
                    val actualMeasurer = rememberTextMeasurer()
                    SideEffect { measurer = actualMeasurer }
                    Box(Modifier.requiredSize(915.5.dp, 429.5.dp)
                        .consumeWindowInsets(WindowInsets.safeDrawing).testTag(VIEWPORT)) {
                        MessagesScreen(MessagesUiState(messages = messages, loading = false), {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        fun textLayout(text: String): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            return layouts.single()
        }
        val title = textLayout("我的收件箱")
        val count = textLayout("3 条新消息")
        val artwork = composeRule.onNodeWithTag("messages-inbox-artwork", useUnmergedTree = true).fetchSemanticsNode()
        val canvas = composeRule.onNodeWithTag("messages-inbox-canvas", useUnmergedTree = true).fetchSemanticsNode()
        val image = composeRule.onNodeWithTag("messages-inbox-image", useUnmergedTree = true).fetchSemanticsNode()
        val report = composeRule.runOnIdle {
            // The production Text's resolved styles, not a guessed font/style
            // copy, feed the same Compose measuring API at the actual density.
            val titleMeasure = measurer.measure("我的收件箱", title.layoutInput.style, softWrap = false)
            val countMeasure = measurer.measure("3 条新消息", count.layoutInput.style, softWrap = false)
            val density = title.layoutInput.density
            val twoDp = with(density) { 2.dp.toPx() }
            val fitScale = min(artwork.size.width / 1199f, artwork.size.height / 1312f)
            val candidates = linkedMapOf(
                "titleWidth" to (titleMeasure.size.width + twoDp + 1f) / 770f,
                "titleHeight" to (titleMeasure.size.height + twoDp) / 123f,
                "countWidth" to (countMeasure.size.width + twoDp + 1f) / 590f,
                "countHeight" to (countMeasure.size.height + twoDp) / 163f,
            )
            fun describe(label: String, actual: TextLayoutResult, measured: TextLayoutResult) =
                "$label renderedSize=${actual.size}, renderedConstraints=${actual.layoutInput.constraints}, " +
                    "style=${actual.layoutInput.style}, density=${actual.layoutInput.density}, " +
                    "lines=${actual.lineCount}, lineBottom=${actual.getLineBottom(actual.lineCount - 1)}, " +
                    "measuredSize=${measured.size}, measuredConstraints=${measured.layoutInput.constraints}, " +
                    "measuredDensity=${measured.layoutInput.density}, measuredStyle=${measured.layoutInput.style}"
            buildString {
                appendLine("MESSAGES_ARTWORK_MEASURE_DIAGNOSTIC")
                appendLine("artwork size=${artwork.size}, position=${artwork.positionInRoot}, clippedBounds=${artwork.boundsInRoot}")
                appendLine("canvas size=${canvas.size}, position=${canvas.positionInRoot}, clippedBounds=${canvas.boundsInRoot}")
                appendLine("image size=${image.size}, position=${image.positionInRoot}, clippedBounds=${image.boundsInRoot}")
                appendLine("fitScalePxPerSourcePixel=$fitScale, minimumReadableCandidates=$candidates, " +
                    "minimumReadableScale=${candidates.values.max()}, expectedScale=${maxOf(fitScale, candidates.values.max())}")
                appendLine(describe("title", title, titleMeasure))
                appendLine(describe("count", count, countMeasure))
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\n$report\n") })
    }

    private fun assertWholeVisibleText(text: String) {
        val interaction = composeRule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        interaction.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        val visible = interaction.fetchSemanticsNode().boundsInRoot
        (0 until layout.lineCount).forEach { line ->
            assertTrue("[$text] must have whole visible glyphs, not an ellipsis or a clipped row: $visible, ${layout.size}",
                !layout.isLineEllipsized(line) && layout.getLineLeft(line) >= -1f &&
                    layout.getLineRight(line) <= visible.width + 1f && layout.getLineBottom(line) <= visible.height + 1f)
        }
    }

    private fun rootBounds(): Rect = composeRule.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot.also {
        assertEquals(1831f, it.width, 1f)
        assertEquals(859f, it.height, 1f)
    }

    private fun Rect.relativeTo(root: Rect) = Rect(left - root.left, top - root.top, right - root.left, bottom - root.top)

    private fun render(onAction: (MessagesAction) -> Unit, consumeInsets: Boolean = true) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                RlTheme {
                    Box(Modifier.requiredSize(915.5.dp, 429.5.dp)
                        .then(if (consumeInsets) Modifier.consumeWindowInsets(WindowInsets.safeDrawing) else Modifier)
                        .testTag(VIEWPORT)) {
                        MessagesScreen(MessagesUiState(messages = messages, loading = false), onAction)
                    }
                }
            }
        }
    }

    companion object {
        private const val VIEWPORT = "messages-reference-viewport"
        private val messages = listOf(
            message("m1", "人工审核已通过", "9 月 2 日日记 · 第 2 章可以继续学习", false),
            message("m2", "学习内容已生成", "本机录音 · 共 4 章", true),
            message("m3", "人工审核未通过", "请根据审核说明修改中文内容", false),
            message("m4", "今日回顾已就绪", "3 个重点句等待回顾", false),
        )

        private fun message(id: String, summary: String, detail: String, read: Boolean) = AppMessage(
            id, if (id == "m1" || id == "m3") MessageKind.MANUAL_REVIEW_RESULT else MessageKind.SYSTEM,
            summary, detail, referenceTime(id), read,
            AppMessageTarget("review", id), MessageTargetState.AVAILABLE,
        )

        // Display-only fixture: match the original labels without changing the
        // formatter, freezing a production clock, or creating server messages.
        private fun referenceTime(id: String): Long {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val local = when (id) {
                "m1" -> today.atTime(14, 32)
                "m2" -> today.atTime(11, 8)
                "m3" -> today.minusDays(1).atTime(19, 46)
                else -> today.minusDays(1).atTime(8, 0)
            }
            return local.atZone(zone).toInstant().toEpochMilli()
        }
    }
}
