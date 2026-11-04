package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.ChapterSelectionBoundary
import com.reallanguage.app.feature.learning.domain.ChapterSelectionLoadResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real UUID-private SQLite reads only. Seeded archive rows are boundaries, not AI/learning proof. */
class ChapterSelectionOwnerEpochTest {
    private val runId = UUID.randomUUID().toString()
    private val owner = "selection-owner-$runId"
    private val other = "selection-other-$runId"
    private val sourceId = "selection-source-$runId"
    private val archivedId = "selection-archived-$runId"
    private val remainingId = "selection-remaining-$runId"
    private lateinit var context: IsolatedLearningDatabaseContext
    private lateinit var helper: FormationDatabase
    private lateinit var runtime: SessionRuntime

    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(
            InstrumentationRegistry.getInstrumentation().targetContext, "selection-epoch-$runId",
        )
        runtime = SessionRuntime().apply { update("isolated-before", owner) }
        helper = FormationDatabase(context)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            listOf(owner, other).forEach { account ->
                db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY',?)",
                    arrayOf(account, sourceId, "隔离目录 ${label(account)}"))
                db.execSQL("insert into chapters values(?,?,?,0,'COMPLETED')",
                    arrayOf(account, sourceId, archivedId))
                db.execSQL("insert into chapters values(?,?,?,1,'NOT_STARTED')",
                    arrayOf(account, sourceId, remainingId))
                db.execSQL("insert into chapter_archives values(?,?,?,'{}','2026-09-09')",
                    arrayOf(account, archivedId, "fixture-archive-$account"))
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) " +
                    "values(?,?,?,0,?,?,'FOCUS')", arrayOf(account, remainingId, "focus-$runId",
                    label(account), if (account == owner) "I drink tea." else "I read books."))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @After fun removeOnlyTheFixtureDatabase() {
        if (::helper.isInitialized) helper.close()
        if (::context.isInitialized) {
            val file = context.getDatabasePath("real-language-learning.db").canonicalFile
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            check(file.parentFile == base.cacheDir.canonicalFile)
            check(file.name.startsWith("selection-epoch-$runId-") && file.name.endsWith(".db"))
            check(context.deleteDatabase("real-language-learning.db"))
        }
    }

    @Test fun accountSwitchDuringDatabaseOpenCannotReturnPreviousOwnersFixedText() = runBlocking {
        val before = databaseSnapshot()
        val boundary = OpenBoundaryContext(context) { runtime.update("isolated-other", other) }
        val repository = AndroidChapterSelectionRepository(boundary, runtime)

        val result = repository.loadRemaining(ChapterSelectionBoundary.ArchivedChapter(archivedId))

        assertTrue("The actual SQLiteOpenHelper path must invoke the boundary", boundary.invoked.get())
        assertEquals(other, runtime.accountId)
        assertEquals(before, databaseSnapshot())
        assertEquals(ChapterSelectionLoadResult.Failed, result)
    }

    @Test fun sameAccountReloginDuringDatabaseOpenCannotReturnEarlierSessionDirectory() = runBlocking {
        val before = databaseSnapshot()
        val epoch = runtime.accountEpoch
        val boundary = OpenBoundaryContext(context) { reauthenticate() }
        val repository = AndroidChapterSelectionRepository(boundary, runtime)

        val result = repository.loadRemaining(ChapterSelectionBoundary.FixedSource(sourceId))

        assertTrue(boundary.invoked.get())
        assertNotEquals(epoch, runtime.accountEpoch)
        assertEquals(before, databaseSnapshot())
        assertEquals(ChapterSelectionLoadResult.Failed, result)
    }

    @Test fun oldRepositoryCannotBorrowAnotherAccountsSession() = runBlocking {
        val repository = AndroidChapterSelectionRepository(context, runtime)
        runtime.update("isolated-other", other)
        val before = databaseSnapshot()

        val result = repository.loadRemaining(ChapterSelectionBoundary.FixedSource(sourceId))

        assertEquals(before, databaseSnapshot())
        assertEquals(ChapterSelectionLoadResult.Failed, result)
    }

    @Test fun oldRepositoryCannotReopenAfterSameAccountRelogin() = runBlocking {
        val repository = AndroidChapterSelectionRepository(context, runtime)
        assertOwnedDirectory(repository.loadRemaining(ChapterSelectionBoundary.ArchivedChapter(archivedId)), owner)
        reauthenticate()
        val before = databaseSnapshot()

        val result = repository.loadRemaining(ChapterSelectionBoundary.ArchivedChapter(archivedId))

        assertEquals(before, databaseSnapshot())
        assertEquals(ChapterSelectionLoadResult.Failed, result)
        assertOwnedDirectory(AndroidChapterSelectionRepository(context, runtime)
            .loadRemaining(ChapterSelectionBoundary.ArchivedChapter(archivedId)), owner)
        assertEquals(before, databaseSnapshot())
    }

    @Test fun tokenRefreshDuringDatabaseOpenPreservesOwnedDirectory() = runBlocking {
        val before = databaseSnapshot()
        val epoch = runtime.accountEpoch
        val boundary = OpenBoundaryContext(context) { runtime.update("isolated-refreshed", owner) }
        val repository = AndroidChapterSelectionRepository(boundary, runtime)

        val result = repository.loadRemaining(ChapterSelectionBoundary.ArchivedChapter(archivedId))

        assertTrue(boundary.invoked.get())
        assertEquals(epoch, runtime.accountEpoch)
        assertOwnedDirectory(result, owner)
        assertEquals(before, databaseSnapshot())
    }

    @Test fun freshRepositoryAfterAccountSwitchReadsOnlyCurrentOwnersDirectory() = runBlocking {
        runtime.update("isolated-other", other)
        val before = databaseSnapshot()
        val repository = AndroidChapterSelectionRepository(context, runtime)

        assertOwnedDirectory(repository.loadRemaining(ChapterSelectionBoundary.ArchivedChapter(archivedId)), other)
        assertOwnedDirectory(repository.loadRemaining(ChapterSelectionBoundary.FixedSource(sourceId)), other)
        assertEquals(before, databaseSnapshot())
    }

    @Test fun cancellationDuringDatabaseOpenPropagatesWithoutChangingAnyTables() = runBlocking {
        val before = databaseSnapshot()
        val message = "isolated selection database cancellation"
        val boundary = OpenBoundaryContext(context) { throw CancellationException(message) }
        val repository = AndroidChapterSelectionRepository(boundary, runtime)

        val result = runCatching { repository.loadRemaining(ChapterSelectionBoundary.FixedSource(sourceId)) }

        assertTrue("Cancellation is injected at the actual database-open boundary", boundary.invoked.get())
        assertEquals(before, databaseSnapshot())
        val exception = result.exceptionOrNull()
        assertTrue("Cancellation must propagate rather than become an ordinary Failed result", exception is CancellationException)
        assertEquals(message, exception?.message)
    }

    private fun reauthenticate() {
        val epoch = runtime.accountEpoch
        runtime.clear()
        runtime.update("isolated-relogin", owner)
        assertNotEquals(epoch, runtime.accountEpoch)
    }

    private fun label(account: String) = if (account == owner) "我喝茶。" else "我读书。"

    private fun assertOwnedDirectory(result: ChapterSelectionLoadResult, account: String) {
        assertTrue(result is ChapterSelectionLoadResult.Ready)
        val ready = result as ChapterSelectionLoadResult.Ready
        assertEquals(sourceId, ready.sourceId)
        assertEquals(listOf(remainingId), ready.chapters.map { it.chapterId })
        assertEquals(2, ready.chapters.single().chapterNumber)
        assertEquals(label(account), ready.chapters.single().identityZh)
    }

    private class OpenBoundaryContext(base: Context, private val onOpen: () -> Unit) : ContextWrapper(base) {
        val invoked = AtomicBoolean(false)
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File {
            val file = super.getDatabasePath(name)
            if (invoked.compareAndSet(false, true)) onOpen()
            return file
        }
    }

    /** Exact type/value/rowid comparison for every application table, not just the directory query. */
    private fun databaseSnapshot(): Map<String, TableSnapshot> {
        val db = helper.readableDatabase
        val tables = db.rawQuery("select name from sqlite_master where type='table' " +
            "and name not like 'sqlite_%' and name!='android_metadata' order by name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return tables.associateWith { table ->
            check(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(table))
            db.rawQuery("select rowid,* from $table order by rowid", null).use { cursor ->
                TableSnapshot(cursor.columnNames.toList(), buildList {
                    while (cursor.moveToNext()) add(List(cursor.columnCount) { column ->
                        val type = cursor.getType(column)
                        Cell(type, when (type) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList().toString()
                            else -> cursor.getString(column)
                        })
                    })
                })
            }
        }
    }

    private data class Cell(val type: Int, val value: String?)
    private data class TableSnapshot(val columns: List<String>, val rows: List<List<Cell>>)
}
