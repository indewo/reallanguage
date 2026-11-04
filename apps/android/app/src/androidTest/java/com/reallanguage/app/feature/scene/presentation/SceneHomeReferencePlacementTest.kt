package com.reallanguage.app.feature.scene.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.scene.domain.PublicSceneTemplates
import com.reallanguage.app.feature.scene.domain.RecentSceneRecommendation
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Original 67's left-hand direct-description entry, at the real 1831x859/d320/font1 viewport.
 * This only protects column ownership/non-overlap. Existing four glyph/touch tests remain separate.
 * Actual eight product templates and two isolated recommendations; no VM, SQL, HTTP, audio or AI.
 */
class SceneHomeReferencePlacementTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun directDescriptionAndContinueBelongToTheArtworkColumnWithoutCoveringPublicTemplates() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.window.decorView.width == 1831 && compose.activity.window.decorView.height == 859
        }
        assertEquals(320, compose.activity.resources.configuration.densityDpi)
        assertEquals(1f, compose.activity.resources.configuration.fontScale, .001f)
        val state = SceneHomeUiState(
            loadingRecommendations = false,
            recommendations = listOf(
                RecentSceneRecommendation("recent-app", "英语 App 宣传", "近期内容", listOf("isolated-source")),
                RecentSceneRecommendation("recent-change", "成长变化", "近期内容", listOf("isolated-source-2")),
            ),
            directDescription = "向同事解释项目延期",
        )
        val actions = mutableListOf<SceneHomeAction>()
        compose.setContent { RlTheme { SceneHomeScreen(state, actions::add) } }
        compose.waitForIdle()
        capture("two-recommendations")

        val artwork = rawBounds(compose.onNodeWithTag("scene-home-artwork").fetchSemanticsNode())
        val input = measuredVisibleBounds(compose.onNodeWithTag("scene-home-direct").fetchSemanticsNode())
        val continueButton = measuredVisibleBounds(compose.onNodeWithTag("scene-home-continue").fetchSemanticsNode())
        val templates = PublicSceneTemplates.map { template ->
            val text = compose.onNodeWithText(template.titleZh, useUnmergedTree = true).fetchSemanticsNode()
            rawBounds(generateSequence(text) { it.parent }.first { SemanticsActions.OnClick in it.config })
        }
        assertEquals("Do not replace the actual product categories to fit the illustration", 8, templates.size)
        val diagnostic = "artwork=$artwork input=$input continue=$continueButton templates=$templates"
        assertTrue("Direct description must occupy the same left column as the artwork: $diagnostic",
            input.left >= artwork.left - 1 && input.right <= artwork.right + 1)
        assertTrue("Continue belongs to the direct-description entry, not the right template column: $diagnostic",
            continueButton.left >= artwork.left - 1 && continueButton.right <= artwork.right + 1)
        assertTrue("Direct entry remains below the artwork and the arrow belongs beside the field: $diagnostic",
            input.top >= artwork.bottom - 1 && continueButton.left >= input.right - 1 &&
                kotlin.math.abs(continueButton.center.y - input.center.y) <= 2f)
        assertTrue("Every actual template stays on the right, outside both direct controls: $diagnostic",
            templates.all { it.left >= artwork.right - 1 && it.left >= input.right - 1 && it.left >= continueButton.right - 1 })
        assertTrue("Rendering cannot select a starting point", actions.isEmpty())
    }

    @Test fun oneRecentRecommendationKeepsOneOfFourTicketSlotsInsteadOfStretching() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.window.decorView.width == 1831 && compose.activity.window.decorView.height == 859
        }
        val state = SceneHomeUiState(
            loadingRecommendations = false,
            recommendations = listOf(
                RecentSceneRecommendation("recent-one", "确认通话是否正常", "近期内容", listOf("isolated-source")),
            ),
        )
        compose.setContent { RlTheme { SceneHomeScreen(state, {}) } }
        compose.waitForIdle()
        capture("one-recommendation")

        val grid = rawBounds(compose.onNodeWithTag("scene-home-recommendation-grid").fetchSemanticsNode())
        val ticket = rawBounds(compose.onNodeWithTag("scene-home-recommendation-0").fetchSemanticsNode())
        assertTrue("One recommendation must remain one quarter-width ticket: grid=$grid ticket=$ticket",
            ticket.width <= grid.width * .27f && ticket.width >= grid.width * .20f)
        assertTrue("Recommendation ticket must keep the approved portrait-ticket proportion: $ticket",
            ticket.width / ticket.height in 1.20f..1.58f)
    }

    @Test fun fourRecommendationsMatchTheApprovedComparisonState() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.window.decorView.width == 1831 && compose.activity.window.decorView.height == 859
        }
        val state = SceneHomeUiState(
            loadingRecommendations = false,
            recommendations = listOf(
                RecentSceneRecommendation("recent-review", "面试结束后的复盘", "近期内容", listOf("source-1")),
                RecentSceneRecommendation("recent-meeting", "和新同事第一次见面", "近期内容", listOf("source-2")),
                RecentSceneRecommendation("recent-restaurant", "餐厅提出特殊需求", "近期内容", listOf("source-3")),
                RecentSceneRecommendation("recent-airport", "机场行李出现问题", "近期内容", listOf("source-4")),
            ),
        )
        compose.setContent { RlTheme { SceneHomeScreen(state, {}) } }
        compose.waitForIdle()
        capture("four-recommendations")

        val grid = rawBounds(compose.onNodeWithTag("scene-home-recommendation-grid").fetchSemanticsNode())
        val tickets = (0..3).map { rawBounds(compose.onNodeWithTag("scene-home-recommendation-$it").fetchSemanticsNode()) }
        assertTrue("Every recommendation must preserve the same quarter-width slot: grid=$grid tickets=$tickets",
            tickets.all { it.width <= grid.width * .27f && it.width >= grid.width * .20f })
        val widths = tickets.map { it.width }
        assertTrue("The four recommendation slots must be equal aside from pixel rounding: $tickets",
            requireNotNull(widths.maxOrNull()) - requireNotNull(widths.minOrNull()) <= 1f)
    }

    private fun rawBounds(node: SemanticsNode): Rect {
        val coordinates = node.layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        return Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
    }

    private fun measuredVisibleBounds(node: SemanticsNode): Rect {
        val raw = rawBounds(node)
        val coordinates = node.layoutInfo.coordinates
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, true)
            .intersect(node.boundsInRoot).intersect(Rect(0f, 0f, 1831f, 859f))
        assertTrue("A clipped/zero-size control cannot stand in for the reference entry: raw=$raw clip=$clip",
            raw.width > 0 && raw.height > 0 && raw.left >= clip.left - 1 && raw.right <= clip.right + 1 &&
                raw.top >= clip.top - 1 && raw.bottom <= clip.bottom + 1)
        return raw
    }

    private fun capture(label: String) {
        val phase = InstrumentationRegistry.getArguments().getString("sceneHomePlacementCapture") ?: return
        require(phase in setOf("before", "after"))
        val decor = compose.activity.window.decorView
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val latch = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { latch.countDown() }
        instrumentation.runOnMainSync { decor.viewTreeObserver.addOnDrawListener(listener); decor.invalidate() }
        try { assertTrue("Actual window must draw before capture", latch.await(3, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener) } }
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(1831, image.width); assertEquals(859, image.height)
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "67-scene-home-$label-$phase.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }
}
