package com.reallanguage.app.feature.learning.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationResult
import com.reallanguage.app.feature.learning.domain.SpeakingTranscriptionResult
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android JSON + real repository only. UUID synthetic bytes are not microphone, AI or billing evidence. */
class AndroidFullSpeakingRetryBoundaryTest {
    @Test fun processingConflictAllowsOnlyAnExplicitRetryWithTheFrozenBody() = runBlocking {
        for (stage in Stage.entries) Fixture().use { f ->
            f.prepare(stage)
            val first = f.requests.size
            f.respond = { HttpResponse(409, errorBody("AI_OPERATION_PROCESSING", true)) }
            assertEquals(stage.failed, f.call(stage))
            assertTrue(f.pending(stage))
            assertFalse(f.repository.technicalRetryBlocked())
            assertTrue(f.audio.exists())
            yield()
            assertEquals("No automatic replay of a processing operation", first + 1, f.requests.size)

            f.respond = { HttpResponse(200, stage.success) }
            stage.assertReady(f.call(stage))
            assertEquals(first + 2, f.requests.size)
            f.assertFrozen(first, first + 1)
            f.assertCaptures(1)
        }
    }

    @Test fun terminalOrUnrecognizedConflictsNeverReplaceTheCommandOrSendAnotherRequest() = runBlocking {
        val faults = listOf(
            HttpResponse(409, errorBody("IDEMPOTENCY_KEY_REUSED", false)),
            HttpResponse(409, errorBody("AI_OPERATION_FAILED", false)),
            HttpResponse(409, errorBody("AI_CHARGE_RECONCILIATION_REQUIRED", false)),
            HttpResponse(410, errorBody("AI_OPERATION_EXPIRED", false)),
            HttpResponse(409, errorBody("AI_OPERATION_PROCESSING", false)),
            HttpResponse(409, """{"error":{"code":"AI_OPERATION_PROCESSING"}}"""),
            HttpResponse(409, """{"error":{"code":"AI_OPERATION_PROCESSING","retryable":"true"}}"""),
            HttpResponse(409, errorBody("UNRECOGNIZED", true)),
            HttpResponse(409, """{"error":{"message":"AI_OPERATION_PROCESSING","retryable":true}}"""),
            HttpResponse(409, "not-json"),
            HttpResponse(410, errorBody("AI_OPERATION_PROCESSING", true)),
        )
        for (stage in Stage.entries) for (fault in faults) Fixture().use { f ->
            f.prepare(stage)
            val first = f.requests.size
            f.respond = { fault }
            assertEquals(stage.failed, f.call(stage))
            val frozenBody = f.requests.last().body
            assertTrue("${stage.name}: ${fault.body}", f.repository.technicalRetryBlocked())
            assertFalse(f.pending(stage))
            assertTrue("Terminal delivery must not silently become a new recording", f.audio.exists())

            f.respond = { HttpResponse(200, stage.success) }
            repeat(2) { assertEquals(stage.failed, f.call(stage)) }
            assertEquals(first + 1, f.requests.size)
            assertEquals(frozenBody, f.requests.last().body)
            assertTrue(f.json(first).getString("commandId").isNotBlank())
            f.assertCaptures(1)
        }
    }

    @Test fun malformedSuccessRetainsTheExactRequestUntilExplicitRetryDeliversAValidResult() = runBlocking {
        for (stage in Stage.entries) for (body in listOf("{", "{}")) Fixture().use { f ->
            f.prepare(stage)
            val first = f.requests.size
            f.respond = { HttpResponse(200, body) }
            assertEquals(stage.failed, f.call(stage))
            assertTrue(f.pending(stage))
            assertFalse(f.repository.technicalRetryBlocked())
            assertTrue(f.audio.exists())

            f.respond = { HttpResponse(200, stage.success) }
            stage.assertReady(f.call(stage))
            assertEquals(first + 2, f.requests.size)
            f.assertFrozen(first, first + 1)
            f.assertCaptures(1)
        }
    }

    @Test fun failedEvaluationRejectsChangedTranscriptOrAnswerAudioEvidence() = runBlocking {
        Fixture().use { f ->
            f.prepare(Stage.EVALUATION)
            f.respond = { HttpResponse(503, "") }
            assertEquals(SpeakingEvaluationResult.Failed, f.call(Stage.EVALUATION))
            assertTrue(f.repository.hasPendingEvaluationRetry())
            val frozen = f.requests.last().body
            f.respond = { HttpResponse(200, EVALUATION_BODY) }

            assertEquals(SpeakingEvaluationResult.Failed, f.evaluate(FRESH_TEXT, true))
            assertEquals(SpeakingEvaluationResult.Failed, f.evaluate(TEXT, false))
            assertEquals(2, f.requests.size)
            assertTrue(f.repository.hasPendingEvaluationRetry())
            assertTrue(f.audio.exists())
            assertTrue(f.evaluate(TEXT, true) is SpeakingEvaluationResult.Ready)
            assertEquals(3, f.requests.size)
            assertEquals(frozen, f.requests.last().body)
            f.assertFrozen(1, 2)
            f.assertCaptures(1)
        }
    }

