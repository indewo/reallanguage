package com.reallanguage.app.feature.knowledge.presentation

import androidx.compose.ui.test.*
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Before
import org.junit.Test

class VocabularyPronunciationScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun useProductionLandscapeWindow() {
        rule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
    }

    @Test fun everyAvailableExampleSpeakerDispatchesItsCurrentSenseAndExactIndex() {
        val actions = mutableListOf<VocabularyBookAction>()
        rule.setContent { RlTheme { VocabularyBookScreen(state(), actions::add) } }
        rule.onNodeWithTag("vocabulary-example-run-business-0").performScrollTo().assertHasClickAction().performClick()
        rule.onNodeWithTag("vocabulary-example-run-business-1").performScrollTo().assertHasClickAction().performClick()
        assertEquals(listOf(VocabularyBookAction.ExamplePronunciationRequested("run-business", 0),
            VocabularyBookAction.ExamplePronunciationRequested("run-business", 1)), actions)
    }

    @Test fun unavailableExampleAudioIsDisabledWithoutReplacingItsOriginalText() {
        rule.setContent { RlTheme { VocabularyBookScreen(state(blankSecond = true), {}) } }
        rule.onNodeWithTag("vocabulary-example-run-business-1").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("They run the shop together.").assertExists()
    }

    @Test fun reportedTtsFailureIsVisibleAndRetryDoesNotChangeLearningStatus() {
        val actions = mutableListOf<VocabularyBookAction>()
        rule.setContent { RlTheme { VocabularyBookScreen(state().copy(pronunciationFailed = true), actions::add) } }
        rule.onNodeWithTag("vocabulary-pronunciation-failed").assertIsDisplayed()
        rule.onNodeWithTag("vocabulary-pronunciation-retry").assertIsDisplayed().performClick()
        assertEquals(listOf(VocabularyBookAction.RetryPronunciationRequested), actions)
        rule.onNodeWithTag("vocabulary-detail-sheet").assertIsDisplayed()
    }

    @Test fun absentWordAudioDoesNotPreventStoredExamplePlaybackOrCollapsingTheDetail() {
        val actions = mutableListOf<VocabularyBookAction>()
        rule.setContent { RlTheme { VocabularyBookScreen(state(wordAudio = null), actions::add) } }
        rule.onNodeWithTag("vocabulary-detail-collapse").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag("vocabulary-example-run-business-0").performScrollTo().assertIsEnabled()
        assertEquals(listOf(VocabularyBookAction.CollapseRequested), actions)
    }

    private fun state(blankSecond: Boolean = false, wordAudio: String? = "run"): VocabularyBookUiState {
        val record = VocabularySenseRecord("run-business", "run", "经营", KnowledgeStatus.MASTERED, 2, setOf("CET4"), 1,
            pronunciationText = wordAudio, examples = listOf(
                VocabularyExample("She runs a small café.", "她经营一家小咖啡店。", "She runs a small café."),
                VocabularyExample("They run the shop together.", "他们一起经营这家店。", if (blankSecond) "" else "They run the shop together."),
            ))
        return VocabularyBookUiState("CET4", listOf(VocabularyEntry("run", listOf(record))), record.senseId, loading = false)
    }
}
