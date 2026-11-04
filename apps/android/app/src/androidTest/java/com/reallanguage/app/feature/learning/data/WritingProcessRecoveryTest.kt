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

/** Opt-in: write draft, force-stop, submit restored draft, force-stop, read accepted result. */
class WritingProcessRecoveryTest {
    @Test fun draftHelpQualificationAndAcceptedResultSurviveSeparateProcesses() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("writingPhase")
        assumeTrue(phase in setOf("draft", "submit", "accepted"))
        val account = requireNotNull(args.getString("progressAccount")).also { require(it.matches(Regex("process-test-[a-z0-9-]+"))) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = SessionRuntime().apply { update("isolated-token-not-a-real-session", account) }
        val chapter = FixedLearningChapter("c1", 1, listOf(
            FixedLearningSentence("s1", "I felt calm.", "我很平静。", true),
            FixedLearningSentence("s2", "I went home.", "我回家了。", true),
        ))
        if (phase == "draft") FormationDatabase(context).use { helper ->
            helper.writableDatabase.execSQL("insert into chapters values (?, 'process-test-flow', 'c1', 0, 'NOT_STARTED')", arrayOf(account))
            chapter.sentences.forEachIndexed { index, sentence ->
                helper.writableDatabase.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, 'c1', ?, ?, ?, ?, 'FOCUS')", arrayOf<Any>(account, sentence.sentenceId, index, sentence.textZh, sentence.textEn))
            }
        }
        var submissions = 0
        val actual = AndroidCompleteWritingRepository(context, runtime)
        val repository = object : CompleteWritingRepository {
            override suspend fun loadFinalBatch(chapterId: String) = actual.loadFinalBatch(chapterId)
            override suspend fun saveFinalBatch(chapterId: String, result: FinalWritingBatchResult): CompleteWritingSaveResult { submissions++; return actual.saveFinalBatch(chapterId, result) }
        }
        val store = AndroidLearningProgressStore(context, runtime, "c1", "s1", "LR-08")
        val model = withContext(Dispatchers.Main) {
            val comparator = FixedWritingComparator()
            CompleteWritingViewModel(chapter, emptyList(), comparator, CompleteWritingEvaluator(comparator), repository, store)
        }
        withTimeout(5_000) { model.uiState.first { !it.saving } }.also { assertFalse(it.saveFailed) }
        suspend fun act(action: CompleteWritingAction) = withContext(Dispatchers.Main) { model.onAction(action) }
        if (phase == "draft") {
            chapter.sentences.forEach { sentence -> repeat(2) {
                act(CompleteWritingAction.InputChanged(sentence.textEn))
                act(CompleteWritingAction.SubmitRequested)
                act(CompleteWritingAction.NextRequested)
            } }
            act(CompleteWritingAction.FinalInputChanged("s1", "unfinished saved answer"))
            act(CompleteWritingAction.HelpRequested)
            act(CompleteWritingAction.BackRequested)
            assertEquals(CompleteWritingEffect.ReturnToPatternSpeaking, withTimeout(5_000) { model.effects.first() })
            assertEquals("unfinished saved answer", store.read()?.get("answer:s1"))
        } else {
            assertEquals(CompleteWritingPhase.FINAL_BATCH, model.uiState.value.phase)
            assertTrue(model.uiState.value.helpUsed)
            assertFalse(model.uiState.value.audioPlaying)
            if (phase == "submit") {
                assertEquals("unfinished saved answer", model.uiState.value.finalInputs["s1"])
                assertNull(model.uiState.value.finalResult)
                chapter.sentences.forEach { act(CompleteWritingAction.FinalInputChanged(it.sentenceId, it.textEn)) }
                act(CompleteWritingAction.SubmitRequested)
                withTimeout(5_000) { model.uiState.first { !it.saving && it.finalResult != null } }
                assertEquals(1, submissions)
                assertEquals(FinalWritingBatchResult.SupportedLearningOnly, actual.loadFinalBatch("c1"))
            } else {
                assertEquals(FinalWritingBatchResult.SupportedLearningOnly, model.uiState.value.finalResult)
                assertEquals(chapter.sentences.associate { it.sentenceId to it.textEn }, model.uiState.value.finalInputs)
                assertEquals(0, submissions)
            }
        }
        if (phase == "accepted") FormationDatabase(context).use { helper ->
            listOf("learning_module_progress", "learning_checkpoints", "writing_result_drafts", "writing_batch_completions", "fixed_sentences", "chapters").forEach {
                helper.writableDatabase.delete(it, "account_id=?", arrayOf(account))
            }
        }
    }
}
