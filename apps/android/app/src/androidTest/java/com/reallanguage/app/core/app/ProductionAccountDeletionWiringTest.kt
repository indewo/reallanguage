package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.navigation.AppStartupResult
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.*
import com.reallanguage.app.feature.formation.data.*
import com.reallanguage.app.feature.home.data.*
import com.reallanguage.app.feature.home.domain.AccountDeletionResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Graph, private receipt/files/keys/SQLite; HTTP is isolated to the confirmation boundary. */
class ProductionAccountDeletionWiringTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val account = "delete-graph-$id"
    private val other = "keep-graph-$id"
    private val root = File(base.cacheDir, "delete-graph-$id")
    private val prefs = mutableSetOf<String>()
    private val isolated = IsolatedLearningDatabaseContext(base, "delete-graph")
    private val context = object : ContextWrapper(isolated) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(root, "no-backup")
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = "delete-graph-$id-$name"; prefs += scoped
            return base.getSharedPreferences(scoped, mode)
        }
    }
    private val runtime = SessionRuntime().apply { update("isolated-access", account) }
    private val credentials = MemoryCredentials(StoredSession(account, "isolated-refresh", true))
    private val source = HomeDiaryRoute.SemanticSplit("完整的隔离日记。", entryId = "draft")
    private val journal get() = AndroidAccountDeletionCleanupJournal(context)
    private fun graph(transport: HttpTransport) = ProductionAppGraph(context, runtime, transport, credentials)

    @After fun cleanFixtureOnly() = runBlocking(Dispatchers.IO) {
        listOf(account, other).forEach { AndroidFormationPayloadKeys().deleteAccount(fixedSourceAccountHash(it)) }
        context.deleteDatabase("real-language-learning.db")
        prefs.forEach { base.deleteSharedPreferences(it) }
        check(root.name == "delete-graph-$id"); root.deleteRecursively()
        Unit
    }

    @Test fun confirmedDeletionThroughGraphClearsItsDraftAndKeyWithoutTouchingAnotherAccount() = runBlocking {
        var requests = 0
        val graph = graph(HttpTransport { request ->
            assertEquals("DELETE", request.method); requests++
            HttpResponse(200, "{\"status\":\"DELETED\"}")
        })
        assertNotNull(graph.prepareFormation(source))
        val otherRuntime = SessionRuntime().apply { update("other-isolated", other) }
        val otherRecovery = AndroidFormationRecoveryRuntime(context, otherRuntime)
        assertNotNull(otherRecovery.journal.prepare(source.copy(text = "另一个账号的内容。")))
        assertEquals(AccountDeletionResult.Deleted, graph.accountDeletionRepository().delete("delete-command"))
        assertEquals(1, requests)
        assertNull(credentials.value); assertNull(runtime.accountId)
        assertTrue((journal.read() as AccountDeletionCleanupLoad.Ready).entries.isEmpty())
        withContext(Dispatchers.IO) {
            assertNull(AndroidFormationPayloadKeys(canUseAccount = { true }).existing(fixedSourceAccountHash(account), 1))
            assertNotNull(AndroidFormationPayloadKeys().existing(fixedSourceAccountHash(other), 1))
        }
        assertNotNull(otherRecovery.journal.read(source.entryId))
        assertNull(graph.readFormation(source.entryId))
    }

    @Test fun coldStartFinishesRecordedDeletionBeforeAnyRefreshOrLocalAppEntry() = runBlocking {
        val first = graph(HttpTransport { error("No network during setup") })
        assertNotNull(first.prepareFormation(source))
        assertTrue(journal.record(ConfirmedAccountDeletion(account, "saved-receipt")))
        var requests = 0
        val reopened = graph(HttpTransport { requests++; HttpResponse(503, "{}") })
        assertEquals(AppStartupResult.SignedOut, reopened.resolveStartupForApp())
        assertEquals(0, requests)
        assertNull(credentials.value)
        assertTrue((journal.read() as AccountDeletionCleanupLoad.Ready).entries.isEmpty())
        withContext(Dispatchers.IO) { assertNull(AndroidFormationPayloadKeys(canUseAccount = { true }).existing(fixedSourceAccountHash(account), 1)) }
    }

    @Test fun unsuccessfulServerDeletionKeepsReadableProgressAndNeverRecordsConfirmation() = runBlocking {
        var requests = 0
        val graph = graph(HttpTransport { requests++; HttpResponse(503, "{}") })
        assertNotNull(graph.prepareFormation(source))
        assertEquals(AccountDeletionResult.Failed, graph.accountDeletionRepository().delete("failed-command"))
        assertEquals(1, requests)
        assertEquals(account, credentials.value?.accountId)
        assertEquals(account, runtime.accountId)
        assertNotNull(graph.readFormation(source.entryId))
        assertTrue((journal.read() as AccountDeletionCleanupLoad.Ready).entries.isEmpty())
    }

    private class MemoryCredentials(var value: StoredSession?) : SessionCredentialStore {
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) { value = session }
        override suspend fun clear() { value = null }
    }
}
