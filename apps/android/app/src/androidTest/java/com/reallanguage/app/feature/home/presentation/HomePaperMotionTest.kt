package com.reallanguage.app.feature.home.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production home controls at the requested exact export canvas; no fake AI or user writes. */
class HomePaperMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("home-reference").captureToImage().asAndroidBitmap()
        assertEquals(1896, bitmap.width)
        assertEquals(830, bitmap.height)
        java.io.File(compose.activity.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun exportsExactCanvasAndDragsThroughBothInkPhasesWithoutOpeningAnEntry() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        val actions = mutableListOf<HomeAction>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f, 1f)) {
                RlTheme {
                    Box(Modifier.requiredSize(632.dp, (830f / 3f).dp)
                        .consumeWindowInsets(WindowInsets.safeDrawing).testTag("home-reference")) {
                        HomeScreen(HomeUiState(), actions::add)
                    }
                }
            }
        }
        capture("home-reference-1896x830.png")
        val deck = compose.onNodeWithTag("home-deck")
        val step = deck.fetchSemanticsNode().boundsInRoot.width * .29f
        val slop = ViewConfiguration.get(compose.activity).scaledTouchSlop
        deck.performTouchInput { down(center); moveBy(Offset(-step * .25f - slop, 0f)) }
        compose.onNodeWithTag("home-quick-recording").assertIsSelected()
        capture("home-motion-25.png")
        deck.performTouchInput { moveBy(Offset(-step * .25f, 0f)) }
        capture("home-motion-50.png")
        deck.performTouchInput { moveBy(Offset(-step * .25f, 0f)) }
        compose.onNodeWithTag("home-screenshot-card").assertIsSelected()
        capture("home-motion-75.png")
        deck.performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithTag("home-screenshot-card").assertIsSelected()
        capture("home-motion-100.png")
        assertTrue("Dragging must not activate a learning/capture entry", actions.isEmpty())
    }
}
