package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.AndroidFixedChapterRepository
import com.reallanguage.app.feature.learning.domain.withSavedExpression
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LearningProgressStoreTest {
    @Test fun completedStepReadsDeliveredExpressionWithoutChangingCurrentCheckpoint() = runBlocking {
        withChapter { context, runtime ->
            val saved = mapOf("version" to "1", "reads" to "3", "expressionId" to "word-1", "activeExpressionId" to "word-1",
                "expressionZh" to "感到；feel 的过去式", "phonetic" to "/felt/", "exampleEn" to "She felt happy.", "exampleZh" to "她感到开心。")
            assertTrue(AndroidLearningProgressStore(context, runtime, "c1", "s1", "LR-02").write(saved))
            val checkpoints = AndroidLearningCheckpointRepository(context, runtime)
            val current = com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint("c1", "LR-03", "s1")
            assertEquals(com.reallanguage.app.feature.learning.domain.CheckpointSaveResult.Saved, checkpoints.save(current))
            val chapter = (AndroidFixedChapterRepository(context, runtime).load("c1") as
                com.reallanguage.app.feature.learning.domain.FixedChapterLoadResult.Loaded).chapter
            val snapshot = com.reallanguage.app.core.app.completedStepSnapshot(chapter, "s1", "LR-02", "基础讲解",
                expressions = listOf(com.reallanguage.app.feature.learning.domain.SelectableExpression("word-1", "felt", 2, 6)))
            val reopened = AndroidLearningProgressStore(context, runtime, "c1", "s1", "LR-02")
            val reviewed = snapshot.withSavedExpression(reopened.read())
            assertEquals("felt", reviewed.reviewedExpression?.expression?.display)
            assertEquals("She felt happy.", reviewed.reviewedExpression?.explanation?.naturalExample?.textEn)
            assertEquals(saved, reopened.read())
            assertEquals(current, checkpoints.read("c1"))
        }
    }
    @Test fun archiveClearsProgressOnlyAfterAnActualCompleteDraftBoundary() = runBlocking {
        withChapter { context, runtime ->
            val account = runtime.accountId!!
            val progress = AndroidLearningProgressStore(context, runtime, "c1", "s1", "LR-02")
            assertTrue(progress.write(mapOf("reads" to "3")))
            val archive = AndroidChapterArchiveRepository(context, runtime)
            assertEquals(com.reallanguage.app.feature.learning.domain.ChapterArchiveResult.Failed, archive.archive("c1", "archive-test"))
            assertEquals(mapOf("reads" to "3"), progress.read())
            FormationDatabase(context).use { helper ->
                val db = helper.writableDatabase
                db.execSQL("insert into writing_batch_completions values (?, 'c1', 'writing-test', 'SUPPORTED')", arrayOf(account))
                db.execSQL("insert into writing_result_drafts values (?, 'c1', 's1', 'WHOLE_WRITING', '', 0)", arrayOf(account))
                db.execSQL("insert into full_speaking_result_drafts values (?, 'c1', 's1', 0)", arrayOf(account))
                db.execSQL("insert into scene_response_result_drafts values (?, 'c1', 's1', 0)", arrayOf(account))
            }
            assertTrue(archive.archive("c1", "archive-test") is com.reallanguage.app.feature.learning.domain.ChapterArchiveResult.Archived)
            assertProgressAbsent(context, account)
            assertFalse(progress.write(mapOf("reads" to "1")))
        }
    }

    @Test fun abandoningChapterClearsItsProgressAndLateWritesStayRejected() = runBlocking {
        withChapter { context, runtime ->
            val progress = AndroidLearningProgressStore(context, runtime, "c1", "s1", "LR-02")
            assertTrue(progress.write(mapOf("reads" to "1")))
            assertTrue(AndroidChapterAbandonRepository(context, runtime).abandonUnarchivedChapter("c1", "abandon-test") is
                com.reallanguage.app.feature.learning.domain.ChapterAbandonResult.Abandoned)
            assertProgressAbsent(context, runtime.accountId!!)
            assertFalse(progress.write(mapOf("reads" to "2")))
        }
    }

    private fun assertProgressAbsent(context: Context, account: String) {
        FormationDatabase(context).use { helper ->
            listOf("learning_module_progress", "learning_checkpoints").forEach { table ->
                helper.readableDatabase.rawQuery("select count(*) from $table where account_id=?", arrayOf(account)).use {
                    it.moveToFirst(); assertEquals(0, it.getInt(0))
                }
            }
        }
    }
    @Test fun lateProgressCannotMoveTheResumePointerBackToAnOlderModule() = runBlocking {
        withChapter { context, runtime ->
            val oldModule = AndroidLearningProgressStore(context, runtime, "c1", "s1", "LR-02")
            assertTrue(oldModule.write(mapOf("reads" to "1")))
            assertEquals(com.reallanguage.app.feature.learning.domain.CheckpointSaveResult.Saved,
                AndroidLearningCheckpointRepository(context, runtime).save(
                    com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint("c1", "LR-03", "s1")))
            assertFalse(oldModule.write(mapOf("reads" to "2")))
            assertEquals(mapOf("reads" to "1"), oldModule.read())
        }
    }
    @Test fun progressAndResumePointerAreSavedTogetherAndSurviveNewRepository() = runBlocking {
        withChapter { context, runtime ->
            val expected = mapOf("version" to "1", "reads" to "2", "hint" to "已交付中文提示")
            fun store() = AndroidLearningProgressStore(context, runtime, "c1", "s1", "LR-02", "explanation:s1")
            assertNull(store().read())
            assertTrue(store().write(expected))
            assertEquals(expected, store().read())
            FormationDatabase(context).use { helper ->
                helper.readableDatabase.rawQuery("select module_id,step_id from learning_checkpoints where account_id=? and chapter_id='c1'", arrayOf(runtime.accountId)).use {
                    assertTrue(it.moveToFirst()); assertEquals("LR-02", it.getString(0)); assertEquals("explanation:s1", it.getString(1))
                }
            }
        }
    }

    @Test fun failedWriteRollsBackPayloadAndDoesNotAdvanceResumePointer() = runBlocking {
        withChapter { context, runtime ->
            val store = AndroidLearningProgressStore(context, runtime, "c1", "s1", "LR-02")
            assertTrue(store.write(mapOf("reads" to "1")))
            FormationDatabase(context).use { helper ->
                // Scoped fault injection: only this isolated account is rejected.
                helper.writableDatabase.execSQL("create trigger reject_test_checkpoint before insert on learning_checkpoints when NEW.account_id='${runtime.accountId}' begin select raise(ABORT, 'test write failure'); end")
            }
            try {
                assertFalse(store.write(mapOf("reads" to "2")))
                assertEquals(mapOf("reads" to "1"), store.read())
            } finally {
                FormationDatabase(context).use { it.writableDatabase.execSQL("drop trigger if exists reject_test_checkpoint") }
            }
        }
    }

    @Test fun logoutAccountSwitchAndArchivedOrDeletedChapterCannotRestoreOrWrite() = runBlocking {
        withChapter { context, runtime ->
            val account = runtime.accountId!!
            val store = AndroidLearningProgressStore(context, runtime, "c1", "s1", "LR-02")
            assertTrue(store.write(mapOf("reads" to "1")))
            runtime.update("other-token", "$account-other")
            assertTrue(runCatching { store.read() }.isFailure)
            assertFalse(store.write(mapOf("reads" to "3")))
            runtime.update("test-token", account)
            FormationDatabase(context).use { it.writableDatabase.execSQL("update chapters set status='COMPLETED' where account_id=? and chapter_id='c1'", arrayOf(account)) }
            assertFalse(store.write(mapOf("reads" to "3")))
            assertTrue(runCatching { store.read() }.isFailure)
            runtime.clear()
            assertTrue(runCatching { store.read() }.isFailure)
        }
    }

    private suspend fun withChapter(block: suspend (Context, SessionRuntime) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = "module-progress-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("test-token", account) }
        FormationDatabase(context).use { helper ->
            helper.writableDatabase.execSQL("insert into chapters values (?, 'test-flow', 'c1', 0, 'NOT_STARTED')", arrayOf(account))
            helper.writableDatabase.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, 'c1', 's1', 0, '我很平静。', 'I felt calm.', 'FOCUS')", arrayOf(account))
        }
        try { block(context, runtime) }
        finally {
            FormationDatabase(context).use { helper ->
                listOf("learning_module_progress", "learning_checkpoints", "fixed_sentences", "chapters",
                    "writing_batch_completions", "full_speaking_result_drafts", "scene_response_result_drafts",
                    "chapter_archives", "chapter_abandon_commands", "normal_learning_days", "sentence_capabilities",
                    "learning_obligations", "correction_flags").forEach {
                    helper.writableDatabase.delete(it, "account_id=?", arrayOf(account))
                }
            }
        }
    }
}
