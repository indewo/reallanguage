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
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Android JSON and repository; synthetic bytes only, no microphone, human judgment, AI or network. */
class AndroidFullSpeakingRetryIdentityTest {
    @Test fun unknownTranscriptionFailureRetainsTheFrozenRequestForExplicitRetry() = runBlocking {
        for (fault in UnknownFailure.entries) AttemptFixture().use { f ->
            f.respond = { request ->
                assertEquals(TRANSCRIBE_PATH, request.path)
                if (f.requests.size == 1) fault.respond() else HttpResponse(200, TRANSCRIPTION)
            }
            assertTrue(f.repository.startCapture())
            assertEquals(SpeakingTranscriptionResult.Failed, f.transcribe())
            assertTrue("Unknown delivery must retain this technical attempt's audio", f.audio.exists())

            assertTrue("Explicit retry must not require another recording", f.transcribe() is SpeakingTranscriptionResult.Ready)
            assertEquals(2, f.requests.size)
            f.assertFrozenRequests(0, 1, evaluation = false)
            f.assertOneCapture()
        }
    }

    @Test fun unknownEvaluationFailureRetainsConfirmedEvidenceAndItsOriginalCommand() = runBlocking {
        for (fault in UnknownFailure.entries) AttemptFixture().use { f ->
            f.respond = { request ->
                when (request.path) {
                    TRANSCRIBE_PATH -> HttpResponse(200, TRANSCRIPTION)
                    EVALUATE_PATH -> if (f.requests.size == 2) fault.respond() else HttpResponse(200, EVALUATION)
                    else -> error("Unexpected isolated endpoint")
                }
            }
            assertTrue(f.repository.startCapture())
            assertTrue(f.transcribe() is SpeakingTranscriptionResult.Ready)
            assertEquals(SpeakingEvaluationResult.Failed, f.evaluate())
            assertTrue("An unknown evaluation is not a delivered result", f.audio.exists())

            assertTrue(f.evaluate() is SpeakingEvaluationResult.Ready)
            assertEquals(3, f.requests.size)
            f.assertFrozenRequests(1, 2, evaluation = true)
            assertNotEquals(f.command(0), f.command(1))
            f.assertOneCapture()
            assertFalse("Successful delivery releases the temporary audio", f.audio.exists())
        }
    }

    @Test fun deliveredTranscriptionIsReusedWithoutFinishingCaptureOrSendingHttpAgain() = runBlocking {
        AttemptFixture().use { f ->
            assertTrue(f.repository.startCapture())
            val first = f.transcribe()
            assertTrue(first is SpeakingTranscriptionResult.Ready)
            assertEquals(first, f.transcribe())
            assertEquals(1, f.requests.size)
            f.assertAudioRequest(0, evaluation = false)
            f.assertOneCapture()
            assertTrue("Confirmation still needs the original audio", f.audio.exists())
        }
    }

    @Test fun deliveredEvaluationIsReusedWithoutAnotherHttpRequestOrRecording() = runBlocking {
        AttemptFixture().use { f ->
            assertTrue(f.repository.startCapture())
            assertTrue(f.transcribe() is SpeakingTranscriptionResult.Ready)
            val first = f.evaluate()
            assertTrue(first is SpeakingEvaluationResult.Ready)
            assertEquals(SpeakingEvaluationDecision.PASS, (first as SpeakingEvaluationResult.Ready).candidate.decision)
            assertFalse(f.audio.exists())
            assertEquals(first, f.evaluate())
            assertEquals(2, f.requests.size)
            f.assertAudioRequest(1, evaluation = true)
            f.assertOneCapture()
        }
    }

    @Test fun changedOwnerOrSameAccountNewSessionCannotReplayAudioOrReceiveOldCandidate() = runBlocking {
        for (sameAccount in listOf(false, true)) for (alreadyDelivered in listOf(false, true)) {
            AttemptFixture().use { f ->
                assertTrue(f.repository.startCapture())
                assertTrue(f.transcribe() is SpeakingTranscriptionResult.Ready)
                if (alreadyDelivered) assertTrue(f.evaluate() is SpeakingEvaluationResult.Ready)
                val requestCount = f.requests.size
                val epoch = f.runtime.accountEpoch
                f.runtime.update(
                    "isolated-new-session",
                    if (sameAccount) f.account else "other-${UUID.randomUUID()}",
                    newSession = true,
                )
                assertNotEquals(epoch, f.runtime.accountEpoch)

                assertEquals(SpeakingEvaluationResult.Failed, f.evaluate())
                assertEquals(requestCount, f.requests.size)
                f.assertOneCapture()
            }
        }
    }

    @Test fun transcriptionTransportCancellationPropagates() = runBlocking {
        assertCancellationPropagates(evaluation = false)
    }

    @Test fun evaluationTransportCancellationPropagates() = runBlocking {
        assertCancellationPropagates(evaluation = true)
    }

    private suspend fun assertCancellationPropagates(evaluation: Boolean) {
        AttemptFixture().use { f ->
            assertTrue(f.repository.startCapture())
            if (evaluation) assertTrue(f.transcribe() is SpeakingTranscriptionResult.Ready)
            val expectedPath = if (evaluation) EVALUATE_PATH else TRANSCRIBE_PATH
            val marker = if (evaluation) "isolated-evaluation-cancel" else "isolated-transcription-cancel"
            f.respond = { request ->
                assertEquals(expectedPath, request.path)
                throw CancellationException(marker)
            }
            val cancelled = try {
                if (evaluation) f.evaluate() else f.transcribe()
                null
            } catch (failure: CancellationException) {
                failure
            }
            assertNotNull("Cancellation must not become an ordinary Failed result", cancelled)
            assertTrue(generateSequence<Throwable>(cancelled) { it.cause }.any { it.message == marker })
            assertEquals(if (evaluation) 2 else 1, f.requests.size)
            f.assertOneCapture()
        }
    }

