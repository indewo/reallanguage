package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Account-scoped SQLite checks. Never clears the application database or user records. */
class ChapterArchiveBoundaryTest {
    @Test fun actualCorrectionConfirmationIsIncludedInTheArchivedSummary() = fixture { context, helper, runtime, account ->
        helper.writableDatabase.execSQL("insert into learning_steps values (?, 'c1', 's1', 'READ_WRITE_CORRECTION', 'COMPLETED', 'correct')", arrayOf(account))
        val result = AndroidChapterArchiveRepository(context, runtime).archive("c1", "archive") as ChapterArchiveResult.Archived
        val correction = result.summary.items.singleOrNull { it.category == ChapterResultCategory.CORRECTION }
        assertNotNull("Confirmed correction must not disappear from the result page", correction)
        assertEquals(1, correction!!.totalCount)
        assertEquals(1, correction.completedCount)
        assertEquals(result.summary, (AndroidChapterArchiveRepository(context, runtime).archive("c1", "retry") as ChapterArchiveResult.AlreadyArchived).summary)
    }

    @Test fun legacyArchiveWithoutNewDisplayFieldsRemainsReadableWithoutRewritingHistory() = fixture { context, helper, runtime, account ->
        val repository = AndroidChapterArchiveRepository(context, runtime)
        val first = repository.archive("c1", "archive") as ChapterArchiveResult.Archived
        val saved = helper.readableDatabase.rawQuery("select summary from chapter_archives where account_id=?", arrayOf(account)).use {
            assertTrue(it.moveToFirst()); org.json.JSONObject(it.getString(0)).apply {
                remove("chapterNumber"); remove("reinforcementItems")
            }.toString()
        }
        helper.writableDatabase.execSQL("update chapter_archives set summary=? where account_id=?", arrayOf(saved, account))
        val reread = AndroidChapterArchiveRepository(context, runtime).archive("c1", "retry") as ChapterArchiveResult.AlreadyArchived
        assertEquals(first.summary.copy(chapterNumber = null, reinforcementItems = emptyList()), reread.summary)
        helper.readableDatabase.rawQuery("select summary from chapter_archives where account_id=?", arrayOf(account)).use {
            assertTrue(it.moveToFirst()); assertEquals(saved, it.getString(0))
        }
    }

    @Test fun archiveKeepsActualChapterIdentityAndTheUnfinishedExpression() = fixture { context, helper, runtime, account ->
        helper.writableDatabase.execSQL("update full_speaking_result_drafts set passed=0 where account_id=?", arrayOf(account))
        val repository = AndroidChapterArchiveRepository(context, runtime)
        val result = repository.archive("c1", "archive")
        assertTrue(result is ChapterArchiveResult.Archived)
        val reread = repository.archive("c1", "read-again")
        assertEquals((result as ChapterArchiveResult.Archived).summary, (reread as ChapterArchiveResult.AlreadyArchived).summary)
        helper.readableDatabase.rawQuery("select summary from chapter_archives where account_id=? and chapter_id='c1'", arrayOf(account)).use {
            assertTrue(it.moveToFirst())
            val json = org.json.JSONObject(it.getString(0))
            assertEquals(1, json.optInt("chapterNumber", -1))
            val pending = json.optJSONArray("reinforcementItems")
            assertNotNull("A category count is not the actual expression that needs practice", pending)
            assertEquals(1, pending!!.length())
            assertEquals("I felt calm.", pending.getJSONObject(0).getString("display"))
            assertEquals("s1", pending.getJSONObject(0).getString("sentenceId"))
            assertEquals("FULL_SPEAKING", pending.getJSONObject(0).getString("capabilityType"))
        }
    }

    @Test fun sourceWithAnAbandonedChapterIsNotACompletelyLearnedContent() = fixture { context, helper, runtime, account ->
        helper.writableDatabase.execSQL("insert into chapter_abandon_commands(account_id,command_id,source_id,has_remaining) values (?, 'abandon-previous', 'f1', 1)", arrayOf(account))
        assertTrue(AndroidChapterArchiveRepository(context, runtime).archive("c1", "archive") is ChapterArchiveResult.Archived)
        helper.readableDatabase.rawQuery("select count(*) from normal_learning_days where account_id=?", arrayOf(account)).use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
    }

    @Test fun oldAccountRepositoryCannotAbandonNewAccountContent() = fixture { context, helper, runtime, account ->
        val other = SessionRuntime().apply { update("isolated", "process-test-other-${UUID.randomUUID()}") }
        val repository = AndroidChapterAbandonRepository(context, other)
        other.update("isolated", runtime.accountId)
        assertEquals(ChapterAbandonResult.Failed, repository.abandonUnarchivedChapter("c1", "abandon"))
        assertUnchanged(helper, account)
    }

    @Test fun ordinaryContentCompletedOnReviewDayDoesNotStartAnotherCycle() = fixture { context, helper, runtime, account ->
        helper.writableDatabase.execSQL("insert into review_sessions values (?, 'rs1', 'cycle1', '2026-09-07', 3)", arrayOf(account))
        assertTrue(AndroidChapterArchiveRepository(context, runtime) { "2026-09-07" }.archive("c1", "archive") is ChapterArchiveResult.Archived)
        helper.readableDatabase.rawQuery("select count(*) from normal_learning_days where account_id=?", arrayOf(account)).use {
            it.moveToFirst(); assertEquals("A review day must not start a new ordinary-day cycle", 0, it.getInt(0))
        }
    }

