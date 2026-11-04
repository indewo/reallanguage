package com.reallanguage.app.feature.home.data

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.home.domain.IncompleteLearningLoadResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class IncompleteLearningIdentityPersistenceTest {
    private lateinit var isolated: IsolatedLearningDatabaseContext
    private lateinit var owner: String
    private lateinit var other: String
    private lateinit var runtime: SessionRuntime

    @Before fun setUp() {
        isolated = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "incomplete-identity")
        owner = "incomplete-owner-${UUID.randomUUID()}"
        other = "incomplete-other-${UUID.randomUUID()}"
        runtime = SessionRuntime().apply { update("isolated-access", owner) }
        FormationDatabase(isolated).use { helper ->
            listOf(owner, other).forEach { account ->
                helper.writableDatabase.insertOrThrow("formation_flows", null, values(
                    "account_id" to account, "flow_id" to "flow", "status" to "FIXED",
                    "source_type" to "DIARY", "source_title" to "isolated-title:$account",
                ))
                helper.writableDatabase.insertOrThrow("chapters", null, values(
                    "account_id" to account, "flow_id" to "flow", "chapter_id" to "chapter:$account",
                    "chapter_order" to 0, "status" to "NOT_STARTED",
                ))
            }
        }
    }

    @After fun tearDown() { isolated.deleteDatabase("real-language-learning.db") }

    @Test fun accountSwitchDuringRealDatabaseOpenDoesNotDeliverPreviousOwnersEntries() = runBlocking {
        val context = OpenBoundaryContext(isolated) { runtime.update("other-access", other) }
        val result = AndroidIncompleteLearningRepository(context, runtime).loadOrdinaryLearning()
        assertTrue("The real SQLite read boundary must have been reached", context.invoked.get())
        assertEquals(IncompleteLearningLoadResult.Failed, result)
        assertEquals(other, runtime.accountId)
    }

    @Test fun logoutThenSameAccountLoginInvalidatesTheEarlierListRead() = runBlocking {
        val context = OpenBoundaryContext(isolated) { runtime.clear(); runtime.update("new-session-access", owner) }
        val result = AndroidIncompleteLearningRepository(context, runtime).loadOrdinaryLearning()
        assertTrue(context.invoked.get())
        assertEquals(IncompleteLearningLoadResult.Failed, result)
    }

    @Test fun ordinarySameAccountTokenRefreshDoesNotInvalidateOwnedRows() = runBlocking {
        val context = OpenBoundaryContext(isolated) { runtime.update("refreshed-access", owner) }
        val result = AndroidIncompleteLearningRepository(context, runtime).loadOrdinaryLearning()
        assertTrue(context.invoked.get())
        assertOwnedEntry(result)
    }

    @Test fun lowerDatabaseCancellationPropagatesInsteadOfBecomingAnOrdinaryFailure() = runBlocking {
        val context = OpenBoundaryContext(isolated) { throw CancellationException("isolated database cancellation") }
        val result = runCatching { AndroidIncompleteLearningRepository(context, runtime).loadOrdinaryLearning() }
        assertTrue(context.invoked.get())
        assertTrue("Cancellation must propagate, not return Failed", result.exceptionOrNull() is CancellationException)
    }

    @Test fun realSqlFailureStillReturnsFailureWithoutAnInventedEmptySuccess() = runBlocking {
        FormationDatabase(isolated).use { it.writableDatabase.execSQL("drop table formation_flows") }
        assertEquals(IncompleteLearningLoadResult.Failed, AndroidIncompleteLearningRepository(isolated, runtime).loadOrdinaryLearning())
    }

    @Test fun unchangedAccountReadsOnlyItsOwnRealPersistedSourceAndChapter() = runBlocking {
        assertOwnedEntry(AndroidIncompleteLearningRepository(isolated, runtime).loadOrdinaryLearning())
    }

    private fun assertOwnedEntry(result: IncompleteLearningLoadResult) {
        assertTrue(result is IncompleteLearningLoadResult.Ready)
        val entry = (result as IncompleteLearningLoadResult.Ready).entries.single()
        assertEquals("chapter:$owner", entry.learningId)
        assertEquals("isolated-title:$owner", entry.sourceLabelZh)
        assertEquals("LR-01", entry.savedStepId)
    }

    /** Only coordinates a deterministic account transition; all rows/cursors are real SQLite. */
    private class OpenBoundaryContext(base: Context, private val onOpen: () -> Unit) : ContextWrapper(base) {
        val invoked = AtomicBoolean(false)
        override fun getApplicationContext(): Context = this
        private fun boundary() { if (invoked.compareAndSet(false, true)) onOpen() }
        override fun getDatabasePath(name: String): File { boundary(); return super.getDatabasePath(name) }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase {
            boundary(); return super.openOrCreateDatabase(name, mode, factory)
        }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
            boundary(); return super.openOrCreateDatabase(name, mode, factory, errorHandler)
        }
    }

    private fun values(vararg fields: Pair<String, Any>) = ContentValues().apply {
        fields.forEach { (key, value) -> when (value) { is String -> put(key, value); is Int -> put(key, value) } }
    }
}
