package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.ChapterSelectionBoundary
import com.reallanguage.app.feature.learning.domain.ChapterSelectionLoadResult
import com.reallanguage.app.feature.learning.domain.ChapterSelectionRepository
import com.reallanguage.app.feature.learning.presentation.ChapterSelectionViewModel
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

/**
 * Actual LR01 -> exit -> abandon -> LR12 -> LR01 -> abandon -> LR12 navigation.
 * Only preaccepted fixed-content fixtures are seeded, in a UUID-private database.
 * No learning completion, speech result, AI response, or user database is fabricated.
 */
class ProductionChapterSelectionReentryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val runId = UUID.randomUUID().toString()
    private val account = "chapter-selection-$runId"
    private val otherAccount = "chapter-selection-other-$runId"
    private val sourceId = "source-$runId"
    private val chapterIds = (1..3).map { "selection-$runId-c$it" }
    private val directory = File(base.cacheDir, "chapter-selection-$runId")
    private val preferenceNames = Collections.synchronizedSet(mutableSetOf<String>())
    private val isolatedDb = IsolatedLearningDatabaseContext(base, "chapter-selection-$runId")
    private val context = object : ContextWrapper(isolatedDb) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(directory, "private")
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "chapter-selection-$runId-$name"
            preferenceNames.add(isolatedName)
            return base.getSharedPreferences(isolatedName, mode)
        }
    }
    private val requests = AtomicInteger()
    private val credentialAccesses = AtomicInteger()
    private val mountedGraph = mutableStateOf<ProductionAppGraph?>(null)
    private var mounted = false

    @After fun removeOnlyOwnedUuidFixture() {
        if (mounted) {
            compose.runOnIdle { mountedGraph.value = null }
            compose.waitForIdle()
        }
        runBlocking(Dispatchers.IO) {
            context.deleteDatabase("real-language-learning.db")
            synchronized(preferenceNames) { preferenceNames.toList() }.forEach { name ->
                check(name.startsWith("chapter-selection-$runId-"))
                base.deleteSharedPreferences(name)
            }
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            check(directory.name == "chapter-selection-$runId")
            check(!directory.exists() || directory.deleteRecursively())
        }
    }

    @Test(timeout = 90_000)
    fun abandoningTwoChaptersReloadsTheSameSourceDirectoryWithoutOfferingTheDeletedChapter() {
        awaitIo { seedFixedSource(account); seedFixedSource(otherAccount) }
        val runtime = SessionRuntime().apply { update("isolated-not-a-real-token", account) }
        mountedGraph.value = graph(runtime)
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.setContent { RlTheme { RlPaperBackground {
            mountedGraph.value?.let { graph ->
                // The initial route is the first chapter, not either expected directory.
                HomeProductionFlow(graph, initialRoute = HomeDiaryRoute.ChapterLearning(chapterIds[0]))
            }
        } } }
        mounted = true

        abandonVisibleChapter(1)
        waitForTag("chapter-selection-start-${chapterIds[1]}")
        compose.onNodeWithTag("chapter-selection-start-${chapterIds[1]}").assertIsDisplayed()
        compose.onNodeWithTag("chapter-selection-start-${chapterIds[2]}").assertIsDisplayed()
        assertEquals(chapterIds.drop(1), awaitIo { remainingChapterIds(account) })
        assertEquals(chapterIds, awaitIo { remainingChapterIds(otherAccount) })

        compose.onNodeWithTag("chapter-selection-start-${chapterIds[1]}").performClick()
        abandonVisibleChapter(2)
        waitForTag("chapter-selection-start-${chapterIds[2]}")

        // Establish successful real deletion before asserting the directory reload.
        assertEquals(listOf(chapterIds[2]), awaitIo { remainingChapterIds(account) })
        assertEquals(chapterIds, awaitIo { remainingChapterIds(otherAccount) })
        assertEquals(2, awaitIo { abandonmentCount() })
        assertNoExternalWork()
        compose.onNodeWithTag("chapter-selection-start-${chapterIds[1]}").assertDoesNotExist()
        compose.onNodeWithText("这份日记还有 1 个未开始章节").assertIsDisplayed()

        // The real remaining choice must still lead to its own chapter, not a failed loader.
        compose.onNodeWithTag("chapter-selection-start-${chapterIds[2]}").assertIsDisplayed().performClick()
        waitForText("第 3 章 · 共同学习")
        compose.onNodeWithText("第 3 章 · 共同学习").assertIsDisplayed()
        compose.onNodeWithText("无法读取已固定章节").assertDoesNotExist()
        assertEquals(listOf(chapterIds[2]), awaitIo { remainingChapterIds(account) })
        assertNoExternalWork()
    }

    /** Lifecycle-only control; the other method proves the real directory and SQL path. */
    @Test fun normalRecompositionAndTokenRefreshDoNotReloadButNewOwnerAndGraphDo() {
        val runtime = SessionRuntime().apply { update("isolated-session", account) }
        val redraw = mutableStateOf(0)
        val reads = AtomicInteger()
        val currentModel = AtomicReference<ChapterSelectionViewModel>()
        val boundary = ChapterSelectionBoundary.FixedSource(sourceId)
        val factory = viewModelFactory { initializer {
            ChapterSelectionViewModel(boundary, ChapterSelectionRepository {
                reads.incrementAndGet()
                ChapterSelectionLoadResult.Ready(sourceId, emptyList())
            })
        } }
        mountedGraph.value = graph(runtime)
        compose.setContent { RlTheme {
            Text("Isolated redraw ${redraw.value}")
            mountedGraph.value?.let { graph ->
                val owner = graph.rememberChapterSelectionEntryOwner(boundary)
                val model: ChapterSelectionViewModel = viewModel(
                    viewModelStoreOwner = owner, key = "selection-control", factory = factory,
                )
                SideEffect { currentModel.set(model) }
            }
        } }
        mounted = true
        compose.waitUntil(5_000) { reads.get() == 1 && currentModel.get() != null }
        val first = currentModel.get()
        compose.runOnIdle { redraw.value += 1 }
        compose.waitForIdle()
        assertSame(first, currentModel.get())
        assertEquals(1, reads.get())
        compose.runOnIdle { runtime.update("isolated-refreshed-token", account) }
        compose.waitForIdle()
        assertSame(first, currentModel.get())
        assertEquals("Token refresh must not re-read the directory", 1, reads.get())

        compose.runOnIdle { runtime.clear(); runtime.update("isolated-new-login", account) }
        compose.waitUntil(5_000) { reads.get() == 2 }
        val second = currentModel.get()
        assertNotSame(first, second)
        compose.runOnIdle { mountedGraph.value = graph(runtime) }
        compose.waitUntil(5_000) { reads.get() == 3 }
        assertNotSame(second, currentModel.get())
        assertNoExternalWork()
    }

    private fun abandonVisibleChapter(number: Int) {
        waitForText("第 $number 章 · 共同学习")
        compose.onNodeWithText("退出学习").assertIsDisplayed().performClick()
        waitForTag("learning-exit-abandon")
        compose.onNodeWithTag("learning-exit-abandon").assertIsDisplayed().performClick()
        waitForTag("chapter-abandon-confirm")
        compose.onNodeWithTag("chapter-abandon-confirm").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("chapter-abandon-confirm").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun seedFixedSource(owner: String) = FormationDatabase(context).use { helper ->
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离章节目录')", arrayOf(owner, sourceId))
            chapterIds.forEachIndexed { index, id ->
                db.execSQL("insert into chapters values(?,?,?,?,'NOT_STARTED')", arrayOf<Any>(owner, sourceId, id, index))
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) " +
                    "values(?,?,?,0,?,?,'FOCUS')", arrayOf(owner, id, "sentence-${index + 1}",
                    listOf("我喝茶。", "我读书。", "我散步。")[index],
                    listOf("I drink tea.", "I read books.", "I take a walk.")[index]))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun remainingChapterIds(owner: String): List<String> = FormationDatabase(context).use { helper ->
        helper.readableDatabase.rawQuery(
            "select chapter_id from chapters where account_id=? and flow_id=? order by chapter_order",
            arrayOf(owner, sourceId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }

    private fun abandonmentCount(): Int = FormationDatabase(context).use { helper ->
        helper.readableDatabase.rawQuery("select count(*) from chapter_abandon_commands where account_id=?",
            arrayOf(account)).use { it.moveToFirst(); it.getInt(0) }
    }

    private fun graph(runtime: SessionRuntime) = ProductionAppGraph(context, runtime, HttpTransport {
        requests.incrementAndGet()
        error("Isolated chapter-selection navigation must not call HTTP or AI")
    }, object : SessionCredentialStore {
        override suspend fun load(): StoredSession? { credentialAccesses.incrementAndGet(); error("No credential read") }
        override suspend fun save(session: StoredSession) { credentialAccesses.incrementAndGet(); error("No credential write") }
        override suspend fun clear() { credentialAccesses.incrementAndGet(); error("No credential clear") }
    })

    private fun assertNoExternalWork() {
        assertEquals("No HTTP or AI", 0, requests.get())
        assertEquals("No user credentials", 0, credentialAccesses.get())
    }

    private fun waitForTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
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
