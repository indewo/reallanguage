package com.reallanguage.app.feature.knowledge.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PatternPronunciationScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun everyExampleSpeakerIsBoundToCurrentPatternAndPositionRatherThanRawText() {
        val actions = mutableListOf<PatternBookAction>()
        rule.setContent { RlTheme { PatternBookScreen(state(), actions::add) } }
        rule.onNodeWithTag("pattern-example-p1-0").performScrollTo().assertHasClickAction().performClick()
        rule.onNodeWithTag("pattern-example-p1-1").performScrollTo().assertHasClickAction().performClick()
        assertEquals(listOf(PatternBookAction.ExamplePronunciationRequested("p1", 0),
            PatternBookAction.ExamplePronunciationRequested("p1", 1)), actions)
    }

    @Test fun blankStoredAudioIsDisabledWithoutReplacingOrHidingTheFullExample() {
        rule.setContent { RlTheme { PatternBookScreen(state(blankSecond = true), {}) } }
        rule.onNodeWithTag("pattern-example-p1-1").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("She traveled alone for the first time.").assertExists()
        rule.onNodeWithTag("pattern-detail-collapse").performScrollTo().assertIsDisplayed()
    }

    @Test fun failureAndRetryRemainVisibleWithoutLosingPatternStates() {
        val actions = mutableListOf<PatternBookAction>()
        rule.setContent { RlTheme { PatternBookScreen(state().copy(pronunciationFailed = true), actions::add) } }
        rule.onNodeWithTag("pattern-pronunciation-failed").assertIsDisplayed()
        rule.onNodeWithTag("pattern-pronunciation-retry").assertIsDisplayed().performClick()
        assertEquals(listOf(PatternBookAction.RetryPronunciationRequested), actions)
        rule.onNodeWithText("连续会写 2 次").assertExists()
        rule.onNodeWithText("连续会说 1 次").assertExists()
    }

    @Test fun doubleFontLongBilingualExampleIsNotClippedAndFooterCanStillBeReached() {
        val english = "I worked up the courage to ask for feedback before the meeting. ".repeat(8)
        val chinese = "我鼓起勇气在会议之前征求反馈，完整内容必须能够读到。".repeat(8)
        val detail = state().patterns.single().copy(examples = listOf(PatternExample(english, chinese, english)))
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { RlTheme {
                Box(Modifier.requiredSize(640.dp, 360.dp)) { PatternBookScreen(state().copy(patterns = listOf(detail)), {}) }
            } }
        }
        for (text in listOf(english, chinese)) {
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse(layouts.single().multiParagraph.didExceedMaxLines)
            assertTrue("Long example must not be cropped by the button height", layouts.single().multiParagraph.height <= layouts.single().size.height + 1)
        }
        rule.onNodeWithTag("pattern-detail-collapse").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("pattern-detail-remove").performScrollTo().assertIsDisplayed()
    }

    private fun state(blankSecond: Boolean = false): PatternBookUiState {
        val record = PatternRecord("p1", "for the first time", "表示第一次经历", KnowledgeStatus.MASTERED, KnowledgeStatus.LEARNING, 2, 5,
            examples = listOf(
                PatternExample("I cooked alone for the first time.", "我第一次独自做饭。", "I cooked alone for the first time."),
                PatternExample("She traveled alone for the first time.", "她第一次独自旅行。", if (blankSecond) "" else "She traveled alone for the first time."),
            ), writingConsecutiveSuccessCount = 2, speakingConsecutiveSuccessCount = 1)
        return PatternBookUiState(listOf(record), record.patternId, loading = false)
    }
}
