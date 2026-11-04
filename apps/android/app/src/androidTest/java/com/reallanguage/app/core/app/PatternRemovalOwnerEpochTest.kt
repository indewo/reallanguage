package com.reallanguage.app.core.app

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.knowledge.data.PatternRemovalOwner
import com.reallanguage.app.feature.knowledge.data.removePatternBookKnowledge
import com.reallanguage.app.feature.knowledge.presentation.PatternRemovalAction
import com.reallanguage.app.feature.knowledge.presentation.PatternRemovalViewModel
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual Graph/VM/removal SQL on UUID-owned data. No application records, network, or credentials. */
class PatternRemovalOwnerEpochTest {
    @Test fun oldGraphConfirmationCannotRemoveKnowledgeAfterTheSameAccountLogsInAgain() = fixture {
        val old = removal("old-command")
        val before = rows()
        relogin()
        confirm(old)
        assertTrue("An old confirmation must not borrow the new login's deletion authority", old.uiState.value.removeFailed)
        assertEquals(before, rows())
        assertEquals(0, count("pattern_removal_commands", account))
        assertNoExternalWork()

        val fresh = removal("fresh-command")
        confirm(fresh)
        assertFalse(fresh.uiState.value.removeFailed)
        assertRemovedOnlyOwnerKnowledge()
        assertEquals(1, count("pattern_removal_commands", account))
        assertNoExternalWork()
    }

    @Test fun reloginAfterEntryCheckAtRealDatabaseOpenCannotDeleteOrWriteTheOldCommand() = fixture {
        val old = removal("opening-command")
        val before = rows()
        val oldEpoch = runtime.accountEpoch
        databasePathAction.set { relogin() }
        confirm(old)
        assertEquals("The device must actually execute the database boundary hook", 1, databasePathHits.get())
        assertNull(databasePathAction.get())
        assertNotEquals("The armed hook must cross a real session epoch", oldEpoch, runtime.accountEpoch)
        assertTrue("Database open occurs after entry authorization; the old owner must be rechecked", old.uiState.value.removeFailed)
        assertEquals("No related-table deletion may survive this rejected owner", before, rows())
        assertEquals(0, count("pattern_removal_commands", account))
        assertNoExternalWork()
    }

    @Test fun refreshingTokenWithoutLeavingTheAccountKeepsConfirmedRemovalAndItsExactReplayUsable() = fixture {
        val current = removal("refreshed-command")
        val oldEpoch = runtime.accountEpoch
        runtime.update("isolated-refreshed", account)
        assertEquals(oldEpoch, runtime.accountEpoch)
        confirm(current)
        assertFalse(current.uiState.value.removeFailed)
        assertRemovedOnlyOwnerKnowledge()
        val after = rows()
        val replay = removal("refreshed-command")
        confirm(replay)
        assertFalse(replay.uiState.value.removeFailed)
        assertEquals("Replay must not repeat deletion or add a second command", after, rows())
        assertEquals(1, count("pattern_removal_commands", account))
        assertNoExternalWork()
    }

    @Test fun reauthenticationAfterActualDeletesAndCommandInsertRollsBackEveryTableBeforeCommit() = fixture {
        val capturedOwner = PatternRemovalOwner(runtime)
        val before = rows()
        val oldEpoch = runtime.accountEpoch
        val db = database()
        var reachedWrittenTransaction = false
        val result = runCatching {
            db.removePatternBookKnowledge(account, "pattern", "transaction-command") {
                if (count("pattern_removal_commands", account) == 1) {
                    assertTrue("The callback must run inside the actual uncommitted SQLite transaction", db.inTransaction())
                    assertRemovedOnlyOwnerKnowledge()
                    // Querying this same connection sees the real deletes and command insert,
                    // not a simulated result or a pre-transaction database-open callback.
                    reachedWrittenTransaction = true
                    relogin()
                }
                capturedOwner.isAuthorized()
            }
        }
        assertTrue("The test must reach actual staged writes before revoking authority", reachedWrittenTransaction)
        assertNotEquals(oldEpoch, runtime.accountEpoch)
        assertTrue("An old owner must not acknowledge or commit staged deletion", result.isFailure)
        assertFalse(db.inTransaction())
        assertEquals("All staged knowledge, queue and command changes must roll back", before, rows())
        assertEquals(0, count("pattern_removal_commands", account))
        assertNoExternalWork()
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { fixture.block() } finally {
            withContext(Dispatchers.Main) { fixture.clearViewModels() }
            fixture.close()
        }
    }

