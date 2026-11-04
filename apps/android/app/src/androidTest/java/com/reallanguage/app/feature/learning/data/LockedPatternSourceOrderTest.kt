package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.toJson
import com.reallanguage.app.feature.formation.domain.SourceEvidence
import com.reallanguage.app.feature.formation.domain.isValidFor
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Local locked snapshots only: no recording, provider, credential store or user database. */
class LockedPatternSourceOrderTest {
    private val runId = UUID.randomUUID().toString()
    private val owner = "pattern-order-$runId"
    private val other = "pattern-order-other-$runId"
    private val chapterId = "chapter-$runId"
    private val sentenceId = "focus-$runId"
    private val otherSentenceId = "other-focus-$runId"
    private val firstId = "z-first-$runId"
    private val secondId = "a-second-$runId"
    private val otherTargetId = "0-other-sentence-$runId"
    private val source = "I used to walk, but now I prefer to read."
    private val otherSource = "I would rather read than wait."
    private lateinit var context: IsolatedLearningDatabaseContext
    private lateinit var helper: FormationDatabase
    private lateinit var runtime: SessionRuntime

    @Before fun seedOnlyUuidPrivateFixedSnapshots() {
        context = IsolatedLearningDatabaseContext(
            InstrumentationRegistry.getInstrumentation().targetContext, "locked-pattern-order-$runId",
        )
        helper = FormationDatabase(context)
        runtime = SessionRuntime().apply { update("isolated-not-a-real-session", owner) }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            listOf(owner, other).forEach { account ->
                db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离顺序夹具')",
                    arrayOf(account, "flow-$runId"))
                db.execSQL("insert into chapters values(?,?,?,0,'NOT_STARTED')",
                    arrayOf(account, "flow-$runId", chapterId))
                insertSentence(account, "context-$runId", 0, "I visited a library.", "CONTEXT")
                insertSentence(account, sentenceId, 1, source, "FOCUS")
                insertSentence(account, otherSentenceId, 2, otherSource, "FOCUS")
            }
            // Both identifier order and insertion order deliberately oppose source occurrence order.
            insertTarget(owner, secondId, sentenceId, "prefer to + verb", "表达现在的偏好", source, "prefer to read")
            insertTarget(owner, firstId, sentenceId, "used to + verb", "表达过去的习惯", source, "used to walk")
            insertTarget(owner, otherTargetId, otherSentenceId, "would rather + verb + than + verb", "表达两种选择中的偏好", otherSource, "would rather read than wait")
            insertTarget(other, firstId, sentenceId, "used to + verb", "另一账号已确认说明", source, "used to walk")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @After fun deleteOnlyTheUuidDatabase() {
        if (::helper.isInitialized) helper.close()
        if (::context.isInitialized) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val file = context.getDatabasePath("real-language-learning.db").canonicalFile
            check(file.parentFile == base.cacheDir.canonicalFile)
            check(file.name.startsWith("locked-pattern-order-$runId-") && file.name.endsWith(".db"))
            check(context.deleteDatabase("real-language-learning.db"))
        }
    }

    @Test fun validLockedPatternsFollowSourceOffsetsRatherThanTargetIdsOrInsertionOrder() {
        val before = databaseSnapshot()

        val patterns = AndroidLockedPatternReader(context, runtime).read(chapterId, sentenceId)

        assertEquals(before, databaseSnapshot())
        assertNotNull(patterns)
        assertEquals(listOf(firstId, secondId), patterns!!.map { it.patternId })
        assertEquals(listOf("used to + verb", "prefer to + verb"), patterns.map { it.structure })
        assertEquals(listOf("表达过去的习惯", "表达现在的偏好"), patterns.map { it.explanationZh })
    }

    @Test fun orderingRetainsEveryCurrentTargetWithoutBorrowingOtherFocusOrAccount() {
        val before = databaseSnapshot()
        val reader = AndroidLockedPatternReader(context, runtime)

        val current = requireNotNull(reader.read(chapterId, sentenceId))
        assertEquals(setOf(firstId, secondId), current.map { it.patternId }.toSet())
        assertEquals(2, current.size)
        assertTrue(current.all { it.sourceSentenceId == sentenceId })
        val next = requireNotNull(reader.read(chapterId, otherSentenceId))
        assertEquals(listOf(otherTargetId), next.map { it.patternId })
        assertTrue(next.all { it.sourceSentenceId == otherSentenceId })
        assertNull(reader.read(chapterId, "context-$runId"))
        assertNull(reader.read("missing-$runId", sentenceId))

        val otherRuntime = SessionRuntime().apply { update("isolated-other-session", other) }
        val otherPatterns = requireNotNull(AndroidLockedPatternReader(context, otherRuntime).read(chapterId, sentenceId))
        assertEquals(listOf(firstId), otherPatterns.map { it.patternId })
        assertEquals("另一账号已确认说明", otherPatterns.single().explanationZh)
        assertEquals(before, databaseSnapshot())
    }

    @Test fun explicitDamagedOrWrongSourceEvidenceCannotBecomeAnOrderedValidPlan() {
        val valid = sourceEvidence(source, "used to walk", "used to + verb")
        val invalidValues = listOf(
            "not-json",
            JSONObject(valid).put("startUtf16", 3).put("endUtf16", 15).toString(),
            sourceEvidence(otherSource, "would rather read than wait", "would rather + verb + than + verb"),
        )
        invalidValues.forEach { invalid ->
            helper.writableDatabase.execSQL("update target_selections set source_evidence=? where account_id=? and target_id=?",
                arrayOf(invalid, owner, firstId))
            val before = databaseSnapshot()

            val result = AndroidLockedPatternReader(context, runtime).read(chapterId, sentenceId)

            assertEquals(before, databaseSnapshot())
            assertNull("Explicit damaged evidence is not legacy NULL or permission to drop a selected pattern", result)
        }
    }

    @Test fun unknownLegacyEvidenceIsNeverBackfilledOrRemovedDuringAnOrderRead() {
        helper.writableDatabase.execSQL("update target_selections set source_evidence=null where account_id=? and unit_id=?",
            arrayOf(owner, sentenceId))
        val before = databaseSnapshot()

        val result = AndroidLockedPatternReader(context, runtime).read(chapterId, sentenceId)

        assertEquals(before, databaseSnapshot())
        // Unknown abstract forms have no authoritative source order. This control does not bless
        // target-ID/rowid order or prescribe whether that legacy plan must be unavailable.
        if (result != null) {
            assertEquals(2, result.size)
            assertEquals(setOf(firstId, secondId), result.map { it.patternId }.toSet())
            assertEquals(setOf("used to + verb", "prefer to + verb"), result.map { it.structure }.toSet())
            assertEquals(setOf("表达过去的习惯", "表达现在的偏好"), result.map { it.explanationZh }.toSet())
            assertTrue(result.all { it.sourceSentenceId == sentenceId })
        }
    }

    @Test fun legacyUniqueLiteralsAndVersionOneEvidenceShareTheOriginalSentenceOrder() {
        helper.writableDatabase.execSQL(
            "update target_selections set display=?,source_evidence=null where account_id=? and target_id=?",
            arrayOf("USED TO WALK", owner, firstId),
        )
        val before = databaseSnapshot()

        val patterns = AndroidLockedPatternReader(context, runtime).read(chapterId, sentenceId)

        assertEquals(before, databaseSnapshot())
        assertNotNull("A unique complete literal can recover a temporary position without rewriting its old snapshot", patterns)
        assertEquals(listOf(firstId, secondId), patterns!!.map { it.patternId })
        assertEquals(listOf("USED TO WALK", "prefer to + verb"), patterns.map { it.structure })
        assertEquals(listOf("表达过去的习惯", "表达现在的偏好"), patterns.map { it.explanationZh })
    }

    @Test fun nonliteralOrRepeatedLegacyTargetsCannotBecomeALearningPlan() {
        // The other target retains valid v1 evidence. Neither a grammatical placeholder nor
        // the repeated whole word "I" establishes this old target's unique occurrence.
        listOf("used to + verb", "I").forEach { legacyDisplay ->
            helper.writableDatabase.execSQL(
                "update target_selections set display=?,source_evidence=null where account_id=? and target_id=?",
                arrayOf(legacyDisplay, owner, firstId),
            )
            val before = databaseSnapshot()

            val result = AndroidLockedPatternReader(context, runtime).read(chapterId, sentenceId)

            assertEquals(before, databaseSnapshot())
            assertNull("Unlocatable legacy target must fail reading, not be dropped, guessed or ID-ordered: $legacyDisplay", result)
        }
    }

    @Test fun sameAccountReloginRevokesALateReaderButAFreshReaderStillLoads() {
        val before = databaseSnapshot()
        val oldEpoch = runtime.accountEpoch

        val late = readWhileDatabaseOpenIsPaused {
            runtime.clear()
            runtime.update("isolated-new-login-not-a-real-session", owner)
        }

        assertTrue(runtime.accountEpoch != oldEpoch)
        assertEquals(before, databaseSnapshot())
        assertNull("An in-flight reader cannot borrow the same account's new login authority", late)
        assertEquals(listOf(firstId, secondId), requireNotNull(
            AndroidLockedPatternReader(context, runtime).read(chapterId, sentenceId),
        ).map { it.patternId })
        assertEquals(before, databaseSnapshot())
    }

    @Test fun tokenRefreshDoesNotRevokeALateReader() {
        val before = databaseSnapshot()
        val epoch = runtime.accountEpoch

        val late = readWhileDatabaseOpenIsPaused {
            runtime.update("isolated-refreshed-not-a-real-session", owner)
        }

        assertEquals(epoch, runtime.accountEpoch)
        assertEquals(listOf(firstId, secondId), requireNotNull(late).map { it.patternId })
        assertEquals(before, databaseSnapshot())
    }

    /** Suspend only this test's private database open after the reader captures its owner.
     * No SQLite lock, UI thread, transport, token storage or real credentials are involved. */
    private fun readWhileDatabaseOpenIsPaused(changeSession: () -> Unit): List<LockedSpeakingPattern>? {
        val opening = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val firstOpen = AtomicBoolean(true)
        val gatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File {
                awaitSessionChange()
                return context.getDatabasePath(name)
            }

            private fun awaitSessionChange() {
                if (firstOpen.compareAndSet(true, false)) {
                    opening.countDown()
                    check(resume.await(5, TimeUnit.SECONDS)) { "Private database open was not released" }
                }
            }

            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase {
                awaitSessionChange()
                return context.openOrCreateDatabase(name, mode, factory)
            }

            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
                awaitSessionChange()
                return context.openOrCreateDatabase(name, mode, factory, errorHandler)
            }
        }
        val reader = AndroidLockedPatternReader(gatedContext, runtime)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit(Callable { reader.read(chapterId, sentenceId) })
            assertTrue("The actual reader must reach the private database open", opening.await(5, TimeUnit.SECONDS))
            changeSession()
            resume.countDown()
            return pending.get(5, TimeUnit.SECONDS)
        } finally {
            resume.countDown()
            worker.shutdownNow()
            check(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun insertSentence(account: String, id: String, order: Int, english: String, role: String) {
        helper.writableDatabase.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) " +
            "values(?,?,?,?,?,?,?)", arrayOf<Any>(account, chapterId, id, order, "隔离原句 $order", english, role))
    }

    private fun insertTarget(account: String, id: String, unit: String, display: String, meaning: String,
        english: String, surface: String) {
        helper.writableDatabase.execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display," +
            "explanation_zh,structure_parts,related_vocabulary,source_evidence) values(?,?,?,?,'PATTERN',?,?,'verb','',?)",
            arrayOf(account, chapterId, id, unit, display, meaning, sourceEvidence(english, surface, display)))
    }

    private fun sourceEvidence(english: String, surface: String, normalized: String): String {
        val start = english.indexOf(surface)
        check(start >= 0 && english.indexOf(surface, start + 1) == -1)
        val evidence = SourceEvidence(1, start, start + surface.length, surface, normalized, listOf(surface))
        check(evidence.isValidFor(english))
        return evidence.toJson().toString()
    }

    /** Every application table remains unchanged, including original JSON bytes and rowids. */
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
