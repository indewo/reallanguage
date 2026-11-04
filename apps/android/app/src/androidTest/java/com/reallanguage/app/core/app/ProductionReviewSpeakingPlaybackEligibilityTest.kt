package com.reallanguage.app.core.app

import android.Manifest
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.database.Cursor
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.data.AndroidSentenceSpeaker
import com.reallanguage.app.feature.learning.data.CapturedReadSpeakAudio
import com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture
import com.reallanguage.app.feature.learning.data.SentenceSpeechEngine
import com.reallanguage.app.feature.review.data.AndroidReviewChapterRepository
import com.reallanguage.app.feature.review.domain.ReviewAbilityModule
import com.reallanguage.app.feature.review.domain.ReviewChapterLoadResult
import com.reallanguage.app.testing.ReviewNoJudgmentFixture
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual review Graph/UI and four training rounds before the fifth-round playback boundary.
 * Fixed facts are preaccepted; HTTP, permission and NOT_AUDIO capture are isolated doubles.
 * Engine acceptance is not proof of audible/complete speech, real pronunciation or AI quality. */
class ProductionReviewSpeakingPlaybackEligibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = ReviewNoJudgmentFixture(base)
    private val originalPermission = ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO)
    private val context = object : ContextWrapper(fixture.context) {
        override fun getApplicationContext() = this
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == Manifest.permission.RECORD_AUDIO) PackageManager.PERMISSION_GRANTED
            else super.checkPermission(permission, pid, uid)
    }
    private val permissionOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                error("The isolated granted permission must never open a platform permission dialog")
            }
        }
    }
    private val visible = mutableStateOf(false)
    private var mounted = false
    private val calls = CopyOnWriteArrayList<HttpRequest>()
    private val problem = AtomicReference<Throwable?>()
    private val starts = AtomicInteger()
    private val finishes = AtomicInteger()
    private val captureCreations = AtomicInteger()
    private val live = AtomicBoolean()
    private val captureFile = File(fixture.context.cacheDir, "review-full-synthetic.test")
    private val speechCalls = CopyOnWriteArrayList<Pair<String, Float>>()

    @After fun releaseOnlyTheUuidFixture() {
        if (mounted) {
            compose.runOnIdle { visible.value = false; compose.activity.viewModelStore.clear() }
            compose.waitForIdle()
        }
        fixture.close()
        assertEquals("Never mutate the real microphone permission", originalPermission,
            ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO))
    }

    @Test(timeout = 120_000)
    fun rejectedAnswerPlaybackDoesNotForfeitFifthRoundIndependentReview() = runBoundary(startAccepted = false)

    @Test(timeout = 120_000)
    fun acceptedAnswerPlaybackStillForfeitsFifthRoundIndependentReview() = runBoundary(startAccepted = true)

    private fun runBoundary(startAccepted: Boolean) {
        val reviewId = awaitIo { seedReview() }
        val graph = ProductionAppGraph(context, fixture.runtime, HttpTransport { request ->
            try { response(request) } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { problem.compareAndSet(null, failure); HttpResponse(500, "{}") }
        }, object : SessionCredentialStore {
            override suspend fun load(): StoredSession? = error("No credential access")
            override suspend fun save(session: StoredSession) = error("No credential access")
            override suspend fun clear() = error("No credential access")
        }, fullSentenceSpeakingCaptureFactory = { _, owner ->
            check(owner === fixture.runtime && captureCreations.incrementAndGet() == 1)
            object : ReadSpeakAudioCapture {
                override fun start(): Boolean { check(live.compareAndSet(false, true)); starts.incrementAndGet(); return true }
                override fun finish(): CapturedReadSpeakAudio {
                    check(live.compareAndSet(true, false))
                    captureFile.writeBytes(audio(finishes.incrementAndGet()))
                    return CapturedReadSpeakAudio(captureFile)
                }
                override fun cancel() { live.set(false); captureFile.delete() }
            }
        })
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        visible.value = true
        compose.setContent { CompositionLocalProvider(
            LocalContext provides context,
            LocalActivityResultRegistryOwner provides permissionOwner,
            LocalSentenceSpeakerFactory provides { _, allowed -> AndroidSentenceSpeaker(object : SentenceSpeechEngine {
                override suspend fun awaitReady() = true
                override fun start(text: String, rate: Float, locale: Locale, finished: (Boolean) -> Unit): Boolean {
                    check(locale == Locale.US)
                    speechCalls += text to rate
                    return startAccepted // Deliberately no onDone: this test only controls start admission.
                }
                override fun stop() = Unit
                override fun close() = Unit
            }, allowed) },
        ) { RlTheme { RlPaperBackground { if (visible.value) HomeProductionFlow(graph,
            initialRoute = HomeDiaryRoute.ReviewChapter(reviewId, "review-audio-${fixture.runId}")) } } } }
        mounted = true
        waitTag("review-chapter-continue")
        compose.onNodeWithTag("review-chapter-continue").assertIsDisplayed().performTouchInput { click(center) }
        waitTag("full-sentence-workspace")
        assertEquals("Never fall back to a real microphone", 1, captureCreations.get())
        val before = awaitIo { rows() }
        repeat(4) { index ->
            waitAttempt(if (index < 2) "中英跟读 · 第 ${index + 1} 次" else "英文朗读 · 第 ${index + 1} 次")
            tap(if (index < 2) R.string.full_sentence_speaking_start_follow else R.string.full_sentence_speaking_start_read)
            waitTag("full-sentence-recording-status")
            tap(if (index < 2) R.string.full_sentence_speaking_follow_finished else R.string.full_sentence_speaking_read_finished)
            waitText(base.getString(R.string.full_sentence_speaking_continue))
            assertEquals(index + 1, calls.size)
            assertEquals("Training is not a formal independent result", before, awaitIo { rows() })
            tap(R.string.full_sentence_speaking_continue)
        }
        waitAttempt("独立复述 · 第 5 次")
        assertTrue("The fifth-round route must not play the answer automatically", speechCalls.isEmpty())
        compose.onNodeWithText(ENGLISH).assertDoesNotExist()
        tap(R.string.full_sentence_speaking_play_answer)
        assertEquals(listOf(ENGLISH to .82f), speechCalls.toList())
        if (!startAccepted) {
            compose.onNodeWithText("音频播放失败，请点“播放”重试。")
                .performScrollTo().assertIsDisplayed()
            tap(R.string.full_sentence_speaking_play_answer)
            assertEquals(listOf(ENGLISH to .82f, ENGLISH to .82f), speechCalls.toList())
        }
        val disqualified = compose.onNodeWithText(base.getString(R.string.full_sentence_speaking_answer_disqualifies))
        if (startAccepted) disqualified.performScrollTo().assertIsDisplayed() else disqualified.assertDoesNotExist()
        assertEquals("A playback interaction cannot settle any review data", before, awaitIo { rows() })

        tap(R.string.full_sentence_speaking_start_retell)
        waitTag("full-sentence-recording-status")
        tap(R.string.full_sentence_speaking_retell_finished)
        waitText(base.getString(R.string.full_sentence_speaking_confirm_transcript))
        compose.onNode(hasSetTextAction()).performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(ENGLISH)))
        assertEquals(before, awaitIo { rows() })
        tap(R.string.full_sentence_speaking_confirm_transcript)
        compose.waitUntil(15_000) { healthy(); calls.size == 6 }
        val evaluation = JSONObject(calls.last().body)
        assertEquals("Use the actual start result, not the click, as the playback evidence", startAccepted,
            evaluation.getBoolean("answerAudioPlayed"))
        if (startAccepted) {
            waitText(base.getString(R.string.full_sentence_speaking_retry))
            compose.onNodeWithText(base.getString(R.string.full_sentence_speaking_stop)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("review-result-summary").assertDoesNotExist()
            assertEquals("A helped attempt remains in this review until the existing explicit decision to continue", before, awaitIo { rows() })
        } else {
            waitTag("review-result-summary")
            assertEquals(1, fixture.count("review_chapter_results"))
            assertEquals(1, fixture.count("review_session_settlements"))
            assertEquals(0, fixture.count("learning_obligations")); assertEquals(0, fixture.count("correction_flags"))
            val after = awaitIo { rows() }
            assertEquals(before.getValue("sentence_capabilities").map { row ->
                if (row["account_id"] == fixture.account) row + ("status" to "VERIFIED") else row
            }, after.getValue("sentence_capabilities"))
            val settled = setOf("sentence_capabilities", "learning_obligations", "correction_flags", "normal_learning_days",
                "review_cycles", "review_queue_items", "review_chapters", "review_chapter_items", "review_sessions",
                "review_working_state", "review_chapter_results", "review_session_settlements")
            assertEquals(before.filterKeys { it !in settled }, after.filterKeys { it !in settled })
            assertEquals(before.mapValues { (_, value) -> value.filter { it["account_id"] != fixture.account } },
                after.mapValues { (_, value) -> value.filter { it["account_id"] != fixture.account } })
        }
        healthy()
        assertEquals(listOf(TRAINING, TRAINING, TRAINING, TRAINING, TRANSCRIPTION, EVALUATION), calls.map { it.path })
        assertEquals(5, starts.get()); assertEquals(5, finishes.get())
        assertFalse(captureFile.exists())
        assertEquals(if (startAccepted) 1 else 2, speechCalls.size)
    }

    private suspend fun seedReview(): String {
        fixture.seed(capability = "FULL_SPEAKING")
        assertEquals(1, fixture.db.update("learning_obligations", ContentValues().apply { put("target_id", "") },
            "account_id=? and obligation_id='obligation1'", arrayOf(fixture.account)))
        for ((account, status) in listOf(fixture.account to "LEARNING", "other-${fixture.runId}" to "VERIFIED")) {
            fixture.db.execSQL("insert into sentence_capabilities(account_id,sentence_id,capability_type,status) values(?,'sentence1','FULL_SPEAKING',?)",
                arrayOf(account, status))
        }
        val reviewId = fixture.queue().single().reviewChapterId
        val loaded = AndroidReviewChapterRepository(context, fixture.runtime).loadChapter(reviewId) as ReviewChapterLoadResult.Ready
        assertEquals(ReviewAbilityModule.FULL_SENTENCE_SPEAKING, loaded.chapter.abilities.single().module)
        assertEquals("sentence1", loaded.chapter.abilities.single().sentenceId)
        return reviewId
    }

    private fun response(request: HttpRequest): HttpResponse {
        val index = calls.size
        check(index in 0..5 && request.method == "POST")
        val body = JSONObject(request.body)
        check(body.getString("chapterId") == "chapter1" && body.getString("sentenceId") == "sentence1")
        check(body.getString("audioMimeType") == "audio/mp4" && body.getString("commandId").isNotBlank())
        assertArrayEquals(audio(if (index < 4) index + 1 else 5), Base64.getDecoder().decode(body.getString("audioBase64")))
        val result = when (index) {
            in 0..3 -> {
                check(request.path == TRAINING && body.getString("targetTextEn") == ENGLISH)
                check(body.getString("completionSignal") == "MANUAL_CONFIRMATION")
                JSONObject().put("feedbackZh", "隔离训练反馈，非真人评价。").put("action", "CONTINUE")
                    .put("transcript", ENGLISH).put("contentComplete", true).put("feedbackConfidence", .95)
            }
            4 -> {
                check(request.path == TRANSCRIPTION)
                JSONObject().put("transcript", ENGLISH).put("lowConfidenceSegments", JSONArray()).put("confidence", JSONObject.NULL)
            }
            else -> {
                check(request.path == EVALUATION && body.getString("targetTextEn") == ENGLISH)
                check(body.getString("confirmedTranscript") == ENGLISH && body.getBoolean("targetHidden") && body.getBoolean("userTranscriptConfirmed"))
                // A schema-valid PASS candidate still cannot bypass the local help-eligibility guard.
                JSONObject().put("decision", "PASS").put("completenessScore", 95).put("coreMeaningRecognizable", true)
                    .put("pronunciationIntelligible", true).put("pronunciationConfidence", .95).put("feedbackZh", "隔离候选，非真实原音判断。")
            }
        }
        calls += request
        return HttpResponse(200, result.toString())
    }

    private fun rows(): Map<String, List<Map<String, String?>>> = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        val names = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' and name!='android_metadata' order by name", null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        names.associateWith { table ->
            check(table.matches(Regex("[a-z_]+")))
            db.rawQuery("select * from $table order by rowid", null).use { cursor -> buildList {
                while (cursor.moveToNext()) add(cursor.columnNames.indices.associate { column -> cursor.getColumnName(column) to
                    when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_BLOB -> "blob:" + Base64.getEncoder().encodeToString(cursor.getBlob(column))
                        else -> cursor.getString(column)
                    }
                })
            } }
        }
    }

    private fun tap(resource: Int) {
        val node = compose.onNode(hasText(base.getString(resource)) and hasClickAction())
        if (runCatching { node.assertIsDisplayed() }.isFailure) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
        compose.waitForIdle(); healthy()
    }
    private fun waitTag(tag: String) {
        compose.waitUntil(15_000) { healthy(); compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun waitText(text: String) { compose.waitUntil(15_000) { healthy(); compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitAttempt(text: String) { waitText(text); compose.onNodeWithTag("full-sentence-attempt").assertTextEquals(text) }
    private fun healthy() { problem.get()?.let { throw AssertionError("Isolated review speaking fixture failed", it) } }
    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try { compose.waitUntil(20_000) { healthy(); result.get() != null }; return requireNotNull(result.get()).getOrThrow() }
        finally { job.cancel(); scope.cancel() }
    }
    private fun audio(attempt: Int) = "NOT_AUDIO:${fixture.runId}:$attempt".toByteArray()
    private companion object {
        const val ENGLISH = "I used to walk."
        const val TRAINING = "/api/v1/learning/reading-feedback"
        const val TRANSCRIPTION = "/api/v1/learning/speaking-transcriptions"
        const val EVALUATION = "/api/v1/learning/full-sentence-speaking-evaluations"
    }
}
