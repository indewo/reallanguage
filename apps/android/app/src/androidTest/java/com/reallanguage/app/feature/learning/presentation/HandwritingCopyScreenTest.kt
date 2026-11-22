package com.reallanguage.app.feature.learning.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import com.reallanguage.app.feature.learning.domain.HandwritingCompletionRepository
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
        composeRule.waitUntil(5_000) {
            composeRule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
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
        assertOriginalLearningHoleRatio(composeRule.onNodeWithTag("learning-round-hole-strip"))
        composeRule.onNodeWithTag("handwriting-workspace").assertIsDisplayed()
        composeRule.onNodeWithTag("handwriting-canvas").assertIsDisplayed()
        composeRule.onNodeWithText("第1次\n至少2次").assertIsDisplayed()
        val statusLayout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNodeWithTag("handwriting-attempt-status").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult,
        ) { check(it(statusLayout)) }
        captureFixtureIfRequested("count-layout")
        check(statusLayout.single().lineCount == 2 && !statusLayout.single().hasVisualOverflow) { "Attempt label: lines=${statusLayout.single().lineCount}, size=${statusLayout.single().size}, paragraph=${statusLayout.single().multiParagraph.width}x${statusLayout.single().multiParagraph.height}, widthOverflow=${statusLayout.single().didOverflowWidth}, heightOverflow=${statusLayout.single().didOverflowHeight}" }
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
        composeRule.onNodeWithTag("handwriting-clear").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("handwriting-clear").assertIsNotEnabled()
        composeRule.onNodeWithText("我已写完").assertIsDisplayed().performClick()
        assert(actions == listOf(HandwritingCopyAction.WrittenFinished))
    }

    @Test
    fun longSentenceCopyAgainShowsSecondAndThirdAttemptsAndStartsWithCleanCanvas() {
        val longSentence = "Please share the timeline for the next phase, including key milestones and deliverables."
        val chapter = FixedLearningChapter("layout-only", 1, listOf(
            FixedLearningSentence("s1", longSentence, "请分享下一阶段的时间表，包括关键里程碑和交付物。", true),
        ))
        composeRule.setContent {
            val model = remember {
                HandwritingCopyViewModel(chapter, "s1", HandwritingCompletionRepository { _, _ ->
                    error("This layout test must not save or open the next learning step")
                })
            }
            val state = model.uiState.collectAsState().value
            RlTheme { RlPaperBackground { HandwritingCopyScreen(state, model::onAction) } }
        }
        composeRule.onNodeWithText(longSentence).assertIsDisplayed()
        composeRule.onNodeWithText("第1次\n至少2次").assertIsDisplayed()
        composeRule.onNodeWithTag("handwriting-clear").assertIsNotEnabled()
        composeRule.onNodeWithText("我已写完").performClick()
        composeRule.onNodeWithText("第2次\n至少2次").assertIsDisplayed()
        composeRule.onNodeWithTag("handwriting-clear").assertIsNotEnabled()
        composeRule.onNodeWithText("我已写完").performClick()
        composeRule.onNodeWithText("已完成2次\n至少2次").assertIsDisplayed()
        composeRule.onNodeWithText("再抄一遍").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("第3次\n至少2次").assertIsDisplayed()
        composeRule.onNodeWithTag("handwriting-clear").assertIsNotEnabled()
        composeRule.onNodeWithText("我已写完").assertIsDisplayed()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()!!.let { bitmap ->
            java.io.File(instrumentation.targetContext.filesDir, "handwriting-long-third-attempt.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
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
