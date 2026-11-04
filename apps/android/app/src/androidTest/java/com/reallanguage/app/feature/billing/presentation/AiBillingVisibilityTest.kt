package com.reallanguage.app.feature.billing.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.billing.domain.AiChargeRecord
import com.reallanguage.app.feature.billing.domain.AiChargeType
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual Screen, native font/density and physical touches. No billing, payment or network calls. */
class AiBillingVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var width = 1831
    private var height = 859
    private val notes = listOf("每次真实 AI 调用按实际成本的两倍扣费。", "未产生新调用的重复请求不会重复收费。")

    @Test fun referenceFourRowsDoNotPushTheBillingRulesAndReturnOutsideTheScreen() {
        val actions = render(reference())
        capture("reference-before")
        for (value in notes + listOf("返回账号", "日记整理", "安全检查", "场景生成", "场景生成重试")) {
            val node = compose.onNodeWithText(value, useUnmergedTree = true)
            val layout = layout(node, value)
            repeat(layout.lineCount) { assertTrue("Reference clipped: $value line $it", visible(node, layout, it)) }
        }
        reference().charges.forEach { charge ->
            val value = if (charge.callType == AiChargeType.INITIAL_CALL) "首次调用" else "手动重试"
            val node = compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag("ai-charge-${charge.chargeId}")), useUnmergedTree = true)
            val text = layout(node, value)
            repeat(text.lineCount) { assertTrue("Reference call type clipped for ${charge.chargeId}", visible(node, text, it)) }
        }
        compose.onNodeWithText("返回账号").performTouchInput { click() }
        assertEquals(listOf(AiBillingAction.ReturnAccountRequested), actions)
    }

    @Test fun referenceLedgerOccupiesTheOriginalPaperRegionRatherThanTheWholeScreen() {
        render(reference())
        val ledger = compose.onNodeWithTag("ai-billing-ledger").fetchSemanticsNode().boundsInRoot
        // Frozen 45: first data cell x~330, first row y~404; right column ends x~1515.
        // Glyph/style acceptance is separate from this root-container regression.
        assertTrue("Wrong horizontal ledger region: $ledger", ledger.left in 315f..350f && ledger.right in 1495f..1545f)
        assertTrue("Wrong vertical ledger region: $ledger", ledger.top in 390f..425f && ledger.bottom <= 692f)
    }

    @Test fun referenceBothBalancesStayWholeAndOnOneLine() {
        render(reference())
        val balances = compose.onAllNodesWithText("￥36.80", useUnmergedTree = true)
        balances.assertCountEquals(2)
        repeat(2) { index ->
            val value = layout(balances[index], "￥36.80")
            assertEquals("Do not split the fractional digits of the reference balance", 1, value.lineCount)
            assertTrue(visible(balances[index], value, 0))
        }
    }

    @Test fun shortLoadingExplainsWhatIsBeingReadAndKeepsReturnReachable() {
        val actions = render(AiBillingUiState(), short = true)
        val value = "正在读取余额与消费明细…"
        val node = compose.onNodeWithText(value, useUnmergedTree = true)
        val text = layout(node, value)
        repeat(text.lineCount) { reachLine(node, text, it) }
        compose.onNodeWithTag("ai-billing-back").performTouchInput { click() }
        assertEquals(listOf(AiBillingAction.ReturnAccountRequested), actions)
    }

    @Test fun shortFailureIsNotAnEmptyLedgerAndItsRetryAndReturnAreReachable() {
        val actions = render(AiBillingUiState(loading = false, loadFailed = true), short = true)
        val value = "余额与消费明细读取失败，请重试。"
        val node = compose.onNodeWithText(value, useUnmergedTree = true)
        val text = layout(node, value)
        repeat(text.lineCount) { reachLine(node, text, it) }
        val retry = compose.onNodeWithText("重试", useUnmergedTree = true)
        val retryLayout = layout(retry, "重试")
        repeat(retryLayout.lineCount) { reachLine(retry, retryLayout, it) }
        compose.onNodeWithText("重试").performTouchInput { click() }
        compose.onNodeWithTag("ai-billing-back").performTouchInput { click() }
        assertEquals(listOf(AiBillingAction.RetryRequested, AiBillingAction.ReturnAccountRequested), actions)
        compose.onNodeWithText("暂无 AI 消费明细").assertDoesNotExist()
    }

    @Test fun shortDoubleFontCanReadLongLedgerFieldsAndReachBothNotesAndReturn() {
        val records = (1..5).map { index -> AiChargeRecord("long-$index", 42, 1_788_396_800_000,
            "provider/precise-model-source-with-a-long-version-$index", AiChargeType.USER_RETRY,
            "第${index}次重新生成用户已经确认的场景内容并检查实际模型调用记录") }
        val actions = render(AiBillingUiState(3_680, records, loading = false), short = true)
        capture("short-initial")
        records.forEach { charge ->
            for (value in listOf(charge.operationLabelZh, charge.modelSource)) {
                val node = compose.onNodeWithText(value, useUnmergedTree = true)
                val layout = layout(node, value)
                repeat(layout.lineCount) { reachLine(node, layout, it) }
            }
        }
        for (value in notes) {
            val node = compose.onNodeWithText(value, useUnmergedTree = true)
            val layout = layout(node, value)
            repeat(layout.lineCount) { reachLine(node, layout, it) }
        }
        val back = compose.onNodeWithTag("ai-billing-back").assertIsDisplayed()
        back.performTouchInput { click() }
        assertEquals(listOf(AiBillingAction.ReturnAccountRequested), actions)
        capture("short-final")
    }

    private fun render(state: AiBillingUiState, short: Boolean = false): MutableList<AiBillingAction> {
        width = if (short) 1280 else 1831
        height = if (short) 720 else 859
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.window.decorView.width == width && compose.activity.window.decorView.height == height }
        assertEquals(320, compose.activity.resources.configuration.densityDpi)
        assertEquals(if (short) 2f else 1f, compose.activity.resources.configuration.fontScale, .001f)
        val actions = mutableListOf<AiBillingAction>()
        compose.setContent { RlTheme { RlPaperBackground { AiBillingScreen(state, actions::add) } } }
        compose.waitForIdle()
        return actions
    }

    private fun layout(node: SemanticsNodeInteraction, value: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single().also {
            assertEquals(value, it.layoutInput.text.text)
            assertFalse("No truncation of actual model or operation", it.multiParagraph.didExceedMaxLines)
            assertEquals(value.length, it.getLineEnd(it.lineCount - 1))
        }
    }

    private fun visible(node: SemanticsNodeInteraction, layout: TextLayoutResult, line: Int): Boolean {
        val node = node.fetchSemanticsNode()
        val coordinates = node.layoutInfo.coordinates
        var origin = coordinates.localToRoot(Offset.Zero)
        // Compose's reconstructed semantics paragraph can retain a wider centered
        // single-line cache than the actual rendered text. Normalize only this
        // established horizontal case; never adjust the y coordinate or clipping.
        val actualWidth = coordinates.size.width
        val paragraphWidth = layout.multiParagraph.width
        if (kotlin.math.abs(paragraphWidth - actualWidth) > 1f) {
            val offset = (paragraphWidth - actualWidth) / 2f
            val align = layout.layoutInput.style.textAlign
            val leftAligned = align == androidx.compose.ui.text.style.TextAlign.Left ||
                ((align == androidx.compose.ui.text.style.TextAlign.Start || align == androidx.compose.ui.text.style.TextAlign.Unspecified) &&
                    layout.layoutInput.layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr)
            assertTrue("Resolve semantic paragraph geometry: text=${layout.layoutInput.text} paragraphWidth=$paragraphWidth actualWidth=$actualWidth size=${layout.size} intrinsic=${layout.multiParagraph.intrinsics.maxIntrinsicWidth} lines=${layout.lineCount} align=$align left=${layout.getLineLeft(0)} right=${layout.getLineRight(0)}",
                layout.lineCount == 1 && layout.size.width == actualWidth && paragraphWidth > actualWidth &&
                    kotlin.math.ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt() == actualWidth)
            if (!(leftAligned && kotlin.math.abs(layout.getLineLeft(0)) <= 1f && layout.getLineRight(0) <= actualWidth + 1f)) {
                assertTrue("Only verified single-line centered semantic offset can be normalized",
                    align == androidx.compose.ui.text.style.TextAlign.Center &&
                        kotlin.math.abs(layout.getLineLeft(0) - offset) <= 1f &&
                        kotlin.math.abs(layout.getLineRight(0) - offset - actualWidth) <= 1f)
                origin -= Offset(offset, 0f)
            }
        }
        val window = Rect(0f, 0f, width.toFloat(), height.toFloat())
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, true).intersect(node.boundsInRoot).intersect(window)
        val value = layout.layoutInput.text.text
        val boxes = (layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true))
            .filter { !value[it].isWhitespace() }.map { layout.getBoundingBox(it).translate(origin) }
        val complete = boxes.isNotEmpty() && boxes.all { it.left >= clip.left - 1 && it.right <= clip.right + 1 && it.top >= clip.top - 1 && it.bottom <= clip.bottom + 1 }
        if (!complete) println("Billing glyph diagnostic value=$value line=$line origin=$origin clip=$clip layout=${layout.size} actual=${coordinates.size} boxes=$boxes")
        return complete
    }

    private fun reachLine(node: SemanticsNodeInteraction, layout: TextLayoutResult, line: Int) {
        var attempts = 0
        while (!visible(node, layout, line) && attempts++ < 65) {
            val ancestors = generateSequence(node.fetchSemanticsNode().parent) { it.parent }.toList()
            val scroll = ancestors.firstOrNull { SemanticsProperties.VerticalScrollAxisRange in it.config }
            assertNotNull("Clipped text needs its own production scroll region: ${layout.layoutInput.text}", scroll)
            val bounds = scroll!!.boundsInRoot.intersect(Rect(0f, 0f, width.toFloat(), height.toFloat()))
            val lineHeight = layout.getLineBottom(line) - layout.getLineTop(line)
            assertTrue("The local viewport must fit a complete actual text line: viewport=$bounds lineHeight=$lineHeight",
                bounds.height >= lineHeight - 1f)
            val bottom = node.fetchSemanticsNode().layoutInfo.coordinates.localToRoot(Offset.Zero).y + layout.getLineBottom(line)
            val upward = bottom > bounds.bottom
            val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
            if (kotlin.math.abs(range.value() - if (upward) range.maxValue() else 0f) < 1) break
            compose.onNode(SemanticsMatcher("exact scroll ancestor") { it.id == scroll.id }).performTouchInput {
                val start = if (upward) bounds.height - 5 else 5f
                swipe(Offset(bounds.width / 2, start), Offset(bounds.width / 2, start + if (upward) -minOf(100f, bounds.height * .4f) else minOf(100f, bounds.height * .4f)), 450)
            }
            compose.waitForIdle()
        }
        assertTrue("Line $line cannot be read after $attempts gestures: ${layout.layoutInput.text}", visible(node, layout, line))
    }

    private fun reference() = AiBillingUiState(3_680, listOf(
        AiChargeRecord("diary", 42, 1_788_396_800_000, "OpenAI", AiChargeType.INITIAL_CALL, "日记整理"),
        AiChargeRecord("safety", 8, 1_788_393_200_000, "OpenAI", AiChargeType.INITIAL_CALL, "安全检查"),
        AiChargeRecord("scene", 67, 1_788_310_400_000, "OpenAI", AiChargeType.INITIAL_CALL, "场景生成"),
        AiChargeRecord("retry", 31, 1_788_306_800_000, "OpenAI", AiChargeType.USER_RETRY, "场景生成重试"),
    ), loading = false)

    private fun capture(name: String) {
        val phase = InstrumentationRegistry.getArguments().getString("billingCapture") ?: return
        require(phase in setOf("before", "after"))
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(width, bitmap.width); assertEquals(height, bitmap.height)
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "45-billing-$name-$phase.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
