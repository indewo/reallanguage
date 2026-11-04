package com.reallanguage.app.feature.learning.data

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.ReadSpeakCompletionSignal
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackResult
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Android JSON/repository, memory training store and UUID synthetic bytes; no microphone or AI evidence. */
class AndroidReadSpeakRetryClassificationTest {
    @Test fun processingConflictRetainsTheOriginalUntilExplicitRetrySavesTrainingExactlyOnce() = runBlocking {
        for (signal in AUDIO_SIGNALS) Fixture().use { f ->
            f.start()
            f.respond = { HttpResponse(409, errorBody("AI_OPERATION_PROCESSING", true)) }
            assertEquals(ReadSpeakFeedbackResult.Failed, f.feedback(signal))
            assertTrue("Processing is retryable without another recording", f.repository.hasPendingFeedbackRetry())
            assertTrue(f.audio.exists())
            assertEquals(0, f.store.attempts.size)
            yield()
            assertEquals("No automatic replay", 1, f.requests.size)

            f.respond = { HttpResponse(200, FEEDBACK) }
            val delivered = f.feedback(signal)
            assertEquals(READY, delivered)
            assertEquals(delivered, f.feedback(signal))
            assertEquals(2, f.requests.size)
            f.assertFrozen(0, 1, signal)
            f.assertOnlyTrainingSave(expectedAttempts = 1, expectedCommand = f.json(0).getString("commandId"))
            assertFalse(f.repository.hasPendingFeedbackRetry())
            assertFalse(f.audio.exists())
            f.assertOneCapture()
        }
    }

    @Test fun terminalDeliveryCannotRecaptureOrCreateAnotherCommandThroughRepeatedFeedback() = runBlocking {
        val faults = listOf(
            HttpResponse(409, errorBody("IDEMPOTENCY_KEY_REUSED", false)),
            HttpResponse(409, errorBody("AI_OPERATION_FAILED", false)),
            HttpResponse(409, errorBody("AI_CHARGE_RECONCILIATION_REQUIRED", false)),
            HttpResponse(410, errorBody("AI_OPERATION_EXPIRED", false)),
        )
        for (signal in AUDIO_SIGNALS) for (fault in faults) assertTerminal(fault, signal)
    }

    @Test fun processingMustHaveTheExactCodeAndBooleanRetryableFlagToPermitReplay() = runBlocking {
        val faults = listOf(
            HttpResponse(409, errorBody("AI_OPERATION_PROCESSING", false)),
            HttpResponse(409, """{"error":{"code":"AI_OPERATION_PROCESSING"}}"""),
            HttpResponse(409, """{"error":{"code":"AI_OPERATION_PROCESSING","retryable":"true"}}"""),
            HttpResponse(409, errorBody("UNRECOGNIZED", true)),
            HttpResponse(409, """{"error":{"message":"AI_OPERATION_PROCESSING","retryable":true}}"""),
            HttpResponse(409, "not-json"),
            HttpResponse(410, errorBody("AI_OPERATION_PROCESSING", true)),
        )
        for (fault in faults) assertTerminal(fault, ReadSpeakCompletionSignal.RECOGNITION_COMPLETE)
    }

    @Test fun malformedSuccessUnavailableAndLostResponseRetryTheSameWireBody() = runBlocking {
        for (signal in AUDIO_SIGNALS) for (fault in UnknownFailure.entries) Fixture().use { f ->
            f.start()
            f.respond = { fault.response() }
            assertEquals(ReadSpeakFeedbackResult.Failed, f.feedback(signal))
            assertTrue(f.repository.hasPendingFeedbackRetry())
            assertTrue(f.audio.exists())
            assertEquals(0, f.store.attempts.size)

            f.respond = { HttpResponse(200, FEEDBACK) }
            assertEquals(READY, f.feedback(signal))
            assertEquals(READY, f.feedback(signal))
            assertEquals(2, f.requests.size)
            f.assertFrozen(0, 1, signal)
            f.assertOnlyTrainingSave(expectedAttempts = 1, expectedCommand = f.json(0).getString("commandId"))
            assertFalse(f.audio.exists())
            f.assertOneCapture()
        }
    }

    @Test fun deliveredFeedbackSurvivesRepeatedLocalSaveFailureWithoutReencodingOrAnotherHttpCall() = runBlocking {
        Fixture().use { f ->
            f.start()
            f.store.failuresRemaining = 2
            assertEquals(ReadSpeakFeedbackResult.Failed, f.feedback())
            assertFalse("Delivered feedback no longer needs the temporary original", f.audio.exists())
            assertTrue(f.repository.hasPendingFeedbackRetry())
            assertEquals(1, f.requests.size)
            val frozen = f.requests.single().body
            f.respond = { throw AssertionError("Saving delivered feedback must not submit audio again") }

            assertEquals(ReadSpeakFeedbackResult.Failed, f.feedback())
            assertTrue(f.repository.hasPendingFeedbackRetry())
            assertEquals(0, f.store.committed.size)
            assertEquals(READY, f.feedback())
            assertEquals(READY, f.feedback())
            assertFalse(f.repository.hasPendingFeedbackRetry())
            assertEquals(1, f.requests.size)
            assertEquals(frozen, f.requests.single().body)
            f.assertOnlyTrainingSave(expectedAttempts = 3, expectedCommand = f.json(0).getString("commandId"))
            f.assertOneCapture()
        }
    }

