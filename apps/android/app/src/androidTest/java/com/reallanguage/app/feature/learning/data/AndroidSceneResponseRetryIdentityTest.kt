package com.reallanguage.app.feature.learning.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.SceneEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SceneEvaluationResult
import com.reallanguage.app.feature.learning.domain.SceneTaskGenerationResult
import com.reallanguage.app.feature.learning.domain.SpeakingTranscriptionResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real repository/JSON/private SQLite; NOT_AUDIO bytes and fake HTTP prove no microphone, AI or billing quality. */
class AndroidSceneResponseRetryIdentityTest {
    @Test fun transcriptionUnknownDeliveryRetainsAudioAndTheExactBodyForExplicitRetry() = runBlocking {
        for (fault in listOf(Fault.UNAVAILABLE, Fault.PROCESSING, Fault.MALFORMED_SUCCESS)) Fixture().use { f ->
            f.prepare(Phase.TRANSCRIPTION)
            f.respond = { fault.response() }
            assertEquals(SpeakingTranscriptionResult.Failed, f.transcribe())
            assertTrue("Unknown transcription must retain its original", f.audio.exists())
            assertEquals(1, f.requests.size)
            f.respond = { HttpResponse(200, TRANSCRIPTION_BODY) }

            assertTrue(f.transcribe() is SpeakingTranscriptionResult.Ready)
            assertEquals(2, f.requests.size)
            f.assertFrozen(0, 1, Phase.TRANSCRIPTION)
            f.assertCaptures(1)
            assertTrue("Confirmed evaluation still needs this recording", f.audio.exists())
        }
    }

    @Test fun evaluationUnknownDeliveryRetainsConfirmedEvidenceAndItsOriginalCommand() = runBlocking {
        for (fault in Fault.entries) Fixture().use { f ->
            f.prepare(Phase.EVALUATION)
            f.respond = { fault.response() }
            assertEquals(SceneEvaluationResult.Failed, f.evaluate())
            assertTrue("Unknown evaluation is not successful delivery", f.audio.exists())
            f.respond = { HttpResponse(200, EVALUATION_BODY) }

            assertTrue(f.evaluate() is SceneEvaluationResult.Ready)
            assertEquals(3, f.requests.size)
            f.assertFrozen(1, 2, Phase.EVALUATION)
            assertNotEquals(f.json(0).getString("commandId"), f.json(1).getString("commandId"))
            f.assertCaptures(1)
            assertFalse(f.audio.exists())
        }
    }

    @Test fun terminalDeliveryCannotCreateAReplacementCommandOrRecaptureOnRepeatedCalls() = runBlocking {
        val faults = listOf(
            HttpResponse(409, errorBody("IDEMPOTENCY_KEY_REUSED", false)),
            HttpResponse(409, errorBody("AI_CHARGE_RECONCILIATION_REQUIRED", false)),
            HttpResponse(410, errorBody("AI_OPERATION_EXPIRED", false)),
        )
        for (phase in Phase.entries) for (fault in faults) Fixture().use { f ->
            f.prepare(phase)
            val first = f.requests.size
            f.respond = { fault }
            assertEquals(phase.failed, f.call(phase))
            f.assertAudioRequest(first, phase)
            val frozen = f.requests.last().body
            f.respond = { throw AssertionError("Terminal delivery must not send a replacement request") }

            repeat(2) { assertEquals(phase.failed, f.call(phase)) }
            assertEquals(first + 1, f.requests.size)
            assertEquals(frozen, f.requests.last().body)
            f.assertCaptures(1)
        }
    }

