package com.reallanguage.app.feature.learning.presentation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual Compose rendering of explicitly saved-state fixtures, never AI or original-audio proof. */
class CompletedSceneReadOnlyScreenTest {
    @get:Rule val compose = createComposeRule()
    private val target = "I felt calm."
    private val meaning = "我感到平静。"
    private val tasks = listOf(
        FixedSceneResponseTask("first-task", "first", "After a team meeting.",
            listOf(SceneDialogueLine("AI", "How did you feel?")), target),
        FixedSceneResponseTask("second-task", "second", "After arriving home.",
            listOf(SceneDialogueLine("AI", "How did you feel at home?")), target),
    )
    private fun snapshot() = CompletedStepSnapshot("LR-09", "第 1 章 · 场景回应", target, meaning, null,
        contextSentences = listOf(FixedLearningSentence("first", target, meaning, true),
            FixedLearningSentence("second", target, "我当时很平静。", true)),
        activeSentenceId = "first", chapterId = "chapter", completedSceneTasks = tasks)

    @Test fun everySavedSceneRemainsSeparatelyReadableAndReturnDoesNotSubmit() {
        val actions = mutableListOf<CompletedStepReviewAction>()
        render(CompletedStepReviewUiState(snapshot()), onAction = actions::add)
        tasks.forEach { task ->
            compose.onNodeWithTag("completed-scene-task-${task.sentenceId}").assertExists()
            compose.onNodeWithText(requireNotNull(task.backgroundEn)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("AI: ${task.contextLines.single().textEn}").performScrollTo().assertIsDisplayed()
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithTag("scene-response-workspace").assertDoesNotExist()
        compose.onNodeWithTag("completed-step-return").assertIsDisplayed().performClick()
        assertEquals(listOf(CompletedStepReviewAction.ReturnToCurrentTaskRequested), actions)
    }

    @Test fun loadingFailedOrUnvalidatedSceneNeverDisplaysRouteAnswersOrEnablesPlayback() {
        val state = mutableStateOf(CompletedStepReviewUiState(snapshot(), loading = true))
        val actions = mutableListOf<CompletedStepReviewAction>()
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
            RlTheme { Box(Modifier.requiredSize(914.5.dp, 430.dp)) { CompletedStepReviewScreen(state.value, actions::add) } }
        } }
        listOf(state.value, state.value.copy(loading = false, loadingFailed = true),
            state.value.copy(loading = false, snapshot = snapshot().copy(completedSceneTasks = emptyList()))).forEach { unavailable ->
            compose.runOnIdle { state.value = unavailable }
            compose.onAllNodesWithText(target).assertCountEquals(0)
            compose.onAllNodesWithText(meaning).assertCountEquals(0)
            compose.onAllNodesWithText("AI: How did you feel?").assertCountEquals(0)
            compose.onAllNodesWithText("发音").fetchSemanticsNodes().indices.forEach { index ->
                compose.onAllNodesWithText("发音")[index].assertIsNotEnabled()
            }
            compose.onNodeWithTag("completed-step-return").assertIsDisplayed()
            if (unavailable.loadingFailed) {
                compose.onNodeWithText("暂时无法读取已保存的场景，请重试。学习结果未改变。").assertIsDisplayed()
                compose.onNodeWithText("重试").performClick()
            }
        }
        compose.onNodeWithTag("completed-step-return").performClick()
        assertEquals(listOf(CompletedStepReviewAction.RetryLoadRequested,
            CompletedStepReviewAction.ReturnToCurrentTaskRequested), actions)
    }

    @Test fun doubleFontLongDialogueCanReachItsWholeLastLineWithReturnAlwaysAvailable() {
        val longLine = "We reviewed the schedule carefully before leaving. ".repeat(14) + "That was the final detail."
        val longTarget = "I felt calm after discussing the plan."
        val saved = snapshot().copy(completedSceneTasks = listOf(tasks.first().copy(
            contextLines = listOf(SceneDialogueLine("AI", longLine)), targetSentenceEn = longTarget)))
        val actions = mutableListOf<CompletedStepReviewAction>()
        render(CompletedStepReviewUiState(saved), short = true, onAction = actions::add)
        val dialogue = compose.onNodeWithText("AI: $longLine")
        dialogue.performScrollTo()
        val scroll = compose.onAllNodes(hasScrollAction() and hasAnyDescendant(hasText("AI: $longLine")),
            useUnmergedTree = true).fetchSemanticsNodes().last()
        compose.onNode(SemanticsMatcher("same local scroll") { it.id == scroll.id }, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, Float.MAX_VALUE) }
        // The target follows the dialogue; it is the final item in the same local content area.
        val targetNode = compose.onNodeWithText(longTarget)
        targetNode.performScrollTo().assertIsDisplayed()
        assertLastLineVisible(targetNode)
        compose.onNodeWithTag("completed-step-return").assertIsDisplayed()
        capture("short-end")
        compose.onNodeWithTag("completed-step-return").performClick()
        assertEquals(listOf(CompletedStepReviewAction.ReturnToCurrentTaskRequested), actions)
    }

    private fun assertLastLineVisible(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertFalse(layout.multiParagraph.didExceedMaxLines)
        assertTrue(layout.multiParagraph.height <= layout.size.height + 1f)
        val top = node.getUnclippedBoundsInRoot().top.value * 2f
        val visible = node.fetchSemanticsNode().boundsInRoot
        assertTrue("Final line must fit its real local clip, not merely exist in semantics",
            top + layout.getLineTop(layout.lineCount - 1) >= visible.top - 1f &&
                top + layout.getLineBottom(layout.lineCount - 1) <= visible.bottom + 1f)
    }

    private fun render(state: CompletedStepReviewUiState, short: Boolean = false,
        onAction: (CompletedStepReviewAction) -> Unit = {}) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(2f, if (short) 2f else 1f)) {
            RlTheme { Box(Modifier.requiredSize(if (short) 640.dp else 914.5.dp, if (short) 360.dp else 430.dp)
                .testTag("completed-scene-test-viewport")) { CompletedStepReviewScreen(state, onAction) } }
        } }
    }

    private fun capture(stage: String) {
        val bitmap = compose.onNodeWithTag("completed-scene-test-viewport").captureToImage().asAndroidBitmap()
        try {
            val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "visual-qa")
            check(folder.isDirectory || folder.mkdirs())
            val output = File(folder, "35-completed-scene-$stage-${UUID.randomUUID()}.png")
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