    @Test fun ordinaryTokenRefreshKeepsTheSameAttemptAuthorized() = runBlocking {
        AttemptFixture().use { f ->
            assertTrue(f.repository.startCapture())
            assertTrue(f.transcribe() is SpeakingTranscriptionResult.Ready)
            val epoch = f.runtime.accountEpoch
            f.runtime.update("isolated-refreshed-session", f.account)
            assertEquals(epoch, f.runtime.accountEpoch)

            assertTrue(f.evaluate() is SpeakingEvaluationResult.Ready)
            assertEquals(2, f.requests.size)
            assertEquals("Bearer isolated-refreshed-session", f.requests.last().headers["Authorization"])
            f.assertAudioRequest(0, evaluation = false)
            f.assertAudioRequest(1, evaluation = true)
            f.assertOneCapture()
            assertFalse(f.audio.exists())
        }
    }

    private enum class UnknownFailure {
        RESPONSE_LOST, SERVICE_UNAVAILABLE;
        fun respond(): HttpResponse = when (this) {
            RESPONSE_LOST -> throw IOException("Isolated response lost after an unknown delivery")
            SERVICE_UNAVAILABLE -> HttpResponse(503, "")
        }
    }

    private class AttemptFixture : Closeable {
        private val parent = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        private val directory = File(parent, "full-speaking-retry-${UUID.randomUUID()}").apply { check(mkdir()) }
        val audio = File(directory, "synthetic-attempt.m4a").apply { writeBytes(SYNTHETIC_AUDIO) }
        val account = "full-speaking-retry-owner-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-original-session", account) }
        val requests = mutableListOf<HttpRequest>()
        private var starts = 0
        private var finishes = 0
        var respond: suspend (HttpRequest) -> HttpResponse = { request ->
            when (request.path) {
                TRANSCRIBE_PATH -> HttpResponse(200, TRANSCRIPTION)
                EVALUATE_PATH -> HttpResponse(200, EVALUATION)
                else -> error("Unexpected isolated endpoint")
            }
        }
        val repository = AndroidFullSentenceSpeakingRepository(
            context = null,
            transport = HttpTransport { request -> requests += request; respond(request) },
            runtime = runtime,
            chapterId = "isolated-chapter",
            targetTextEn = TEXT,
            capture = object : ReadSpeakAudioCapture {
                override fun start(): Boolean { starts += 1; return true }
                override fun finish(): CapturedReadSpeakAudio? {
                    finishes += 1
                    return if (finishes == 1) CapturedReadSpeakAudio(audio) else null
                }
                override fun cancel() = Unit
            },
            codec = JsonFullSentenceSpeakingWireCodec(),
        )

        suspend fun transcribe() = repository.transcribeIndependentRetelling("isolated-sentence")
        suspend fun evaluate() = repository.evaluateIndependentRetelling("isolated-sentence", TEXT, true)
        fun command(index: Int): String = JSONObject(requireNotNull(requests[index].body)).getString("commandId")
        fun assertOneCapture() { assertEquals(1, starts); assertEquals(1, finishes) }
        fun assertFrozenRequests(first: Int, retry: Int, evaluation: Boolean) {
            assertAudioRequest(first, evaluation)
            assertAudioRequest(retry, evaluation)
            assertEquals(command(first), command(retry))
            assertTrue("Technical retry must use the exact original wire body", requests[first].body == requests[retry].body)
        }
        fun assertAudioRequest(index: Int, evaluation: Boolean) {
            val request = requests[index]
            assertEquals("POST", request.method)
            assertEquals(if (evaluation) EVALUATE_PATH else TRANSCRIBE_PATH, request.path)
            val json = JSONObject(requireNotNull(request.body))
            assertEquals("isolated-chapter", json.getString("chapterId"))
            assertEquals("isolated-sentence", json.getString("sentenceId"))
            assertEquals("audio/mp4", json.getString("audioMimeType"))
            assertTrue(json.getString("commandId").isNotBlank())
            assertArrayEquals(SYNTHETIC_AUDIO, Base64.getDecoder().decode(json.getString("audioBase64")))
            if (evaluation) {
                assertEquals(TEXT, json.getString("targetTextEn"))
                assertEquals(TEXT, json.getString("confirmedTranscript"))
                assertTrue(json.getBoolean("targetHidden"))
                assertTrue(json.getBoolean("answerAudioPlayed"))
                assertTrue(json.getBoolean("userTranscriptConfirmed"))
            }
        }

        override fun close() {
            check(directory.name.matches(Regex("full-speaking-retry-[a-f0-9-]{36}")))
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
        const val TRANSCRIBE_PATH = "/api/v1/learning/speaking-transcriptions"
        const val EVALUATE_PATH = "/api/v1/learning/full-sentence-speaking-evaluations"
        const val TEXT = "I felt calm."
        const val TRANSCRIPTION = """{"transcript":"I felt calm.","lowConfidenceSegments":[],"confidence":0.9}"""
        const val EVALUATION = """{"decision":"PASS","completenessScore":90,"coreMeaningRecognizable":true,"pronunciationIntelligible":true,"feedbackZh":"内容完整。","pronunciationConfidence":0.9}"""
        val SYNTHETIC_AUDIO = byteArrayOf(0x46, 0x41, 0x4b, 0x45, 0x00, 0x01)
    }
}