    @Test fun cancelledLateEvaluationCannotDeleteOrReplaceTheExplicitNewRecordingsEvidence() = runBlocking {
        Fixture().use { f ->
            f.prepare(Phase.EVALUATION)
            val oldAudio = f.audio
            val held = HeldResponse()
            f.respond = { request ->
                when (request.path) {
                    TRANSCRIBE_PATH -> HttpResponse(200, TRANSCRIPTION_BODY.replace(TEXT, FRESH_TEXT))
                    EVALUATE_PATH -> if (f.requests.count { it.path == EVALUATE_PATH } == 1) held.await()
                        else HttpResponse(200, EVALUATION_BODY.replace("PASS", "INCONCLUSIVE"))
                    else -> error("Unexpected isolated endpoint")
                }
            }
            val old = async(start = CoroutineStart.UNDISPATCHED) { f.evaluate() }
            try {
                assertTrue(held.isWaiting)
                f.repository.cancelCapture()
                assertFalse(oldAudio.exists())
                assertTrue("Explicit new recording is a new attempt after cancellation", f.repository.startCapture())
                val freshAudio = f.audio
                assertNotEquals(oldAudio, freshAudio)
                val freshTranscript = withTimeout(2_000) { f.transcribe() } as SpeakingTranscriptionResult.Ready
                assertEquals(FRESH_TEXT, freshTranscript.value.transcript)
                assertTrue(freshAudio.exists())

                held.release(HttpResponse(200, EVALUATION_BODY))
                val oldResult = old.await()
                assertTrue("Old finally must not clear the new attempt's pendingAudio", freshAudio.exists())
                assertEquals(SceneEvaluationResult.Failed, oldResult)
                val fresh = f.evaluate(FRESH_TEXT) as SceneEvaluationResult.Ready
                assertEquals(SceneEvaluationDecision.INCONCLUSIVE, fresh.candidate.decision)
                assertEquals(4, f.requests.size)
                assertNotEquals(f.json(1).getString("commandId"), f.json(3).getString("commandId"))
                assertNotEquals(f.json(1).getString("audioBase64"), f.json(3).getString("audioBase64"))
                assertFalse(freshAudio.exists())
                f.assertCaptures(2)
            } finally {
                held.release(HttpResponse(503, ""))
                withContext(NonCancellable) { withTimeout(2_000) { old.join() } }
            }
        }
    }

    @Test fun concurrentCallsFailImmediatelyWithoutQueueingAnotherHttpOrFinishingAgain() = runBlocking {
        for (phase in Phase.entries) Fixture().use { f ->
            f.prepare(phase)
            val first = f.requests.size
            val held = HeldResponse()
            f.respond = { if (f.requests.size == first + 1) held.await() else HttpResponse(503, "") }
            val original = async(start = CoroutineStart.UNDISPATCHED) { f.call(phase) }
            var duplicate: Deferred<Any>? = null
            try {
                assertTrue(held.isWaiting)
                val concurrent = async(start = CoroutineStart.UNDISPATCHED) { f.call(phase) }
                duplicate = concurrent
                assertTrue("Duplicate must reject now, not queue behind an unknown response", concurrent.isCompleted)
                assertEquals(phase.failed, concurrent.await())
                assertEquals(first + 1, f.requests.size)
                f.assertCaptures(1)
                held.release(HttpResponse(200, phase.success))
                phase.assertReady(original.await())
                yield()
                assertEquals("No queued second request after delivery", first + 1, f.requests.size)
            } finally {
                // Always release non-cooperative callbacks, including when a RED assertion fails.
                held.release(HttpResponse(503, ""))
                withContext(NonCancellable) { withTimeout(2_000) { original.join(); duplicate?.join() } }
            }
        }
    }

    @Test fun ordinaryRefreshStillAcceptsAnAlreadySubmittedCurrentOwnerResult() = runBlocking {
        for (phase in Phase.entries) Fixture().use { f ->
            f.prepare(phase)
            val epoch = f.runtime.accountEpoch
            val first = f.requests.size
            val held = HeldResponse()
            f.respond = { held.await() }
            val result = async(start = CoroutineStart.UNDISPATCHED) { f.call(phase) }
            try {
                assertTrue(held.isWaiting)
                f.runtime.update("isolated-refreshed-session", f.account)
                assertEquals(epoch, f.runtime.accountEpoch)
                held.release(HttpResponse(200, phase.success))
                phase.assertReady(result.await())
                assertEquals(first + 1, f.requests.size)
                assertEquals("Bearer isolated-original-session", f.requests.last().headers["Authorization"])
                f.assertCaptures(1)
            } finally {
                held.release(HttpResponse(503, ""))
                withContext(NonCancellable) { withTimeout(2_000) { result.join() } }
            }
        }
    }

    @Test fun sameAccountNewEpochRejectsAnAlreadySubmittedOldOwnerResult() = runBlocking {
        for (phase in Phase.entries) Fixture().use { f ->
            f.prepare(phase)
            val epoch = f.runtime.accountEpoch
            val first = f.requests.size
            val held = HeldResponse()
            f.respond = { held.await() }
            val result = async(start = CoroutineStart.UNDISPATCHED) { f.call(phase) }
            try {
                assertTrue(held.isWaiting)
                f.runtime.update("isolated-new-session", f.account, newSession = true)
                assertNotEquals(epoch, f.runtime.accountEpoch)
                held.release(HttpResponse(200, phase.success))
                assertEquals(phase.failed, result.await())
                f.respond = { throw AssertionError("An old owner cannot submit again under the new session") }
                assertEquals(phase.failed, f.call(phase))
                assertFalse(f.repository.startCapture())
                assertEquals(first + 1, f.requests.size)
                assertFalse(f.audio.exists())
                f.assertCaptures(1)
            } finally {
                held.release(HttpResponse(503, ""))
                withContext(NonCancellable) { withTimeout(2_000) { result.join() } }
            }
        }
    }

