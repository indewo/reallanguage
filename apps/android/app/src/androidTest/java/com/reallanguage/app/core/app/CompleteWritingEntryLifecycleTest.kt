package com.reallanguage.app.core.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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

/** Entry-only checks; account epoch enforcement in SQLite has its own native evidence. */
class CompleteWritingEntryLifecycleTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val chapter = FixedLearningChapter("writing-entry-chapter", 1,
        listOf(FixedLearningSentence("s1", "I write every day.", "我每天写字。", true)))
    private val session = SessionRuntime().apply { update("isolated-writing-token", "isolated-writing-owner") }

    @Test fun reentryAfterTheSameAccountSignsInRestoresCurrentBatchInsteadOfAnInvalidOldStore() {
        val visible = mutableStateOf(true)
        val created = mutableListOf<CompleteWritingViewModel>()
        val progress = Progress(batchProgress("I"))
        var submissions = 0
        val factory = factory { model(progress.store()) { submissions++ }.also(created::add) }
        rule.setContent { RlTheme {
            if (visible.value) CompleteWritingEntry(factory, "same-chapter", {}, {})
        } }
        rule.waitUntil(5_000) { created.firstOrNull()?.uiState?.value?.let { !it.saving && it.finalInputs["s1"] == "I" } == true }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        rule.runOnIdle {
            session.clear(); session.update("isolated-writing-reentry", "isolated-writing-owner")
            progress.saved = batchProgress("I write every day.")
            visible.value = true
        }; rule.waitForIdle()
        assertEquals(2, created.size)
        rule.waitUntil(5_000) { created.last().uiState.value.let { !it.saving && it.finalInputs["s1"] == "I write every day." } }
        rule.runOnIdle { created.last().onAction(CompleteWritingAction.SubmitRequested) }
        rule.waitUntil(5_000) { created.last().uiState.value.finalResult != null }
        assertEquals(1, submissions)
        val result = created.last().uiState.value.finalResult as FinalWritingBatchResult.Valid
        assertTrue(result.sentenceResults.single().wholeSentencePassed)
        assertFalse(created.last().uiState.value.saveFailed)
    }

    @Test fun normalTokenRefreshDoesNotRecreateTheEntryOrLoseItsUnsubmittedBatch() {
        val created = mutableListOf<CompleteWritingViewModel>()
        val progress = Progress(batchProgress("I"))
        var submissions = 0
        rule.setContent { RlTheme {
            CompleteWritingEntry(factory { model(progress.store()) { submissions++ }.also(created::add) },
                "refresh-chapter", {}, {})
        } }
        rule.waitUntil(5_000) { created.firstOrNull()?.uiState?.value?.let { !it.saving && it.finalInputs["s1"] == "I" } == true }
        rule.runOnIdle {
            session.update("isolated-writing-refreshed", "isolated-writing-owner")
            created.single().onAction(CompleteWritingAction.FinalInputChanged("s1", "I write every day."))
            created.single().onAction(CompleteWritingAction.SubmitRequested)
        }
        rule.waitUntil(5_000) { created.single().uiState.value.finalResult != null }
        assertEquals(1, created.size)
        assertEquals(1, submissions)
        assertEquals("I write every day.", progress.saved["answer:s1"])
        assertFalse(created.single().uiState.value.saveFailed)
    }

    @Test fun systemBackSavesTheCurrentDraftAndUsesTheModuleReturnOnlyOnce() {
        val progress = Progress(batchProgress("I"))
        var parent = 0
        var returned = 0
        var next = 0
        lateinit var writing: CompleteWritingViewModel
        val factory = factory { model(progress.store()) { error("Back must not submit a batch") }.also { writing = it } }
        rule.setContent { RlTheme {
            BackHandler { parent++ }
            CompleteWritingEntry(factory, "writing-back", { returned++ }, { next++ })
        } }
        rule.waitForIdle()
        rule.waitUntil(5_000) { !writing.uiState.value.saving }
        rule.runOnIdle { writing.onAction(CompleteWritingAction.FinalInputChanged("s1", "unfinished retained answer")) }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        assertEquals(1, returned)
        assertEquals(0, parent)
        assertEquals(0, next)
        assertEquals("unfinished retained answer", progress.saved["answer:s1"])
    }

    private inner class Progress(var saved: Map<String, String>) {
        fun store(): LearningProgressStore {
            val epoch = session.accountEpoch
            return object : LearningProgressStore {
                override suspend fun read(): Map<String, String> { check(session.accountEpoch == epoch); return saved.toMap() }
                override suspend fun write(values: Map<String, String>): Boolean {
                    if (session.accountEpoch != epoch) return false
                    saved = values.toMap(); return true
                }
            }
        }
    }
    private fun model(store: LearningProgressStore, submitted: () -> Unit): CompleteWritingViewModel {
        val comparator = FixedWritingComparator()
        return CompleteWritingViewModel(chapter, emptyList(), comparator, CompleteWritingEvaluator(comparator),
            CompleteWritingRepository { _, _ -> submitted(); CompleteWritingSaveResult.Saved }, store)
    }
    private fun batchProgress(answer: String) = mapOf("version" to "1", "phase" to "FINAL_BATCH",
        "sentenceIndex" to "0", "input" to "", "helpUsed" to "false", "attemptCorrect" to "", "answer:s1" to answer)
    private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
}
