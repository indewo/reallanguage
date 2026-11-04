package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.SceneDialogueLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Actual screen and touch, not a repository/AI test. No outer scroll, screenshots, assets, accounts or storage. */
class SceneResponseTechnicalRetryVisibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingDefaultShowsTheRetainedRequestAndDispatchesOnlyRetry() = verifyPending(shortFont = false)
    @Test fun pendingShortFontShowsEveryMessageAndActionGlyphAndDispatchesOnlyRetry() = verifyPending(shortFont = true)
    @Test fun terminalDefaultOffersExplicitNewRecordingAndDisablesSecondaryRetry() = verifyTerminal(shortFont = false)
    @Test fun terminalShortFontKeepsTheWholeNewRecordingActionTouchable() = verifyTerminal(shortFont = true)
    @Test fun busyDefaultDisablesBothTranscriptionAndFeedbackSubmissionActions() = verifyBusy(shortFont = false)
    @Test fun busyShortFontDisablesBothTranscriptionAndFeedbackSubmissionActions() = verifyBusy(shortFont = true)

    private fun verifyPending(shortFont: Boolean) {
        val actions = mutableListOf<SceneResponseAction>()
        val state = fixture().copy(captureFailed = true, technicalRetryPending = true)
        render({ state }, actions, shortFont)
        assertMessageVisible(R.string.learning_audio_retry_pending)
        compose.onNodeWithText(string(R.string.scene_response_inconclusive)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.full_sentence_speaking_retry_blocked)).assertDoesNotExist()
        val label = string(R.string.read_speak_retry)
        val primary = primarySubmissionButton()
        primary.assertTextEquals(label).assertIsEnabled()
        assertButtonGlyphsVisible(primary, label)
        primary.performTouchInput { click(center) }
        assertEquals(listOf(SceneResponseAction.RetryRequested), actions)
        // The existing secondary Retry must also reuse this same request, never mean fresh capture.
        val primaryId = primary.fetchSemanticsNode().id
        val secondary = compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes()
            .filter { it.id != primaryId }
        for (node in secondary) {
            val button = byId(node.id).assertIsEnabled()
            assertButtonGlyphsVisible(button, label)
            button.performTouchInput { click(center) }
        }
        assertEquals(List(1 + secondary.size) { SceneResponseAction.RetryRequested }, actions)
    }

    private fun verifyTerminal(shortFont: Boolean) {
        val actions = mutableListOf<SceneResponseAction>()
        val state = fixture().copy(captureFailed = true, technicalRetryBlocked = true)
        render({ state }, actions, shortFont)
        assertMessageVisible(R.string.full_sentence_speaking_retry_blocked)
        compose.onNodeWithText(string(R.string.scene_response_inconclusive)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.learning_audio_retry_pending)).assertDoesNotExist()
        val retryLabel = string(R.string.read_speak_retry)
        val secondary = compose.onNode(hasText(retryLabel) and hasClickAction()).assertIsNotEnabled()
        assertButtonGlyphsVisible(secondary, retryLabel)
        secondary.performTouchInput { click(center) }
        assertTrue("A terminal operation must not dispatch technical retry", actions.isEmpty())
        val primaryLabel = string(R.string.full_sentence_speaking_new_recording)
        val primary = primarySubmissionButton().assertTextEquals(primaryLabel).assertIsEnabled()
        assertButtonGlyphsVisible(primary, primaryLabel)
        primary.performTouchInput { click(center) }
        assertEquals(listOf(SceneResponseAction.StartRequested), actions)
    }

    private fun verifyBusy(shortFont: Boolean) {
        val actions = mutableListOf<SceneResponseAction>()
        val submitting = fixture().copy(submitting = true, technicalRetryPending = true)
        val state = mutableStateOf(submitting)
        render({ state.value }, actions, shortFont)
        for (busy in listOf(submitting, fixture().copy(transcribing = true, technicalRetryPending = true, transcript = ""))) {
            compose.runOnIdle { state.value = busy }
            compose.waitForIdle()
            val buttons = submissionButtons()
            assertTrue("The real busy screen must still expose its disabled submission control", buttons.isNotEmpty())
            for (button in buttons) {
                val node = byId(button.id).assertIsDisplayed().assertIsNotEnabled()
                val label = submissionLabels().single { it in nodeText(button) }
                assertButtonGlyphsVisible(node, label)
                node.performTouchInput { click(center) }
            }
            assertTrue("Busy controls must not dispatch another recording, finish, confirmation or retry", actions.isEmpty())
        }
    }

    private fun render(state: () -> SceneResponseUiState, actions: MutableList<SceneResponseAction>, shortFont: Boolean) {
        compose.setContent {
            if (shortFont) CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
                ScreenContent(state, actions, Modifier.requiredSize(640.dp, 360.dp))
            } else ScreenContent(state, actions, Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        if (shortFont) {
            val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().layoutInfo
            assertEquals("Requested physical width, not a wider host", 1280, viewport.width)
            assertEquals("Requested physical height, not a taller host", 720, viewport.height)
        }
    }

    @Composable
    private fun ScreenContent(state: () -> SceneResponseUiState, actions: MutableList<SceneResponseAction>, modifier: Modifier) {
        RlTheme {
            Box(modifier.testTag(VIEWPORT)) { SceneResponseScreen(state = state(), onAction = actions::add) }
        }
    }

    private fun assertMessageVisible(resource: Int) {
        val label = string(resource)
        val node = compose.onNodeWithText(label, useUnmergedTree = true)
        node.assertExists()
        // Only production's existing inner scroll may reveal the notice, if it owns the text.
        if (compose.onAllNodes(hasText(label) and hasAnyAncestor(hasScrollAction()), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()) node.performScrollTo()
        compose.waitForIdle()
        node.assertIsDisplayed()
        assertAllGlyphsVisible(node, label, node.fetchSemanticsNode().boundsInRoot)
    }

    private fun primarySubmissionButton(): SemanticsNodeInteraction {
        val primary = submissionButtons().maxByOrNull { it.boundsInRoot.right }
        requireNotNull(primary) { "The actual Scene screen must expose its primary submission button" }
        return byId(primary.id)
    }

    private fun submissionButtons(): List<SemanticsNode> {
        val labels = submissionLabels()
        return compose.onAllNodes(hasClickAction() and SemanticsMatcher("a real Scene submission action") { node ->
            nodeText(node).any { it in labels }
        }).fetchSemanticsNodes()
    }

    private fun nodeText(node: SemanticsNode): List<String> = if (node.config.contains(SemanticsProperties.Text)) {
        node.config[SemanticsProperties.Text].map { it.text }
    } else emptyList()

    private fun submissionLabels() = listOf(
        R.string.read_speak_retry, R.string.full_sentence_speaking_new_recording,
        R.string.pattern_speaking_start_response, R.string.pattern_speaking_response_finished,
        R.string.scene_response_confirm_transcript, R.string.scene_response_next_round,
        R.string.full_sentence_speaking_retry_save, R.string.scene_response_loading,
    ).map(::string)

    private fun byId(id: Int) = compose.onNode(SemanticsMatcher("same Scene node $id") { it.id == id })

    private fun assertButtonGlyphsVisible(button: SemanticsNodeInteraction, label: String) {
        button.assertIsDisplayed()
        val id = button.fetchSemanticsNode().id
        // Resolve this button's own Text so identical primary/secondary Retry labels are not ambiguous.
        val text = compose.onNode(hasText(label) and hasAnyAncestor(SemanticsMatcher("button $id") { it.id == id }),
            useUnmergedTree = true)
        assertAllGlyphsVisible(text, label, button.fetchSemanticsNode().boundsInRoot)
    }

    private fun assertAllGlyphsVisible(node: SemanticsNodeInteraction, label: String, ownerBounds: Rect) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals("One real Text paragraph, not an existence-only assertion", 1, layouts.size)
        val layout = layouts.single()
        assertEquals(label, layout.layoutInput.text.text)
        assertTrue(layout.lineCount > 0)
        assertFalse("No text may be truncated", layout.multiParagraph.didExceedMaxLines)
        assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1))
        repeat(layout.lineCount) { line ->
            assertFalse("Every original line must remain, not an ellipsis", layout.isLineEllipsized(line))
            assertTrue(layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
        }
        val coordinate = node.fetchSemanticsNode().layoutInfo.coordinates
        val origin = coordinate.localToRoot(Offset.Zero)
        val clip = coordinate.findRootCoordinates().localBoundingBoxOf(coordinate, clipBounds = true)
            .intersect(ownerBounds).intersect(compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot)
        val glyphs = label.indices.filter { !label[it].isWhitespace() }
            .map { layout.getBoundingBox(it).translate(origin) }
        assertTrue("Whole label must fit its actual clipped node and viewport: label=$label lines=${layout.lineCount} clip=$clip glyphs=$glyphs",
            clip.width > 0 && clip.height > 0 && glyphs.isNotEmpty() && glyphs.all { fits(it, clip) })
    }

    private fun fixture() = SceneResponseUiState(
        chapter = FixedLearningChapter("render-scene-technical", 1, listOf(
            FixedLearningSentence("render-focus", "I drink tea.", "我喝茶。", true),
        )),
        tasks = listOf(FixedSceneResponseTask("render-task", "render-focus", "Talk about drinks.",
            listOf(SceneDialogueLine("AI", "What do you drink?")), "I drink tea.")),
        roundIndex = 2,
        transcript = "I drink tea.",
    )

    private fun string(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private companion object {
        const val VIEWPORT = "scene-technical-retry-viewport"
        fun fits(box: Rect, clip: Rect) = box.top >= clip.top - 1f && box.bottom <= clip.bottom + 1f &&
            box.left >= clip.left - 1f && box.right <= clip.right + 1f
    }
}