    private enum class Phase(val failed: Any, val success: String) {
        TRANSCRIPTION(SpeakingTranscriptionResult.Failed, TRANSCRIPTION_BODY),
        EVALUATION(SceneEvaluationResult.Failed, EVALUATION_BODY);
        fun assertReady(result: Any) = assertTrue(if (this == TRANSCRIPTION)
            result is SpeakingTranscriptionResult.Ready else result is SceneEvaluationResult.Ready)
    }

    private enum class Fault {
        UNAVAILABLE, PROCESSING, MALFORMED_SUCCESS, RESPONSE_LOST;
        fun response(): HttpResponse = when (this) {
            UNAVAILABLE -> HttpResponse(503, "")
            PROCESSING -> HttpResponse(409, errorBody("AI_OPERATION_PROCESSING", true))
            MALFORMED_SUCCESS -> HttpResponse(200, "{}")
            RESPONSE_LOST -> throw IOException("Isolated response loss after unknown delivery")
        }
    }

    private class HeldResponse {
        private var continuation: Continuation<HttpResponse>? = null
        val isWaiting get() = continuation != null
        suspend fun await(): HttpResponse = suspendCoroutine { check(continuation == null); continuation = it }
        fun release(response: HttpResponse) {
            val pending = continuation ?: return
            continuation = null
            pending.resume(response)
        }
    }

    private class Fixture : Closeable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val parent = base.cacheDir.canonicalFile
        private val label = "scene-retry-${UUID.randomUUID()}"
        private val directory = File(parent, label).apply { check(mkdir()) }
        private val context = IsolatedLearningDatabaseContext(base, label)
        private val databaseFile = context.getDatabasePath(DATABASE).canonicalFile
        val account = "owner-$label"
        val runtime = SessionRuntime().apply { update("isolated-original-session", account) }
        private val chapter = FixedLearningChapter("chapter-$label", 1,
            listOf(FixedLearningSentence("s1", TEXT, "我喝茶。", true)))
        private var seeded = false
        private var starts = 0
        private var finishes = 0
        private var liveAudio: File? = null
        private var lastAudio: File? = null
        val audio get() = requireNotNull(lastAudio)
        val requests = mutableListOf<HttpRequest>()
        var respond: suspend (HttpRequest) -> HttpResponse = { request ->
            when (request.path) {
                TRANSCRIBE_PATH -> HttpResponse(200, TRANSCRIPTION_BODY)
                EVALUATE_PATH -> HttpResponse(200, EVALUATION_BODY)
                else -> throw AssertionError("Only isolated transcription/evaluation endpoints are allowed")
            }
        }
        val repository = AndroidSceneResponseRepository(
            context = context,
            transport = HttpTransport { request -> requests += request; respond(request) },
            runtime = runtime,
            capture = object : ReadSpeakAudioCapture {
                override fun start(): Boolean {
                    check(liveAudio == null)
                    starts++
                    liveAudio = File(directory, "synthetic-$starts.test").apply { writeBytes(bytes(starts)) }
                        .also { lastAudio = it }
                    return true
                }
                override fun finish(): CapturedReadSpeakAudio? {
                    finishes++
                    val file = liveAudio ?: return null
                    liveAudio = null
                    return CapturedReadSpeakAudio(file)
                }
                override fun cancel() { liveAudio?.delete(); liveAudio = null }
            },
            codec = JsonSceneResponseWireCodec(),
        )

