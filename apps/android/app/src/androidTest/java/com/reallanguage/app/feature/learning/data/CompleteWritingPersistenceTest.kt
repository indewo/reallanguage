package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompleteWritingPersistenceTest {
    private fun result(passed: Boolean = true) = FinalWritingBatchResult.Valid(listOf(
        SentenceWritingResult("s1", passed, listOf(WritingTargetResult("t1", WritingTargetType.MEANING, passed))),
        SentenceWritingResult("s2", passed, emptyList()),
    ))

    @Test fun newCommandMayRecoverTheSameResultButCannotReplaceAnAcceptedBatch() = runBlocking {
        withChapter { context, runtime ->
            assertEquals(CompleteWritingSaveResult.Saved, AndroidCompleteWritingRepository(context, runtime, "first").saveFinalBatch("c1", result()))
            assertEquals(CompleteWritingSaveResult.Saved, AndroidCompleteWritingRepository(context, runtime, "retry").saveFinalBatch("c1", result()))
            assertEquals(result(), AndroidCompleteWritingRepository(context, runtime).loadFinalBatch("c1"))
            assertEquals(CompleteWritingSaveResult.Failed, AndroidCompleteWritingRepository(context, runtime, "replace").saveFinalBatch("c1", result(false)))
            FormationDatabase(context).use { helper ->
                helper.readableDatabase.rawQuery("select count(*),min(passed) from writing_result_drafts where account_id=? and chapter_id='c1'", arrayOf(runtime.accountId)).use {
                    assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0)); assertEquals(1, it.getInt(1))
                }
                helper.readableDatabase.rawQuery("select command_id from writing_batch_completions where account_id=? and chapter_id='c1'", arrayOf(runtime.accountId)).use {
                    assertTrue(it.moveToFirst()); assertEquals("first", it.getString(0))
                }
            }
        }
    }

    @Test fun partialForeignOrDuplicateTargetsCannotBecomeAnAcceptedBatch() = runBlocking {
        withChapter { context, runtime ->
            val valid = result()
            val invalid = listOf(
                valid.copy(sentenceResults = valid.sentenceResults.take(1)),
                valid.copy(sentenceResults = valid.sentenceResults + valid.sentenceResults.first()),
                valid.copy(sentenceResults = valid.sentenceResults.map { it.copy(targetResults = emptyList()) }),
                valid.copy(sentenceResults = valid.sentenceResults.map { if (it.sentenceId == "s1") it.copy(targetResults = listOf(WritingTargetResult("foreign", WritingTargetType.MEANING, true))) else it }),
                valid.copy(sentenceResults = valid.sentenceResults.map { if (it.sentenceId == "s1") it.copy(targetResults = it.targetResults + it.targetResults) else it }),
            )
            invalid.forEachIndexed { index, batch ->
                assertEquals(CompleteWritingSaveResult.Failed, AndroidCompleteWritingRepository(context, runtime, "invalid-$index").saveFinalBatch("c1", batch))
            }
            FormationDatabase(context).use { helper ->
                helper.readableDatabase.rawQuery("select count(*) from writing_batch_completions where account_id=?", arrayOf(runtime.accountId)).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            }
        }
    }

    @Test fun missingArchivedAndChangedAccountCannotSaveAnOrphanResult() = runBlocking {
        withChapter { context, runtime ->
            val repository = AndroidCompleteWritingRepository(context, runtime)
            assertEquals(CompleteWritingSaveResult.Failed, repository.saveFinalBatch("missing", result()))
            FormationDatabase(context).use { it.writableDatabase.execSQL("update chapters set status='COMPLETED' where account_id=?", arrayOf(runtime.accountId)) }
            assertEquals(CompleteWritingSaveResult.Failed, repository.saveFinalBatch("c1", result()))
            val account = runtime.accountId!!
            runtime.update("other-test-token", "$account-other")
            try { assertEquals(CompleteWritingSaveResult.Failed, repository.saveFinalBatch("c1", result())) }
            finally { runtime.update("test-token", account) }
        }
    }

    @Test fun supportedBatchCannotBeChangedIntoIndependentSuccessByReentry() = runBlocking {
        withChapter { context, runtime ->
            assertEquals(CompleteWritingSaveResult.Saved, AndroidCompleteWritingRepository(context, runtime, "helped").saveFinalBatch("c1", FinalWritingBatchResult.SupportedLearningOnly))
            assertEquals(CompleteWritingSaveResult.Failed, AndroidCompleteWritingRepository(context, runtime, "independent").saveFinalBatch("c1", result()))
        }
    }

    private suspend fun withChapter(block: suspend (Context, SessionRuntime) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = "writing-persistence-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("test-token", account) }
        FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("insert into chapters values (?, 'test-flow', 'c1', 0, 'NOT_STARTED')", arrayOf(account))
            db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, 'c1', 's1', 0, '我很平静。', 'I felt calm.', 'FOCUS')", arrayOf(account))
            db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, 'c1', 's2', 1, '我回家了。', 'I went home.', 'FOCUS')", arrayOf(account))
            db.execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary,learning_scopes) values (?, 'c1', 't1', 's1', 'WORD', 'calm', '平静', '', '', '')", arrayOf(account))
        }
        try { block(context, runtime) }
        finally {
            FormationDatabase(context).use { helper ->
                listOf("writing_result_drafts", "writing_batch_completions", "target_selections", "fixed_sentences", "chapters").forEach {
                    helper.writableDatabase.delete(it, "account_id in (?,?)", arrayOf(account, "$account-other"))
                }
            }
        }
    }
}
