package com.reallanguage.app.development

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.designsystem.RlKeyboardEditingHost
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Offline, deterministic UI fixtures. No repository, microphone or user-data mutations. */
class UiV73LayoutAuditTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureScreensAndTextGeometry() {
        val args = InstrumentationRegistry.getArguments()
        val requested = args.getString("destinations")
        assumeTrue("Explicit visual audit only", requested != null)
        val destinations = requested!!.split(',').filter { it.isNotBlank() }
        val scrollToText = args.getString("scrollToText")
        val scale = args.getString("fontScale")?.toFloat() ?: 1f
        var current by mutableStateOf(destinations.first())
        rule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                RlTheme { RlKeyboardEditingHost {
                    key(current) {
                        if (!renderExtendedEditorialPreview(current, referenceCanvas = false))
                            DevelopmentPreviewScreen(initialDestinationName = current)
                    }
                } }
            }
        }
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "ui-v73-audit/${args.getString("auditName") ?: "current"}")
        check(directory.isDirectory || directory.mkdirs())
        for (destination in destinations) {
            File(directory, "current-screen.txt").writeText(destination)
            println("VISUAL_AUDIT: $destination")
            rule.runOnIdle { current = destination }
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(300)
            Thread.sleep(1600) // Let Android finish its immersive-window transition before capturing.
            rule.waitForIdle()
            val records = JSONArray()
            val nodes = rule.onAllNodes(SemanticsMatcher("text") {
                it.config.contains(SemanticsProperties.Text) || it.config.contains(SemanticsProperties.EditableText)
            }, useUnmergedTree = true).fetchSemanticsNodes()
            rule.runOnIdle {
                for (node in nodes) {
                    val texts = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }
                        ?: node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
                    if (texts.isBlank()) continue
                    val rect = node.boundsInRoot
                    val record = JSONObject().put("text", texts).put("id", node.id)
                        .put("tag", node.config.getOrNull(SemanticsProperties.TestTag).orEmpty())
                        .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
                        .put("measuredSize", JSONArray(listOf(node.size.width, node.size.height)))
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                    val lines = JSONArray()
                    layouts.forEach { layout ->
                        record.put("layoutSize", JSONArray(listOf(layout.size.width, layout.size.height)))
                        record.put("overflowWidth", layout.didOverflowWidth)
                            .put("overflowHeight", layout.didOverflowHeight)
                            .put("ellipsis", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
                        for (line in 0 until layout.lineCount) lines.put(JSONArray(listOf(
                            layout.getLineLeft(line), layout.getLineTop(line), layout.getLineRight(line), layout.getLineBottom(line))))
                    }
                    record.put("lines", lines)
                    records.put(record)
                }
            }
            File(directory, "$destination.json").writeText(JSONObject()
                .put("destination", destination).put("fontScale", scale)
                .put("width", rule.activity.resources.displayMetrics.widthPixels)
                .put("height", rule.activity.resources.displayMetrics.heightPixels)
                .put("fixtureOnly", true).put("textNodes", records).toString(2))
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (image != null) {
                File(directory, "$destination.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            if (!scrollToText.isNullOrBlank()) {
                rule.onNodeWithText(scrollToText).performScrollTo().assertIsDisplayed()
                rule.waitForIdle()
                val scrolledImage = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                if (scrolledImage != null) {
                    File(directory, "$destination-scrolled.png").outputStream().use {
                        scrolledImage.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    scrolledImage.recycle()
                }
            }
        }
    }
}
