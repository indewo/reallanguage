package com.reallanguage.app.feature.home.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.home.domain.*
import com.reallanguage.app.feature.review.data.AndroidTodayReviewRepository
import com.reallanguage.app.feature.review.domain.ReviewQueueResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** All state is an explicit fixture in one UUID database, never the user's database. */
class ReminderTaskFactsPersistenceTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val runtime = SessionRuntime().apply { update("isolated-access", "owner") }
    private var date = "2026-09-08"
    private val db: SQLiteDatabase get() = database.writableDatabase

    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "reminder-facts")
        database = FormationDatabase(context)
        database.writableDatabase
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase("real-language-learning.db")
    }

    @Test fun dueFactsMatchForegroundBeforeAndAfterMaterializationWithoutChangingAnyTable() = runBlocking {
        seedChapter("c1")
        assertReadOnly(ReminderReviewAvailability.NOT_DUE, false)
        seedDays()
        assertReadOnly(ReminderReviewAvailability.AVAILABLE, false)
        val foreground = foreground()
        assertTrue(foreground.chapters.isNotEmpty())
        assertReadOnly(ReminderReviewAvailability.AVAILABLE, false)
        assertEquals(foreground, foreground())
    }

    @Test fun verifiedSharedVocabularyProducesNoTaskWithoutSettlingOrErasingLearningDays() = runBlocking {
        seedDays()
        seedChapter("word")
        seedTarget("word", "WORD", "calm", "平静")
        db.execSQL("update learning_obligations set capability_type='MEANING_WRITING',target_id='t-word'")
        insert("vocabulary_items", "account_id" to "owner", "item_id" to "v-word", "normalized_form" to "calm",
            "semantic_sense_key" to "平静", "display" to "calm", "meaning_zh" to "平静", "status" to "VERIFIED", "verified_success_count" to 1)
        assertReadOnly(ReminderReviewAvailability.NOT_DUE, false)
        assertEquals(3, count("normal_learning_days"))
        assertEquals(0, count("review_cycles"))
        assertTrue(foreground().chapters.isEmpty())
        // Only the explicit foreground read settles the empty cycle.
        assertEquals(0, count("normal_learning_days"))
        assertReadOnly(ReminderReviewAvailability.NOT_DUE, false)
    }

    @Test fun verifiedPatternStillUsesTheExistingThreeSuccessEligibilityRule() = runBlocking {
        seedDays()
        seedChapter("pattern")
        seedTarget("pattern", "PATTERN", "used to", "过去常常")
        db.execSQL("update learning_obligations set capability_type='PATTERN_WRITING',target_id='t-pattern'")
        insert("pattern_items", "account_id" to "owner", "item_id" to "p", "semantic_pattern_key" to "used to|过去常常",
            "structure_en" to "used to", "meaning_zh" to "过去常常", "writing_status" to "VERIFIED",
            "speaking_status" to "VERIFIED", "writing_streak" to 2, "speaking_streak" to 3, "active_validation" to 1)
        assertReadOnly(ReminderReviewAvailability.AVAILABLE, false)
        assertTrue(foreground().chapters.isNotEmpty())
        db.execSQL("update pattern_items set writing_streak=3")
        assertReadOnly(ReminderReviewAvailability.NOT_DUE, false)
        assertTrue(foreground().chapters.isEmpty())
    }

    @Test fun dailyLimitAndNextDayAreFactsNotAnEmptyQueueOrAPastDayReminder() = runBlocking {
        seedDays()
        seedChapter("c1")
        val cycle = foreground().chapters.single().reviewChapterId.let {
            db.rawQuery("select review_cycle_id from review_chapters where review_chapter_id=?", arrayOf(it)).use { c -> c.moveToFirst(); c.getString(0) }
        }
        insert("review_sessions", "account_id" to "owner", "review_session_id" to "fixture-session",
            "review_cycle_id" to cycle, "local_date" to date, "completed_focus_count" to 3)
        assertReadOnly(ReminderReviewAvailability.DEFERRED_BY_DAILY_LIMIT, false)
        val deferred = foreground()
        assertTrue(deferred.chapters.isEmpty()); assertTrue(deferred.deferredByDailyLimit)
        date = "2026-09-09"
        assertReadOnly(ReminderReviewAvailability.AVAILABLE, false)
        assertTrue(foreground().chapters.isNotEmpty())
    }

    @Test fun ordinarySourceAndArchiveFiltersMatchTheExistingHomeRepositoryAndKeepAccountsSeparate() = runBlocking {
        seedChapter("other", owner = "other", status = "NOT_STARTED", sourceType = "AI_SCENE")
        assertReadOnly(ReminderReviewAvailability.NOT_DUE, false)
        seedChapter("recording", status = "NOT_STARTED", sourceType = "QUICK_RECORDING")
        val ordinary = AndroidIncompleteLearningRepository(context, runtime).loadOrdinaryLearning() as IncompleteLearningLoadResult.Ready
        assertEquals(listOf("recording"), ordinary.entries.map { it.learningId })
        assertReadOnly(ReminderReviewAvailability.NOT_DUE, true)
        insert("chapter_archives", "account_id" to "owner", "chapter_id" to "recording", "command_id" to "archive-fixture",
            "summary" to "fixture", "completed_local_date" to date)
        assertTrue((AndroidIncompleteLearningRepository(context, runtime).loadOrdinaryLearning() as IncompleteLearningLoadResult.Ready).entries.isEmpty())
        assertReadOnly(ReminderReviewAvailability.NOT_DUE, false)
        seedChapter("diary", status = "NOT_STARTED")
        insert("learning_checkpoints", "account_id" to "owner", "chapter_id" to "diary", "module_id" to "LR-08", "step_id" to "s-diary")
        assertReadOnly(ReminderReviewAvailability.NOT_DUE, true)
    }

    @Test fun missingFormalEvidenceIsFailureAndDoesNotInventNoTasksOrChangeAnyTable() = runBlocking {
        seedDays()
        seedChapter("c1")
        assertReadOnly(ReminderReviewAvailability.AVAILABLE, false)
        db.delete("sentence_capabilities", "account_id=?", arrayOf("owner"))
        val before = snapshots()
        assertEquals(ReminderTaskFactsResult.Failed, reader().read())
        assertEquals(before, snapshots())
        assertEquals(ReviewQueueResult.Failed, AndroidTodayReviewRepository(context, runtime) { date }.loadTodayQueue(3))
        assertEquals(before, snapshots())
    }

    @Test fun logoutAndIdentityChangeDuringReadRejectTheResultWithoutMutatingLearningState() = runBlocking {
        seedDays()
        seedChapter("c1")
        val old = reader()
        runtime.update("other-access", "other")
        val before = snapshots()
        assertEquals(ReminderTaskFactsResult.Failed, old.read())
        var checks = 0
        val late = object : ReminderLocalIdentity {
            override val accountId = "owner"
            override suspend fun isCurrent(): Boolean = ++checks == 1
        }
        assertEquals(ReminderTaskFactsResult.Failed, AndroidReminderTaskFactsReader(context, late) { date }.read())
        assertTrue(checks >= 2)
        assertEquals(before, snapshots())
    }

    @Test fun anAbsentLearningDatabaseIsNotCreatedByReminderFacts() = runBlocking {
        database.close()
        assertTrue(context.deleteDatabase("real-language-learning.db"))
        val file = context.getDatabasePath("real-language-learning.db")
        assertFalse(file.exists())
        assertEquals(ReminderTaskFactsResult.Ready(ReminderTaskFacts(ReminderReviewAvailability.NOT_DUE, false)), reader().read())
        assertFalse(file.exists())
    }

    @Test fun anUnreadableOldSchemaIsFailureWithoutCreatingOrUpgradingAnyTable() = runBlocking {
        database.close()
        assertTrue(context.deleteDatabase("real-language-learning.db"))
        val file = context.getDatabasePath("real-language-learning.db")
        fun tableDefinitions(database: SQLiteDatabase): List<Pair<String, String>> = database.rawQuery(
            "select name,sql from sqlite_master where type='table' order by name", null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        val beforeTables = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.CREATE_IF_NECESSARY).use {
            it.execSQL("create table fixture_marker(value text)")
            it.execSQL("insert into fixture_marker values ('retained')")
            it.version = 1
            tableDefinitions(it)
        }
        assertEquals(ReminderTaskFactsResult.Failed, reader().read())
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(1, it.version)
            assertEquals(beforeTables, tableDefinitions(it))
            it.rawQuery("select value from fixture_marker", null).use { c -> c.moveToFirst(); assertEquals("retained", c.getString(0)) }
        }
    }

    private fun reader() = AndroidReminderTaskFactsReader(context, RuntimeReminderLocalIdentity(runtime)) { date }
    private suspend fun foreground() = AndroidTodayReviewRepository(context, runtime) { date }.loadTodayQueue(3) as ReviewQueueResult.Ready
    private suspend fun assertReadOnly(review: ReminderReviewAvailability, incomplete: Boolean) {
        val before = snapshots()
        val expected = ReminderTaskFactsResult.Ready(ReminderTaskFacts(review, incomplete))
        assertEquals(expected, reader().read())
        assertEquals(expected, reader().read())
        assertEquals("Every table, including days, cycles, queue, formal results and other accounts, must remain unchanged", before, snapshots())
    }

    private fun seedDays() = listOf("2026-09-01", "2026-09-03", "2026-09-05").forEach {
        insert("normal_learning_days", "account_id" to "owner", "local_date" to it)
    }

    private fun seedChapter(id: String, owner: String = "owner", status: String = "COMPLETED", sourceType: String = "DIARY") {
        insert("formation_flows", "account_id" to owner, "flow_id" to "f-$id", "status" to "FIXED", "source_type" to sourceType, "source_title" to "中性测试来源")
        insert("chapters", "account_id" to owner, "flow_id" to "f-$id", "chapter_id" to id, "chapter_order" to 0, "status" to status)
        insert("fixed_sentences", "account_id" to owner, "chapter_id" to id, "unit_id" to "s-$id", "sentence_order" to 0,
            "text_zh" to "我保持平静。", "text_en" to "I stayed calm.", "role" to "FOCUS")
        if (status == "COMPLETED") {
            insert("sentence_capabilities", "account_id" to owner, "sentence_id" to "s-$id", "capability_type" to "FULL_WRITING", "status" to "LEARNING")
            insert("learning_obligations", "account_id" to owner, "obligation_id" to "o-$id", "chapter_id" to id,
                "sentence_id" to "s-$id", "capability_type" to "FULL_WRITING", "target_id" to "")
        }
    }

    private fun seedTarget(id: String, type: String, display: String, meaning: String) = insert("target_selections",
        "account_id" to "owner", "chapter_id" to id, "target_id" to "t-$id", "unit_id" to "s-$id", "type" to type,
        "display" to display, "explanation_zh" to meaning, "structure_parts" to "", "related_vocabulary" to "")

    private fun insert(table: String, vararg fields: Pair<String, Any>) {
        db.insertOrThrow(table, null, ContentValues().apply { fields.forEach { (key, value) ->
            when (value) { is Int -> put(key, value); is String -> put(key, value); else -> error("Unsupported fixture type") }
        } })
    }

    private fun count(table: String): Int = db.rawQuery("select count(*) from $table", null).use { it.moveToFirst(); it.getInt(0) }

    private fun snapshots(): Map<String, String> {
        val tables = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return tables.associateWith { table ->
            val digest = MessageDigest.getInstance("SHA-256")
            db.rawQuery("select * from \"${table.replace("\"", "\"\"")}\" order by rowid", null).use { c ->
                digest.update(c.columnNames.joinToString("|").toByteArray())
                while (c.moveToNext()) repeat(c.columnCount) { index ->
                    val value = if (c.isNull(index)) "null" else "${c.getType(index)}:${c.getString(index)}"
                    digest.update("${value.length}:$value;".toByteArray())
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
