package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.ChapterAbandonResult
import com.reallanguage.app.feature.learning.domain.ChapterArchiveResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Preset decisions are isolation fixtures, not evidence of real speech or AI quality. */
class ChapterCommitOwnerEpochTest {
    private lateinit var context: Context
    private val runtime = SessionRuntime().apply { update("isolated-first", "owner") }

    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "chapter-commit-epoch")
        FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("insert into formation_flows values('owner','flow','FIXED','DIARY','隔离日记')")
            db.execSQL("insert into chapters values('owner','flow','chapter',0,'NOT_STARTED')")
            db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values('owner','chapter','s1',0,'我喝茶。','I drink tea.','FOCUS')")
            db.execSQL("insert into writing_batch_completions values('owner','chapter','write','INDEPENDENT')")
            db.execSQL("insert into writing_result_drafts values('owner','chapter','s1','WHOLE_WRITING','',1)")
            db.execSQL("insert into full_speaking_result_drafts values('owner','chapter','s1',1)")
            db.execSQL("insert into scene_response_result_drafts values('owner','chapter','s1',1)")
        }
    }

    @After fun tearDown() { context.deleteDatabase("real-language-learning.db") }

    @Test fun oldAbandonPageCannotDeleteChapterAfterLogoutAndSameAccountLogin() = runBlocking {
        val old = AndroidChapterAbandonRepository(context, runtime)
        reauthenticate()
        assertEquals(ChapterAbandonResult.Failed, old.abandonUnarchivedChapter("chapter", "stale-abandon"))
        assertPendingAndUnchanged()
        val fresh = AndroidChapterAbandonRepository(context, runtime)
        assertTrue(fresh.abandonUnarchivedChapter("chapter", "fresh-abandon") is ChapterAbandonResult.Abandoned)
        assertEquals(0, count("chapters"))
        assertEquals(1, count("chapter_abandon_commands"))
        assertEquals(0, count("chapter_archives"))
    }

    @Test fun oldResultPageCannotArchiveOrClearNewSessionWorkAfterReauthentication() = runBlocking {
        val old = AndroidChapterArchiveRepository(context, runtime)
        reauthenticate()
        assertEquals(ChapterArchiveResult.Failed, old.archive("chapter", "stale-archive"))
        assertPendingAndUnchanged()
        val fresh = AndroidChapterArchiveRepository(context, runtime)
        val result = fresh.archive("chapter", "fresh-archive")
        assertTrue(result is ChapterArchiveResult.Archived)
        val reread = AndroidChapterArchiveRepository(context, runtime).archive("chapter", "read-again")
        assertEquals((result as ChapterArchiveResult.Archived).summary,
            (reread as ChapterArchiveResult.AlreadyArchived).summary)
        assertEquals(1, count("chapter_archives"))
        assertEquals(0, count("chapter_abandon_commands"))
    }

    @Test fun ordinaryTokenRefreshKeepsCurrentArchiveAuthorityAndIdempotentReadback() = runBlocking {
        val current = AndroidChapterArchiveRepository(context, runtime)
        val epoch = runtime.accountEpoch
        runtime.update("isolated-refreshed", "owner")
        assertEquals(epoch, runtime.accountEpoch)
        val result = current.archive("chapter", "archive") as ChapterArchiveResult.Archived
        assertEquals(result.summary,
            (current.archive("chapter", "retry") as ChapterArchiveResult.AlreadyArchived).summary)
        assertEquals(1, count("chapter_archives"))
    }

    @Test fun ordinaryTokenRefreshKeepsCurrentAbandonAuthorityAndIdempotentReadback() = runBlocking {
        val current = AndroidChapterAbandonRepository(context, runtime)
        val epoch = runtime.accountEpoch
        runtime.update("isolated-refreshed", "owner")
        assertEquals(epoch, runtime.accountEpoch)
        val result = current.abandonUnarchivedChapter("chapter", "abandon")
        assertTrue(result is ChapterAbandonResult.Abandoned)
        assertEquals(result, current.abandonUnarchivedChapter("chapter", "abandon"))
        assertEquals(1, count("chapter_abandon_commands"))
    }

    private fun reauthenticate() {
        val epoch = runtime.accountEpoch
        runtime.clear()
        runtime.update("isolated-next", "owner")
        assertNotEquals(epoch, runtime.accountEpoch)
    }

    private fun assertPendingAndUnchanged() {
        assertEquals(1, count("chapters"))
        assertEquals(1, count("fixed_sentences"))
        assertEquals(1, count("writing_result_drafts"))
        assertEquals(1, count("scene_response_result_drafts"))
        assertEquals(1, count("full_speaking_result_drafts"))
        assertEquals(0, count("chapter_archives"))
        assertEquals(0, count("chapter_abandon_commands"))
        FormationDatabase(context).use { helper ->
            helper.readableDatabase.rawQuery("select status from chapters where account_id='owner' and chapter_id='chapter'", null).use {
                assertTrue(it.moveToFirst()); assertEquals("NOT_STARTED", it.getString(0)); assertFalse(it.moveToNext())
            }
        }
    }

    private fun count(table: String) = FormationDatabase(context).use { helper ->
        helper.readableDatabase.rawQuery("select count(*) from $table where account_id='owner'", null).use {
            assertTrue(it.moveToFirst()); it.getInt(0)
        }
    }
}
