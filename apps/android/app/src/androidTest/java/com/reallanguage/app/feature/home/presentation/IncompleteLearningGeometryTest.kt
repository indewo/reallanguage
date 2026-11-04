package com.reallanguage.app.feature.home.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.home.domain.IncompleteLearningEntry
import com.reallanguage.app.feature.home.domain.OrdinarySourceType
import com.reallanguage.app.feature.home.domain.ResumeLearningKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.min

/** Source: immutable 56-incomplete-learning.png, 1831×859. These regional checks are not pixel parity. */
class IncompleteLearningGeometryTest {
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

    @Test fun referenceTitleAndListUseTheOriginalRegionsInsteadOfTheGenericHeaderStack() {
        render()
        val root = rootBounds()
        val title = composeRule.onNodeWithText("未完成学习", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Reference title x≈196, actual $title", title.left - root.left in 185f..210f)
        assertTrue("Reference title y≈44, actual $title", title.top - root.top in 30f..62f)
        val list = composeRule.onNodeWithTag("incomplete-learning-list").fetchSemanticsNode().boundsInRoot
        assertTrue("Reference list begins x≈664, actual $list", list.left - root.left in 650f..690f)
        assertTrue("Reference list begins y≈126, actual $list", list.top - root.top in 114f..154f)
        assertTrue("Reference list extends near x1780, actual $list", list.right - root.left in 1760f..1802f)
        assertTrue("List must leave room for Return rather than cover it", list.bottom <=
            composeRule.onNodeWithTag("incomplete-learning-return-home").fetchSemanticsNode().boundsInRoot.top + 1f)
    }

    @Test fun referenceBlueInkOccupiesTheBookRegionAndTheLiveStatisticsStayOnItsBluePaper() {
        render()
        val root = rootBounds()
        val image = composeRule.onNodeWithTag("incomplete-learning-book-image", useUnmergedTree = true)
        val imageNode = image.fetchSemanticsNode()
        val imageBounds = imageNode.boundsInRoot
        val pixels = image.captureToImage().toPixelMap()
        val bitmap = BitmapFactory.decodeResource(composeRule.activity.resources, R.drawable.rl_incomplete_learning_book_collage_v2)
        val scale = min(imageNode.size.width.toFloat() / bitmap.width, imageNode.size.height.toFloat() / bitmap.height)
        val originX = imageNode.positionInRoot.x + (imageNode.size.width - bitmap.width * scale) / 2f
        val originY = imageNode.positionInRoot.y + (imageNode.size.height - bitmap.height * scale) / 2f
        var left = pixels.width
        var top = pixels.height
        var right = -1
        var bottom = -1
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            // captureToImage is the composed window crop, not an isolated layer. In the
            // bitmap's transparent right margin it also captures the blue Return border.
            // Ignore only those transparent source pixels, without loosening the ink color
            // or reference bounds; the original wrong book position still fails this check.
            val sourceX = ((imageBounds.left + x - originX) / scale).toInt()
            val sourceY = ((imageBounds.top + y - originY) / scale).toInt()
            if (sourceX !in 0 until bitmap.width || sourceY !in 0 until bitmap.height ||
                android.graphics.Color.alpha(bitmap.getPixel(sourceX, sourceY)) < 128) continue
            val color = pixels[x, y]
            if (bluePaper(color.red * 255f, color.green * 255f, color.blue * 255f)) {
                left = minOf(left, x); top = minOf(top, y)
                right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        bitmap.recycle()
        assertTrue("The screenshot must actually contain the existing blue book", right >= left && bottom >= top)
        val ink = Rect(imageBounds.left - root.left + left, imageBounds.top - root.top + top,
            imageBounds.left - root.left + right + 1, imageBounds.top - root.top + bottom + 1)
        // Raster blue bbox≈84,176..590,771. The immutable separate asset is not identical;
        // these tolerances verify its regional placement, not its missing texture/stamp fidelity.
        assertTrue("Blue book must start near the original x84/y176: $ink", ink.left in 70f..105f && ink.top in 166f..191f)
        assertTrue("Blue book must retain the original large scale: $ink", ink.right in 573f..615f && ink.bottom in 750f..789f)
        listOf("继续你的表达", "3", "个进行中").forEach(::assertVisibleTextOnActualBluePaper)
        composeRule.onNodeWithTag("incomplete-book-scroll-indicator").assertDoesNotExist()
    }

    @Test fun shortDoubleFontCanReadAllStatisticsWithoutPaintingOnTheRibbonOrOutsideTheBook() {
        render(width = 640.dp, height = 360.dp, fontScale = 2f, referenceInsets = false)
        listOf("继续你的表达", "3", "个进行中").forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertTrue("Statistics must not be shortened to fit the artwork [$text]: " +
                layouts.joinToString { "size=${it.size}, overflowW=${it.didOverflowWidth}, overflowH=${it.didOverflowHeight}, " +
                    "lines=${it.lineCount}, constraints=${it.layoutInput.constraints}" } +
                ", image=${composeRule.onNodeWithTag("incomplete-learning-book-image", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot}",
                layouts.isNotEmpty() && layouts.all {
                !it.hasVisualOverflow && it.getLineEnd(it.lineCount - 1) == text.length
            })
            composeRule.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            assertVisibleTextOnActualBluePaper(text)
        }
        composeRule.onNodeWithTag("incomplete-learning-return-home").assertIsDisplayed()
    }

    @Test fun overflowingBookStatisticsAdvertiseAndRespondToRealVerticalTouchScrolling() {
        render(width = 640.dp, height = 360.dp, fontScale = 2f, referenceInsets = false)
        composeRule.onNodeWithTag("incomplete-book-scroll-indicator").assertIsDisplayed()
        val statistics = composeRule.onNodeWithTag("incomplete-book-statistics")
        val before = statistics.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        repeat(8) { statistics.performTouchInput { swipeUp() } }
        val range = statistics.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("A finger swipe inside the visible blue book must actually reveal later statistics", range.value() > before)
        assertTrue("A bounded series of swipes must reach the bottom, not an endlessly clipped image", range.value() >= range.maxValue() - 1f)
        composeRule.onNodeWithText("个进行中", useUnmergedTree = true).assertIsDisplayed()
        assertVisibleTextOnActualBluePaper("个进行中")
        composeRule.onNodeWithTag("incomplete-learning-return-home").assertIsDisplayed()
    }

    private fun assertVisibleTextOnActualBluePaper(text: String) {
        val image = composeRule.onNodeWithTag("incomplete-learning-book-image", useUnmergedTree = true).fetchSemanticsNode()
        val textBounds = composeRule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val bitmap = BitmapFactory.decodeResource(composeRule.activity.resources, R.drawable.rl_incomplete_learning_book_collage_v2)
        try {
            // The production Image uses Fit with this unchanged bitmap. Test the untouched source
            // beneath the live text, not the white glyph pixels over it in the composed screenshot.
            val scale = min(image.size.width.toFloat() / bitmap.width, image.size.height.toFloat() / bitmap.height)
            val originX = image.positionInRoot.x + (image.size.width - bitmap.width * scale) / 2f
            val originY = image.positionInRoot.y + (image.size.height - bitmap.height * scale) / 2f
            for (row in 0..4) for (column in 0..4) {
                val x = ((textBounds.left + (column + .5f) * textBounds.width / 5f - originX) / scale).toInt()
                val y = ((textBounds.top + (row + .5f) * textBounds.height / 5f - originY) / scale).toInt()
                assertTrue("[$text] must remain inside the actual image, bounds=$textBounds, source point=$x,$y",
                    x in 0 until bitmap.width && y in 0 until bitmap.height)
                val color = bitmap.getPixel(x, y)
                assertTrue("[$text] must sit on blue paper, not the tan ribbon/background, source point=$x,$y",
                    android.graphics.Color.alpha(color) >= 128 && bluePaper(android.graphics.Color.red(color).toFloat(),
                        android.graphics.Color.green(color).toFloat(), android.graphics.Color.blue(color).toFloat()))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun bluePaper(red: Float, green: Float, blue: Float) = blue - red > 12f && green - red > 5f && blue < 175f && red < 140f

    private fun rootBounds(): Rect = composeRule.onNodeWithTag("incomplete-geometry-viewport").fetchSemanticsNode().boundsInRoot.also {
        assertEquals(1831f, it.width, 1f)
        assertEquals(859f, it.height, 1f)
    }

    private fun render(width: Dp = 915.5.dp, height: Dp = 429.5.dp, fontScale: Float = 1f, referenceInsets: Boolean = true) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                RlTheme {
                    Box(Modifier.requiredSize(width, height)
                        .then(if (referenceInsets) Modifier.consumeWindowInsets(WindowInsets.safeDrawing) else Modifier)
                        .testTag("incomplete-geometry-viewport")) {
                        IncompleteLearningScreen(IncompleteLearningUiState(entries = entries, loading = false)) {}
                    }
                }
            }
        }
    }

    companion object {
        private val entries = listOf(
            IncompleteLearningEntry("learning-1", OrdinarySourceType.DIARY, "9月2日日记", 2, "LR-06", "整句会说", ResumeLearningKind.ORDINARY),
            IncompleteLearningEntry("learning-2", OrdinarySourceType.QUICK_RECORDING, "本机录音 · 01:24", 1, "LR-04", "手写抄写", ResumeLearningKind.ORDINARY),
            IncompleteLearningEntry("learning-3", OrdinarySourceType.AI_SCENE, "AI场景 · 面试前", 3, "LR-08", "完整书写", ResumeLearningKind.ORDINARY),
        )
    }
}
