package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Bitmap
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.AesGcmFixedSourcePayloadCodec
import com.reallanguage.app.feature.formation.data.AndroidContentFixRepository
import com.reallanguage.app.feature.formation.data.AndroidFixedSourceRepository
import com.reallanguage.app.feature.formation.data.FixedSourceTestKeys
import com.reallanguage.app.feature.formation.domain.ContentFixRequest
import com.reallanguage.app.feature.formation.domain.ContentFixResult
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import com.reallanguage.app.feature.formation.domain.FixedSourceLoad
import com.reallanguage.app.feature.formation.domain.FixedSourceUnit
import com.reallanguage.app.feature.learning.data.AndroidChapterArchiveRepository
import com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore
import com.reallanguage.app.feature.learning.domain.ChapterArchiveResult
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import com.reallanguage.app.testing.withExplicitSource
import java.io.File
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** ER-01 returns through the real Home flow, without permitting offline learning.
 * LR-10/13 require visible exit choices and a separate, cancellable destructive confirmation.
 * Only UUID-private SQLite/prefs and memory HTTP/credentials are used; no AI, sound, or server. */
class ProductionLearningConnectionReturnTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val prefix = "learning-connection-return-$id"
    private val directory = File(base.cacheDir, prefix)
    private val privateDb = IsolatedLearningDatabaseContext(base, prefix)
    private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
    private val context = object : ContextWrapper(privateDb) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$prefix-$name"
            preferences.add(isolated)
            return base.getSharedPreferences(isolated, mode)
        }
    }
    private val account = "learning-owner-$id"
    private val otherAccount = "learning-other-$id"
    private val chapterId = "learning-chapter-$id-1"
    private val chapter = FixedLearningChapter(chapterId, 1,
        listOf(FixedLearningSentence("s", ENGLISH, CHINESE, true)))
    private val runtime = SessionRuntime().apply { update("ISOLATED_ACCESS", account) }
    private val credentials = MemoryCredentials(StoredSession(account, "ISOLATED_REFRESH", true))
    private val calls = CopyOnWriteArrayList<String>()
    private val mounted = mutableStateOf(false)
    private lateinit var graph: ProductionAppGraph

    @Before fun prepareOnlyPrivateDataAndRealWindow() {
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.mkdir())
        val database = privateDb.getDatabasePath(DATABASE).canonicalFile
        check(database.parentFile == base.cacheDir.canonicalFile && database.name.startsWith("$prefix-") && !database.exists())
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        assertEquals(1f, compose.activity.resources.configuration.fontScale, .01f)
        awaitIo { seedChapter(account); seedChapter(otherAccount) }
        graph = ProductionAppGraph(context, runtime, HttpTransport { request ->
            val call = "${request.method} ${request.path}"
            calls += call
            when (call) {
                HEALTH -> HttpResponse(200, """{"status":"ok","service":"api","requestId":"isolated-learning-return"}""")
                REFRESH -> HttpResponse(200, """{"status":"AUTHENTICATED","accountId":"$account","accessToken":"ISOLATED_ROTATED_ACCESS","refreshToken":"ISOLATED_ROTATED_REFRESH","settingsCompleted":true}""")
                else -> error("The isolated local-learning fixture forbids business HTTP")
            }
        }, credentials)
    }

    @After fun disposeThenRemoveOnlyThisUuidStorage() {
        compose.runOnIdle { mounted.value = false; compose.activity.viewModelStore.clear() }
        compose.waitForIdle()
        val database = privateDb.getDatabasePath(DATABASE).canonicalFile
        check(database.parentFile == base.cacheDir.canonicalFile && database.name.startsWith("$prefix-") && database.extension == "db")
        check(privateDb.deleteDatabase(DATABASE) || !database.exists())
        synchronized(preferences) { preferences.toList() }.forEach { name ->
            check(name.startsWith("$prefix-")); base.deleteSharedPreferences(name)
        }
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.name == prefix)
        check(!directory.exists() || directory.deleteRecursively())
    }

    @Test fun offlineChapterExitShowsTheSeparateAbandonConfirmationAndCancelPreservesAllData() {
        mount(HomeDiaryRoute.ChapterLearning(chapterId), "chapter_entry_sentences")
        val before = awaitIo { allRows() }
        val checkpoint = OrdinaryLearningCheckpoint(chapterId, "LR-01", "entry")
        assertEquals(checkpoint, awaitIo { graph.learningCheckpointRepository.read(chapterId) })
        loseConnection()
        tap("connection-error-return")
        requireVisibleExitChoices()
        tap("learning-exit-abandon")
        compose.waitForIdle()
        // Snapshot before the expected visibility RED: a return is never implicit consent.
        assertEquals("Opening confirmation must not delete or settle any account's rows", before, awaitIo { allRows() })
        capture("lr13-confirmation")
        compose.onNodeWithTag("chapter-abandon-sheet").assertIsDisplayed()
        compose.onNodeWithTag("chapter-abandon-confirm").assertIsDisplayed().assertIsEnabled()
        assertPrivateChapterHidden()
        assertTrue(calls.isEmpty())
        // Real button dispatch, not direct VM action; never press ConfirmAbandon in this test.
        compose.onNode(hasText("继续学习") and hasClickAction()).assertIsDisplayed().assertIsEnabled()
            .performTouchInput { click(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("chapter-abandon-sheet").assertDoesNotExist()
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        assertPrivateChapterHidden()
        assertEquals("Cancel must preserve every product table, including foreign-account data", before, awaitIo { allRows() })
        reconnectExplicitly()
        compose.onNodeWithTag("chapter_entry_sentences").assertIsDisplayed()
        assertEquals(checkpoint, awaitIo { graph.learningCheckpointRepository.read(chapterId) })
        assertEquals(before, awaitIo { allRows() })
    }

    @Test fun offlineHandwritingReturnOffersVisibleExitInsteadOfCyclingThroughHiddenCompletedPages() {
        mount(HomeDiaryRoute.Handwriting(chapter, "s", "说明喝绿茶。", emptyList()), "handwriting-workspace")
        // Actual user acknowledgement persists the first copy. This is not handwriting recognition.
        tap("handwriting-finish")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("第 2 次 / 共 2 次").fetchSemanticsNodes().isNotEmpty() }
        val expectedProgress = mapOf("version" to "1", "confirmedCopyCount" to "1", "copyInProgress" to "true")
        assertEquals(expectedProgress, awaitIo { progressReader().read() })
        val before = awaitIo { allRows() }
        loseConnection()
        tap("connection-error-return")
        compose.waitForIdle()
        capture("lr04-exit")
        // A single Return must expose the existing exit decision. Repeated blind returns,
        // reconnecting first, or clicking a hidden page's Exit would mask the routing bug.
        requireVisibleExitChoices()
        assertEquals(before, awaitIo { allRows() })
        assertTrue(calls.isEmpty())
        tap("learning-exit-continue")
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        compose.onNodeWithTag("handwriting-workspace").assertDoesNotExist()
        assertPrivateChapterHidden()
        reconnectExplicitly()
        compose.onNodeWithTag("handwriting-workspace").assertIsDisplayed()
        compose.onNodeWithText("第 2 次 / 共 2 次").assertIsDisplayed()
        assertEquals(OrdinaryLearningCheckpoint(chapterId, "LR-04", "s"), awaitIo { graph.learningCheckpointRepository.read(chapterId) })
        assertEquals("A fresh SQL reader must retain the same confirmed copy count", expectedProgress, awaitIo { progressReader().read() })
        assertEquals(before, awaitIo { allRows() })
    }

    @Test fun confirmingOfflineSecondChapterAbandonReturnsHomeAndPreservesArchivedFirstSource() =
        confirmSecondChapterAbandon(hasThirdChapter = false)

    @Test fun confirmingOfflineSecondChapterAbandonOffersOnlyTheUnstartedThirdChapter() =
        confirmSecondChapterAbandon(hasThirdChapter = true)

    private fun confirmSecondChapterAbandon(hasThirdChapter: Boolean) {
        val fixed = awaitIo { seedAbandonSource(if (hasThirdChapter) 3 else 2) }
        val second = fixed.chapters[1]
        val existingCheckpoint = requireNotNull(awaitIo { graph.learningCheckpointRepository.read(second) })
        assertEquals(second, existingCheckpoint.chapterId)
        assertEquals("LR-04", existingCheckpoint.moduleId)
        mount(HomeDiaryRoute.ChapterLearning(second), "chapter_entry_sentences")
        val before = awaitIo { abandonRows() }
        // Looking at chapter entry must not overwrite previously saved module progress.
        assertEquals(existingCheckpoint,
            awaitIo { graph.learningCheckpointRepository.read(second) })
        loseConnection()
        tap("connection-error-return")
        requireVisibleExitChoices()
        tap("learning-exit-abandon")
        compose.onNodeWithTag("chapter-abandon-sheet").assertIsDisplayed()
        assertPrivateChapterHidden()
        assertEquals("Opening the destructive confirmation still changes no saved row", before, awaitIo { abandonRows() })
        capture(if (hasThirdChapter) "lr13-delete-to-selection" else "lr13-delete-to-home")
        tap("chapter-abandon-confirm")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("chapter-abandon-sheet").fetchSemanticsNodes().isEmpty() }
        // Confirming a local delete does not grant network access or expose the destination behind ER-01.
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        assertTrue(calls.isEmpty())
        assertAbandonDelta(fixed, before, hasThirdChapter)

        reconnectExplicitly()
        if (hasThirdChapter) {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("chapter-selection-start-${fixed.chapters[2]}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chapter-selection-list").assertIsDisplayed()
            compose.onNodeWithTag("chapter-selection-start-${fixed.chapters[2]}").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("chapter-selection-start-${fixed.chapters[0]}").assertDoesNotExist()
            compose.onNodeWithTag("chapter-selection-start-$second").assertDoesNotExist()
            compose.onNodeWithTag("chapter_entry_sentences").assertDoesNotExist()
        } else {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("home-diary-card").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("home-diary-card").assertIsDisplayed()
            compose.onNodeWithTag("chapter-selection-list").assertDoesNotExist()
        }
        assertAbandonDelta(fixed, before, hasThirdChapter)
    }

    private suspend fun seedAbandonSource(chapterCount: Int): AbandonSource {
        val flow = "confirmed-abandon-flow-$id"
        val units = (1..chapterCount).map { index -> FixedSourceUnit("abandon-unit-$index-$id", index - 1,
            when (index) { 1 -> "我感到平静。"; 2 -> CHINESE; else -> "我们明天散步。" }) }
        val outside = FixedSourceUnit("outside-$id", chapterCount, "这句原文未进入学习章节。")
        val request = ContentFixRequest(flow, "confirmed-fix-$id", units.mapIndexed { index, unit ->
            EnglishChapterDraft("draft-$index", listOf(EnglishSentenceDraft(unit.unitId, unit.textZh, true,
                listOf(EnglishCandidate("candidate-$index", when (index) {
                    0 -> "I felt calm."; 1 -> ENGLISH; else -> "We will walk tomorrow."
                })))))
        }, units.mapIndexed { index, unit -> unit.unitId to "candidate-$index" }.toMap(), emptyList(), emptySet())
            .withExplicitSource((units + outside).joinToString("\n") { it.textZh }, units + outside)
        val cipher = AesGcmFixedSourcePayloadCodec(FixedSourceTestKeys())
        // Genuine source encryption/fixing; the later archive uses isolated preaccepted result rows,
        // not a claim that this test performed nine learning modules or judged real speech.
        assertTrue(AndroidContentFixRepository(context, runtime, sourceCipher = cipher).fix(request) is ContentFixResult.Fixed)
        val foreign = SessionRuntime().apply { update("ISOLATED_FOREIGN_ACCESS", otherAccount) }
        assertTrue(AndroidContentFixRepository(context, foreign, sourceCipher = cipher).fix(request) is ContentFixResult.Fixed)
        val fixedFocusIds = mutableMapOf<String, String>()
        val chapters = FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            val ids = db.rawQuery("select chapter_id from chapters where account_id=? and flow_id=? order by chapter_order",
                arrayOf(account, flow)).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            assertEquals(chapterCount, ids.size)
            for (index in 0..1) {
                val current = ids[index]
                // Fixing remaps source-unit IDs. Bind preaccepted temporary results
                // to the real persisted focus sentence, never the draft source ID.
                val sentence = db.rawQuery(
                    "select unit_id,text_zh from fixed_sentences where account_id=? and chapter_id=? and role='FOCUS'",
                    arrayOf(account, current),
                ).use { cursor ->
                    assertTrue("Each fixture chapter must have a persisted focus sentence", cursor.moveToFirst())
                    val fixedId = cursor.getString(0)
                    assertEquals(units[index].textZh, cursor.getString(1))
                    assertFalse("The one-sentence fixture cannot borrow another focus", cursor.moveToNext())
                    fixedId
                }
                fixedFocusIds[current] = sentence
                db.execSQL("insert into writing_batch_completions(account_id,chapter_id,command_id,qualification) values(?,?,?,'INDEPENDENT')",
                    arrayOf(account, current, "isolated-writing-$index-$id"))
                db.execSQL("insert into writing_result_drafts(account_id,chapter_id,sentence_id,ability_type,target_id,passed) values(?,?,?,'WHOLE_WRITING','',1)",
                    arrayOf(account, current, sentence))
                listOf("full_speaking_result_drafts", "scene_response_result_drafts").forEach { table ->
                    db.execSQL("insert into $table(account_id,chapter_id,sentence_id,passed) values(?,?,?,1)", arrayOf(account, current, sentence))
                }
                db.execSQL("insert into scene_response_tasks(account_id,chapter_id,task_id,sentence_id,sentence_order,background_en,context_lines,target_sentence_en) values(?,?,?,?,0,'Talk about today.','AI\u001fWhat happened?',?)",
                    arrayOf(account, current, "task-$index-$id", sentence, if (index == 0) "I felt calm." else ENGLISH))
            }
            ids
        }
        assertTrue(AndroidChapterArchiveRepository(context, runtime).archive(chapters[0], "confirmed-archive-$id") is ChapterArchiveResult.Archived)
        assertTrue(AndroidLearningProgressStore(context, runtime, chapters[1], fixedFocusIds.getValue(chapters[1]), "LR-04")
            .write(mapOf("version" to "1", "confirmedCopyCount" to "1", "copyInProgress" to "true")))
        val source = AndroidFixedSourceRepository(context, runtime, cipher).load(flow) as? FixedSourceLoad.Loaded
        assertNotNull(source)
        return AbandonSource(flow, chapters, cipher, requireNotNull(source))
    }

    private fun assertAbandonDelta(fixed: AbandonSource, before: Map<String, List<AbandonRow>>, hasThird: Boolean) {
        val second = fixed.chapters[1]
        val after = awaitIo { abandonRows() }
        val cleared = setOf("chapters", "fixed_sentences", "target_selections", "learning_steps", "writing_result_drafts",
            "writing_batch_completions", "full_speaking_result_drafts", "scene_response_result_drafts", "scene_response_tasks",
            "learning_checkpoints", "learning_explanation_cache", "learning_module_progress", "learning_self_assessments",
            "target_vocabulary_details", "target_pattern_details")
        val expected = before.mapValues { (table, rows) -> rows.filterNot {
            table in cleared && it.account == account && it.chapter == second
        } }
        val receipts = after.getValue("chapter_abandon_commands").filter { it.account == account && it.chapter == second }
        assertEquals("One actual confirmation creates exactly one receipt", 1, receipts.size)
        val withoutNewReceipt = after.mapValues { (table, rows) -> rows.filterNot {
            table == "chapter_abandon_commands" && it.account == account && it.chapter == second
        } }
        assertEquals("Only the abandoned chapter may disappear; source, archive, all formal and foreign rows stay byte-for-byte", expected, withoutNewReceipt)
        awaitIo { FormationDatabase(context).use { helper ->
            helper.readableDatabase.rawQuery("select source_id,has_remaining from chapter_abandon_commands where account_id=? and chapter_id=?",
                arrayOf(account, second)).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(fixed.flow, cursor.getString(0))
                assertEquals(if (hasThird) 1 else 0, cursor.getInt(1)); assertFalse(cursor.moveToNext())
            }
        } }
        assertNull(awaitIo { graph.learningCheckpointRepository.read(second) })
        assertEquals(fixed.source, awaitIo { AndroidFixedSourceRepository(context, runtime, fixed.cipher).load(fixed.flow) })
    }

    private fun abandonRows(): Map<String, List<AbandonRow>> = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        val tables = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        tables.associateWith { table ->
            check(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.rawQuery("select * from $table order by rowid", null).use { cursor -> buildList {
                val ownerIndex = cursor.getColumnIndex("account_id")
                val chapterIndex = cursor.getColumnIndex("chapter_id")
                while (cursor.moveToNext()) add(AbandonRow(
                    if (ownerIndex >= 0) cursor.getString(ownerIndex) else null,
                    if (chapterIndex >= 0) cursor.getString(chapterIndex) else null,
                    List(cursor.columnCount) { index -> when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> "null"
                        Cursor.FIELD_TYPE_INTEGER -> "integer:${cursor.getLong(index)}"
                        Cursor.FIELD_TYPE_FLOAT -> "float:${cursor.getDouble(index)}"
                        Cursor.FIELD_TYPE_BLOB -> "blob:${Base64.getEncoder().encodeToString(cursor.getBlob(index))}"
                        else -> "string:${cursor.getString(index)}"
                    } }))
            } }
        }
    }
    private data class AbandonRow(val account: String?, val chapter: String?, val values: List<String>)
    private data class AbandonSource(val flow: String, val chapters: List<String>,
        val cipher: AesGcmFixedSourcePayloadCodec, val source: FixedSourceLoad.Loaded)

    private fun mount(route: HomeDiaryRoute, readyTag: String) {
        mounted.value = true
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides compose.activity) {
                RlTheme { RlPaperBackground { if (mounted.value) HomeProductionFlow(graph, initialRoute = route) } }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(readyTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(readyTag).assertIsDisplayed()
        assertTrue(calls.isEmpty())
    }
    private fun loseConnection() {
        compose.runOnIdle { graph.connectionGate.networkLost() }
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        assertPrivateChapterHidden()
    }
    private fun requireVisibleExitChoices() {
        listOf("learning-exit-continue", "learning-exit-pause", "learning-exit-abandon").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed().assertIsEnabled()
        }
        assertPrivateChapterHidden()
    }
    private fun assertPrivateChapterHidden() {
        compose.onAllNodesWithText(ENGLISH).assertCountEquals(0)
        compose.onAllNodesWithText(CHINESE).assertCountEquals(0)
    }
    private fun reconnectExplicitly() {
        val epoch = runtime.accountEpoch
        tap("connection-error-reconnect")
        compose.waitUntil(5_000) { graph.connectionGate.incident.value == null }
        compose.waitForIdle()
        assertEquals(listOf(HEALTH, REFRESH), calls.toList())
        assertEquals(account, runtime.accountId)
        assertEquals(epoch, runtime.accountEpoch)
        assertEquals(StoredSession(account, "ISOLATED_ROTATED_REFRESH", true), awaitIo { credentials.load() })
    }
    private fun tap(tag: String) = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
        .performTouchInput { click(center) }
    private fun progressReader() = AndroidLearningProgressStore(context, runtime, chapterId, "s", "LR-04")
    private fun seedChapter(owner: String) = FormationDatabase(context).use { helper ->
        val db = helper.writableDatabase
        db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离学习退出')", arrayOf(owner, "flow-$id"))
        db.execSQL("insert into chapters values(?,?,?,0,'IN_PROGRESS')", arrayOf(owner, "flow-$id", chapterId))
        db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) " +
            "values(?,?,'s',0,?,?,'FOCUS')", arrayOf(owner, chapterId, CHINESE, ENGLISH))
    }
    private fun allRows(): Map<String, List<List<String?>>> = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        val tables = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        tables.associateWith { table ->
            require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.rawQuery("select * from $table order by rowid", null).use { cursor -> buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) })
            } }
        }
    }
    private fun capture(label: String) {
        val phase = InstrumentationRegistry.getArguments().getString("learningConnectionCapture") ?: return
        require(phase in setOf("before", "after") && label in setOf("lr13-confirmation", "lr04-exit", "lr13-delete-to-home", "lr13-delete-to-selection"))
        compose.waitForIdle()
        val view = WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }.single()
        val drawn = AtomicBoolean()
        val listener = ViewTreeObserver.OnDrawListener { drawn.set(true) }
        instrumentation.runOnMainSync { view.viewTreeObserver.addOnDrawListener(listener); view.postInvalidateOnAnimation() }
        try { compose.waitUntil(5_000) { drawn.get() } }
        finally { instrumentation.runOnMainSync { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener) } }
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val output = File(base.getExternalFilesDir(null), "visual-qa/49-learning-return-$label-$phase-$id.png")
            check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
            check(!output.exists())
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try {
            compose.waitUntil(10_000) { result.get() != null }
            return checkNotNull(result.get()).getOrThrow()
        } finally { job.cancel(); scope.cancel() }
    }
    private class MemoryCredentials(@Volatile private var value: StoredSession?) : SessionCredentialStore {
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) { value = session }
        override suspend fun clear() { value = null }
    }
    private companion object {
        const val DATABASE = "real-language-learning.db"
        const val ENGLISH = "I drink green tea."
        const val CHINESE = "我喝绿茶。"
        const val HEALTH = "GET /api/v1/health"
        const val REFRESH = "POST /api/v1/auth/sessions/refresh"
    }
}