    @Test fun explicitManualConfirmationStillOffersANoAiTrainingExitAfterDeliveryFailure() = runBlocking {
        val faults = listOf(
            HttpResponse(409, errorBody("AI_OPERATION_PROCESSING", true)),
            HttpResponse(409, errorBody("AI_OPERATION_FAILED", false)),
            HttpResponse(410, errorBody("AI_OPERATION_EXPIRED", false)),
            HttpResponse(503, ""),
        )
        for (fault in faults) Fixture().use { f ->
            f.start()
            f.respond = { fault }
            assertEquals(ReadSpeakFeedbackResult.Failed, f.feedback())
            assertEquals(0, f.store.attempts.size)
            val paidBody = f.requests.single().body
            f.respond = { throw AssertionError("Explicit manual training is not another AI evaluation") }

            assertEquals(ReadSpeakFeedbackResult.CompletedWithoutAudio,
                f.feedback(ReadSpeakCompletionSignal.MANUAL_CONFIRMATION))
            assertEquals(1, f.requests.size)
            assertEquals(paidBody, f.requests.single().body)
            f.assertOnlyTrainingSave(expectedAttempts = 1)
            assertFalse(f.repository.hasPendingFeedbackRetry())
            assertFalse(f.audio.exists())
        }
    }

    private suspend fun assertTerminal(response: HttpResponse, signal: ReadSpeakCompletionSignal) {
        Fixture().use { f ->
            f.start()
            f.respond = { response }
            assertEquals(ReadSpeakFeedbackResult.Failed, f.feedback(signal))
            assertFalse(f.repository.hasPendingFeedbackRetry())
            val frozen = f.requests.single().body
            f.assertAudioRequest(0, signal)
            f.respond = { throw AssertionError("A terminal operation must not issue a replacement request") }

            // finish() would now return null. Missing audio is not an automatic training completion.
            repeat(2) { assertEquals(ReadSpeakFeedbackResult.Failed, f.feedback(signal)) }
            assertEquals(1, f.requests.size)
            assertEquals(frozen, f.requests.single().body)
            assertEquals(0, f.store.attempts.size)
            assertEquals(0, f.store.committed.size)
            assertFalse(f.repository.hasPendingFeedbackRetry())
            f.assertOneCapture()
        }
    }

    private enum class UnknownFailure {
        MALFORMED_JSON, MISSING_REQUIRED_FIELDS, SERVICE_UNAVAILABLE, RESPONSE_LOST;
        fun response(): HttpResponse = when (this) {
            MALFORMED_JSON -> HttpResponse(200, "{")
            MISSING_REQUIRED_FIELDS -> HttpResponse(200, "{}")
            SERVICE_UNAVAILABLE -> HttpResponse(503, "")
            RESPONSE_LOST -> throw IOException("Isolated response lost after unknown delivery")
        }
    }

    private data class TrainingSave(
        val accountId: String,
        val chapterId: String,
        val sentenceId: String,
        val step: String,
        val commandId: String,
    )

    private class MemoryStepStore : LearningStepStore {
        var failuresRemaining = 0
        private val attempted = mutableListOf<TrainingSave>()
        private val saved = mutableListOf<TrainingSave>()
        val attempts get() = synchronized(this) { attempted.toList() }
        val committed get() = synchronized(this) { saved.toList() }
        @Synchronized override fun complete(
            accountId: String, chapterId: String, sentenceId: String, step: String, commandId: String,
        ): Boolean {
            val value = TrainingSave(accountId, chapterId, sentenceId, step, commandId)
            attempted += value
            if (failuresRemaining > 0) { failuresRemaining--; return false }
            saved += value
            return true
        }
    }

