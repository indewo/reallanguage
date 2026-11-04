package com.reallanguage.app.core.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.AudioDictationCompletionRepository
import com.reallanguage.app.feature.learning.domain.AudioDictationCompletionResult
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.presentation.ReadWriteCorrectionAction
import com.reallanguage.app.feature.learning.presentation.ReadWriteCorrectionViewModel
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadWriteCorrectionEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val chapter = FixedLearningChapter("correction-chapter", 1,
        listOf(FixedLearningSentence("s1", "I write every day.", "我每天写字。", true)))

    @Test fun returningAndReenteringTheSameSentenceCreatesAUsableCorrectionInsteadOfAConsumedViewModel() {
        val visible = mutableStateOf(true)
        val created = mutableListOf<ReadWriteCorrectionViewModel>()
        var returns = 0
        var completions = 0
        var speaking = 0
        val factory = factory { viewModel {
            completions++
            AudioDictationCompletionResult.Saved
        }.also(created::add) }
        rule.setContent { RlTheme {
            if (visible.value) ReadWriteCorrectionEntry(factory, "same-correction", { returns++ }, { speaking++ })
        } }
        rule.runOnIdle { created.single().onAction(ReadWriteCorrectionAction.BackRequested) }
        rule.waitUntil(5_000) { returns == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        rule.runOnIdle { visible.value = true }; rule.waitForIdle()
        assertEquals(2, created.size)
        rule.runOnIdle {
            created.last().onAction(ReadWriteCorrectionAction.WrittenFinished)
            created.last().onAction(ReadWriteCorrectionAction.WrittenFinished)
        }
        rule.waitUntil(5_000) { speaking == 1 }
        assertEquals(1, completions)
        assertEquals(1, returns)
        assertEquals(1, speaking)
    }

    @Test fun systemBackUsesCorrectionStateAndDoesNotFallThroughToAnUnrelatedParent() {
        var returns = 0
        var parent = 0
        var speaking = 0
        lateinit var correction: ReadWriteCorrectionViewModel
        val factory = factory { viewModel { error("Back must not complete dictation") }.also { correction = it } }
        rule.setContent { RlTheme {
            BackHandler { parent++ }
            ReadWriteCorrectionEntry(factory, "back-correction", { returns++ }, { speaking++ })
        } }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        assertEquals(1, returns)
        assertEquals(0, parent)
        assertEquals(0, speaking)
        assertFalse(correction.uiState.value.audioLooping)
    }

    @Test fun aNewWrongAnswerEntryForTheSameSentenceDoesNotKeepThePriorAudioFailureOrHints() {
        val episode = mutableStateOf("first-wrong-answer")
        val created = mutableListOf<ReadWriteCorrectionViewModel>()
        rule.setContent { RlTheme {
            val input = episode.value
            ReadWriteCorrectionEntry(
                factory { viewModel(listOf(input)) { AudioDictationCompletionResult.Saved }.also(created::add) },
                Pair(chapter.chapterId, input), {}, {},
            )
        } }
        rule.runOnIdle { created.single().onAction(ReadWriteCorrectionAction.AudioPlaybackFailed) }
        assertTrue(created.single().uiState.value.audioFailed)
        rule.runOnIdle { episode.value = "second-wrong-answer" }; rule.waitForIdle()
        assertEquals(2, created.size)
        assertEquals(listOf("second-wrong-answer"), created.last().uiState.value.lockedHintItems)
        assertFalse(created.last().uiState.value.audioFailed)
        assertTrue(created.last().uiState.value.audioLooping)
        assertFalse(created.last().uiState.value.saving)
    }

    @Test fun leavingCancelsOnlyTheEntryThatIsSavingAndReentryIsNotPermanentlyBusy() {
        val visible = mutableStateOf(true)
        val created = mutableListOf<ReadWriteCorrectionViewModel>()
        var requests = 0
        var cancelled = 0
        var speaking = 0
        val factory = factory { viewModel {
            requests++
            try { awaitCancellation() } finally { cancelled++ }
        }.also(created::add) }
        rule.setContent { RlTheme {
            if (visible.value) ReadWriteCorrectionEntry(factory, "saving-correction", {}, { speaking++ })
        } }
        rule.runOnIdle { created.single().onAction(ReadWriteCorrectionAction.WrittenFinished) }
        rule.waitUntil(5_000) { requests == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        assertEquals(1, cancelled)
        assertEquals(0, speaking)
        rule.runOnIdle { visible.value = true }; rule.waitForIdle()
        assertEquals(2, created.size)
        assertFalse(created.last().uiState.value.saving)
    }

    private fun viewModel(
        hints: List<String> = listOf("每天"),
        complete: suspend (String) -> AudioDictationCompletionResult,
    ) = ReadWriteCorrectionViewModel(chapter, "s1", hints,
        object : AudioDictationCompletionRepository {
            override suspend fun complete(sentenceId: String) = complete.invoke(sentenceId)
        })

    private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
}
