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

class VocabularyPlaybackEntryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun realPlaybackFailureReachesTheScreenAndRetryUsesTheSameStoredExample() {
        val calls = mutableListOf<String>()
        val model = model()
        rule.setContent { RlTheme {
            VocabularyBookEntry(factory(model), {}, { calls += it; false })
        } }
        openExample()
        rule.onNodeWithTag("vocabulary-pronunciation-failed").assertIsDisplayed()
        rule.onNodeWithTag("vocabulary-pronunciation-retry").performClick()
        rule.runOnIdle { assertEquals(listOf("She runs a café.", "She runs a café."), calls) }
    }

    @Test fun returnDoesNotWaitForSpeechCompletionAndCancelsTheInFlightPlayback() {
        val model = model()
        var stopped = 0
        var cancelled = false
        var returned = false
        rule.setContent { RlTheme {
            VocabularyBookEntry(factory(model), { returned = true }, {
                try { CompletableDeferred<Boolean>().await() } finally { cancelled = true }
            }, { stopped++ })
        } }
        openExample()
        rule.onNodeWithTag("vocabulary-book-back").performClick()
        rule.runOnIdle {
            assertTrue("Back must not queue behind an unfinished utterance", returned)
            assertTrue(cancelled)
            assertTrue(stopped > 0)
            assertFalse(model.uiState.value.pronunciationBusy)
        }
    }

    @Test fun backgroundStopsPlaybackWithoutLosingTheOpenMeaningOrInventingFailure() {
        val model = model()
        val owner = TestOwner()
        var stopped = 0
        var cancelled = false
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { RlTheme {
            VocabularyBookEntry(factory(model), {}, {
                try { CompletableDeferred<Boolean>().await() } finally { cancelled = true }
            }, { stopped++ })
        } } }
        openExample()
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        rule.runOnIdle {
            assertTrue(cancelled)
            assertTrue(stopped > 0)
            assertEquals("run-business", model.uiState.value.expandedSenseId)
            assertFalse(model.uiState.value.pronunciationBusy)
            assertFalse(model.uiState.value.pronunciationFailed)
        }
    }

    @Test fun removingTheEntryStopsTheNativePlayerAsWellAsCancellingItsCoroutine() {
        val visible = mutableStateOf(true)
        val model = model()
        var stopped = 0
        var cancelled = false
        rule.setContent { RlTheme { if (visible.value) {
            VocabularyBookEntry(factory(model), {}, {
                try { CompletableDeferred<Boolean>().await() } finally { cancelled = true }
            }, { stopped++ })
        } } }
        openExample()
        rule.runOnIdle { visible.value = false }
        rule.runOnIdle { assertTrue(cancelled); assertTrue(stopped > 0) }
    }

    private fun openExample() {
        rule.onNodeWithTag("vocabulary-sense-run-business").performClick()
        rule.onNodeWithTag("vocabulary-example-run-business-0").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun model() = VocabularyBookViewModel(object : VocabularyBookRepository {
        override suspend fun loadAllRecords() = VocabularyLoadResult.Ready(setOf("CET4"), listOf(
            VocabularySenseRecord("run-business", "run", "经营", KnowledgeStatus.LEARNING, 1, setOf("CET4"), 1,
                pronunciationText = "run", examples = listOf(VocabularyExample("She runs a café.", "她经营一家咖啡店。", "She runs a café.")))
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
