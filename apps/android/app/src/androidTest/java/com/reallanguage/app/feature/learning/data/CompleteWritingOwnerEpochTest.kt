package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.toJson
import com.reallanguage.app.feature.formation.domain.SourceEvidence
import com.reallanguage.app.feature.learning.domain.CompleteWritingSaveResult
import com.reallanguage.app.feature.learning.domain.FinalWritingBatchResult
import com.reallanguage.app.feature.learning.domain.LockedWritingTarget
import com.reallanguage.app.feature.learning.domain.SentenceWritingResult
import com.reallanguage.app.feature.learning.domain.WritingTargetResult
import com.reallanguage.app.feature.learning.domain.WritingTargetSpan
import com.reallanguage.app.feature.learning.domain.WritingTargetType
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** UUID SQLite and the real session epoch; supplied results do not claim to test scoring or AI. */
class CompleteWritingOwnerEpochTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private lateinit var runtime: SessionRuntime
    private val account = "complete-writing-owner-${UUID.randomUUID()}"
    private val source = SourceEvidence(1, 8, 11, "tea", "tea", listOf("tea"))
    private val expectedTarget = LockedWritingTarget("t1", "s1", WritingTargetType.MEANING,
        listOf("tea"), WritingTargetSpan(8, 11, "tea"))

    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(
            InstrumentationRegistry.getInstrumentation().targetContext, "complete-writing-owner-epoch",
        )
        runtime = SessionRuntime().apply { update("isolated-first-token", account) }
        database = FormationDatabase(context)
        database.writableDatabase.apply {
            execSQL("insert into formation_flows values (?, 'flow', 'FIXED', 'DIARY', '隔离书写结果')", arrayOf(account))
            execSQL("insert into chapters values (?, 'flow', 'c1', 0, 'NOT_STARTED')", arrayOf(account))
            execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) " +
                "values (?, 'c1', 's1', 0, '我喝茶。', 'I drink tea.', 'FOCUS')", arrayOf(account))
            execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display," +
                "explanation_zh,structure_parts,related_vocabulary,learning_scopes,source_evidence) " +
                "values (?, 'c1', 't1', 's1', 'WORD', 'tea', '茶', '', '', '', ?)", arrayOf(account, source.toJson().toString()))
        }
    }

    @After fun tearDown() {
        database.close()
        assertTrue(context.deleteDatabase("real-language-learning.db"))
        runtime.clear()
    }

    @Test fun exitedRepositoryCannotClaimTheUnfinishedBatchBeforeTheNewSameAccountSession() = runBlocking {
        val old = repository("old-command")
        relogin()
        assertEquals(CompleteWritingSaveResult.Failed, old.saveFinalBatch("c1", result(false)))
        assertEquals(0, count("writing_batch_completions"))
        assertEquals(0, count("writing_result_drafts"))

        val fresh = repository("new-command")
        assertEquals(CompleteWritingSaveResult.Saved, fresh.saveFinalBatch("c1", result()))
        assertEquals(result(), fresh.loadFinalBatch("c1"))
        assertEquals(result(), repository().loadFinalBatch("c1"))
        assertStoredBatch("new-command", true)
        assertEquals(CompleteWritingSaveResult.Failed, old.saveFinalBatch("c1", result(false)))
        assertStoredBatch("new-command", true)
    }

    @Test fun exitedRepositoryCannotReadTheNewSameAccountSessionsSavedBatch() = runBlocking {
        val old = repository()
        relogin()
        val fresh = repository("new-command")
        assertEquals(CompleteWritingSaveResult.Saved, fresh.saveFinalBatch("c1", result()))
        assertEquals(result(), fresh.loadFinalBatch("c1"))
        assertTrue("An exited repository must fail authorization instead of reading the new session's result",
            runCatching { old.loadFinalBatch("c1") }.isFailure)
        assertStoredBatch("new-command", true)
    }

    @Test fun exitedWholeChapterReaderCannotRegainAuthorityAfterSameAccountLogin() {
        val old = AndroidLockedWritingTargetReader(context, runtime)
        assertEquals(listOf(expectedTarget), old.read("c1"))
        relogin()
        assertEquals(listOf(expectedTarget), AndroidLockedWritingTargetReader(context, runtime).read("c1"))
        assertNull(old.read("c1"))
        assertEquals(0, count("writing_batch_completions"))
        assertEquals(source.toJson().toString(), storedEvidence())
    }

    @Test fun exitedExactTargetReaderCannotRegainAuthorityAfterSameAccountLogin() {
        val old = AndroidLockedWritingTargetReader(context, runtime)
        assertEquals(listOf(expectedTarget), old.readTarget("c1", "s1", "t1"))
        relogin()
        assertEquals(listOf(expectedTarget), AndroidLockedWritingTargetReader(context, runtime).readTarget("c1", "s1", "t1"))
        assertNull(old.readTarget("c1", "s1", "t1"))
        assertEquals(0, count("writing_result_drafts"))
        assertEquals(source.toJson().toString(), storedEvidence())
    }

    @Test fun exitedRepositoryCannotAcknowledgeOrReplaceAnAlreadyAcceptedNewSessionBatch() = runBlocking {
        val old = repository("old-command")
        relogin()
        val fresh = repository("new-command")
        assertEquals(CompleteWritingSaveResult.Saved, fresh.saveFinalBatch("c1", result()))
        assertEquals(CompleteWritingSaveResult.Failed, old.saveFinalBatch("c1", result()))
        assertEquals(CompleteWritingSaveResult.Failed, old.saveFinalBatch("c1", result(false)))
        assertEquals(result(), fresh.loadFinalBatch("c1"))
        assertStoredBatch("new-command", true)
    }

    @Test fun epochChangeAfterInitialCheckAlsoRejectsTheExistingBatchAcknowledgementBranch() = runBlocking {
        assertEquals(CompleteWritingSaveResult.Saved, repository("accepted-command").saveFinalBatch("c1", result()))
        val openingContext = SessionChangeOnDatabaseAccess(context)
        val old = AndroidCompleteWritingRepository(openingContext, runtime, "old-retry")
        val previousEpoch = runtime.accountEpoch
        openingContext.onDatabaseAccess = ::relogin
        val saved = old.saveFinalBatch("c1", result())
        // API 36 may open through getDatabasePath rather than Context's older
        // openOrCreateDatabase overloads. A non-fired hook is not a valid RED.
        assertNull("The session-change boundary must actually execute", openingContext.onDatabaseAccess)
        assertNotEquals(previousEpoch, runtime.accountEpoch)
        assertEquals(CompleteWritingSaveResult.Failed, saved)
        assertEquals(result(), repository().loadFinalBatch("c1"))
        assertStoredBatch("accepted-command", true)
    }

    @Test fun ordinaryTokenRefreshKeepsCurrentSaveLoadAndBothReadersUsable() = runBlocking {
        val current = repository("current-command")
        val reader = AndroidLockedWritingTargetReader(context, runtime)
        val epoch = runtime.accountEpoch
        runtime.update("isolated-refreshed-token", account)
        assertEquals(epoch, runtime.accountEpoch)
        assertEquals(listOf(expectedTarget), reader.read("c1"))
        assertEquals(listOf(expectedTarget), reader.readTarget("c1", "s1", "t1"))
        assertEquals(CompleteWritingSaveResult.Saved, current.saveFinalBatch("c1", result()))
        assertEquals(CompleteWritingSaveResult.Saved, current.saveFinalBatch("c1", result()))
        assertEquals(result(), current.loadFinalBatch("c1"))
        assertEquals(result(), repository().loadFinalBatch("c1"))
        assertStoredBatch("current-command", true)
    }

    private fun repository(commandId: String = UUID.randomUUID().toString()) = AndroidCompleteWritingRepository(context, runtime, commandId)

    private fun relogin() {
        val epoch = runtime.accountEpoch
        runtime.clear()
        runtime.update("isolated-second-token", account)
        assertNotEquals(epoch, runtime.accountEpoch)
    }

    private fun result(passed: Boolean = true) = FinalWritingBatchResult.Valid(listOf(
        SentenceWritingResult("s1", passed, listOf(WritingTargetResult("t1", WritingTargetType.MEANING, passed))),
    ))

    private fun assertStoredBatch(command: String, passed: Boolean) {
        FormationDatabase(context).use { helper ->
            helper.readableDatabase.rawQuery("select command_id,qualification from writing_batch_completions where account_id=? and chapter_id='c1'",
                arrayOf(account)).use {
                assertTrue(it.moveToFirst())
                assertEquals(command, it.getString(0))
                assertEquals("INDEPENDENT", it.getString(1))
                assertFalse(it.moveToNext())
            }
            helper.readableDatabase.rawQuery("select count(*),min(passed),max(passed) from writing_result_drafts where account_id=? and chapter_id='c1'",
                arrayOf(account)).use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0))
                assertEquals(if (passed) 1 else 0, it.getInt(1))
                assertEquals(if (passed) 1 else 0, it.getInt(2))
            }
        }
    }

    private fun count(table: String) = database.readableDatabase.rawQuery("select count(*) from $table", null).use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }

    private fun storedEvidence() = database.readableDatabase.rawQuery(
        "select source_evidence from target_selections where account_id=? and target_id='t1'", arrayOf(account),
    ).use { assertTrue(it.moveToFirst()); it.getString(0) }

    /** Deterministic single boundary hook, still delegates all storage to the UUID test DB. */
    private class SessionChangeOnDatabaseAccess(base: Context) : ContextWrapper(base) {
        var onDatabaseAccess: (() -> Unit)? = null
        override fun getApplicationContext(): Context = this
        private fun crossBoundary() { onDatabaseAccess?.let { action -> onDatabaseAccess = null; action() } }
        override fun getDatabasePath(name: String): File = super.getDatabasePath(name).also { crossBoundary() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            super.openOrCreateDatabase(name, mode, factory).also { crossBoundary() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            super.openOrCreateDatabase(name, mode, factory, errorHandler).also { crossBoundary() }
    }
}
