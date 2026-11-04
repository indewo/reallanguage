package com.reallanguage.app.core.designsystem

import android.content.pm.ActivityInfo
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real shared component/painters only. Root sets 1829x860 / d320 / system font 1.
 * No Graph, database, network, account, screenshot replacement or user content.
 */
class RlPaperPanelIntrinsicHeightTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sizes = ConcurrentHashMap<String, IntSize>()
    private val density = AtomicReference<Density>()

    @Test fun unboundedScrollPaperHeightComesOnlyFromTheSameShortContentAndPadding() {
        mount {
            Row(Modifier.width(760.dp).height(300.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false, true).forEach { spine ->
                    val name = if (spine) "spine" else "plain"
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        shortPaper(name, spine, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        assertReady()
        val actualDensity = requireNotNull(density.get())
        val textHeight = sizes.getValue("plain-text").height
        assertEquals(textHeight, sizes.getValue("spine-text").height)
        // This is the component's real existing Column padding: contentPadding + Space8
        // at both edges. No guessed image aspect ratio or clipped semantics height.
        val expected = textHeight + 2 * with(actualDensity) { (RlSpace.Space8 + RlSpace.Space8).roundToPx() }
        assertEquals("Control without spine must be content-sized", expected, sizes.getValue("plain-paper").height)
        assertEquals("A decorative spine must not impose its painter's natural height on a scroll item",
            expected, sizes.getValue("spine-paper").height)
        assertEquals(sizes.getValue("plain-paper"), sizes.getValue("spine-paper"))
        compose.onNodeWithTag("plain-text").assertIsDisplayed()
        compose.onNodeWithTag("spine-text").assertIsDisplayed()
    }

    @Test fun boundedFillHeightPanelsKeepTheirExplicitViewportWithOrWithoutSpine() {
        mount {
            Row(Modifier.width(760.dp).height(180.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false, true).forEach { spine ->
                    val name = if (spine) "spine" else "plain"
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        shortPaper(name, spine, Modifier.fillMaxWidth().fillMaxHeight())
                    }
                }
            }
        }
        assertReady()
        val expected = with(requireNotNull(density.get())) { 180.dp.roundToPx() }
        assertEquals(expected, sizes.getValue("plain-paper").height)
        assertEquals(expected, sizes.getValue("spine-paper").height)
        assertEquals(sizes.getValue("plain-paper"), sizes.getValue("spine-paper"))
        assertTrue(sizes.getValue("plain-text").height < expected)
        compose.onNodeWithTag("plain-text").assertIsDisplayed()
        compose.onNodeWithTag("spine-text").assertIsDisplayed()
    }

    @Composable private fun shortPaper(name: String, spine: Boolean, modifier: Modifier) {
        RlPaperPanel(
            modifier = modifier.onSizeChanged { sizes["$name-paper"] = it }.testTag("$name-paper"),
            contentPadding = RlSpace.Space8,
            contentSpacing = RlSpace.Space4,
            showSpine = spine,
            showTopAccent = false,
        ) {
            Text("tea · 茶", style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.onSizeChanged { sizes["$name-text"] = it }.testTag("$name-text"))
        }
    }

    private fun mount(content: @Composable () -> Unit) {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.setContent {
            val actual = LocalDensity.current
            SideEffect { density.set(actual) }
            RlTheme {
                Box(Modifier.fillMaxSize().onSizeChanged { sizes["canvas"] = it }) { content() }
            }
        }
    }

    private fun assertReady() {
        compose.waitUntil(10_000) { sizes["canvas"] == IntSize(1829, 860) &&
            listOf("plain-paper", "spine-paper", "plain-text", "spine-text").all { sizes[it]?.height?.let { height -> height > 0 } == true } }
        val actual = requireNotNull(density.get())
        assertEquals("Fixture requires real d320", 2f, actual.density, .001f)
        assertEquals("Fixture requires real system font 1", 1f, actual.fontScale, .001f)
        Log.i("RealLanguagePaperMeasure", "PAPER_INTRINSIC canvas=${sizes.getValue("canvas")} plain=${sizes.getValue("plain-paper")} spine=${sizes.getValue("spine-paper")} text=${sizes.getValue("plain-text")} density=${actual.density} font=${actual.fontScale}")
    }
}
