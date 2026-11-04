package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.SelfAssessmentAbility
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

/** Real UUID SQLite and session ownership. Self-assessment is not an AI or verified result. */
class TargetSelfAssessmentOwnerEpochTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private lateinit var runtime: SessionRuntime
    private val account = "self-assessment-owner-${UUID.randomUUID()}"

    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(
            InstrumentationRegistry.getInstrumentation().targetContext, "self-assessment-owner-epoch",
        )
        runtime = SessionRuntime().apply { update("isolated-first-token", account) }
        database = FormationDatabase(context)
        database.writableDatabase.apply {
            execSQL("insert into formation_flows values (?, 'flow', 'FIXED', 'DIARY', '隔离目标自评')", arrayOf(account))
            execSQL("insert into chapters values (?, 'flow', 'c1', 0, 'IN_PROGRESS')", arrayOf(account))
            execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) " +
                "values (?, 'c1', 's1', 0, '我计划去。', 'I plan to go.', 'FOCUS')", arrayOf(account))
            listOf(
                arrayOf("word", "WORD", "plan", "计划"),
                arrayOf("phrase", "FIXED_EXPRESSION", "plan to go", "计划去"),
                arrayOf("pattern", "PATTERN", "plan to", "计划做"),
            ).forEach { target ->
                execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display," +
                    "explanation_zh,structure_parts,related_vocabulary) values (?, 'c1', ?, 's1', ?, ?, ?, '', '')",
                    arrayOf(account, target[0], target[1], target[2], target[3]))
            }
            execSQL("insert into learning_checkpoints values (?, 'c1', 'LR-02', 'explanation:s1')", arrayOf(account))
        }
    }

    @After fun tearDown() {
        database.close()
        assertTrue(context.deleteDatabase("real-language-learning.db"))
        runtime.clear()
    }

    @Test fun exitedInstanceCannotMarkOrAcknowledgeTheNewSameAccountSessionsTarget() = runBlocking {
        val old = repository()
        relogin()
        assertFalse(old.mark("word", SelfAssessmentAbility.MEANING_WRITING))
        assertEquals(0, count("learning_self_assessments"))
        val fresh = repository()
        assertTrue(fresh.mark("word", SelfAssessmentAbility.MEANING_WRITING))
        assertEquals(setOf(SelfAssessmentAbility.MEANING_WRITING),
            repository().load().single { it.targetId == "word" }.assessedAbilities)
        assertFalse(old.mark("word", SelfAssessmentAbility.MEANING_WRITING))
        assertFalse(old.mark("pattern", SelfAssessmentAbility.PATTERN_SPEAKING))
        assertEquals(1, count("learning_self_assessments"))
        assertTrue(fresh.load().single { it.targetId == "pattern" }.assessedAbilities.isEmpty())
        assertNoFormalResult()
    }

    @Test fun exitedInstanceCannotLoadNewSessionTargetsOrSelfAssessments() = runBlocking {
        val old = repository()
        relogin()
        val fresh = repository()
        assertTrue(fresh.mark("pattern", SelfAssessmentAbility.PATTERN_WRITING))
        assertEquals(setOf(SelfAssessmentAbility.PATTERN_WRITING),
            fresh.load().single { it.targetId == "pattern" }.assessedAbilities)
        assertTrue("An exited page must not read a newly authenticated session's choices",
            runCatching { old.load() }.isFailure)
        assertEquals(1, count("learning_self_assessments"))
        assertNoFormalResult()
    }

    @Test fun exitedInstanceCannotReadSelectedExpressionsAfterSameAccountLogin() = runBlocking {
        val old = repository()
        assertEquals(listOf("plan", "plan to go"), old.selectedExpressionDisplays())
        relogin()
        assertEquals(listOf("plan", "plan to go"), repository().selectedExpressionDisplays())
        assertTrue(runCatching { old.selectedExpressionDisplays() }.isFailure)
        assertEquals(0, count("learning_self_assessments"))
        assertEquals(3, count("target_selections"))
        assertNoFormalResult()
    }

    @Test fun completedChapterRemainsReadOnlyForFreshSessionAndCannotAuthorizeTheExitedReader() = runBlocking {
        val old = repository()
        val archive = """{"chapterId":"c1","hasNextChapter":false,"chapterNumber":1,"items":[],"reinforcementItems":[]}"""
        database.writableDatabase.apply {
            execSQL("update chapters set status='COMPLETED' where account_id=? and chapter_id='c1'", arrayOf(account))
            execSQL("insert into chapter_archives values (?, 'c1', 'archive-command', ?, '2026-09-09')", arrayOf(account, archive))
        }
        relogin()
        val fresh = repository()
        assertEquals(listOf("plan", "plan to go"), fresh.selectedExpressionDisplays())
        assertFalse(fresh.mark("word", SelfAssessmentAbility.MEANING_WRITING))
        assertTrue(runCatching { fresh.load() }.isFailure)
        assertTrue(runCatching { old.selectedExpressionDisplays() }.isFailure)
        assertFalse(old.mark("pattern", SelfAssessmentAbility.PATTERN_SPEAKING))
        assertEquals(0, count("learning_self_assessments"))
        assertEquals(3, count("target_selections"))
        assertEquals(1, count("chapter_archives"))
        database.readableDatabase.rawQuery("select c.status,a.summary from chapters c join chapter_archives a " +
            "on a.account_id=c.account_id and a.chapter_id=c.chapter_id where c.account_id=?", arrayOf(account)).use {
            assertTrue(it.moveToFirst())
            assertEquals("COMPLETED", it.getString(0))
            assertEquals(archive, it.getString(1))
            assertFalse(it.moveToNext())
        }
        assertNoFormalResult()
    }

    @Test fun sameAccountReauthenticationDuringDatabaseAccessRollsBackWithoutAcknowledgingTheMark() = runBlocking {
        val boundary = SessionChangeOnDatabaseAccess(context)
        val old = AndroidTargetSelfAssessmentRepository(boundary, runtime, "c1", "s1", "LR-02")
        val epoch = runtime.accountEpoch
        boundary.onDatabaseAccess = ::relogin
        val marked = old.mark("word", SelfAssessmentAbility.MEANING_WRITING)
        assertNull("The boundary must actually fire on the device's SQLiteOpenHelper path", boundary.onDatabaseAccess)
        assertNotEquals(epoch, runtime.accountEpoch)
        assertFalse(marked)
        assertEquals(0, count("learning_self_assessments"))
        assertTrue(repository().mark("word", SelfAssessmentAbility.MEANING_WRITING))
        assertEquals(1, count("learning_self_assessments"))
        assertNoFormalResult()
    }

    @Test fun ordinaryTokenRefreshPreservesCurrentLoadMarkExpressionAndIdempotentRetry() = runBlocking {
        val current = repository()
        val epoch = runtime.accountEpoch
        runtime.update("isolated-refreshed-token", account)
        assertEquals(epoch, runtime.accountEpoch)
        assertEquals(3, current.load().size)
        assertEquals(listOf("plan", "plan to go"), current.selectedExpressionDisplays())
        assertTrue(current.mark("word", SelfAssessmentAbility.MEANING_WRITING))
        assertTrue(current.mark("word", SelfAssessmentAbility.MEANING_WRITING))
        assertTrue(current.mark("pattern", SelfAssessmentAbility.PATTERN_SPEAKING))
        assertEquals(current.load(), repository().load())
        assertEquals(setOf(SelfAssessmentAbility.MEANING_WRITING), current.load().single { it.targetId == "word" }.assessedAbilities)
        assertEquals(setOf(SelfAssessmentAbility.PATTERN_SPEAKING), current.load().single { it.targetId == "pattern" }.assessedAbilities)
        assertEquals(2, count("learning_self_assessments"))
        assertNoFormalResult()
    }

    private fun repository() = AndroidTargetSelfAssessmentRepository(context, runtime, "c1", "s1", "LR-02")

    private fun relogin() {
        val epoch = runtime.accountEpoch
        runtime.clear()
        runtime.update("isolated-second-token", account)
        assertNotEquals(epoch, runtime.accountEpoch)
    }

    private fun count(table: String) = database.readableDatabase.rawQuery("select count(*) from $table", null).use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }

    private fun assertNoFormalResult() {
        listOf("vocabulary_items", "pattern_items", "sentence_capabilities", "learning_obligations", "writing_result_drafts")
            .forEach { assertEquals("Self-assessment must not manufacture a formal or verified result in $it", 0, count(it)) }
    }

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
