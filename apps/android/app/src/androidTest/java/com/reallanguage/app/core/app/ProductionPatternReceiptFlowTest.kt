package com.reallanguage.app.core.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.DatabaseUtils
import android.graphics.Bitmap
import android.view.ViewTreeObserver
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.data.CapturedReadSpeakAudio
import com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture
import com.reallanguage.app.feature.review.domain.*
import com.reallanguage.app.testing.ReviewNoJudgmentFixture
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Production composition/HTTP JSON/SQL/entry lifecycle; capture and server are explicit isolated doubles. */
class ProductionPatternReceiptFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val fixture = ReviewNoJudgmentFixture(instrumentation.targetContext)
    private val visible = mutableStateOf(false)
    private val captureCreations = AtomicInteger()
    private val captureStarts = AtomicInteger()
    private val captureFinishes = AtomicInteger()
    private val requests = AtomicInteger()
    private val evaluationRequests = CopyOnWriteArrayList<HttpRequest>()
    private val lastEvaluation = AtomicReference<HttpRequest?>()
    private val syntheticBytes = "synthetic-graph-not-human-audio".toByteArray()
    private val captureFile = File(fixture.context.cacheDir, "synthetic-pattern.test")
    private val generation = """{"status":"READY","patternId":"target1","examples":[{"exampleId":"example","sceneZh":"过去的习惯","aiPromptEn":"What changed?","targetResponseEn":"I used to walk.","targetResponseZh":"我以前常走路。"}]}"""
    private val evaluated = """{"decision":"PASS","feedbackZh":"隔离响应已验证","structurePreserved":true,"contentSuitable":true,"pronunciationIntelligible":true,"confidence":0.9}"""

    @After fun cleanOnlyUuidFixture() {
        if (visible.value) { compose.runOnIdle { visible.value = false }; compose.waitForIdle() }
        fixture.close()
    }

    @Test(timeout = 90_000)
    fun realButtonsUseTheRegisteredHttpReceiptThenCommitAndReleaseTheEntry() {
        awaitIo { seedExactPatternSource() }
        val graph = graph()
        assertNotNull(awaitIo { graph.reviewPattern("chapter1", "sentence1", "target1") })
        mount(graph, HomeDiaryRoute.TodayReview)
        waitTag("today-review-start"); compose.onNodeWithTag("today-review-start").performClick()
        waitTag("review-chapter-continue")
        val lease = requireNotNull(graph.currentPatternReviewEntry()) { "The actual chapter must own its receipt lease" }
        compose.onNodeWithTag("review-chapter-continue").performClick()
        waitText("开始回应")
        assertEquals("Stop before any real microphone access if the production capture seam is missing", 1, captureCreations.get())
        // The external runner records/restores the previous permission. Do not
        // mutate device permission here or revoke while instrumentation is alive.
        assertEquals("Runner must temporarily grant and then restore the original microphone permission", PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(instrumentation.targetContext, Manifest.permission.RECORD_AUDIO))
        compose.onNodeWithText("开始回应").performScrollTo().performClick()
        waitText("说完了"); compose.onNodeWithText("说完了").performClick()
        waitText("隔离响应已验证")
        assertEquals(1, captureFinishes.get()); assertFalse(captureFile.exists())
        val body = requireNotNull(lastEvaluation.get()).body
        val stored = awaitIo { fixture.db.rawQuery("select ability_id,evidence_id from review_working_state where account_id=?",
            arrayOf(fixture.account)).use { check(it.moveToFirst()); it.getString(0) to it.getString(1) } }
        val expected = PatternIndependentReceipt(1,
            PatternIndependentReceiptIdentity(fixture.account, fixture.runtime.accountEpoch, lease.reviewChapterId, lease.entryId,
                "chapter1", "sentence1", stored.first, "target1"), "example", JSONObject(body).getString("commandId"),
            hash(syntheticBytes), hash(body.toByteArray()), PatternIndependentDecision.PASS, true, true, true, .9)
        assertEquals(expected.evidenceId, stored.second)
        compose.onNodeWithText("继续").performScrollTo().performClick()
        waitTag("review-result-summary")
        assertEquals(3, fixture.scalar("select speaking_streak from pattern_items where account_id=?"))
        assertEquals(0, fixture.count("learning_obligations")); assertEquals(0, fixture.count("review_working_state"))
        assertEquals(1, fixture.count("review_chapter_results")); assertEquals(2, requests.get())
        assertFalse(lease.isActiveFor(fixture.account, fixture.runtime.accountEpoch))
        assertNull(graph.currentPatternReviewEntry())
    }

    @Test(timeout = 90_000)
    fun failedEvaluationRetriesOriginalAudioAndReceiptBeforeOneFormalReviewCommit() {
        awaitIo { seedExactPatternSource(); seedOtherAccountSentinels() }
        val attempts = AtomicInteger()
        val graph = graph { if (attempts.incrementAndGet() == 1) HttpResponse(503, "{}") else HttpResponse(200, evaluated) }
        assertNotNull(awaitIo { graph.reviewPattern("chapter1", "sentence1", "target1") })
        mount(graph, HomeDiaryRoute.TodayReview)
        waitTag("today-review-start")
        compose.onNodeWithTag("today-review-start").performTouchInput { click(center) }
        waitTag("review-chapter-continue")
        val lease = requireNotNull(graph.currentPatternReviewEntry())
        compose.onNodeWithTag("review-chapter-continue").performTouchInput { click(center) }
        waitText("开始回应")
        assertEquals("Only the injected synthetic capture is allowed", 1, captureCreations.get())
        assertEquals("Runner owns temporary microphone grant/restoration; this fixture never starts platform capture",
            PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(instrumentation.targetContext, Manifest.permission.RECORD_AUDIO))
        val before = awaitIo { allRows() }
        touchText("开始回应")
        waitText("说完了"); touchText("说完了")
        waitText("重试")
        captureRetryIfRequested("pending")
        compose.onNodeWithText(instrumentation.targetContext.getString(R.string.learning_audio_retry_pending))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("本次处理失败，未产生有效结果。").assertDoesNotExist()
        compose.onNodeWithText("开始回应").assertDoesNotExist()
        compose.onNodeWithText("继续").assertDoesNotExist()
        compose.waitForIdle()
        assertEquals("No automatic evaluation retry", 1, attempts.get())
        assertEquals(2, requests.get()) // One generation, one failed evaluation.
        assertEquals(1, captureStarts.get()); assertEquals(1, captureFinishes.get())
        assertTrue("Unknown delivery retains this attempt's original audio", captureFile.isFile)
        assertArrayEquals(syntheticBytes, captureFile.readBytes())
        assertEquals("Unknown delivery is not a formal judgment or a receipt", before, awaitIo { allRows() })

        touchText("重试")
        waitText("隔离响应已验证")
        captureRetryIfRequested("received")
        assertEquals(2, attempts.get()); assertEquals(3, requests.get())
        assertEquals(1, captureStarts.get()); assertEquals(1, captureFinishes.get())
        assertEquals(2, evaluationRequests.size)
        val original = evaluationRequests.first()
        assertEquals(original.path, evaluationRequests.last().path)
        assertEquals(original.body, evaluationRequests.last().body)
        val commandId = JSONObject(original.body).getString("commandId")
        assertEquals(commandId, JSONObject(evaluationRequests.last().body).getString("commandId"))
        assertFalse("Delivered and registered audio is released", captureFile.exists())
        // Open a new database helper: a visible PASS is insufficient without its exact committed receipt.
        val received = awaitIo { allRows() }
        assertEquals(before - "review_working_state", received - "review_working_state")
        val row = received.getValue("review_working_state").single { it["account_id"] == fixture.account }
        val expected = PatternIndependentReceipt(1,
            PatternIndependentReceiptIdentity(fixture.account, fixture.runtime.accountEpoch, lease.reviewChapterId, lease.entryId,
                "chapter1", "sentence1", requireNotNull(row["ability_id"]), "target1"),
            "example", commandId, hash(syntheticBytes), hash(original.body.toByteArray()),
            PatternIndependentDecision.PASS, true, true, true, .9)
        assertEquals(lease.reviewChapterId, row["review_chapter_id"])
        assertEquals(expected.evidenceId, row["evidence_id"])
        assertEquals(0, fixture.count("review_chapter_results"))

        touchText("继续")
        waitTag("review-result-summary")
        val after = awaitIo { allRows() }
        assertEquals(1, fixture.count("review_chapter_results"))
        assertEquals(1, fixture.count("review_session_settlements"))
        assertEquals(0, fixture.count("review_working_state"))
        assertEquals(0, fixture.count("learning_obligations")); assertEquals(0, fixture.count("correction_flags"))
        assertEquals(3, fixture.scalar("select speaking_streak from pattern_items where account_id=?"))
        // Exact change to this one formal pattern; no other knowledge fields or account rows may drift.
        val expectedPatterns = before.getValue("pattern_items").map { old ->
            if (old["account_id"] == fixture.account && old["item_id"] == "pattern1") old + ("speaking_streak" to "3") else old
        }
        assertEquals(expectedPatterns, after.getValue("pattern_items"))
        for (table in listOf("learning_obligations", "correction_flags")) {
            assertEquals(before.getValue(table).filterNot { it["account_id"] == fixture.account && it["obligation_id"] == "obligation1" },
                after.getValue(table))
        }
        val settlementTables = setOf("pattern_items", "learning_obligations", "correction_flags", "normal_learning_days",
            "review_cycles", "review_queue_items", "review_chapters", "review_chapter_items", "review_sessions",
            "review_working_state", "review_chapter_results", "review_session_settlements")
        assertEquals(before.filterKeys { it !in settlementTables }, after.filterKeys { it !in settlementTables })
        assertEquals(before.mapValues { (_, rows) -> rows.filter { it["account_id"] != fixture.account } },
            after.mapValues { (_, rows) -> rows.filter { it["account_id"] != fixture.account } })
        assertEquals(3, requests.get()); assertEquals(1, captureStarts.get()); assertEquals(1, captureFinishes.get())
        assertFalse(lease.isActiveFor(fixture.account, fixture.runtime.accountEpoch))
        assertNull(graph.currentPatternReviewEntry())
    }

    @Test fun exitDialogContinueRetainsLeaseButConfirmedExitRevokesIt() {
        val reviewId = awaitIo { fixture.seed(); fixture.queue().single().reviewChapterId }
        val graph = graph()
        mount(graph, HomeDiaryRoute.ReviewChapter(reviewId, "isolated-entry"))
        waitTag("review-chapter-continue")
        val lease = requireNotNull(graph.currentPatternReviewEntry())
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitTag("review-exit-continue")
        assertSame(lease, graph.currentPatternReviewEntry())
        compose.onNodeWithTag("review-exit-continue").performClick()
        waitTag("review-chapter-continue")
        assertSame(lease, graph.currentPatternReviewEntry())
        assertTrue(lease.isActiveFor(fixture.account, fixture.runtime.accountEpoch))
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitTag("review-exit-confirm"); compose.onNodeWithTag("review-exit-confirm").performClick()
        compose.waitUntil(15_000) { graph.currentPatternReviewEntry() == null }
        assertFalse(lease.isActiveFor(fixture.account, fixture.runtime.accountEpoch))
        assertEquals(0, captureCreations.get()); assertEquals(0, fixture.count("review_chapter_results"))
    }

    @Test fun disposingAndReenteringSameGraphAndEntryNeverReusesAnOldLease() {
        val reviewId = awaitIo { fixture.seed(); fixture.queue().single().reviewChapterId }
        val graph = graph()
        mount(graph, HomeDiaryRoute.ReviewChapter(reviewId, "same-route-entry"))
        waitTag("review-chapter-continue")
        val old = requireNotNull(graph.currentPatternReviewEntry())
        compose.runOnIdle { visible.value = false }; compose.waitForIdle()
        assertFalse(old.isActiveFor(fixture.account, fixture.runtime.accountEpoch))
        assertNull(graph.currentPatternReviewEntry())
        compose.runOnIdle { visible.value = true }
        waitTag("review-chapter-continue")
        val current = requireNotNull(graph.currentPatternReviewEntry())
        assertNotSame(old, current)
        assertTrue(current.isActiveFor(fixture.account, fixture.runtime.accountEpoch))
        assertEquals(0, requests.get()); assertEquals(0, fixture.count("review_working_state"))
    }

    private fun graph(evaluate: (HttpRequest) -> HttpResponse = { HttpResponse(200, evaluated) }) = ProductionAppGraph(fixture.context, fixture.runtime, HttpTransport { request ->
        requests.incrementAndGet()
        when (request.path) {
            "/api/v1/learning/pattern-practice-sets" -> HttpResponse(200, generation)
            "/api/v1/learning/pattern-independent-evaluation" -> {
                lastEvaluation.set(request); evaluationRequests += request; evaluate(request)
            }
            else -> error("No unrelated HTTP request is authorized in this isolated fixture")
        }
    }, object : SessionCredentialStore {
        override suspend fun load(): StoredSession? = error("No credential access")
        override suspend fun save(session: StoredSession) = error("No credential access")
        override suspend fun clear() = error("No credential access")
    }, reviewPatternCaptureFactory = { _, _ ->
        captureCreations.incrementAndGet()
        object : ReadSpeakAudioCapture {
            override fun start(): Boolean { captureStarts.incrementAndGet(); return true }
            override fun cancel() { captureFile.delete() }
            override fun finish(): CapturedReadSpeakAudio {
                captureFinishes.incrementAndGet(); captureFile.writeBytes(syntheticBytes)
                return CapturedReadSpeakAudio(captureFile)
            }
        }
    })

    private fun seedExactPatternSource() {
        fixture.seed()
        // The template is not a literal fallback: retain the exact accepted source span.
        val evidence = JSONObject().put("version", 1).put("startUtf16", 2).put("endUtf16", 14)
            .put("surfaceForm", "used to walk").put("normalizedForm", "used to + verb")
            .put("allowedForms", JSONArray().put("used to walk"))
        assertEquals(1, fixture.db.update("target_selections", ContentValues().apply {
            put("source_evidence", evidence.toString())
        }, "account_id=? and chapter_id='chapter1' and target_id='target1'", arrayOf(fixture.account)))
    }

    private fun seedOtherAccountSentinels() {
        for (table in listOf("formation_flows", "chapters", "fixed_sentences", "target_selections", "pattern_items",
            "learning_obligations", "correction_flags", "normal_learning_days")) {
            val copies = fixture.db.rawQuery("select * from $table where account_id=?", arrayOf(fixture.account)).use { cursor ->
                buildList { while (cursor.moveToNext()) add(ContentValues().apply {
                    DatabaseUtils.cursorRowToContentValues(cursor, this)
                    put("account_id", "${fixture.account}-other")
                }) }
            }
            check(copies.isNotEmpty())
            copies.forEach { fixture.db.insertOrThrow(table, null, it) }
        }
    }

    /** UUID context only; every read uses a new helper, with blobs preserved as byte identities. */
    private fun allRows(): Map<String, List<Map<String, String?>>> = FormationDatabase(fixture.context).use { helper ->
        val db = helper.readableDatabase
        val tables = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' and name!='android_metadata' order by name", null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        tables.associateWith { table ->
            check(table.matches(Regex("[a-z_]+")))
            db.rawQuery("select * from $table order by rowid", null).use { cursor -> buildList {
                while (cursor.moveToNext()) add(cursor.columnNames.indices.associate { column ->
                    cursor.getColumnName(column) to when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_BLOB -> "blob:" + cursor.getBlob(column).joinToString("") { "%02x".format(it) }
                        else -> cursor.getString(column)
                    }
                })
            } }
        }
    }

    private fun touchText(text: String) {
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
    }

    /** Optional raw, synchronized window evidence; synthetic state only, unique name, no overwrite. */
    private fun captureRetryIfRequested(stage: String) {
        val phase = InstrumentationRegistry.getArguments().getString("patternReceiptRetryCapture") ?: return
        require(phase in setOf("before", "after") && stage in setOf("pending", "received"))
        val view = compose.activity.window.decorView
        val drawn = AtomicBoolean()
        val listener = ViewTreeObserver.OnDrawListener { drawn.set(true) }
        compose.activityRule.scenario.onActivity {
            view.viewTreeObserver.addOnDrawListener(listener); view.postInvalidateOnAnimation()
        }
        try { compose.waitUntil(10_000) { drawn.get() } }
        finally { compose.activityRule.scenario.onActivity {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
        } }
        val automation = instrumentation.uiAutomation
        automation.waitForIdle(200, 5_000)
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "visual-qa")
        check(directory.isDirectory || directory.mkdirs())
        val output = File(directory, "37-pattern-receipt-retry-$stage-$phase-${fixture.runId}.png")
        check(output.createNewFile())
        val bitmap = requireNotNull(automation.takeScreenshot())
        try { output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    private fun mount(graph: ProductionAppGraph, route: HomeDiaryRoute) {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.activityRule.scenario.onActivity { configureFocusedLearningWindow(it.window) }
        visible.value = true
        compose.setContent { RlTheme { RlPaperBackground { if (visible.value) HomeProductionFlow(graph, initialRoute = route) } } }
    }
    private fun waitTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun waitText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try { compose.waitUntil(20_000) { result.get() != null }; return requireNotNull(result.get()).getOrThrow() }
        finally { job.cancel(); scope.cancel() }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
