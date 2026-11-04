package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.data.AndroidExplanationCache
import com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore
import com.reallanguage.app.feature.learning.data.AndroidChapterArchiveRepository
import com.reallanguage.app.feature.learning.domain.ChapterArchiveResult
import com.reallanguage.app.feature.learning.domain.CompletedStepSnapshot
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.SceneDialogueLine
import com.reallanguage.app.feature.learning.domain.SceneEvaluationCandidate
import com.reallanguage.app.feature.learning.domain.SceneEvaluationDecision
import com.reallanguage.app.feature.learning.presentation.SceneResponseUiState
import com.reallanguage.app.feature.learning.presentation.encodeSceneProgress
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Real unfinished entry -> restored LR09 completion -> atomic archive -> system Back -> LR14.
 * Fixed content, cached explanation and already-accepted results are explicit neutral fixtures;
 * they do not prove AI or original-audio quality. No recording, transcription or HTTP is invoked.
 * All tables, preferences and private files belong to a fresh UUID namespace, never the user DB.
 */
class ProductionCompletedSceneReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val runId = UUID.randomUUID().toString()
    private val account = "completed-scene-$runId"
    private val chapterId = "scene-chapter-$runId"
    private val sentenceId = "scene-focus-$runId"
    private val directory = File(base.cacheDir, "completed-scene-$runId")
    private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
    private val isolatedDb = IsolatedLearningDatabaseContext(base, "completed-scene-$runId")
    private val context = object : ContextWrapper(isolatedDb) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(directory, "private")
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "completed-scene-$runId-$name"
            preferences.add(isolatedName)
            return base.getSharedPreferences(isolatedName, mode)
        }
    }
    private val requests = AtomicInteger()
    private val credentialAccesses = AtomicInteger()
    private val mountedGraph = mutableStateOf<ProductionAppGraph?>(null)
    private var mounted = false
    private val target = "I felt calm."
    private val meaning = "我感到平静。"
    private val background = "After a team meeting."
    private val prompt = "How did you feel?"
    private val oldFeedback = "隔离历史反馈，不是原音质量证据。"
    private val chapter get() = FixedLearningChapter(chapterId, 1,
        listOf(FixedLearningSentence(sentenceId, target, meaning, true)))
    private val task get() = FixedSceneResponseTask("scene-task-$runId", sentenceId,
        background, listOf(SceneDialogueLine("AI", prompt)), target)

    @After fun removeOnlyOwnedUuidFixture() {
        if (mounted) {
            compose.runOnIdle { mountedGraph.value = null }
            compose.waitForIdle()
        }
        runBlocking(Dispatchers.IO) {
            context.deleteDatabase("real-language-learning.db")
            synchronized(preferences) { preferences.toList() }.forEach { name ->
                check(name.startsWith("completed-scene-$runId-"))
                base.deleteSharedPreferences(name)
            }
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            check(directory.name == "completed-scene-$runId")
            check(!directory.exists() || directory.deleteRecursively())
        }
    }

    @Test(timeout = 90_000)
    fun archivedSceneCanBeReadFromTheActualResultBackEntryWithoutRegenerationOrRecommit() {
        val runtime = session()
        awaitIo { seedAcceptedProgress(runtime, completed = true) }
        mountedGraph.value = graph(runtime)
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.setContent { RlTheme { RlPaperBackground {
            mountedGraph.value?.let { graph ->
                HomeProductionFlow(graph, initialRoute = HomeDiaryRoute.IncompleteLearning())
            }
        } } }
        mounted = true

        val resumeTag = "incomplete-learning-resume-$chapterId"
        waitForTag(resumeTag)
        compose.onNodeWithTag(resumeTag).assertIsDisplayed().performClick()
        waitForTag("chapter-result-summary")
        compose.onNodeWithText("本章已完成并归档").assertIsDisplayed()
        val archived = awaitIo {
            assertArchivedAndOnlyOriginalSceneRemains()
            snapshotAllIsolatedTables()
        }
        assertNoExternalWork()

        // Use the actual production BackHandler, not a preselected read-only destination.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForTag("completed-step-review-sheet")
        compose.onNodeWithText("第 1 章 · 场景回应").assertIsDisplayed()
        compose.onNodeWithText("已完成·只读查看").assertIsDisplayed()
        captureActualReadOnlyScreen()
        compose.onNodeWithText(background, substring = true).assertIsDisplayed()
        compose.onNodeWithText("AI: $prompt", substring = true).assertIsDisplayed()
        assertTrue("The original fixed target remains available for completed read-only review",
            compose.onAllNodesWithText(target).fetchSemanticsNodes().isNotEmpty())
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText(oldFeedback).assertDoesNotExist()
        compose.onNodeWithTag("scene-response-workspace").assertDoesNotExist()
        assertEquals("Reading an archived scene must not mutate any stored facts",
            archived, awaitIo { snapshotAllIsolatedTables() })
        assertNoExternalWork()

        compose.onNodeWithTag("completed-step-return").assertIsDisplayed().performClick()
        waitForTag("chapter-result-summary")
        compose.onNodeWithText("本章已完成并归档").assertIsDisplayed()
        assertEquals("Returning to the existing summary must not re-commit capabilities or progress",
            archived, awaitIo { snapshotAllIsolatedTables() })
        assertNoExternalWork()
    }

    /** Invalid/stale completed-page input must not turn an unfinished third round into an answer viewer. */
    @Test fun unfinishedThirdRoundCannotLoadAnArchivedSceneReadOnlySnapshot() {
        val runtime = session()
        awaitIo { seedAcceptedProgress(runtime, completed = false) }
        val graph = graph(runtime)
        val before = awaitIo { snapshotAllIsolatedTables() }
        val snapshot = completedStepSnapshot(chapter, sentenceId, "LR-09", "场景回应")
        val load = requireNotNull(graph.completedStepSnapshotLoader(snapshot)) {
            "LR09 must have a validating read-only loader; null silently displays the route answer"
        }
        assertTrue("An unfinished third round has no archived scene-view permission",
            awaitIo { runCatching { load() }.isFailure })
        assertEquals(before, awaitIo { snapshotAllIsolatedTables() })
        assertNoExternalWork()
    }

    @Test fun archivedLoaderAllowsTokenRefreshButRejectsAnOldLoginAndAChangedAccount() {
        val runtime = session()
        awaitIo { createRealArchive(runtime) }
        val graph = graph(runtime)
        val snapshot = sceneSnapshot()
        val original = requireNotNull(graph.completedStepSnapshotLoader(snapshot))
        val before = awaitIo { snapshotAllIsolatedTables() }

        runtime.update("isolated-refreshed-token", account)
        assertFixedFacts(awaitIo { original() })
        assertEquals(before, awaitIo { snapshotAllIsolatedTables() })
        runtime.clear()
        runtime.update("isolated-new-login", account)
        assertTrue("A previous login's loader must not borrow a fresh login of the same account",
            awaitIo { runCatching { original() }.isFailure })
        assertEquals(before, awaitIo { snapshotAllIsolatedTables() })

        val fresh = requireNotNull(graph.completedStepSnapshotLoader(snapshot))
        assertFixedFacts(awaitIo { fresh() })
        runtime.clear()
        runtime.update("isolated-other-account", "other-$account")
        assertTrue("A loader cannot expose its previous account's scene after account replacement",
            awaitIo { runCatching { fresh() }.isFailure })
        assertEquals(before, awaitIo { snapshotAllIsolatedTables() })
        assertNoExternalWork()
    }

    @Test fun anArchiveReceiptAndCompletedChapterStatusAreBothRequiredForReadOnlyScene() {
        val runtime = session()
        awaitIo { createRealArchive(runtime) }
        val graph = graph(runtime)
        awaitIo {
            FormationDatabase(context).use { it.writableDatabase.execSQL(
                "update chapters set status='NOT_STARTED' where account_id=? and chapter_id=?", arrayOf(account, chapterId)) }
        }
        assertReadOnlyRejectedWithoutMutation(graph, sceneSnapshot(), "An archive row alone is not a completed chapter")
        awaitIo {
            FormationDatabase(context).use { helper ->
                helper.writableDatabase.execSQL("update chapters set status='COMPLETED' where account_id=? and chapter_id=?", arrayOf(account, chapterId))
                helper.writableDatabase.delete("chapter_archives", "account_id=? and chapter_id=?", arrayOf(account, chapterId))
            }
        }
        assertReadOnlyRejectedWithoutMutation(graph, sceneSnapshot(), "A completed flag alone is not an archive receipt")
    }

    @Test fun changedRouteEnglishChineseContextOrFocusIdentityCannotBorrowAnArchivedScene() {
        val runtime = session()
        awaitIo { createRealArchive(runtime) }
        val graph = graph(runtime)
        val original = sceneSnapshot()
        val altered = listOf(
            original.copy(contentEn = "I felt worried."),
            original.copy(contentZh = "我感到担忧。"),
            original.copy(contextSentences = listOf(FixedLearningSentence(sentenceId, "I felt worried.", meaning, true))),
            original.copy(activeSentenceId = "different-focus-$runId"),
        )
        altered.forEach { snapshot ->
            assertReadOnlyRejectedWithoutMutation(graph, snapshot, "The route must match the exact archived fixed facts")
        }
    }

    @Test fun emptyLegacyBackgroundIsReadableButChangedTargetMalformedDialogueAndMissingTaskAreRejected() {
        val runtime = session()
        awaitIo { createRealArchive(runtime) }
        val graph = graph(runtime)
        awaitIo {
            FormationDatabase(context).use { it.writableDatabase.execSQL(
                "update scene_response_tasks set background_en='' where account_id=? and chapter_id=?", arrayOf(account, chapterId)) }
        }
        val emptyBackgroundBefore = awaitIo { snapshotAllIsolatedTables() }
        val load = requireNotNull(graph.completedStepSnapshotLoader(sceneSnapshot()))
        assertFixedFacts(awaitIo { load() })
        assertEquals("A legacy scene with valid preceding dialogue does not require invented background",
            emptyBackgroundBefore, awaitIo { snapshotAllIsolatedTables() })
        assertNoExternalWork()

        awaitIo {
            FormationDatabase(context).use { it.writableDatabase.execSQL(
                "update scene_response_tasks set target_sentence_en='I felt worried.' where account_id=? and chapter_id=?", arrayOf(account, chapterId)) }
        }
        assertReadOnlyRejectedWithoutMutation(graph, sceneSnapshot(), "A task from a different fixed answer is not this scene")
        awaitIo {
            FormationDatabase(context).use { it.writableDatabase.execSQL(
                "update scene_response_tasks set target_sentence_en=?,context_lines='missing-field-separator' where account_id=? and chapter_id=?",
                arrayOf(target, account, chapterId)) }
        }
        assertReadOnlyRejectedWithoutMutation(graph, sceneSnapshot(), "Malformed saved dialogue is not an empty successful scene")
        awaitIo {
            FormationDatabase(context).use { it.writableDatabase.delete(
                "scene_response_tasks", "account_id=? and chapter_id=?", arrayOf(account, chapterId)) }
        }
        assertReadOnlyRejectedWithoutMutation(graph, sceneSnapshot(), "A missing task must not regenerate or fall back to route text")
    }

    @Test fun allFocusScenesKeepTheirOriginalOrderAndIdentityEvenWhenTheFixedEnglishIsTheSame() {
        val runtime = session()
        val secondSentenceId = "second-focus-$runId"
        val secondMeaning = "我当时很平静。"
        val contextSentence = FixedLearningSentence("context-$runId", "Then I went home.", "然后我回家了。", false)
        val secondSentence = FixedLearningSentence(secondSentenceId, target, secondMeaning, true)
        val fullChapter = chapter.copy(sentences = chapter.sentences + contextSentence + secondSentence)
        val secondTask = FixedSceneResponseTask("second-task-$runId", secondSentenceId, "After arriving home.",
            listOf(SceneDialogueLine("AI", "How did you feel at home?")), target)
        awaitIo {
            seedAcceptedProgress(runtime, completed = true)
            FormationDatabase(context).use { helper ->
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) " +
                        "values(?,?,?,1,?,?,'CONTEXT')", arrayOf(account, chapterId, contextSentence.sentenceId, contextSentence.textZh, contextSentence.textEn))
                    db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) " +
                        "values(?,?,?,2,?,?,'FOCUS')", arrayOf(account, chapterId, secondSentenceId, secondMeaning, target))
                    db.execSQL("insert into scene_response_tasks values(?,?,?,?,1,?,?,?)", arrayOf(account, chapterId,
                        secondTask.taskId, secondSentenceId, requireNotNull(secondTask.backgroundEn),
                        "AI\u001f${secondTask.contextLines.single().textEn}", target))
                    db.execSQL("insert into writing_result_drafts values(?,?,?,'WHOLE_WRITING','',1)", arrayOf(account, chapterId, secondSentenceId))
                    db.execSQL("insert into full_speaking_result_drafts values(?,?,?,1)", arrayOf(account, chapterId, secondSentenceId))
                    db.execSQL("insert into scene_response_result_drafts values(?,?,?,1)", arrayOf(account, chapterId, secondSentenceId))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            val accepted = SceneEvaluationCandidate(SceneEvaluationDecision.PASS, true, true, true, oldFeedback)
            assertTrue(AndroidLearningProgressStore(context, runtime, chapterId, sentenceId, "LR-09").write(
                encodeSceneProgress(SceneResponseUiState(fullChapter, listOf(task, secondTask), taskIndex = 1,
                    roundIndex = 2, audioMuted = true, completed = true, finalDecision = accepted.decision,
                    transcript = target, feedbackZh = accepted.feedbackZh), accepted)))
            assertTrue(AndroidChapterArchiveRepository(context, runtime).archive(chapterId, "archive-$runId") is ChapterArchiveResult.Archived)
        }
        val before = awaitIo { snapshotAllIsolatedTables() }
        val snapshot = completedStepSnapshot(fullChapter, secondSentenceId, "LR-09", "场景回应")
        val load = requireNotNull(graph(runtime).completedStepSnapshotLoader(snapshot))
        val restored = awaitIo { load() }
        assertEquals("Identical English must not collapse two different saved scene identities",
            listOf(task, secondTask), restored.completedSceneTasks)
        assertEquals(fullChapter.sentences, restored.contextSentences)
        assertEquals(secondSentenceId, restored.activeSentenceId)
        assertEquals(target, restored.contentEn)
        assertEquals(secondMeaning, restored.contentZh)
        assertEquals(before, awaitIo { snapshotAllIsolatedTables() })
        assertNoExternalWork()
    }

    private suspend fun createRealArchive(runtime: SessionRuntime) {
        seedAcceptedProgress(runtime, completed = true)
        val archived = AndroidChapterArchiveRepository(context, runtime).archive(chapterId, "archive-$runId")
        assertTrue("The fixture must be archived by the real transactional repository", archived is ChapterArchiveResult.Archived)
        assertArchivedAndOnlyOriginalSceneRemains()
    }

    private fun sceneSnapshot() = completedStepSnapshot(chapter, sentenceId, "LR-09", "场景回应")

    private fun assertFixedFacts(snapshot: CompletedStepSnapshot) {
        assertEquals(chapterId, snapshot.chapterId)
        assertEquals(sentenceId, snapshot.activeSentenceId)
        assertEquals(target, snapshot.contentEn)
        assertEquals(meaning, snapshot.contentZh)
        assertEquals(chapter.sentences, snapshot.contextSentences)
    }

    private fun assertReadOnlyRejectedWithoutMutation(graph: ProductionAppGraph, snapshot: CompletedStepSnapshot, message: String) {
        val before = awaitIo { snapshotAllIsolatedTables() }
        val load = requireNotNull(graph.completedStepSnapshotLoader(snapshot)) {
            "LR09 needs a validating loader rather than an unchecked route snapshot"
        }
        assertTrue(message, awaitIo { runCatching { load() }.isFailure })
        assertEquals(message, before, awaitIo { snapshotAllIsolatedTables() })
        assertNoExternalWork()
    }

    private suspend fun seedAcceptedProgress(runtime: SessionRuntime, completed: Boolean) {
        FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离场景回看')", arrayOf(account, "flow-$runId"))
                db.execSQL("insert into chapters values(?,?,?,0,'NOT_STARTED')", arrayOf(account, "flow-$runId", chapterId))
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) " +
                    "values(?,?,?,0,?,?,'FOCUS')", arrayOf(account, chapterId, sentenceId, meaning, target))
                db.execSQL("insert into scene_response_tasks values(?,?,?,?,0,?,?,?)",
                    arrayOf(account, chapterId, task.taskId, sentenceId, background, "AI\u001f$prompt", target))
                if (completed) {
                    // Explicit already-delivered interface fixtures, not synthetic microphone evidence.
                    db.execSQL("insert into writing_batch_completions values(?,?,?,'INDEPENDENT')", arrayOf(account, chapterId, "write-$runId"))
                    db.execSQL("insert into writing_result_drafts values(?,?,?,'WHOLE_WRITING','',1)", arrayOf(account, chapterId, sentenceId))
                    db.execSQL("insert into full_speaking_result_drafts values(?,?,?,1)", arrayOf(account, chapterId, sentenceId))
                    db.execSQL("insert into scene_response_result_drafts values(?,?,?,1)", arrayOf(account, chapterId, sentenceId))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        val cacheKey = UUID.nameUUIDFromBytes("v1:1:sentence:".toByteArray(Charsets.UTF_8)).toString()
        AndroidExplanationCache(context).write(account, chapterId, sentenceId, cacheKey,
            JSONObject().put("kind", "SENTENCE").put("explanationZh", "这句话说明会后感到平静。").toString())
        val candidate = if (completed) SceneEvaluationCandidate(SceneEvaluationDecision.PASS,
            true, true, true, oldFeedback) else null
        val progress = encodeSceneProgress(SceneResponseUiState(chapter, listOf(task), roundIndex = 2,
            audioMuted = true, completed = completed, finalDecision = candidate?.decision,
            transcript = if (completed) target else "", feedbackZh = candidate?.feedbackZh), candidate)
        assertTrue(AndroidLearningProgressStore(context, runtime, chapterId, sentenceId, "LR-09").write(progress))
    }

    private fun assertArchivedAndOnlyOriginalSceneRemains() = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        db.rawQuery("select status from chapters where account_id=? and chapter_id=?", arrayOf(account, chapterId)).use {
            assertTrue(it.moveToFirst()); assertEquals("COMPLETED", it.getString(0)); assertFalse(it.moveToNext())
        }
        db.rawQuery("select count(*) from chapter_archives where account_id=? and chapter_id=?", arrayOf(account, chapterId)).use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
        db.rawQuery("select background_en,context_lines,target_sentence_en from scene_response_tasks where account_id=? and chapter_id=?",
            arrayOf(account, chapterId)).use {
            assertTrue(it.moveToFirst()); assertEquals(background, it.getString(0))
            assertEquals("AI\u001f$prompt", it.getString(1)); assertEquals(target, it.getString(2)); assertFalse(it.moveToNext())
        }
        listOf("learning_module_progress", "learning_checkpoints", "scene_response_result_drafts").forEach { table ->
            db.rawQuery("select count(*) from $table where account_id=? and chapter_id=?", arrayOf(account, chapterId)).use {
                assertTrue(it.moveToFirst()); assertEquals("Archived temporary state is gone: $table", 0, it.getInt(0))
            }
        }
    }

    private fun snapshotAllIsolatedTables(): Map<String, List<List<String?>>> = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        val tables = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        tables.associateWith { table ->
            require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.rawQuery("select * from $table order by rowid", null).use { cursor -> buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) })
            } }
        }
    }

    private fun session() = SessionRuntime().apply { update("isolated-not-a-real-token", account) }
    private fun graph(runtime: SessionRuntime) = ProductionAppGraph(context, runtime, HttpTransport {
        requests.incrementAndGet(); error("Completed-scene read-only fixture must never call HTTP or AI")
    }, object : SessionCredentialStore {
        override suspend fun load(): StoredSession? { credentialAccesses.incrementAndGet(); error("No credential read") }
        override suspend fun save(session: StoredSession) { credentialAccesses.incrementAndGet(); error("No credential write") }
        override suspend fun clear() { credentialAccesses.incrementAndGet(); error("No credential clear") }
    })

    private fun assertNoExternalWork() {
        assertEquals("No HTTP or AI", 0, requests.get())
        assertEquals("No user credentials", 0, credentialAccesses.get())
        assertFalse("No original audio is captured by this test", directory.exists() && directory.walkTopDown().any {
            it.isFile && it.extension.lowercase() in setOf("m4a", "wav", "mp3", "aac", "3gp")
        })
    }

    private fun waitForTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    /** Preserve the actual painted production host before assertions; never a cropped Compose image. */
    private fun captureActualReadOnlyScreen() {
        val phase = InstrumentationRegistry.getArguments().getString("completedSceneCapture") ?: "before"
        require(phase in setOf("before", "after"))
        val view = compose.activity.window.decorView
        val drawn = AtomicBoolean()
        val listener = ViewTreeObserver.OnDrawListener { drawn.set(true) }
        compose.activityRule.scenario.onActivity {
            view.viewTreeObserver.addOnDrawListener(listener)
            view.postInvalidateOnAnimation()
        }
        try { compose.waitUntil(10_000) { drawn.get() } }
        finally { compose.activityRule.scenario.onActivity {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
        } }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 5_000)
        val output = File(requireNotNull(base.getExternalFilesDir(null)),
            "visual-qa/35-completed-scene-$phase-$runId.png")
        check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
        check(!output.exists())
        val bitmap = requireNotNull(automation.takeScreenshot())
        try {
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        Log.i("RealLanguageJourney", "Completed-scene $phase screenshot=${output.absolutePath}")
    }

    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try {
            compose.waitUntil(20_000) { result.get() != null }
            return requireNotNull(result.get()).getOrThrow()
        } finally { job.cancel(); scope.cancel() }
    }
}