    private class Fixture : Closeable {
        private val parent = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        private val directory = File(parent, "read-speaking-classification-${UUID.randomUUID()}").apply { check(mkdir()) }
        val audio = File(directory, "synthetic.m4a")
        private val account = "read-speaking-classification-owner-${UUID.randomUUID()}"
        private val runtime = SessionRuntime().apply { update("isolated-session", account) }
        private var live = false
        private var starts = 0
        private var finishes = 0
        val store = MemoryStepStore()
        val requests = mutableListOf<HttpRequest>()
        var respond: suspend (HttpRequest) -> HttpResponse = { HttpResponse(200, FEEDBACK) }
        val repository = AndroidReadSpeakRepository(
            transport = HttpTransport { request -> requests += request; respond(request) },
            runtime = runtime,
            chapterId = CHAPTER,
            targetTextEn = TEXT,
            commandId = UUID.randomUUID().toString(),
            capture = object : ReadSpeakAudioCapture {
                override fun start(): Boolean {
                    check(!live)
                    starts++
                    audio.writeBytes(SYNTHETIC_AUDIO)
                    live = true
                    return true
                }
                override fun finish(): CapturedReadSpeakAudio? {
                    finishes++
                    if (!live) return null
                    live = false
                    return CapturedReadSpeakAudio(audio)
                }
                override fun cancel() { if (live) audio.delete(); live = false }
            },
            stepStore = store,
            codec = JsonReadSpeakWireCodec(),
        )

        fun start() = assertTrue(repository.startCapture())
        suspend fun feedback(signal: ReadSpeakCompletionSignal = ReadSpeakCompletionSignal.RECOGNITION_COMPLETE) =
            repository.feedback(SENTENCE, signal)
        fun json(index: Int) = JSONObject(requireNotNull(requests[index].body))
        fun assertOneCapture() { assertEquals(1, starts); assertEquals("No implicit recapture on retry", 1, finishes) }
        fun assertOnlyTrainingSave(expectedAttempts: Int, expectedCommand: String? = null) {
            assertEquals(expectedAttempts, store.attempts.size)
            assertEquals(1, store.committed.size)
            val committed = store.committed.single()
            assertEquals(account, committed.accountId)
            assertEquals(CHAPTER, committed.chapterId)
            assertEquals(SENTENCE, committed.sentenceId)
            assertEquals("READ_SPEAK", committed.step)
            assertTrue(committed.commandId.isNotBlank())
            if (expectedCommand != null) assertEquals(expectedCommand, committed.commandId)
            assertTrue("Local save retries retain one training command", store.attempts.all { it == committed })
        }
        fun assertFrozen(first: Int, retry: Int, signal: ReadSpeakCompletionSignal) {
            assertAudioRequest(first, signal)
            assertAudioRequest(retry, signal)
            assertEquals(json(first).getString("commandId"), json(retry).getString("commandId"))
            assertEquals("Technical retry must retain the full original body", requests[first].body, requests[retry].body)
        }
        fun assertAudioRequest(index: Int, signal: ReadSpeakCompletionSignal) {
            val request = requests[index]
            assertEquals("POST", request.method)
            assertEquals("/api/v1/learning/reading-feedback", request.path)
            assertEquals("Bearer isolated-session", request.headers["Authorization"])
            val json = json(index)
            assertEquals(CHAPTER, json.getString("chapterId"))
            assertEquals(SENTENCE, json.getString("sentenceId"))
            assertEquals(TEXT, json.getString("targetTextEn"))
            assertEquals(signal.name, json.getString("completionSignal"))
            assertEquals("audio/mp4", json.getString("audioMimeType"))
            assertTrue(json.getString("commandId").isNotBlank())
            assertArrayEquals(SYNTHETIC_AUDIO, Base64.decode(json.getString("audioBase64"), Base64.NO_WRAP))
        }

        override fun close() {
            check(directory.name.matches(Regex("read-speaking-classification-[a-f0-9-]{36}")))
            check(directory.canonicalFile.parentFile == parent)
            check(audio.canonicalFile.parentFile == directory.canonicalFile)
            repository.cancelCapture()
            runtime.clear()
            if (audio.exists()) check(audio.delete())
            check(directory.listFiles()?.isEmpty() == true)
            check(directory.delete())
        }
    }

    private companion object {
        const val CHAPTER = "isolated-chapter"
        const val SENTENCE = "isolated-sentence"
        const val TEXT = "I felt calm."
        const val FEEDBACK = """{"feedbackZh":"隔离反馈，不是人声判断。","transcript":"I felt calm.","contentComplete":true,"feedbackConfidence":0.9,"action":"CONTINUE"}"""
        val READY = ReadSpeakFeedbackResult.Ready("隔离反馈，不是人声判断。", true)
        val SYNTHETIC_AUDIO = byteArrayOf(0x46, 0x41, 0x4b, 0x45, 0x00, 0x01)
        val AUDIO_SIGNALS = listOf(ReadSpeakCompletionSignal.RECOGNITION_COMPLETE, ReadSpeakCompletionSignal.TOUCH_REACHED_END)
        fun errorBody(code: String, retryable: Boolean) =
            """{"error":{"code":"$code","message":"isolated","retryable":$retryable,"requestId":"isolated","details":{}}}"""
    }
}
