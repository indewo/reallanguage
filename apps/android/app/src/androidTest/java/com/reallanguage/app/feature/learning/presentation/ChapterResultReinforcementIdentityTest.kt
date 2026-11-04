package com.reallanguage.app.feature.learning.presentation

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.data.AndroidChapterArchiveRepository
import com.reallanguage.app.feature.learning.domain.ChapterArchiveResult
import com.reallanguage.app.feature.learning.domain.ChapterArchiveSummary
import com.reallanguage.app.feature.learning.domain.ChapterResultCategory
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Accepted fixed targets/final judgments are explicit fixtures, not AI-quality evidence.
 * Archive, SQL, replay and Screen are real. No HTTP/capture/provider or save substitute exists.
 * Every database operation and cleanup is restricted to a fresh UUID database Context.
 */
class ChapterResultReinforcementIdentityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val runId = UUID.randomUUID().toString()
    private val account = "result-identity-$runId"
    private val context = IsolatedLearningDatabaseContext(base, "result-identity-$runId")
    private val runtime = SessionRuntime().apply { update("isolated-not-a-real-token", account) }
    private lateinit var helper: FormationDatabase

    @Before fun prepareOnlyThePrivateDatabaseAndProductionWindow() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        awaitIo { helper = FormationDatabase(context); helper.writableDatabase }
    }

    @After fun closeAndDeleteOnlyTheUuidDatabase() {
        if (::helper.isInitialized) helper.close()
        context.deleteDatabase("real-language-learning.db")
    }

    @Test fun twoMeaningsOfRunRemainDistinctThroughRealArchiveReplayAndResultScreen() {
        val first = awaitIo {
            seedTwoMeanings()
            archive("first")
        }
        assertCategory(first, ChapterResultCategory.SENTENCE, 6, 6)
        assertCategory(first, ChapterResultCategory.MEANING, 0, 2)
        assertCategory(first, ChapterResultCategory.INCOMPLETE, 2, 2)
        assertCategory(first, ChapterResultCategory.WRONG, 2, 2)
        assertEquals(setOf("s1|MEANING_WRITING|run-running", "s2|MEANING_WRITING|run-business"),
            first.reinforcementItems.map { "${it.sentenceId}|${it.capabilityType}|${it.targetId}" }.toSet())
        assertEquals(listOf("run", "run"), first.reinforcementItems.map { it.display })
        val archivedRows = awaitIo {
            assertEquals(setOf("run|跑步|LEARNING", "run|经营|LEARNING"),
                rows("select display,meaning_zh,status from vocabulary_items").map { it.joinToString("|") }.toSet())
            assertEquals(2, count("learning_obligations"))
            assertEquals(2, count("correction_flags"))
            snapshotAllTables()
        }
        val restored = awaitIo { replay("new-repository") }
        assertEquals(first, restored)
        assertEquals(archivedRows, awaitIo { snapshotAllTables() })

        val actions = mutableListOf<ChapterResultAction>()
        render(restored, actions)
        captureIfRequested("double-meaning-initial")
        // Match content rather than impose punctuation or a new visual row layout.
        compose.onNodeWithText("跑步", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("经营", substring = true).performScrollTo().assertIsDisplayed()
        assertItem("s1", "MEANING_WRITING", "run-running", "run", "跑步", R.string.review_module_word_meaning)
        assertItem("s2", "MEANING_WRITING", "run-business", "run", "经营", R.string.review_module_word_meaning)
        captureIfRequested("double-meaning-scrolled")
        awaitIo {
            val entries = archivedJson().getJSONArray("reinforcementItems")
            val explanations = (0 until entries.length()).associate { index ->
                entries.getJSONObject(index).let { it.getString("targetId") to it.optString("explanationZh") }
            }
            assertEquals("The new archive must preserve the fixed meanings, not infer them on reopening",
                mapOf("run-running" to "跑步", "run-business" to "经营"), explanations)
        }
        compose.onNodeWithText("返回首页").assertIsDisplayed().performClick()
        assertEquals(listOf(ChapterResultAction.ReturnHomeRequested), actions)
        assertEquals("Reading both meanings and leaving must not change any SQL table",
            archivedRows, awaitIo { snapshotAllTables() })
    }

    @Test fun onePatternWithTwoUnfinishedAbilitiesKeepsBothCapabilitiesVisibleWithoutChangingCounts() {
        val first = awaitIo {
            seedChapter()
            seedSentence("s1", 0, "I felt calm.", "我感到平静。")
            seedTarget("p1", "s1", "PATTERN", "feel + adjective", "表达感受")
            seedWriting("s1", "PATTERN_WRITING", "p1", false)
            archive("pattern-first")
        }
        assertCategory(first, ChapterResultCategory.SENTENCE, 3, 3)
        assertCategory(first, ChapterResultCategory.PATTERN, 0, 2)
        assertCategory(first, ChapterResultCategory.INCOMPLETE, 2, 2)
        assertCategory(first, ChapterResultCategory.WRONG, 1, 1)
        assertEquals(setOf("PATTERN_WRITING", "PATTERN_SPEAKING"),
            first.reinforcementItems.map { it.capabilityType }.toSet())
        assertTrue(first.reinforcementItems.all { it.targetId == "p1" && it.sentenceId == "s1" })
        val archivedRows = awaitIo {
            assertEquals(listOf(listOf("LEARNING", "PENDING")),
                rows("select writing_status,speaking_status from pattern_items"))
            snapshotAllTables()
        }
        val restored = awaitIo { replay("pattern-reopen") }
        assertEquals(first, restored)
        render(restored)
        captureIfRequested("pattern-initial")
        compose.onNodeWithText(base.getString(R.string.review_module_pattern_writing), substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(base.getString(R.string.review_module_pattern_speaking), substring = true)
            .performScrollTo().assertIsDisplayed()
        assertItem("s1", "PATTERN_WRITING", "p1", "feel + adjective", "表达感受", R.string.review_module_pattern_writing)
        assertItem("s1", "PATTERN_SPEAKING", "p1", "feel + adjective", "表达感受", R.string.review_module_pattern_speaking)
        captureIfRequested("pattern-scrolled")
        assertEquals("A single expression carrier must not merge the two stored capabilities",
            archivedRows, awaitIo { snapshotAllTables() })
    }

    @Test fun legacyArchiveWithoutExplanationFieldsDoesNotInventMeaningsOrRewriteHistory() {
        val legacy = awaitIo {
            seedTwoMeanings()
            archive("legacy-first")
            val json = archivedJson()
            val entries = json.getJSONArray("reinforcementItems")
            repeat(entries.length()) { entries.getJSONObject(it).remove("explanationZh") }
            val saved = json.toString()
            helper.writableDatabase.execSQL("update chapter_archives set summary=? where account_id=? and chapter_id='c1'",
                arrayOf(saved, account))
            saved
        }
        val before = awaitIo { snapshotAllTables() }
        val restored = awaitIo { replay("legacy-reopen") }
        assertEquals(2, restored.reinforcementItems.size)
        assertCategory(restored, ChapterResultCategory.SENTENCE, 6, 6)
        render(restored)
        captureIfRequested("legacy-initial")
        compose.onNodeWithText("需要继续巩固").assertIsDisplayed()
        // Fixed targets still contain these meanings. Old result snapshots must not silently borrow them.
        compose.onNodeWithText("跑步", substring = true).assertDoesNotExist()
        compose.onNodeWithText("经营", substring = true).assertDoesNotExist()
        assertEquals(legacy, awaitIo { archivedJson().toString() })
        assertEquals(before, awaitIo { snapshotAllTables() })
    }

    private fun assertItem(sentence: String, capability: String, target: String, display: String, explanation: String, label: Int) {
        val row = "chapter-reinforcement-$sentence-$capability-$target"
        listOf(display, explanation, base.getString(label)).forEach { text ->
            compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(row)), useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed()
        }
    }

    private fun render(summary: ChapterArchiveSummary, actions: MutableList<ChapterResultAction> = mutableListOf()) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                RlTheme {
                    Box(Modifier.requiredSize(836.dp, 470.5.dp)) {
                        ChapterResultScreen(ChapterResultUiState("c1", "display-only", chapterNumber = 1, summary = summary), actions::add)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Opt-in actual Android screenshot while this neutral result Screen is still mounted. */
    private fun captureIfRequested(suffix: String) {
        val phase = InstrumentationRegistry.getArguments().getString("chapterReinforcementCapture") ?: return
        require(phase in setOf("before", "after"))
        require(suffix.matches(Regex("[a-z-]+")))
        compose.waitForIdle()
        fun anchors() = listOf(
            compose.onNodeWithTag("chapter-result-summary").assertIsDisplayed().fetchSemanticsNode().boundsInRoot,
            compose.onNodeWithText("返回首页").assertIsDisplayed().fetchSemanticsNode().boundsInRoot,
        )
        val before = anchors()
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
        assertEquals("Capture only after the displayed result and scroll layout are stable", before, anchors())
        val folder = File(requireNotNull(base.getExternalFilesDir(null)), "visual-qa")
        check(folder.isDirectory || folder.mkdirs())
        val output = File(folder, "32-reinforcement-$suffix-$phase-$runId.png")
        check(output.canonicalFile.parentFile == folder.canonicalFile && !output.exists())
        val bitmap = requireNotNull(automation.takeScreenshot())
        try {
            assertEquals("Capture requires the agreed real reference-size device", 1672, bitmap.width)
            assertEquals("Capture requires the agreed real reference-size device", 941, bitmap.height)
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        Log.i("RealLanguageJourney", "Result reinforcement screenshot=${output.absolutePath}")
    }

    private suspend fun archive(command: String): ChapterArchiveSummary {
        val result = AndroidChapterArchiveRepository(context, runtime) { "2026-09-09" }.archive("c1", command)
        assertTrue("The real archive transaction must succeed before checking its presentation", result is ChapterArchiveResult.Archived)
        return (result as ChapterArchiveResult.Archived).summary
    }

    private suspend fun replay(command: String): ChapterArchiveSummary {
        val result = AndroidChapterArchiveRepository(context, runtime).archive("c1", command)
        assertTrue("A new repository must read the original archive without settling twice", result is ChapterArchiveResult.AlreadyArchived)
        return (result as ChapterArchiveResult.AlreadyArchived).summary
    }

    private fun seedTwoMeanings() {
        seedChapter()
        seedSentence("s1", 0, "I run every morning.", "我每天早上跑步。")
        seedSentence("s2", 1, "I run a small shop.", "我经营一家小店。")
        seedTarget("run-running", "s1", "WORD", "run", "跑步")
        seedTarget("run-business", "s2", "WORD", "run", "经营")
        seedWriting("s1", "MEANING_WRITING", "run-running", false)
        seedWriting("s2", "MEANING_WRITING", "run-business", false)
    }

    private fun seedChapter() {
        insert("formation_flows", "flow_id" to "f1", "status" to "FIXED", "source_type" to "DIARY", "source_title" to "隔离显示样本")
        insert("chapters", "flow_id" to "f1", "chapter_id" to "c1", "chapter_order" to 0, "status" to "NOT_STARTED")
        insert("writing_batch_completions", "chapter_id" to "c1", "command_id" to "accepted-writing", "qualification" to "INDEPENDENT")
    }

    private fun seedSentence(id: String, order: Int, en: String, zh: String) {
        insert("fixed_sentences", "chapter_id" to "c1", "unit_id" to id, "sentence_order" to order,
            "text_en" to en, "text_zh" to zh, "role" to "FOCUS")
        seedWriting(id, "WHOLE_WRITING", "", true)
        insert("full_speaking_result_drafts", "chapter_id" to "c1", "sentence_id" to id, "passed" to 1)
        insert("scene_response_result_drafts", "chapter_id" to "c1", "sentence_id" to id, "passed" to 1)
    }

    private fun seedTarget(id: String, sentence: String, type: String, display: String, explanation: String) {
        insert("target_selections", "chapter_id" to "c1", "target_id" to id, "unit_id" to sentence,
            "type" to type, "display" to display, "explanation_zh" to explanation,
            "structure_parts" to "", "related_vocabulary" to "")
    }

    private fun seedWriting(sentence: String, ability: String, target: String, passed: Boolean) {
        insert("writing_result_drafts", "chapter_id" to "c1", "sentence_id" to sentence,
            "ability_type" to ability, "target_id" to target, "passed" to if (passed) 1 else 0)
    }

    private fun insert(table: String, vararg fields: Pair<String, Any>) {
        helper.writableDatabase.insertOrThrow(table, null, ContentValues().apply {
            put("account_id", account)
            fields.forEach { (key, value) -> when (value) {
                is String -> put(key, value)
                is Int -> put(key, value)
                else -> error("Unexpected isolated fixture type")
            } }
        })
    }

    private fun archivedJson() = JSONObject(rows("select summary from chapter_archives").single().single()!!)
    private fun count(table: String) = rows("select count(*) from $table").single().single()!!.toInt()
    private fun rows(sql: String): List<List<String?>> = helper.readableDatabase.rawQuery(sql, null).use { cursor ->
        buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
            when (cursor.getType(column)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_BLOB -> android.util.Base64.encodeToString(cursor.getBlob(column), android.util.Base64.NO_WRAP)
                else -> cursor.getString(column)
            }
        }) }
    }

    private fun snapshotAllTables(): Map<String, List<List<String?>>> {
        val tables = rows("select name from sqlite_master where type='table' order by name").map { it.single()!! }
        return tables.associateWith { table ->
            require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            rows("select * from \"$table\"").sortedBy { JSONObject.wrap(it).toString() }
        }
    }

    private fun assertCategory(summary: ChapterArchiveSummary, category: ChapterResultCategory, completed: Int, total: Int) {
        val item = summary.items.single { it.category == category }
        assertEquals(completed, item.completedCount)
        assertEquals(total, item.totalCount)
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