    @Test fun partialContentDoesNotCountAsACompletedLearningDay() = fixture { context, helper, runtime, account ->
        helper.writableDatabase.execSQL("insert into chapters values (?, 'f1', 'c2', 1, 'NOT_STARTED')", arrayOf(account))
        assertTrue(AndroidChapterArchiveRepository(context, runtime).archive("c1", "archive") is ChapterArchiveResult.Archived)
        helper.readableDatabase.rawQuery("select count(*) from normal_learning_days where account_id=?", arrayOf(account)).use {
            it.moveToFirst(); assertEquals("An unfinished source is not a completed ordinary learning day", 0, it.getInt(0))
        }
    }

    @Test fun equalCountsWithWrongSentenceCannotArchive() = fixture { context, helper, runtime, account ->
        helper.writableDatabase.execSQL("update scene_response_result_drafts set sentence_id='wrong' where account_id=?", arrayOf(account))
        assertEquals(ChapterArchiveResult.Failed, AndroidChapterArchiveRepository(context, runtime).archive("c1", "archive"))
        assertUnchanged(helper, account)
    }

    @Test fun completedWritingMarkerWithoutFullResultsCannotArchive() = fixture { context, helper, runtime, account ->
        helper.writableDatabase.delete("writing_result_drafts", "account_id=?", arrayOf(account))
        assertEquals(ChapterArchiveResult.Failed, AndroidChapterArchiveRepository(context, runtime).archive("c1", "archive"))
        assertUnchanged(helper, account)
    }

    @Test fun oldAccountRepositoryCannotArchiveAfterSessionSwitch() = fixture { context, helper, runtime, account ->
        val other = SessionRuntime().apply { update("isolated", "process-test-other-${UUID.randomUUID()}") }
        val repository = AndroidChapterArchiveRepository(context, other)
        other.update("isolated", runtime.accountId)
        assertEquals(ChapterArchiveResult.Failed, repository.archive("c1", "archive"))
        assertUnchanged(helper, account)
    }

    @Test fun archiveRetainsConfirmedSceneTaskForReadOnlyReviewWithoutNewGeneration() = fixture { context, helper, runtime, account ->
        val repository = AndroidChapterArchiveRepository(context, runtime)
        val first = repository.archive("c1", "archive")
        assertTrue(first is ChapterArchiveResult.Archived)
        val reread = repository.archive("c1", "retry") as ChapterArchiveResult.AlreadyArchived
        assertEquals((first as ChapterArchiveResult.Archived).summary, reread.summary)
        val scenes = AndroidSceneResponseRepository(context, HttpTransport { error("No generation permitted") }, runtime,
            object : ReadSpeakAudioCapture { override fun start() = false; override fun finish(): CapturedReadSpeakAudio? = null; override fun cancel() = Unit })
        val result = scenes.restoreTasks(FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true))))
        assertTrue("Archived confirmed scene must remain readable", result is SceneTaskGenerationResult.Ready)
        assertEquals("t1", (result as SceneTaskGenerationResult.Ready).tasks.single().taskId)
        helper.readableDatabase.rawQuery("select status from chapters where account_id=?", arrayOf(account)).use { assertTrue(it.moveToFirst()); assertEquals("COMPLETED", it.getString(0)) }
    }

    private fun assertUnchanged(helper: FormationDatabase, account: String) {
        helper.readableDatabase.rawQuery("select count(*) from chapter_archives where account_id=?", arrayOf(account)).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        helper.readableDatabase.rawQuery("select status from chapters where account_id=?", arrayOf(account)).use { it.moveToFirst(); assertEquals("NOT_STARTED", it.getString(0)) }
    }

    private fun fixture(block: suspend (Context, FormationDatabase, SessionRuntime, String) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = "process-test-archive-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-not-real", account) }
        FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            try {
                db.execSQL("insert into chapters values (?, 'f1', 'c1', 0, 'NOT_STARTED')", arrayOf(account))
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, 'c1', 's1', 0, '我很平静。', 'I felt calm.', 'FOCUS')", arrayOf(account))
                db.execSQL("insert into writing_batch_completions values (?, 'c1', 'write', 'INDEPENDENT')", arrayOf(account))
                db.execSQL("insert into writing_result_drafts values (?, 'c1', 's1', 'WHOLE_WRITING', '', 1)", arrayOf(account))
                db.execSQL("insert into full_speaking_result_drafts values (?, 'c1', 's1', 1)", arrayOf(account))
                db.execSQL("insert into scene_response_result_drafts values (?, 'c1', 's1', 1)", arrayOf(account))
                db.execSQL("insert into scene_response_tasks values (?, 'c1', 't1', 's1', 0, 'After a meeting', ?, 'I felt calm.')", arrayOf(account, "AI\u001fHow did you feel?"))
                block(context, helper, runtime, account)
            } finally {
                listOf("learning_steps", "chapter_abandon_commands", "review_sessions", "chapter_archives", "sentence_capabilities", "normal_learning_days", "correction_flags", "learning_obligations", "scene_response_tasks", "scene_response_result_drafts", "full_speaking_result_drafts", "writing_result_drafts", "writing_batch_completions", "fixed_sentences", "chapters").forEach { db.delete(it, "account_id=?", arrayOf(account)) }
            }
        }
    }
}