        suspend fun prepare(phase: Phase) {
            check(!seeded)
            withContext(Dispatchers.IO) {
                FormationDatabase(context).use { helper ->
                    val db = helper.writableDatabase
                    db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离测试')", arrayOf(account, label))
                    db.execSQL("insert into chapters values(?,?,?,0,'IN_PROGRESS')", arrayOf(account, label, chapter.chapterId))
                    db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,'s1',0,'我喝茶。',?,'FOCUS')",
                        arrayOf(account, chapter.chapterId, TEXT))
                    db.execSQL("insert into scene_response_tasks(account_id,chapter_id,task_id,sentence_id,sentence_order,background_en,context_lines,target_sentence_en) values(?,?,'t1','s1',0,'Talk about drinks.',?,?)",
                        arrayOf(account, chapter.chapterId, "AI\u001fWhat do you drink?", TEXT))
                    seeded = true
                }
            }
            val restored = repository.restoreTasks(chapter) as SceneTaskGenerationResult.Ready
            assertEquals(listOf("t1"), restored.tasks.map { it.taskId })
            assertTrue(requests.isEmpty())
            assertTrue(repository.startCapture())
            if (phase == Phase.EVALUATION) assertTrue(transcribe() is SpeakingTranscriptionResult.Ready)
        }
        suspend fun transcribe() = repository.transcribeResponse("t1")
        suspend fun evaluate(transcript: String = TEXT) = repository.evaluateFinalRound("t1", transcript)
        suspend fun call(phase: Phase): Any = if (phase == Phase.TRANSCRIPTION) transcribe() else evaluate()
        fun json(index: Int) = JSONObject(requireNotNull(requests[index].body))
        fun assertCaptures(expected: Int) { assertEquals(expected, starts); assertEquals(expected, finishes) }
        private fun bytes(index: Int) = "NOT_AUDIO:$label:$index".toByteArray(Charsets.UTF_8)
        fun assertFrozen(first: Int, retry: Int, phase: Phase) {
            assertAudioRequest(first, phase)
            assertAudioRequest(retry, phase)
            assertEquals(json(first).getString("commandId"), json(retry).getString("commandId"))
            assertEquals("Technical retries retain every wire parameter", requests[first].body, requests[retry].body)
        }
        fun assertAudioRequest(index: Int, phase: Phase) {
            assertEquals("POST", requests[index].method)
            assertEquals(if (phase == Phase.TRANSCRIPTION) TRANSCRIBE_PATH else EVALUATE_PATH, requests[index].path)
            val json = json(index)
            assertEquals(chapter.chapterId, json.getString("chapterId"))
            assertEquals("s1", json.getString("sentenceId"))
            assertEquals("audio/mp4", json.getString("audioMimeType"))
            assertTrue(json.getString("commandId").isNotBlank())
            assertArrayEquals(bytes(1), Base64.getDecoder().decode(json.getString("audioBase64")))
            if (phase == Phase.EVALUATION) {
                assertEquals("t1", json.getString("taskId"))
                assertEquals(TEXT, json.getString("targetTextEn"))
                assertEquals(TEXT, json.getString("confirmedTranscript"))
                assertEquals(2, json.getInt("roundIndex"))
                assertTrue(json.getBoolean("targetHidden"))
                assertTrue(json.getBoolean("userTranscriptConfirmed"))
            }
        }

        override fun close() {
            check(directory.canonicalFile.parentFile == parent && directory.name == label)
            check(databaseFile.parentFile == parent && databaseFile.name.startsWith("$label-"))
            try {
                repository.cancelCapture()
                if (seeded) FormationDatabase(context).use { helper ->
                    for (table in listOf("scene_response_result_drafts", "learning_module_progress", "learning_steps")) {
                        helper.readableDatabase.rawQuery("select count(*) from $table where account_id=?", arrayOf(account)).use {
                            assertTrue(it.moveToFirst()); assertEquals("Repository delivery is not a saved learning result", 0, it.getInt(0))
                        }
                    }
                }
            } finally {
                runtime.clear()
                directory.listFiles().orEmpty().forEach { file ->
                    check(file.canonicalFile.parentFile == directory.canonicalFile && file.name.matches(Regex("synthetic-[0-9]+\\.test")))
                    check(file.delete())
                }
                check(context.deleteDatabase(DATABASE) || !databaseFile.exists())
                check(directory.delete())
            }
        }
    }

    private companion object {
        const val DATABASE = "real-language-learning.db"
        const val TRANSCRIBE_PATH = "/api/v1/learning/speaking-transcriptions"
        const val EVALUATE_PATH = "/api/v1/learning/scene-response-evaluations"
        const val TEXT = "I drink tea."
        const val FRESH_TEXT = "I drink water."
        const val TRANSCRIPTION_BODY = """{"transcript":"I drink tea.","lowConfidenceSegments":[],"confidence":0.9}"""
        const val EVALUATION_BODY = """{"decision":"PASS","correctTurn":true,"targetSentenceComplete":true,"pronunciationIntelligible":true,"feedbackZh":"隔离反馈，不是原音评价。","pronunciationConfidence":0.9}"""
        fun errorBody(code: String, retryable: Boolean) =
            """{"error":{"code":"$code","message":"isolated","retryable":$retryable,"requestId":"isolated","details":{}}}"""
    }
}
