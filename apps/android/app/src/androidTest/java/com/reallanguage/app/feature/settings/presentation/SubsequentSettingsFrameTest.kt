package com.reallanguage.app.feature.settings.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Raster-reference fixture: 1831x859, density 320, font 100%, zero reference Insets.
 * Insets are consumed only in this explicit art comparison, not in the production screen.
 * Root also captures the actual Activity with its physical cutout retained.
 */
class SubsequentSettingsFrameTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun useProductionLandscapeWindow() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
        composeRule.waitUntil(5_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test fun sidebarStartsAtCanvasOriginAndNavigationOccupiesTheOriginalPaperSlots() {
        renderBaseline()
        val root = referenceBounds()
        val image = composeRule.onNodeWithTag("settings-reference-viewport").captureToImage()
        assertTrue("Original blue lane starts at x0/y0; header must not push it down",
            fraction(image.toPixelMap(), Rect(190f, 8f, 365f, 130f), ::isBlue) > .90f)
        val navigation = composeRule.onNodeWithTag("subsequent-settings-navigation-0").fetchSemanticsNode().boundsInRoot
        assertBetween("First navigation paper x", navigation.left - root.left, 40f, 65f)
        assertBetween("First navigation paper y", navigation.top - root.top, 138f, 164f)
        assertBetween("First navigation paper width", navigation.width, 300f, 334f)
        assertBetween("First navigation paper height", navigation.height, 105f, 135f)
    }

    @Test fun titleAndFirstRealGroupUseTheOriginalRegionsInsteadOfTheGenericLedgerFlow() {
        renderBaseline()
        val root = referenceBounds()
        val title = composeRule.onNodeWithText("学习设置").fetchSemanticsNode().boundsInRoot
        assertBetween("Title center x", title.center.x - root.left, 950f, 992f)
        assertBetween("Title text layout top", title.top - root.top, 14f, 42f)
        val group = composeRule.onNodeWithText("①  表达基础").fetchSemanticsNode().boundsInRoot
        assertBetween("First group x", group.left - root.left, 480f, 512f)
        assertBetween("First group top", group.top - root.top, 192f, 224f)
    }

    @Test fun yellowNoteStaysInHeaderLayerAndNeverTakesSpaceInTheScrollableBody() {
        renderBaseline()
        val root = referenceBounds()
        val note = composeRule.onNodeWithTag("subsequent-settings-effect-note").assertIsDisplayed()
        val before = note.fetchSemanticsNode().boundsInRoot
        assertBetween("Note left", before.left - root.left, 1380f, 1420f)
        assertBetween("Note top", before.top - root.top, 12f, 40f)
        assertBetween("Note width", before.width, 255f, 294f)
        assertBetween("Note height", before.height, 142f, 178f)
        composeRule.onNode(hasTestTag("subsequent-settings-effect-note") and hasAnyAncestor(hasScrollAction()))
            .assertDoesNotExist()
        composeRule.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("subsequent-settings-group")))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 300f) }
        composeRule.waitForIdle()
        assertEquals("Only real form content scrolls, not the yellow note", before, note.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun unrelatedGenericRedStripAndDocumentsAreAbsentFromTheirActualPixelRegions() {
        renderBaseline()
        referenceBounds()
        val pixels = composeRule.onNodeWithTag("settings-reference-viewport").captureToImage().toPixelMap()
        assertTrue("Original header/paper join contains no extra terracotta strip",
            fraction(pixels, Rect(1570f, 182f, 1710f, 216f)) { color ->
                color.red > .4f && color.red > color.green * 1.35f && color.red > color.blue * 1.35f
            } < .01f)
        assertTrue("Original blue below navigation must not contain the generic process-document collage",
            fraction(pixels, Rect(195f, 605f, 320f, 644f), ::isBlue) > .85f)
    }

    @Test fun shortLandscapeAtDoubleFontKeepsRealContentScrollableAndReturnAvailable() {
        val actions = mutableListOf<SubsequentSettingsAction>()
        renderShortLandscape(actions = actions)
        val viewport = composeRule.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("subsequent-settings-group")))
        assertTrue("A 200% font user must still have space to read an entire content line",
            viewport.fetchSemanticsNode().boundsInRoot.height >= 76f)
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        composeRule.waitForIdle()
        assertWholeText(composeRule.onNodeWithText("默认使用美式英语声音"))
        composeRule.onNodeWithTag("subsequent-settings-return").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf(SubsequentSettingsAction.ReturnRequested), actions)
    }

    @Test fun shortLandscapeAtDoubleFontShowsTheEntireConflictAndKeepsItsSafeExit() {
        val actions = mutableListOf<SubsequentSettingsAction>()
        renderShortLandscape(SubsequentSettingsError.VERSION_CONFLICT, actions)
        assertWholeText(composeRule.onNodeWithTag("settings-save-status"))
        composeRule.onNodeWithTag("subsequent-settings-save").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithTag("subsequent-settings-return").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf(SubsequentSettingsAction.ReturnRequested), actions)
    }

    private fun renderShortLandscape(
        error: SubsequentSettingsError? = null,
        actions: MutableList<SubsequentSettingsAction>,
    ) {
        val draft = InitialSettingsDraft(formalityLevel = 3, speechSpeedLevel = 3)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
                RlTheme {
                    // Real safeDrawing remains unconsumed in this adaptation fixture.
                    Box(Modifier.requiredSize(640.dp, 360.dp).testTag("settings-short-viewport")) {
                        SubsequentSettingsScreen(SubsequentSettingsUiState(
                            original = draft, draft = draft.copy(speechSpeedLevel = 4), loading = false, error = error),
                            { actions += it })
                    }
                }
            }
        }
        val bounds = composeRule.onNodeWithTag("settings-short-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(1280f, bounds.width, 1f)
        assertEquals(720f, bounds.height, 1f)
    }

    private fun assertWholeText(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertTrue("Text must not be truncated or masked: bounds=$bounds; " + layouts.joinToString { "${it.size}/overflow=${it.hasVisualOverflow}" },
            layouts.isNotEmpty() && layouts.all { layout ->
                !layout.didOverflowHeight && layout.size.height <= bounds.height + 1f &&
                    (0 until layout.lineCount).all { line -> !layout.isLineEllipsized(line) &&
                        layout.getLineLeft(line) >= -.5f && layout.getLineRight(line) <= layout.size.width + .5f }
            })
    }

    private fun renderBaseline() {
        val draft = InitialSettingsDraft(formalityLevel = 3, slangEnabled = false,
            basicVocabulary = BasicVocabulary.CET_6, voiceEnabled = true, autoPlayEnabled = false,
            speechSpeedLevel = 3)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                RlTheme {
                    Box(Modifier.requiredSize(915.5.dp, 429.5.dp)
                        .consumeWindowInsets(WindowInsets.safeDrawing).testTag("settings-reference-viewport")) {
                        SubsequentSettingsScreen(SubsequentSettingsUiState(draft, draft, loading = false), {})
                    }
                }
            }
        }
    }

    private fun referenceBounds(): Rect {
        val bounds = composeRule.onNodeWithTag("settings-reference-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals("The reference fixture must really occupy the selected raster width", 1831f, bounds.width, 1f)
        assertEquals("The reference fixture must really occupy the selected raster height", 859f, bounds.height, 1f)
        return bounds
    }

    private fun assertBetween(label: String, value: Float, minimum: Float, maximum: Float) {
        assertTrue("$label = $value; measured reference region is $minimum..$maximum", value in minimum..maximum)
    }

    private fun isBlue(color: Color): Boolean = color.blue > color.red * 1.12f && color.blue > color.green && color.red < .5f

    private fun fraction(pixels: androidx.compose.ui.graphics.PixelMap, region: Rect, matches: (Color) -> Boolean): Float {
        var count = 0; var total = 0
        for (y in region.top.toInt() until region.bottom.toInt()) {
            for (x in region.left.toInt() until region.right.toInt()) {
                if (matches(pixels[x, y])) count++
                total++
            }
        }
        assertTrue("Reference pixel region must contain actual pixels", total > 0)
        return count.toFloat() / total
    }
}
