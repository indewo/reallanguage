package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.CheckpointSaveResult
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual Graph, checkpoint SQL, and production exit dialog; only UUID-local neutral facts. */
class ProductionLearningCheckpointSessionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val runId = UUID.randomUUID().toString()
    private val account = "checkpoint-owner-$runId"
    private val otherAccount = "checkpoint-other-$runId"
    private val chapterId = "checkpoint-chapter-$runId"
    private val directory = File(base.cacheDir, "checkpoint-session-$runId")
    private val preferenceNames = Collections.synchronizedSet(mutableSetOf<String>())
    private val isolatedDb = IsolatedLearningDatabaseContext(base, "checkpoint-session-$runId")
    private val context = object : ContextWrapper(isolatedDb) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(directory, "private")
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "checkpoint-session-$runId-$name"
            preferenceNames.add(isolatedName)
            return base.getSharedPreferences(isolatedName, mode)
        }
    }
    private val requests = AtomicInteger()
    private val credentialAccesses = AtomicInteger()
    private val showPage = mutableStateOf(false)
    private var mounted = false
    private val saveFailure = "当前步骤保存失败，请重试；学习内容仍然保留。"

    @After fun removeOnlyOwnedUuidData() {
        if (mounted) {
            compose.runOnIdle { showPage.value = false }
            compose.waitForIdle()
        }
        runBlocking(Dispatchers.IO) {
            context.deleteDatabase("real-language-learning.db")
            synchronized(preferenceNames) { preferenceNames.toList() }.forEach { name ->
                check(name.startsWith("checkpoint-session-$runId-"))
                base.deleteSharedPreferences(name)
            }
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            check(directory.name == "checkpoint-session-$runId")
            check(!directory.exists() || directory.deleteRecursively())
        }
    }

    @Test fun sharedGraphRepositoryCreatedBeforeLoginStillServesFreshSessionsAndTokenRefresh() {
        val runtime = SessionRuntime()
        val graph = graph(runtime)
        val shared = graph.learningCheckpointRepository // Deliberately resolve lazy before login.
        awaitIo { seedChapter(account); seedChapter(otherAccount) }
        runtime.update("isolated-session-a", account)
        val first = OrdinaryLearningCheckpoint(chapterId, "LR-03", "s")
        assertEquals(CheckpointSaveResult.Saved, awaitIo { shared.save(first) })
        assertEquals(first, awaitIo { shared.read(chapterId) })

        runtime.clear()
        runtime.update("isolated-session-a-again", account)
        val second = OrdinaryLearningCheckpoint(chapterId, "LR-04", "s")
        assertSame(shared, graph.learningCheckpointRepository)
        assertEquals(CheckpointSaveResult.Saved, awaitIo { graph.learningCheckpointRepository.save(second) })
        runtime.update("isolated-refreshed-token", account)
        assertEquals(second, awaitIo { shared.read(chapterId) })

        runtime.clear()
        runtime.update("isolated-session-b", otherAccount)
        val third = OrdinaryLearningCheckpoint(chapterId, "LR-06", "s")
        assertEquals(CheckpointSaveResult.Saved, awaitIo { shared.save(third) })
        assertEquals(third, awaitIo { shared.read(chapterId) })
        assertEquals(second, awaitIo { savedCheckpoint(account) })
        assertNoExternalWork()
    }

    @Test fun actualOldExitDialogCannotOverwriteReauthenticatedSessionsCheckpoint() {
        val runtime = SessionRuntime().apply { update("isolated-session-a", account) }
        val graph = graph(runtime)
        awaitIo { seedChapter(account) }
        mountHandwriting(graph)
        openExit()
        runtime.clear()
        runtime.update("isolated-session-a-again", account)
        val newCheckpoint = OrdinaryLearningCheckpoint(chapterId, "LR-06", "s")
        assertEquals(CheckpointSaveResult.Saved, awaitIo { graph.learningCheckpointRepository.save(newCheckpoint) })

        compose.onNodeWithTag("learning-exit-pause").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(saveFailure).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("learning-exit-pause").fetchSemanticsNodes().isEmpty()
        }
        assertEquals("An old page must not acquire the new login's permission to replace its checkpoint",
            newCheckpoint, awaitIo { savedCheckpoint(account) })
        compose.onNodeWithText(saveFailure).assertIsDisplayed()
        compose.onNodeWithTag("learning-exit-continue").assertIsDisplayed().assertIsEnabled()
        assertNoExternalWork()
    }

    @Test fun closingAndReopeningActualExitDialogDoesNotReuseItsConsumedFailedViewModel() {
        val runtime = SessionRuntime().apply { update("isolated-session-a", account) }
        val graph = graph(runtime)
        awaitIo { seedChapter(account) }
        mountHandwriting(graph)
        openExit()
        awaitIo {
            FormationDatabase(context).use { helper ->
                helper.writableDatabase.execSQL("create trigger reject_checkpoint before insert on learning_checkpoints " +
                    "begin select raise(ABORT,'isolated checkpoint failure'); end")
            }
        }
        compose.onNodeWithTag("learning-exit-pause").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(saveFailure).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(saveFailure).assertIsDisplayed()
        compose.onNodeWithTag("learning-exit-continue").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("learning-exit-pause").fetchSemanticsNodes().isEmpty() }
        awaitIo { FormationDatabase(context).use { it.writableDatabase.execSQL("drop trigger reject_checkpoint") } }

        openExit()
        compose.onNodeWithText(saveFailure).assertDoesNotExist()
        compose.onNodeWithTag("learning-exit-pause").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("learning-exit-pause").fetchSemanticsNodes().isEmpty() }
        assertEquals(OrdinaryLearningCheckpoint(chapterId, "LR-04", "s"), awaitIo { savedCheckpoint(account) })
        assertNoExternalWork()
    }

    private fun mountHandwriting(graph: ProductionAppGraph) {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        val chapter = FixedLearningChapter(chapterId, 1,
            listOf(FixedLearningSentence("s", "I drink green tea.", "我喝绿茶。", true)))
        showPage.value = true
        compose.setContent { RlTheme {
            if (showPage.value) HomeProductionFlow(graph,
                initialRoute = HomeDiaryRoute.Handwriting(chapter, "s", "说明喝绿茶。", emptyList()))
        } }
        mounted = true
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("handwriting-workspace").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("退出学习").assertIsDisplayed()
        assertEquals(OrdinaryLearningCheckpoint(chapterId, "LR-04", "s"), awaitIo { savedCheckpoint(account) })
    }

    private fun openExit() {
        compose.onNodeWithText("退出学习").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("learning-exit-pause").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("learning-exit-pause").assertIsDisplayed()
    }

    private fun seedChapter(owner: String) = FormationDatabase(context).use { helper ->
        val db = helper.writableDatabase
        db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离检查点')", arrayOf(owner, "flow-$runId"))
        db.execSQL("insert into chapters values(?,?,?,0,'IN_PROGRESS')", arrayOf(owner, "flow-$runId", chapterId))
        db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) " +
            "values(?,?,'s',0,'我喝绿茶。','I drink green tea.','FOCUS')", arrayOf(owner, chapterId))
    }

    private fun savedCheckpoint(owner: String): OrdinaryLearningCheckpoint? = FormationDatabase(context).use { helper ->
        helper.readableDatabase.rawQuery("select module_id,step_id from learning_checkpoints where account_id=? and chapter_id=?",
            arrayOf(owner, chapterId)).use { cursor ->
            if (cursor.moveToFirst()) OrdinaryLearningCheckpoint(chapterId, cursor.getString(0), cursor.getString(1)) else null
        }
    }

    private fun graph(runtime: SessionRuntime) = ProductionAppGraph(context, runtime, HttpTransport {
        requests.incrementAndGet()
        error("Checkpoint fixture must not make HTTP or AI requests")
    }, object : SessionCredentialStore {
        override suspend fun load(): StoredSession? { credentialAccesses.incrementAndGet(); error("No credential read") }
        override suspend fun save(session: StoredSession) { credentialAccesses.incrementAndGet(); error("No credential write") }
        override suspend fun clear() { credentialAccesses.incrementAndGet(); error("No credential clear") }
    })

    private fun assertNoExternalWork() {
        assertEquals("No HTTP or AI", 0, requests.get())
        assertEquals("No user credentials", 0, credentialAccesses.get())
    }

    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try {
            compose.waitUntil(15_000) { result.get() != null }
            return requireNotNull(result.get()).getOrThrow()
        } finally { job.cancel(); scope.cancel() }
    }
}