    @Test fun concurrentRequestsDoNotQueueAndCaptureCannotRestartWhileHttpIsPending() = runBlocking {
        for (stage in Stage.entries) Fixture().use { f ->
            f.prepare(stage)
            val first = f.requests.size
            val held = HeldResponse()
            f.respond = { if (f.requests.size == first + 1) held.await() else HttpResponse(503, "") }
            val original = async(start = CoroutineStart.UNDISPATCHED) { f.call(stage) }
            var duplicate: Deferred<Any>? = null
            try {
                assertTrue(held.isWaiting)
                val concurrent = async(start = CoroutineStart.UNDISPATCHED) { f.call(stage) }
                duplicate = concurrent
                assertTrue("A second tap must fail now, not queue behind the first call", concurrent.isCompleted)
                assertEquals(stage.failed, concurrent.await())
                assertFalse(f.repository.startCapture())
                assertEquals(first + 1, f.requests.size)
                f.assertCaptures(1)
                held.release(HttpResponse(503, ""))
                assertEquals(stage.failed, original.await())
                yield()
                assertEquals("No queued HTTP retry after the failure", first + 1, f.requests.size)
                assertTrue(f.pending(stage))

                f.respond = { HttpResponse(200, stage.success) }
                stage.assertReady(f.call(stage))
                assertEquals(first + 2, f.requests.size)
                f.assertFrozen(first, first + 1)
            } finally {
                // Non-cooperative callbacks must be released even if an earlier assertion fails.
                held.release(HttpResponse(503, ""))
                withContext(NonCancellable) {
                    withTimeout(2_000) { original.join(); duplicate?.join() }
                }
            }
        }
    }

    @Test fun cancelledAttemptRejectsLateSuccessAndCannotContaminateTheNextRecording() = runBlocking {
        for (stage in Stage.entries) Fixture().use { f ->
            f.prepare(stage)
            val first = f.requests.size
            val oldAudio = f.audio
            val held = HeldResponse()
            f.respond = { held.await() }
            val original = async(start = CoroutineStart.UNDISPATCHED) { f.call(stage) }
            try {
                assertTrue(held.isWaiting)
                f.repository.cancelCapture()
                assertFalse(oldAudio.exists())
                // The shared request lock keeps a new capture out until the old callback settles.
                assertFalse(f.repository.startCapture())
                held.release(HttpResponse(200, stage.success))
                assertEquals(stage.failed, original.await())
                assertFalse(f.pending(stage))
                assertFalse(f.repository.technicalRetryBlocked())

                assertTrue(f.repository.startCapture())
                val freshAudio = f.audio
                assertNotEquals(oldAudio, freshAudio)
                assertTrue(freshAudio.exists())
                f.respond = { request ->
                    when (request.path) {
                        TRANSCRIBE_PATH -> HttpResponse(200, TRANSCRIPTION_BODY.replace(TEXT, FRESH_TEXT))
                        EVALUATE_PATH -> HttpResponse(200, EVALUATION_BODY.replace("PASS", "INCONCLUSIVE"))
                        else -> error("Unexpected isolated endpoint")
                    }
                }
                val fresh = f.transcribe() as SpeakingTranscriptionResult.Ready
                assertEquals(FRESH_TEXT, fresh.value.transcript)
                if (stage == Stage.EVALUATION) {
                    val evaluated = f.evaluate(FRESH_TEXT, false) as SpeakingEvaluationResult.Ready
                    assertEquals(SpeakingEvaluationDecision.INCONCLUSIVE, evaluated.candidate.decision)
                    assertFalse(freshAudio.exists())
                } else {
                    assertTrue("Old cleanup cannot delete the next attempt's audio", freshAudio.exists())
                }
                val latest = f.requests.lastIndex
                assertEquals(stage.path, f.requests[latest].path)
                assertNotEquals(f.json(first).getString("commandId"), f.json(latest).getString("commandId"))
                assertNotEquals(f.json(first).getString("audioBase64"), f.json(latest).getString("audioBase64"))
                assertEquals(first + (if (stage == Stage.EVALUATION) 3 else 2), f.requests.size)
                f.assertCaptures(2)
            } finally {
                held.release(HttpResponse(503, ""))
                withContext(NonCancellable) { withTimeout(2_000) { original.join() } }
            }
        }
    }

    private enum class Stage(val path: String, val failed: Any, val success: String) {
        TRANSCRIPTION(TRANSCRIBE_PATH, SpeakingTranscriptionResult.Failed, TRANSCRIPTION_BODY),
        EVALUATION(EVALUATE_PATH, SpeakingEvaluationResult.Failed, EVALUATION_BODY);

