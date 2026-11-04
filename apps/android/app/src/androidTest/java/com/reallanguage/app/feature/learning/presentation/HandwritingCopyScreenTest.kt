package com.reallanguage.app.feature.learning.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class HandwritingCopyScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun focusedLearningWindow() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
    }

    @Test
    fun showsSeparatedWritingWorkspaceAndDispatchesSelfConfirmation() {
        val actions = mutableListOf<HandwritingCopyAction>()
        val state = HandwritingCopyUiState(
            chapter = FixedLearningChapter(
                "c1", 1,
                listOf(
                    FixedLearningSentence(
                        "s1", "I expressed myself clearly.", "我清楚地表达了自己。", true,
                    ),
                ),
            ),
            currentFocusSentenceId = "s1",
        )
        composeRule.setContent { RlTheme { RlPaperBackground { HandwritingCopyScreen(state, actions::add) } } }
        composeRule.onNodeWithTag("handwriting-workspace").assertIsDisplayed()
        composeRule.onNodeWithTag("handwriting-canvas").assertIsDisplayed()
        composeRule.onNodeWithText("第 1 次 / 共 2 次").assertIsDisplayed()
        val statusLayout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNodeWithTag("handwriting-attempt-status").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult,
        ) { check(it(statusLayout)) }
        check(statusLayout.single().lineCount == 1) { "The copy count must stay on one compact line" }
        val instruction = composeRule.onNodeWithTag("handwriting-instruction-note").fetchSemanticsNode().boundsInRoot
        val status = composeRule.onNodeWithTag("handwriting-attempt-status-container").fetchSemanticsNode().boundsInRoot
        check(status.top > instruction.bottom) {
            "The compact copy count must sit below the instruction note: instruction=$instruction status=$status"
        }

        val paper = composeRule.onNodeWithTag("handwriting-paper").fetchSemanticsNode().boundsInRoot
        val drawingRegion = composeRule.onNodeWithTag("handwriting-drawing-region").fetchSemanticsNode().boundsInRoot
        val canvas = composeRule.onNodeWithTag("handwriting-canvas").fetchSemanticsNode().boundsInRoot
        check(drawingRegion.left > paper.left && drawingRegion.top > paper.top &&
            drawingRegion.right < paper.right && drawingRegion.bottom < paper.bottom) {
            "Handwriting must have a safe inset inside the white paper: paper=$paper region=$drawingRegion"
        }
        check(canvas.left >= drawingRegion.left && canvas.top >= drawingRegion.top &&
            canvas.right <= drawingRegion.right && canvas.bottom <= drawingRegion.bottom) {
            "The interactive canvas must be contained by the clipped drawing region"
        }
        captureFixtureIfRequested("layout")
        composeRule.onNodeWithTag("handwriting-canvas").performTouchInput {
            down(center)
            moveTo(Offset(-120f, -120f))
            moveTo(Offset(canvas.width + 120f, canvas.height + 120f))
            up()
        }
        captureFixtureIfRequested("edge-stroke")
        composeRule.onNodeWithText("手写只保留在当前页面，不上传、不识别、不判错。").assertIsDisplayed()
        composeRule.onNodeWithText("我已写完").assertIsDisplayed().performClick()
        assert(actions == listOf(HandwritingCopyAction.WrittenFinished))
    }

    private fun captureFixtureIfRequested(suffix: String) {
        val requested = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("handwritingSnapshot") ?: return
        require(requested == "after")
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        try {
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val folder = java.io.File(context.filesDir, "visual-qa")
            check(folder.isDirectory || folder.mkdirs())
            val output = java.io.File(folder, "handwriting-copy-after-$suffix.png")
            output.outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
