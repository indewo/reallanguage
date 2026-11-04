package com.reallanguage.app.core.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.learning.presentation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Entry ownership only. Actual SQLite account epoch semantics are tested separately. */
class CopyDictationEntryLifecycleTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val chapter = FixedLearningChapter("isolated-entry-chapter", 1,
        listOf(FixedLearningSentence("s1", "I write every day.", "我每天写字。", true)))
    private val session = SessionRuntime().apply { update("isolated-entry-token", "isolated-entry-owner") }

    @Test fun handwritingReentryAfterTheSameAccountSignsInReadsCurrentProgressAndCanSaveAgain() {
        val visible = mutableStateOf(true)
        val created = mutableListOf<HandwritingCopyViewModel>()
        val progress = Progress(handwritingProgress(1))
        val factory = factory { handwriting(progress.store()).also(created::add) }
        rule.setContent { RlTheme {
            if (visible.value) HandwritingCopyEntry(factory, "same-copy", {}, {})
        } }
        rule.waitUntil(5_000) { created.firstOrNull()?.uiState?.value?.confirmedCopyCount == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        rule.runOnIdle {
            session.clear(); session.update("isolated-reentry-token", "isolated-entry-owner")
            progress.saved = handwritingProgress(2)
            visible.value = true
        }; rule.waitForIdle()
        assertEquals(2, created.size)
        rule.waitUntil(5_000) { created.last().uiState.value.confirmedCopyCount == 2 }
        rule.runOnIdle { created.last().onAction(HandwritingCopyAction.CopyAgainRequested) }
        rule.waitUntil(5_000) { created.last().uiState.value.copyInProgress && !created.last().uiState.value.saving }
        rule.runOnIdle { created.last().onAction(HandwritingCopyAction.WrittenFinished) }
        rule.waitUntil(5_000) { created.last().uiState.value.confirmedCopyCount == 3 }
        assertEquals("3", progress.saved["confirmedCopyCount"])
        assertFalse(created.last().uiState.value.saveFailed)
    }

    @Test fun handwritingTokenRefreshKeepsTheActiveEntryAndItsWorkingProgress() {
        val created = mutableListOf<HandwritingCopyViewModel>()
        val progress = Progress(handwritingProgress(1))
        rule.setContent { RlTheme {
            HandwritingCopyEntry(factory { handwriting(progress.store()).also(created::add) }, "refresh-copy", {}, {})
        } }
        rule.waitUntil(5_000) { created.firstOrNull()?.uiState?.value?.confirmedCopyCount == 1 }
        rule.runOnIdle {
            session.update("isolated-refreshed-token", "isolated-entry-owner")
            created.single().onAction(HandwritingCopyAction.WrittenFinished)
        }
        rule.waitUntil(5_000) { created.single().uiState.value.confirmedCopyCount == 2 }
        assertEquals(1, created.size)
        assertEquals("2", progress.saved["confirmedCopyCount"])
        assertFalse(created.single().uiState.value.saveFailed)
    }

    @Test fun dictationReentryAfterTheSameAccountSignsInReadsCurrentInputAndCanSubmitAgain() {
        val visible = mutableStateOf(true)
        val created = mutableListOf<AudioDictationViewModel>()
        val progress = Progress(dictationProgress("I"))
        var completions = 0
        val factory = factory { dictation(progress.store()) { completions++ }.also(created::add) }
        rule.setContent { RlTheme {
            if (visible.value) AudioDictationEntry(factory, "same-dictation", {}, {}, {})
        } }
        rule.waitUntil(5_000) { created.firstOrNull()?.uiState?.value?.let { !it.submitting && it.input == "I" } == true }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        rule.runOnIdle {
            session.clear(); session.update("isolated-reentry-token", "isolated-entry-owner")
            progress.saved = dictationProgress("I write every day.")
            visible.value = true
        }; rule.waitForIdle()
        assertEquals(2, created.size)
        rule.waitUntil(5_000) { created.last().uiState.value.let { !it.submitting && it.input == "I write every day." } }
        rule.runOnIdle { created.last().onAction(AudioDictationAction.SubmitRequested) }
        rule.waitUntil(5_000) { created.last().uiState.value.correct }
        assertEquals(1, completions)
        assertEquals("true", progress.saved["correct"])
        assertFalse(created.last().uiState.value.saveFailed)
    }

    @Test fun dictationTokenRefreshKeepsTheActiveEntryAndDoesNotResetTypedInput() {
        val created = mutableListOf<AudioDictationViewModel>()
        val progress = Progress(dictationProgress("I"))
        var completions = 0
        rule.setContent { RlTheme {
            AudioDictationEntry(factory { dictation(progress.store()) { completions++ }.also(created::add) },
                "refresh-dictation", {}, {}, {})
        } }
        rule.waitUntil(5_000) { created.firstOrNull()?.uiState?.value?.let { !it.submitting && it.input == "I" } == true }
        rule.runOnIdle {
            session.update("isolated-refreshed-token", "isolated-entry-owner")
            created.single().onAction(AudioDictationAction.InputChanged("I write every day."))
            created.single().onAction(AudioDictationAction.SubmitRequested)
        }
        rule.waitUntil(5_000) { created.single().uiState.value.correct }
        assertEquals(1, created.size)
        assertEquals(1, completions)
        assertEquals("I write every day.", progress.saved["input"])
        assertFalse(created.single().uiState.value.saveFailed)
    }

    private inner class Progress(var saved: Map<String, String>) {
        fun store(): LearningProgressStore {
            val epoch = session.accountEpoch
            return object : LearningProgressStore {
                override suspend fun read(): Map<String, String> {
                    check(session.accountEpoch == epoch)
                    return saved.toMap()
                }
                override suspend fun write(values: Map<String, String>): Boolean {
                    if (session.accountEpoch != epoch) return false
                    saved = values.toMap()
                    return true
                }
            }
        }
    }

    private fun handwriting(store: LearningProgressStore) = HandwritingCopyViewModel(chapter, "s1",
        HandwritingCompletionRepository { _, _ -> HandwritingCompletionResult.Saved }, store)
    private fun dictation(store: LearningProgressStore, completed: () -> Unit) = AudioDictationViewModel(
        chapter, "s1", listOf("每天"), comparator = FixedWritingComparator(),
        completionRepository = AudioDictationCompletionRepository { completed(); AudioDictationCompletionResult.Saved },
        progressStore = store,
    )
    private fun handwritingProgress(count: Int) = mapOf("version" to "1", "confirmedCopyCount" to count.toString(),
        "copyInProgress" to (count < 2).toString())
    private fun dictationProgress(input: String) = mapOf("version" to "1", "input" to input,
        "audioLooping" to "false", "hintExpanded" to "false", "correct" to "false", "incorrect" to "false")
    private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
}
