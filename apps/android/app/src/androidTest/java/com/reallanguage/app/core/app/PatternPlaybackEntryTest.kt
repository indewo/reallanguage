package com.reallanguage.app.core.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.*
import com.reallanguage.app.feature.knowledge.presentation.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PatternPlaybackEntryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun nativeFailureAndRetryUseOnlyTheSavedExampleAndDoNotReadAbstractPattern() {
        val calls = mutableListOf<String>()
        val model = model()
        rule.setContent { RlTheme {
            PatternBookEntry(factory(model), removalFactory, {}, { calls += it; false })
        } }
        openExample()
        rule.onNodeWithTag("pattern-pronunciation-failed").assertIsDisplayed()
        rule.onNodeWithTag("pattern-pronunciation-retry").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(listOf("I cooked alone for the first time.", "I cooked alone for the first time."), calls) }
    }

    @Test fun removalConfirmationStopsTheUtteranceWithoutWaitingOrDeletingAnything() {
        val model = model()
        var stopped = 0
        var cancelled = false
        rule.setContent { RlTheme {
            PatternBookEntry(factory(model), removalFactory, {}, {
                try { CompletableDeferred<Boolean>().await() } finally { cancelled = true }
            }, { stopped++ })
        } }
        openExample()
        rule.onNodeWithTag("pattern-detail-remove").performScrollTo().performClick()
        rule.onNodeWithTag("pattern-removal-confirm").assertIsDisplayed()
        rule.runOnIdle { assertTrue(cancelled); assertTrue(stopped > 0); assertEquals(1, model.uiState.value.patterns.size) }
    }

    @Test fun backgroundStopsAudioButKeepsPatternAndBothAbilityStates() {
        val model = model()
        val owner = TestOwner()
        var stopped = 0
        var cancelled = false
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { RlTheme {
            PatternBookEntry(factory(model), removalFactory, {}, {
                try { CompletableDeferred<Boolean>().await() } finally { cancelled = true }
            }, { stopped++ })
        } } }
        openExample()
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        rule.runOnIdle {
            assertTrue(cancelled); assertTrue(stopped > 0)
            assertEquals("p1", model.uiState.value.expandedPatternId)
            assertEquals(KnowledgeStatus.MASTERED, model.uiState.value.expandedPattern?.writingStatus)
            assertEquals(KnowledgeStatus.LEARNING, model.uiState.value.expandedPattern?.speakingStatus)
            assertFalse(model.uiState.value.pronunciationBusy)
            assertFalse(model.uiState.value.pronunciationFailed)
        }
    }

    @Test fun pageRemovalAlsoStopsTheNativePlayer() {
        val visible = mutableStateOf(true)
        val model = model()
        var stopped = 0
        var cancelled = false
        rule.setContent { RlTheme { if (visible.value) {
            PatternBookEntry(factory(model), removalFactory, {}, {
                try { CompletableDeferred<Boolean>().await() } finally { cancelled = true }
            }, { stopped++ })
        } } }
        openExample()
        rule.runOnIdle { visible.value = false }
        rule.runOnIdle { assertTrue(cancelled); assertTrue(stopped > 0) }
    }

    private fun openExample() {
        rule.onNodeWithTag("pattern-row-p1").performScrollTo().performClick()
        rule.onNodeWithTag("pattern-example-p1-0").performScrollTo().performClick()
        rule.waitForIdle()
    }
    private val removalFactory: (String, String) -> ViewModelProvider.Factory = { id, command ->
        factory(PatternRemovalViewModel(id, command, object : PatternRemovalRepository {
            override suspend fun removeFromPatternBook(patternId: String, commandId: String): PatternRemovalResult =
                error("Opening the confirmation must not delete a pattern")
        }))
    }
    private fun model() = PatternBookViewModel(object : PatternBookRepository {
        override suspend fun loadAllPatterns() = PatternLoadResult.Ready(listOf(
            PatternRecord("p1", "for the first time", "第一次经历", KnowledgeStatus.MASTERED, KnowledgeStatus.LEARNING, 2, 3,
                examples = listOf(PatternExample("I cooked alone for the first time.", "我第一次独自做饭。", "I cooked alone for the first time.")))
        ))
    })
    private fun factory(model: ViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
    }
    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