        fun assertReady(result: Any) = assertTrue(
            if (this == TRANSCRIPTION) result is SpeakingTranscriptionResult.Ready
            else result is SpeakingEvaluationResult.Ready,
        )
    }

    private class HeldResponse {
        private var continuation: Continuation<HttpResponse>? = null
        val isWaiting get() = continuation != null
        suspend fun await(): HttpResponse = suspendCoroutine {
            check(continuation == null)
            continuation = it
        }
        fun release(response: HttpResponse) {
            val pending = continuation ?: return
            continuation = null
            pending.resume(response)
        }
    }

    private class Fixture : Closeable {
        private val parent = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        private val directory = File(parent, "full-speaking-boundary-${UUID.randomUUID()}").apply { check(mkdir()) }
        private val runtime = SessionRuntime().apply { update("isolated-session", "boundary-${UUID.randomUUID()}") }
        private var lastAudio: File? = null
        private var liveAudio: File? = null
        private var starts = 0
        private var finishes = 0
        val audio get() = requireNotNull(lastAudio)
        val requests = mutableListOf<HttpRequest>()
        var respond: suspend (HttpRequest) -> HttpResponse = { request ->
            HttpResponse(200, when (request.path) {
                TRANSCRIBE_PATH -> TRANSCRIPTION_BODY
                EVALUATE_PATH -> EVALUATION_BODY
                else -> error("Unexpected isolated endpoint")
            })
        }
        val repository = AndroidFullSentenceSpeakingRepository(
            context = null,
            transport = HttpTransport { request -> requests += request; respond(request) },
            runtime = runtime,
            chapterId = "isolated-chapter",
            targetTextEn = TEXT,
            capture = object : ReadSpeakAudioCapture {
                override fun start(): Boolean {
                    check(liveAudio == null)
                    starts++
                    liveAudio = File(directory, "synthetic-$starts.m4a").apply {
                        writeBytes(byteArrayOf(0x46, 0x41, 0x4b, 0x45, starts.toByte()))
                    }.also { lastAudio = it }
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
            codec = JsonFullSentenceSpeakingWireCodec(),
        )

        suspend fun prepare(stage: Stage) {
            assertTrue(repository.startCapture())
            if (stage == Stage.EVALUATION) assertTrue(transcribe() is SpeakingTranscriptionResult.Ready)
        }
        suspend fun transcribe() = repository.transcribeIndependentRetelling("isolated-sentence")
        suspend fun evaluate(text: String = TEXT, played: Boolean = true) =
            repository.evaluateIndependentRetelling("isolated-sentence", text, played)
        suspend fun call(stage: Stage): Any = if (stage == Stage.TRANSCRIPTION) transcribe() else evaluate()
        fun pending(stage: Stage) = if (stage == Stage.TRANSCRIPTION)
            repository.hasPendingTranscriptionRetry() else repository.hasPendingEvaluationRetry()
        fun json(index: Int) = JSONObject(requireNotNull(requests[index].body))
        fun assertCaptures(expected: Int) { assertEquals(expected, starts); assertEquals(expected, finishes) }
        fun assertFrozen(first: Int, retry: Int) {
            assertEquals(requests[first].path, requests[retry].path)
            assertEquals("POST", requests[first].method)
            assertTrue(json(first).getString("commandId").isNotBlank())
            assertEquals(json(first).getString("commandId"), json(retry).getString("commandId"))
            assertEquals("Retry must retain every wire parameter and original audio", requests[first].body, requests[retry].body)
        }

        override fun close() {
            check(directory.name.matches(Regex("full-speaking-boundary-[a-f0-9-]{36}")))
            check(directory.canonicalFile.parentFile == parent)
            repository.cancelCapture()
            runtime.clear()
            directory.listFiles().orEmpty().forEach { file ->
                check(file.name.matches(Regex("synthetic-[0-9]+\\.m4a")))
                check(file.canonicalFile.parentFile == directory.canonicalFile)
                check(file.delete())
            }
            check(directory.delete())
        }
    }

    private companion object {
        const val TRANSCRIBE_PATH = "/api/v1/learning/speaking-transcriptions"
        const val EVALUATE_PATH = "/api/v1/learning/full-sentence-speaking-evaluations"
        const val TEXT = "I felt calm."
        const val FRESH_TEXT = "I feel ready."
        const val TRANSCRIPTION_BODY = """{"transcript":"I felt calm.","lowConfidenceSegments":[],"confidence":0.9}"""
        const val EVALUATION_BODY = """{"decision":"PASS","completenessScore":90,"coreMeaningRecognizable":true,"pronunciationIntelligible":true,"feedbackZh":"内容完整。","pronunciationConfidence":0.9}"""
        fun errorBody(code: String, retryable: Boolean) =
            """{"error":{"code":"$code","message":"isolated","retryable":$retryable,"requestId":"isolated","details":{}}}"""
    }
}
