package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.learning.presentation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in phases separated by an external force-stop; only the isolated account is cleaned. */
class CopyDictationProcessRecoveryTest {
    @Test fun confirmedCopiesWrongInputAndAcceptedAnswerSurviveProcesses() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("copyDictationPhase")
        assumeTrue(phase in setOf("copy", "wrong", "correct", "accepted"))
        val account = requireNotNull(args.getString("progressAccount")).also {
            require(it.matches(Regex("process-test-[a-z0-9-]+")))
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = SessionRuntime().apply { update("isolated-token-not-a-real-session", account) }
        val chapter = FixedLearningChapter("c1", 1, listOf(
            FixedLearningSentence("s1", "I felt calm.", "我很平静。", true),
        ))
        if (phase == "copy") FormationDatabase(context).use { helper ->
            helper.writableDatabase.execSQL("insert into chapters values (?, 'process-test-flow', 'c1', 0, 'NOT_STARTED')", arrayOf(account))
            helper.writableDatabase.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, 'c1', 's1', 0, '我很平静。', 'I felt calm.', 'FOCUS')", arrayOf(account))
        }
        val steps = AndroidLearningStepStore(context)
        if (phase in setOf("copy", "wrong")) {
            val model = withContext(Dispatchers.Main) {
                HandwritingCopyViewModel(chapter, "s1",
                    AndroidHandwritingCompletionRepository(runtime, "c1", "$account-copy", steps),
                    AndroidLearningProgressStore(context, runtime, "c1", "s1", "LR-04"))
            }
            assertFalse(withTimeout(5_000) { model.uiState.first { !it.saving } }.saveFailed)
            assertEquals(if (phase == "copy") 0 else 1, model.uiState.value.confirmedCopyCount)
            withContext(Dispatchers.Main) { model.onAction(HandwritingCopyAction.WrittenFinished) }
            assertFalse(withTimeout(5_000) { model.uiState.first { !it.saving } }.saveFailed)
            assertEquals(if (phase == "copy") 1 else 2, model.uiState.value.confirmedCopyCount)
            if (phase == "copy") return@runBlocking
            withContext(Dispatchers.Main) { model.onAction(HandwritingCopyAction.ContinueRequested) }
            assertEquals(HandwritingCopyEffect.OpenAudioDictation, withTimeout(5_000) { model.effects.first() })
            assertEquals(CheckpointSaveResult.Saved, AndroidLearningCheckpointRepository(context, runtime).save(
                OrdinaryLearningCheckpoint("c1", "LR-05", "s1"),
            ))
        }
        var submissions = 0
        val actual = AndroidAudioDictationCompletionRepository(runtime, "c1", "$account-dictation", steps)
        val store = AndroidLearningProgressStore(context, runtime, "c1", "s1", "LR-05")
        val model = withContext(Dispatchers.Main) {
            AudioDictationViewModel(chapter, "s1", emptyList(), comparator = FixedWritingComparator(),
                completionRepository = AudioDictationCompletionRepository { submissions++; actual.complete(it) }, progressStore = store)
        }
        assertFalse(withTimeout(5_000) { model.uiState.first { !it.submitting } }.saveFailed)
        suspend fun act(action: AudioDictationAction) = withContext(Dispatchers.Main) { model.onAction(action) }
        when (phase) {
            "wrong" -> {
                act(AudioDictationAction.InputChanged("I felt"))
                act(AudioDictationAction.HintToggled)
                act(AudioDictationAction.SubmitRequested)
                assertEquals(AudioDictationEffect.OpenReadWriteCorrection("I felt"), withTimeout(5_000) { model.effects.first() })
                assertEquals("I felt", store.read()?.get("input"))
                assertEquals(0, submissions)
            }
            "correct" -> {
                assertEquals("I felt", model.uiState.value.input)
                assertTrue(model.uiState.value.incorrect)
                assertTrue(model.uiState.value.hintExpanded)
                act(AudioDictationAction.InputChanged("I felt calm."))
                act(AudioDictationAction.SubmitRequested)
                withTimeout(5_000) { model.uiState.first { it.correct && !it.submitting } }
                act(AudioDictationAction.BackRequested)
                assertEquals(AudioDictationEffect.ReturnToHandwriting, withTimeout(5_000) { model.effects.first() })
                assertEquals("true", store.read()?.get("correct"))
                assertEquals(1, submissions)
            }
            "accepted" -> {
                assertTrue(model.uiState.value.correct)
                assertEquals("I felt calm.", model.uiState.value.input)
                act(AudioDictationAction.InputChanged("must not overwrite accepted answer"))
                act(AudioDictationAction.SubmitRequested)
                act(AudioDictationAction.ContinueRequested)
                assertEquals(AudioDictationEffect.OpenFullSentenceSpeaking, withTimeout(5_000) { model.effects.first() })
                assertEquals(0, submissions)
                assertEquals("I felt calm.", model.uiState.value.input)
                FormationDatabase(context).use { helper ->
                    helper.readableDatabase.rawQuery("select count(*) from learning_steps where account_id=?", arrayOf(account)).use {
                        assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
                    }
                    listOf("learning_module_progress", "learning_checkpoints", "learning_steps", "fixed_sentences", "chapters").forEach {
                        helper.writableDatabase.delete(it, "account_id=?", arrayOf(account))
                    }
                }
            }
        }
    }
}
