package com.reallanguage.app.feature.learning.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.reallanguage.app.R
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual scroll/touch and controlled screen states only; no microphone, account, SQL or AI. */
class PatternSpeakingStagePositionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun window() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun startingResponsesShowsItsNewHeadingWithoutUserRepairScrolling() {
        verifyTransition(PatternSpeakingStage.READING, PatternSpeakingAction.StartResponsesRequested)
    }

    @Test fun nextResponseShowsNewHeadingButSameResponseUpdatesKeepReadingPosition() {
        verifyTransition(PatternSpeakingStage.RESPONDING, PatternSpeakingAction.NextSceneRequested)
    }

    private fun verifyTransition(initialStage: PatternSpeakingStage, expectedAction: PatternSpeakingAction) {
        val actions = mutableListOf<PatternSpeakingAction>()
        val state = mutableStateOf(PatternSpeakingUiState(
            FixedLearningChapter("c", 1, listOf(FixedLearningSentence("s", "I drink green tea.", "我喝绿茶。", true))),
            "s", listOf(LockedSpeakingPattern("p", "s", "drink + noun", "说明喝的饮品")),
            stage = initialStage,
            examples = (1..3).map { index -> PatternPracticeExample("e$index", "询问饮品", "What do you drink?",
                "I drink green tea every morning at home.", "第${index}条例句：我每天早晨在家喝绿茶。")} ,
            completedReadingExampleIds = setOf("e1", "e2", "e3"),
            currentResponseCompleted = initialStage == PatternSpeakingStage.RESPONDING,
            transcript = "I drink green tea every morning at home.",
            feedbackZh = "已保存本轮反馈。".repeat(12),
        ))
        compose.setContent { RlTheme { PatternSpeakingScreen(state.value) { action ->
            actions += action
            state.value = when (action) {
                PatternSpeakingAction.StartResponsesRequested -> state.value.copy(stage = PatternSpeakingStage.RESPONDING)
                PatternSpeakingAction.NextSceneRequested -> state.value.copy(responseIndex = 1,
                    currentResponseCompleted = false, transcript = "", feedbackZh = null)
                else -> state.value
            }
        } } }
        val scroll = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("pattern-speaking-workspace")))
        fun position() = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        repeat(3) {
            scroll.performTouchInput { swipe(Offset(width * .6f, height * .85f), Offset(width * .6f, height * .15f), 400) }
        }
        compose.waitForIdle()
        val before = position()
        assertTrue("The real content must have been scrolled before transition", before > 20f)
        // Error/busy completion and other same-object state emissions must not reset reading.
        compose.runOnIdle { state.value = state.value.copy(audioMuted = !state.value.audioMuted) }
        compose.waitForIdle()
        assertEquals("Same stage/example retains its original position", before, position(), 1f)
        val label = compose.activity.getString(if (initialStage == PatternSpeakingStage.READING)
            R.string.pattern_speaking_start_responses else R.string.pattern_speaking_next_scene)
        compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(listOf(expectedAction), actions)
        // No post-transition scrolling is permitted: the newly selected content must start in view.
        assertEquals("A different stage/example cannot inherit the previous content's offset", 0f, position(), 1f)
        val heading = compose.onNodeWithTag("pattern-speaking-scene-progress")
        heading.assertTextEquals(if (initialStage == PatternSpeakingStage.READING) "场景 1 / 3" else "场景 2 / 3")
        assertAllGlyphsVisible(heading)
        val after = position()
        compose.runOnIdle { state.value = state.value.copy(submitting = true) }
        compose.waitForIdle()
        assertEquals("Busy must not move the new content either", after, position(), 1f)
    }

    private fun assertAllGlyphsVisible(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        val coordinates = node.fetchSemanticsNode().layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, true)
        layout.layoutInput.text.text.forEachIndexed { index, ch -> if (!ch.isWhitespace()) {
            val box = layout.getBoundingBox(index).translate(origin)
            assertTrue("New heading glyph $index must be wholly in the actual viewport: $box vs $clip",
                box.left >= clip.left - 1 && box.right <= clip.right + 1 && box.top >= clip.top - 1 && box.bottom <= clip.bottom + 1)
        } }
    }
}
