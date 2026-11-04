package com.reallanguage.app.feature.home.presentation

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.development.DevelopmentPreviewActivity
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Uses the existing running Activity/window, not only the extracted Content composable. */
class DailyReminderProductionWindowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun referenceActivityWindowDoesNotCropFooterAfterFocusSettles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, DevelopmentPreviewActivity::class.java)
            .putExtra("preview_destination", "DAILY_REMINDER_INTRO")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val scenario = androidx.test.core.app.ActivityScenario.launch<DevelopmentPreviewActivity>(intent)
        try {
            rule.waitUntil(5_000) {
                rule.onAllNodes(androidx.compose.ui.test.hasTestTag("daily-reminder-sheet")).fetchSemanticsNodes().isNotEmpty()
            }
            instrumentation.waitForIdleSync()
            // The screenshot reproduces the post-focus state seen in the phone-like preview.
            val settled = java.util.concurrent.CountDownLatch(1)
            scenario.onActivity { it.window.decorView.postDelayed({ settled.countDown() }, 700) }
            assertTrue(settled.await(3, java.util.concurrent.TimeUnit.SECONDS))
            rule.waitForIdle()
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(context.getExternalFilesDir(null), "55-real-window.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            rule.onNodeWithTag("daily-reminder-settings-assurance").performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithTag("daily-reminder-settings-assurance").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val footer = rule.onNodeWithTag("daily-reminder-settings-assurance").fetchSemanticsNode().boundsInRoot
            val root = rule.onNodeWithTag("daily-reminder-sheet").fetchSemanticsNode().boundsInRoot
            val panel = rule.onNodeWithTag("daily-reminder-main-panel").fetchSemanticsNode().boundsInRoot
            val windows = android.view.inspector.WindowInspector.getGlobalWindowViews().joinToString { view ->
                "${view.width}x${view.height}; insets=${view.rootWindowInsets}"
            }
            assertTrue("Footer glyphs must remain visible in actual Activity: layout=${layout.size} footer=$footer root=$root panel=$panel windows=$windows",
                !layout.didOverflowHeight && footer.height + 1 >= layout.getLineBottom(layout.lineCount - 1))
            // The physical emulator has a display cutout; keep its safe area.
            assertTrue("Only paper content scrolls; frame must stay below header", panel.top > root.top)
        } finally { scenario.close() }
    }
}
