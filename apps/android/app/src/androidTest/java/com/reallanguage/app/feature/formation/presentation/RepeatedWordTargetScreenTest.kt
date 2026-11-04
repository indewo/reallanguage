package com.reallanguage.app.feature.formation.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.formation.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real text links and character coordinates; neutral UI fixture, not AI-service evidence. */
class RepeatedWordTargetScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val english = "I saw a saw."
    private val first = word("first", 2, "看见了")
    private val second = word("second", 8, "锯子")

    @Test fun selectedSecondOccurrenceDoesNotHighlightOrToggleTheFirstOccurrence() {
        val actions = mutableListOf<LearningTargetAction>()
        mount(listOf(first, second), setOf("second"), actions)
        val node = compose.onNodeWithText(english)
        val text = node.fetchSemanticsNode().config[SemanticsProperties.Text].single()
        val highlights = text.spanStyles.filter { it.item.background.alpha > 0f }
        assertTrue(highlights.any { it.start == 8 && it.end == 11 })
        assertFalse(highlights.any { it.start < 5 && it.end > 2 })
        clickWordAt(8)
        assertEquals(listOf(LearningTargetAction.TargetToggled("second")), actions)
    }

    @Test fun tappingUnseenSecondOccurrenceRequestsItsOwnOffset() {
        val actions = mutableListOf<LearningTargetAction>()
        mount(listOf(first), setOf("first"), actions)
        clickWordAt(8)
        assertEquals(listOf(LearningTargetAction.ExpressionRequested("s1", "word:8")), actions)
    }

    private fun mount(targets: List<LearningTarget>, selected: Set<String>, actions: MutableList<LearningTargetAction>) {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val sentence = EnglishSentenceDraft("s1", "我看见了一把锯子。", true, listOf(EnglishCandidate("e1", english)))
        val state = LearningTargetUiState(listOf(EnglishChapterDraft("c1", listOf(sentence))), mapOf("s1" to "e1"),
            targets, selected, emptyList())
        compose.setContent { RlTheme { LearningTargetScreen(state, actions::add) } }
    }

    private fun clickWordAt(offset: Int) {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText(english)
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        node.performTouchInput { click(layouts.single().getBoundingBox(offset).center) }
    }

    private fun word(id: String, start: Int, meaning: String) = LearningTarget(id, "s1", "saw", meaning,
        LearningTargetType.WORD, sourceEvidence = SourceEvidence(1, start, start + 3, "saw", "saw", listOf("saw")))
}
