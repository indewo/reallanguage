package com.reallanguage.app.core.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.fixedSourceAccountHash
import com.reallanguage.app.feature.learning.data.AndroidFullSpeakingPayloadKeys
import com.reallanguage.app.feature.learning.data.AndroidFullSpeakingRecoveryJournal
import com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore
import com.reallanguage.app.feature.learning.data.CapturedReadSpeakAudio
import com.reallanguage.app.feature.learning.data.FullSpeakingRecoveryLoad
import com.reallanguage.app.feature.learning.data.FullSpeakingRecoveryPhase
import com.reallanguage.app.feature.learning.data.FullSpeakingRecoverySnapshot
import com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Graph, route, repositories/codecs and private SQLite; only HTTP/capture are doubles.
 * NOT_AUDIO bytes prove lifecycle wiring, never real microphone, pronunciation, AI or OEM behavior.
 * Four previously accepted training rounds are fixture input, not evidence of that earlier journey. */
class ProductionFullSpeakingConnectionCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "full-speaking-connection-${UUID.randomUUID()}"
    private val account = "owner-$prefix"
    private val chapterId = "chapter-$prefix"
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
    private val runtime = SessionRuntime().apply { update("ISOLATED_ACCESS", account) }
    private val credentials = MemoryCredentials(StoredSession(account, "ISOLATED_REFRESH", true))
    private val chapter = FixedLearningChapter(chapterId, 1,
        listOf(FixedLearningSentence("s1", ENGLISH, "我喝绿茶。", true)))
    private val mounted = mutableStateOf(false)
    private val calls = CopyOnWriteArrayList<String>()
    private val fixtureFailure = AtomicReference<Throwable?>(null)
    private val activeCapture = AtomicBoolean()
    private val captureStarts = AtomicInteger()
    private val captureFinishes = AtomicInteger()
    private val liveCancellations = AtomicInteger()
    private val captureCreations = AtomicInteger()
    private val pendingEvaluation = CompletableDeferred<HttpResponse>()
    private val original = AtomicReference<File?>(null)
    private val originalSha = AtomicReference<String?>(null)
    private val transcriptionBody = AtomicReference<String?>(null)
    private var evaluationsAllowed = false
    private var evaluationRetryAllowed = false
    private val evaluationBodies = CopyOnWriteArrayList<String>()
    private lateinit var graph: ProductionAppGraph

    @Before fun prepareOnlyUuidContextAndExistingPermission() {
        assertEquals("Host owns permission; this test never grants/revokes it", PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO))
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.mkdir())
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        awaitIo { FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离口说连接测试')", arrayOf(account, prefix))
            db.execSQL("insert into chapters values(?,?,?,0,'IN_PROGRESS')", arrayOf(account, prefix, chapterId))
            db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,'s1',0,'我喝绿茶。',?,'FOCUS')",
                arrayOf(account, chapterId, ENGLISH))
        } }
        graph = ProductionAppGraph(context, runtime, HttpTransport { request ->
            try { respond(request) } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { fixtureFailure.set(failure); HttpResponse(500, "{}") }
        }, credentials, fullSentenceSpeakingCaptureFactory = { _, owner ->
            check(owner === runtime && captureCreations.incrementAndGet() == 1)
            object : ReadSpeakAudioCapture {
                override fun start(): Boolean {
                    check(activeCapture.compareAndSet(false, true))
                    captureStarts.incrementAndGet()
                    return true
                }
                override fun finish(): CapturedReadSpeakAudio {
                    check(activeCapture.compareAndSet(true, false))
                    check(captureFinishes.incrementAndGet() == 1)
                    val file = File(context.cacheDir, "owned-attempt.test")
                    check(!file.exists())
                    file.writeBytes(syntheticAudio())
                    original.set(file)
                    originalSha.set(sha(file.readBytes()))
                    return CapturedReadSpeakAudio(file)
                }
                override fun cancel() { if (activeCapture.compareAndSet(true, false)) liveCancellations.incrementAndGet() }
            }
        })
    }

    @After fun disposeAndRemoveOnlyThisFixture() {
        pendingEvaluation.complete(HttpResponse(503, "{}"))
        compose.runOnIdle { mounted.value = false; compose.activity.viewModelStore.clear() }
        compose.waitForIdle()
        check(account == "owner-$prefix")
        runBlocking(Dispatchers.IO) { AndroidFullSpeakingPayloadKeys().deleteAccount(fixedSourceAccountHash(account)) }
        val database = privateDb.getDatabasePath(DATABASE).canonicalFile
        check(database.parentFile == base.cacheDir.canonicalFile && database.name.startsWith("$prefix-"))
        check(privateDb.deleteDatabase(DATABASE) || !database.exists())
        synchronized(preferences) { preferences.toList() }.forEach { name ->
            check(name.startsWith("$prefix-")); base.deleteSharedPreferences(name)
        }
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.name == prefix)
        check(!directory.exists() || directory.deleteRecursively())
    }

    @Test fun connectionCoverStopsOnlyLiveCaptureAndReconnectRequiresAnExplicitNewStart() {
        mount(finalRound = false)
        tapText(R.string.full_sentence_speaking_start_follow)
        waitForCapture(1)
        compose.onNodeWithTag("full-sentence-recording-status").assertIsDisplayed()
        assertTrue(activeCapture.get())
        loseConnection()
        compose.waitUntil(5_000) { !activeCapture.get() }
        assertEquals("Cover must interrupt the live microphone exactly once", 1, liveCancellations.get())
        assertEquals("Interruption is not the user's Finished action", 0, captureFinishes.get())
        assertTrue("Unsubmitted audio must not trigger any HTTP", calls.isEmpty())
        reconnect()
        compose.onNodeWithTag("full-sentence-recording-status").assertDoesNotExist()
        assertFalse(activeCapture.get())
        assertEquals("Recovery must not automatically reopen the microphone", 1, captureStarts.get())
        assertEquals(listOf(HEALTH, REFRESH), calls.toList())
        tapText(R.string.full_sentence_speaking_start_follow)
        waitForCapture(2)
        compose.onNodeWithTag("full-sentence-recording-status").assertIsDisplayed()
        assertTrue(activeCapture.get())
        assertEquals(2, captureStarts.get())
        assertEquals(0, captureFinishes.get())
        assertEquals(listOf(HEALTH, REFRESH), calls.toList())
    }

    @Test fun currentOwnerDeliveredCandidatePersistsUnderCoverWithoutRerecordingOrReevaluation() {
        evaluationsAllowed = true
        mount(finalRound = true)
        tapText(R.string.full_sentence_speaking_start_retell)
        waitForCapture(1)
        tapText(R.string.full_sentence_speaking_retell_finished)
        val transcribed = assertTranscriptionCustody()
        tapText(R.string.full_sentence_speaking_confirm_transcript)
        val pending = assertPendingEvaluationCustody(transcribed.recordingId)
        assertFalse(activeCapture.get())
        assertFalse("Read-back-confirmed encrypted ASR custody releases the plaintext file", requireNotNull(original.get()).exists())
        loseConnection()
        assertEquals("Connection cover must preserve the same encrypted paid request", pending,
            awaitSnapshot(FullSpeakingRecoveryPhase.EvaluationPending))
        assertEquals(0, liveCancellations.get())
        pendingEvaluation.complete(HttpResponse(200, JSONObject().put("decision", "INCONCLUSIVE")
            .put("completenessScore", 40).put("coreMeaningRecognizable", false)
            .put("pronunciationIntelligible", false).put("feedbackZh", FEEDBACK).put("pronunciationConfidence", 0.4).toString()))
        val saved = awaitIo { withTimeout(10_000) {
            var value: Map<String, String>?
            do { value = progress().read(); if (value?.get("finalDecision") != "INCONCLUSIVE") delay(20) }
            while (value?.get("finalDecision") != "INCONCLUSIVE")
            requireNotNull(value)
        } }
        assertEquals("INCONCLUSIVE", saved["candidateDecision"])
        assertEquals(FEEDBACK, saved["candidateFeedback"])
        assertEquals(ENGLISH, saved["transcript"])
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        compose.onAllNodesWithText(FEEDBACK).assertCountEquals(0)
        assertEquals(listOf(TRANSCRIPTION, EVALUATION), calls.toList())
        assertFalse("Accepted evaluation releases its temporary original normally", requireNotNull(original.get()).exists())
        assertDeliveredCustody(transcribed.recordingId)
        reconnect()
        compose.onNodeWithText(FEEDBACK).assertExists()
        assertEquals(saved, awaitIo { progress().read() })
        assertEquals(1, captureStarts.get())
        assertEquals(1, captureFinishes.get())
        assertEquals(listOf(TRANSCRIPTION, EVALUATION, HEALTH, REFRESH), calls.toList())
    }

    @Test fun evaluationFailureOffersAnExplicitSameAttemptRetryWithoutReopeningTheMicrophone() {
        evaluationsAllowed = true
        evaluationRetryAllowed = true
        mount(finalRound = true)
        tapText(R.string.full_sentence_speaking_start_retell)
        waitForCapture(1)
        tapText(R.string.full_sentence_speaking_retell_finished)
        val transcribed = assertTranscriptionCustody()
        tapText(R.string.full_sentence_speaking_confirm_transcript)
        val pending = assertPendingEvaluationCustody(transcribed.recordingId)
        pendingEvaluation.complete(HttpResponse(503, "{}"))
        val failedText = base.getString(R.string.learning_audio_retry_pending)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(failedText).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(failedText).performScrollTo().assertIsDisplayed()
        assertEquals("Unknown evaluation retains the exact encrypted body, command and original audio", pending,
            awaitSnapshot(FullSpeakingRecoveryPhase.EvaluationPending))
        assertFalse(requireNotNull(original.get()).exists())
        captureRetryIfRequested()
        tapText(R.string.read_speak_retry)
        compose.waitUntil(10_000) { assertHealthy(); compose.onAllNodesWithText(FEEDBACK).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(FEEDBACK).assertExists()
        assertEquals(listOf(TRANSCRIPTION, EVALUATION, EVALUATION), calls.toList())
        assertEquals(2, evaluationBodies.size)
        assertEquals("The real UI must reuse the frozen evaluation command and facts", evaluationBodies[0], evaluationBodies[1])
        assertEquals(1, captureStarts.get())
        assertEquals(1, captureFinishes.get())
        assertEquals(0, liveCancellations.get())
        val saved = awaitIo { withTimeout(10_000) {
            var value: Map<String, String>?
            do { value = progress().read(); if (value?.get("finalDecision") != "INCONCLUSIVE") delay(20) }
            while (value?.get("finalDecision") != "INCONCLUSIVE")
            requireNotNull(value)
        } }
        assertEquals("INCONCLUSIVE", saved["finalDecision"])
        assertDeliveredCustody(transcribed.recordingId)
    }

    @Test fun terminalEvaluationOffersOnlyAnExplicitNewRecordingAndNeverReplaysItsCommand() {
        evaluationsAllowed = true
        mount(finalRound = true)
        tapText(R.string.full_sentence_speaking_start_retell)
        waitForCapture(1)
        tapText(R.string.full_sentence_speaking_retell_finished)
        val transcribed = assertTranscriptionCustody()
        tapText(R.string.full_sentence_speaking_confirm_transcript)
        assertPendingEvaluationCustody(transcribed.recordingId)
        pendingEvaluation.complete(HttpResponse(409, """{"error":{"code":"AI_OPERATION_FAILED","retryable":false}}"""))
        val explanation = base.getString(R.string.full_sentence_speaking_retry_blocked)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(explanation).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(explanation).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(base.getString(R.string.read_speak_retry)).assertDoesNotExist()
        compose.onNodeWithText(ENGLISH).assertIsNotEnabled()
        assertEquals(listOf(TRANSCRIPTION, EVALUATION), calls.toList())
        val blocked = awaitSnapshot(FullSpeakingRecoveryPhase.Blocked)
        assertEquals(transcribed.recordingId, blocked.recordingId)
        assertNull("A terminal command cannot retain a replayable request", blocked.requestBody)
        assertNull(blocked.evaluation)
        assertFalse(requireNotNull(original.get()).exists())
        tapText(R.string.full_sentence_speaking_new_recording)
        waitForCapture(2)
        compose.onNodeWithTag("full-sentence-recording-status").assertIsDisplayed()
        assertTrue(activeCapture.get())
        assertEquals(2, captureStarts.get())
        assertEquals(1, captureFinishes.get())
        assertEquals(listOf(TRANSCRIPTION, EVALUATION), calls.toList())
        assertFalse("Explicit new capture replaces only this old attempt", requireNotNull(original.get()).exists())
        val fresh = awaitSnapshot(FullSpeakingRecoveryPhase.Cleared)
        assertNotEquals("Only explicit Start authorizes a fresh recording identity", blocked.recordingId, fresh.recordingId)
        assertNull(fresh.requestBody)
        assertEquals("", awaitIo { progress().read() }?.get("finalDecision"))
    }

    private fun mount(finalRound: Boolean) {
        if (finalRound) assertTrue(awaitIo { progress().write(mapOf("version" to "1", "attemptIndex" to "4",
            "answerAudioPlayed" to "false", "transcript" to "", "feedbackZh" to "",
            "trainingCanContinue" to "false", "finalDecision" to "")) })
        mounted.value = true
        compose.setContent { CompositionLocalProvider(LocalContext provides context,
            LocalActivityResultRegistryOwner provides compose.activity) { RlTheme { RlPaperBackground {
            if (mounted.value) HomeProductionFlow(graph, initialRoute = HomeDiaryRoute.FullSentenceSpeaking(chapter, "s1", "隔离既有讲解", emptyList()))
        } } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("full-sentence-workspace").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Never start without the injected capture seam", 1, captureCreations.get())
        assertTrue(calls.isEmpty())
    }
    private fun captureRetryIfRequested() {
        val name = InstrumentationRegistry.getArguments().getString("retryScreenshot") ?: return
        check(name.matches(Regex("full-speaking-retry-[a-z-]+\\.png")))
        val destination = File(requireNotNull(base.getExternalFilesDir(null)), name)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { destination.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun loseConnection() {
        compose.runOnIdle { graph.connectionGate.networkLost() }
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        compose.onNodeWithTag("full-sentence-workspace").assertDoesNotExist()
    }
    private fun reconnect() {
        val epoch = runtime.accountEpoch
        compose.onNodeWithTag("connection-error-reconnect").assertIsDisplayed().performTouchInput { click(center) }
        compose.waitUntil(5_000) { graph.connectionGate.incident.value == null }
        compose.waitForIdle()
        assertEquals(epoch, runtime.accountEpoch)
        assertEquals(account, runtime.accountId)
        compose.onNodeWithTag("full-sentence-workspace").assertIsDisplayed()
        assertHealthy()
    }
    private fun tapText(resource: Int) {
        val text = base.getString(resource)
        compose.waitUntil(10_000) {
            assertHealthy()
            runCatching { compose.onNodeWithText(text).assertIsDisplayed().assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithText(text).performTouchInput { click(center) }
    }
    private fun waitForCapture(expectedStarts: Int) {
        compose.waitUntil(10_000) { assertHealthy(); activeCapture.get() && captureStarts.get() == expectedStarts &&
            compose.onAllNodesWithTag("full-sentence-recording-status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("full-sentence-recording-status").assertIsDisplayed()
    }
    private fun awaitSnapshot(phase: FullSpeakingRecoveryPhase): FullSpeakingRecoverySnapshot = awaitIo {
        withTimeout(10_000) {
            val journal = AndroidFullSpeakingRecoveryJournal(context, runtime, chapterId, "s1", ENGLISH, 4)
            var loaded: FullSpeakingRecoveryLoad
            do {
                loaded = journal.load()
                if (loaded !is FullSpeakingRecoveryLoad.Loaded || loaded.snapshot.phase != phase) delay(20)
            } while (loaded !is FullSpeakingRecoveryLoad.Loaded || loaded.snapshot.phase != phase)
            loaded.snapshot
        }
    }
    private fun assertOriginalBody(body: String) {
        val request = JSONObject(body)
        assertEquals(chapterId, request.getString("chapterId"))
        assertEquals("s1", request.getString("sentenceId"))
        assertEquals("audio/mp4", request.getString("audioMimeType"))
        assertTrue(request.getString("commandId").isNotBlank())
        assertEquals(requireNotNull(originalSha.get()), sha(Base64.getDecoder().decode(request.getString("audioBase64"))))
    }
    private fun assertTranscriptionCustody(): FullSpeakingRecoverySnapshot {
        val confirm = base.getString(R.string.full_sentence_speaking_confirm_transcript)
        compose.waitUntil(10_000) { assertHealthy()
            runCatching { compose.onNodeWithText(confirm).assertIsDisplayed().assertIsEnabled(); true }.getOrDefault(false) }
        val snapshot = awaitSnapshot(FullSpeakingRecoveryPhase.TranscriptionDelivered)
        val body = requireNotNull(snapshot.requestBody)
        assertEquals(requireNotNull(transcriptionBody.get()), body)
        assertOriginalBody(body)
        assertEquals(ENGLISH, snapshot.transcription?.transcript)
        assertFalse(requireNotNull(original.get()).exists())
        return snapshot
    }
    private fun assertPendingEvaluationCustody(recordingId: String): FullSpeakingRecoverySnapshot {
        compose.waitUntil(5_000) { assertHealthy(); calls.contains(EVALUATION) && evaluationBodies.isNotEmpty() }
        val snapshot = awaitSnapshot(FullSpeakingRecoveryPhase.EvaluationPending)
        assertEquals(recordingId, snapshot.recordingId)
        val body = requireNotNull(snapshot.requestBody)
        assertEquals(evaluationBodies[0], body)
        assertOriginalBody(body)
        assertNotEquals(JSONObject(requireNotNull(transcriptionBody.get())).getString("commandId"), JSONObject(body).getString("commandId"))
        assertEquals(ENGLISH, snapshot.confirmedTranscript)
        assertFalse(snapshot.answerAudioPlayed)
        return snapshot
    }
    private fun assertDeliveredCustody(recordingId: String) {
        val delivered = awaitSnapshot(FullSpeakingRecoveryPhase.EvaluationDelivered)
        assertEquals(recordingId, delivered.recordingId)
        assertNull("Accepted candidate drops the encrypted audio-bearing body", delivered.requestBody)
        assertEquals(FEEDBACK, delivered.evaluation?.feedbackZh)
        assertEquals(ENGLISH, delivered.confirmedTranscript)
    }
    private suspend fun respond(request: HttpRequest): HttpResponse {
        val call = "${request.method} ${request.path}"
        check(call in setOf(HEALTH, REFRESH, TRANSCRIPTION, EVALUATION))
        check(call !in calls || (evaluationRetryAllowed && call == EVALUATION && calls.count { it == call } == 1))
        calls += call
        if (call == HEALTH) return HttpResponse(200, """{"status":"ok","service":"api","requestId":"isolated-speaking"}""")
        if (call == REFRESH) return HttpResponse(200, """{"status":"AUTHENTICATED","accountId":"$account","accessToken":"ISOLATED_ROTATED_ACCESS","refreshToken":"ISOLATED_ROTATED_REFRESH","settingsCompleted":true}""")
        check(evaluationsAllowed)
        val body = JSONObject(requireNotNull(request.body))
        check(body.getString("chapterId") == chapterId && body.getString("sentenceId") == "s1")
        check(body.getString("commandId").isNotBlank())
        check(Base64.getDecoder().decode(body.getString("audioBase64")).contentEquals(syntheticAudio()))
        if (call == TRANSCRIPTION) {
            transcriptionBody.set(requireNotNull(request.body))
            return HttpResponse(200, JSONObject().put("transcript", ENGLISH)
                .put("lowConfidenceSegments", JSONArray()).put("confidence", JSONObject.NULL).toString())
        }
        check(body.getString("confirmedTranscript") == ENGLISH && body.getBoolean("userTranscriptConfirmed"))
        evaluationBodies += requireNotNull(request.body)
        if (evaluationRetryAllowed && evaluationBodies.size == 2) return HttpResponse(200, JSONObject()
            .put("decision", "INCONCLUSIVE").put("completenessScore", 40)
            .put("coreMeaningRecognizable", false).put("pronunciationIntelligible", false)
            .put("feedbackZh", FEEDBACK).put("pronunciationConfidence", 0.4).toString())
        return pendingEvaluation.await()
    }
    private fun progress() = AndroidLearningProgressStore(context, runtime, chapterId, "s1", "LR-06")
    private fun syntheticAudio() = "NOT_AUDIO:$prefix".toByteArray(Charsets.UTF_8)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun assertHealthy() { fixtureFailure.get()?.let { throw AssertionError("Isolated fixture contract failed", it) } }
    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try {
            compose.waitUntil(15_000) { assertHealthy(); result.get() != null }
            return requireNotNull(result.get()).getOrThrow()
        } finally { job.cancel(); scope.cancel() }
    }
    private class MemoryCredentials(var value: StoredSession?) : SessionCredentialStore {
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) { value = session }
        override suspend fun clear() { value = null }
    }
    private companion object {
        const val DATABASE = "real-language-learning.db"
        const val ENGLISH = "I drink green tea."
        const val FEEDBACK = "隔离反馈：证据不足，暂不作能力判定。"
        const val HEALTH = "GET /api/v1/health"
        const val REFRESH = "POST /api/v1/auth/sessions/refresh"
        const val TRANSCRIPTION = "POST /api/v1/learning/speaking-transcriptions"
        const val EVALUATION = "POST /api/v1/learning/full-sentence-speaking-evaluations"
    }
}