    private class Fixture : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val runId = UUID.randomUUID().toString()
        private val isolated = IsolatedLearningDatabaseContext(base, "pattern-owner-$runId")
        private val directory = File(base.cacheDir, "pattern-owner-$runId")
        private val preferenceNames = Collections.synchronizedSet(mutableSetOf<String>())
        val databasePathAction = AtomicReference<(() -> Unit)?>(null)
        val databasePathHits = AtomicInteger()
        private val context = object : ContextWrapper(isolated) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File {
                val file = super.getDatabasePath(name)
                databasePathAction.getAndSet(null)?.let { action -> databasePathHits.incrementAndGet(); action() }
                return file
            }
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = "pattern-owner-$runId-$name"
                preferenceNames.add(isolatedName)
                return base.getSharedPreferences(isolatedName, mode)
            }
        }
        val account = "owner-$runId"
        private val other = "other-$runId"
        val runtime = SessionRuntime().apply { update("isolated-first", account) }
        private val requests = AtomicInteger()
        private val credentials = AtomicInteger()
        private val stores = mutableListOf<ViewModelStore>()
        private val helper = FormationDatabase(context)
        private val db get() = helper.writableDatabase
        private val graph by lazy {
            ProductionAppGraph(context, runtime, HttpTransport {
                requests.incrementAndGet(); error("Local pattern removal must not call HTTP")
            }, object : SessionCredentialStore {
                override suspend fun load(): StoredSession? { credentials.incrementAndGet(); error("No credential reads") }
                override suspend fun save(session: StoredSession) { credentials.incrementAndGet(); error("No credential writes") }
                override suspend fun clear() { credentials.incrementAndGet(); error("No credential clearing") }
            })
        }
        private val removedTables = listOf("pattern_items", "pattern_examples", "pattern_occurrences", "learning_obligations",
            "correction_flags", "review_queue_items", "review_chapter_items")
        private val preservedTables = listOf("formation_flows", "chapters", "fixed_sentences", "target_selections")

        init {
            for (owner in listOf(account, other)) {
                db.execSQL("insert into formation_flows values(?,'flow','FIXED','DIARY','隔离日记')", arrayOf(owner))
                db.execSQL("insert into chapters values(?,'flow','chapter',0,'COMPLETED')", arrayOf(owner))
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,'chapter','sentence',0,'我以前常喝茶。','I used to drink tea.','FOCUS')", arrayOf(owner))
                db.execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary) values(?,'chapter','target','sentence','PATTERN','used to + verb','过去习惯',?,'tea')", arrayOf(owner, "used to\u001fverb"))
                db.insertOrThrow("pattern_items", null, ContentValues().apply {
                    put("account_id", owner); put("item_id", "pattern"); put("semantic_pattern_key", "used to + verb|过去习惯")
                    put("structure_en", "used to + verb"); put("meaning_zh", "过去习惯")
                    put("writing_status", "VERIFIED"); put("speaking_status", "LEARNING")
                    put("writing_streak", 3); put("speaking_streak", 1); put("active_validation", 1)
                    put("representative_scene", "原代表场景"); put("natural_revalidation", 1)
                })
                db.execSQL("insert into pattern_examples values(?,'pattern',0,'I used to read.','我以前常阅读。','I used to read.')", arrayOf(owner))
                db.execSQL("insert into pattern_occurrences values(?,'pattern','chapter','sentence',1)", arrayOf(owner))
                db.execSQL("insert into learning_obligations values(?,'obligation','chapter','sentence','PATTERN_SPEAKING','target')", arrayOf(owner))
                db.execSQL("insert into correction_flags values(?,'obligation')", arrayOf(owner))
                db.execSQL("insert into review_queue_items(account_id,review_cycle_id,queue_item_id,obligation_id,original_chapter_id,sentence_id,priority_rank,entered_at) values(?,'cycle','queue','obligation','chapter','sentence',1,1)", arrayOf(owner))
                db.execSQL("insert into review_chapter_items values(?,'review','queue','sentence','PATTERN_SPEAKING')", arrayOf(owner))
            }
        }

        suspend fun removal(commandId: String): PatternRemovalViewModel = withContext(Dispatchers.Main) {
            val store = ViewModelStore().also(stores::add)
            ViewModelProvider(store, graph.patternRemovalFactory("pattern", commandId))[PatternRemovalViewModel::class.java]
        }
        suspend fun confirm(viewModel: PatternRemovalViewModel) {
            withContext(Dispatchers.Main) { viewModel.onAction(PatternRemovalAction.ConfirmRequested) }
            withTimeout(5_000) { viewModel.uiState.first { !it.removing } }
        }
        fun relogin() {
            val epoch = runtime.accountEpoch
            runtime.clear(); runtime.update("isolated-next", account)
            assertNotEquals(epoch, runtime.accountEpoch)
        }
        fun rows() = (removedTables + preservedTables + "pattern_removal_commands").associateWith { table ->
            db.rawQuery("select rowid,* from $table order by rowid", null).use { cursor -> buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) })
            } }
        }
        fun database() = db
        fun count(table: String, owner: String) = db.rawQuery("select count(*) from $table where account_id=?", arrayOf(owner)).use {
            it.moveToFirst(); it.getInt(0)
        }
        fun assertRemovedOnlyOwnerKnowledge() {
            removedTables.forEach { table -> assertEquals("Old knowledge reference remains in $table", 0, count(table, account)) }
            (removedTables + preservedTables).forEach { table -> assertEquals("Another account's $table changed", 1, count(table, other)) }
            preservedTables.forEach { table -> assertEquals("Historical $table must remain", 1, count(table, account)) }
            assertEquals(0, count("pattern_removal_commands", other))
        }
        fun assertNoExternalWork() { assertEquals(0, requests.get()); assertEquals(0, credentials.get()) }
        fun clearViewModels() { stores.forEach(ViewModelStore::clear) }
        override fun close() {
            helper.close(); context.deleteDatabase("real-language-learning.db")
            synchronized(preferenceNames) { preferenceNames.toList() }.forEach { name ->
                check(name.startsWith("pattern-owner-$runId-")); base.deleteSharedPreferences(name)
            }
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.name == "pattern-owner-$runId")
            check(!directory.exists() || directory.deleteRecursively())
        }
    }
}
