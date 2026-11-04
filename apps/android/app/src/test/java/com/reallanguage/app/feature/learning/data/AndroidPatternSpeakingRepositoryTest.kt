package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeExample
import com.reallanguage.app.feature.learning.domain.PatternPracticeFeedback
import com.reallanguage.app.feature.learning.domain.PatternPracticeFeedbackResult
import com.reallanguage.app.feature.learning.domain.PatternPracticeGenerationResult
import com.reallanguage.app.feature.learning.domain.PatternPracticeSet
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidPatternSpeakingRepositoryTest {
    @Test fun `same account reauthentication rejects old pattern generation before HTTP`() = runTest {
        val runtime = SessionRuntime().apply { update("isolated-first", "isolated-account") }
        val transport = PatternTransport(HttpResponse(200, "generated"))
        val repository = AndroidPatternSpeakingRepository(transport, runtime, chapter, EmptyPatternCapture, RecordingPatternCodec())
        runtime.clear()
        runtime.update("isolated-new-session", "isolated-account")
        assertEquals(PatternPracticeGenerationResult.Failed, repository.generate(pattern))
        assertTrue(transport.requests.isEmpty())
        assertFalse(repository.startCapture())
    }

    @Test fun `same account reauthentication during generation cannot accept or cache old output`() = runTest {
        val runtime = SessionRuntime().apply { update("isolated-first", "isolated-account") }
        var requests = 0
        val repository = AndroidPatternSpeakingRepository(
            HttpTransport {
                requests++
                runtime.clear()
                runtime.update("isolated-new-session", "isolated-account")
                HttpResponse(200, "generated")
            }, runtime, chapter, EmptyPatternCapture, RecordingPatternCodec(),
        )
        assertEquals(PatternPracticeGenerationResult.Failed, repository.generate(pattern))
        assertEquals(1, requests)
        assertEquals(PatternPracticeGenerationResult.Failed, repository.generate(pattern))
        assertEquals("A stale entry must not retry a paid generation under a new session", 1, requests)
    }

    @Test fun `same account reauthentication cannot submit audio from a restored old practice`() = runTest {
        val runtime = SessionRuntime().apply { update("isolated-first", "isolated-account") }
        val file = File.createTempFile("pattern-owner", ".m4a")
        val transport = PatternTransport(HttpResponse(200, "feedback"))
        val repository = AndroidPatternSpeakingRepository(
            transport, runtime, chapter, OneShotPatternCapture(CapturedReadSpeakAudio(file)), RecordingPatternCodec(),
        )
        try {
            repository.restorePractice(pattern, set)
            runtime.clear()
            runtime.update("isolated-new-session", "isolated-account")
            assertEquals(PatternPracticeFeedbackResult.Failed, repository.responseFeedback("p1", "e1"))
            assertTrue(transport.requests.isEmpty())
        } finally { repository.cancelCapture(); file.delete() }
    }

    @Test fun `ordinary token refresh preserves pattern generation and restored practice reuse`() = runTest {
        val runtime = SessionRuntime().apply { update("isolated-first", "isolated-account") }
        val transport = PatternTransport(HttpResponse(200, "generated"))
        val codec = RecordingPatternCodec()
        val repository = AndroidPatternSpeakingRepository(transport, runtime, chapter, EmptyPatternCapture, codec)
        val epoch = runtime.accountEpoch
        runtime.update("isolated-refreshed", "isolated-account")
        assertEquals(epoch, runtime.accountEpoch)
        assertEquals(PatternPracticeGenerationResult.Ready(set), repository.generate(pattern))
        assertEquals("Bearer isolated-refreshed", transport.requests.single().headers["Authorization"])
        assertEquals(PatternPracticeGenerationResult.Ready(set), repository.generate(pattern))
        assertEquals(1, transport.requests.size)
        assertEquals(1, codec.generationCommands.size)
    }

    @Test fun `restored practice is reused by the generator without another request`() = runTest {
        var requests = 0
        val repository = AndroidPatternSpeakingRepository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse { requests++; return HttpResponse(200, "generated") }
        }, SessionRuntime().apply { update("token", "account") }, chapter, EmptyPatternCapture, RecordingPatternCodec())
        repository.restorePractice(pattern, set)
        assertEquals(PatternPracticeGenerationResult.Ready(set), repository.generate(pattern))
        assertEquals(0, requests)
    }

    @Test fun `simultaneous requests reuse one delivered practice set`() = runTest {
        var requests = 0
        val response = CompletableDeferred<HttpResponse>()
        val repository = AndroidPatternSpeakingRepository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse { requests++; return response.await() }
        }, SessionRuntime().apply { update("token", "account") }, chapter, EmptyPatternCapture, RecordingPatternCodec())
        val first = async { repository.generate(pattern) }
        runCurrent()
        val second = async { repository.generate(pattern) }
        runCurrent()
        response.complete(HttpResponse(200, "generated"))
        assertEquals(PatternPracticeGenerationResult.Ready(set), first.await())
        assertEquals(PatternPracticeGenerationResult.Ready(set), second.await())
        assertEquals(1, requests)
    }

    @Test fun `cancelled entry cannot accept late practice generation`() = runTest {
        val response = CompletableDeferred<HttpResponse>()
        val repository = AndroidPatternSpeakingRepository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest) = response.await()
        }, SessionRuntime().apply { update("token", "account") }, chapter, EmptyPatternCapture, RecordingPatternCodec())
        val pending = async { repository.generate(pattern) }
        runCurrent()
        repository.cancelCapture()
        response.complete(HttpResponse(200, "generated"))
        assertEquals(PatternPracticeGenerationResult.Failed, pending.await())
    }

    @Test fun `restored saved examples win over an older inflight generation`() = runTest {
        val response = CompletableDeferred<HttpResponse>()
        var requests = 0
        val repository = AndroidPatternSpeakingRepository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse { requests++; return response.await() }
        }, SessionRuntime().apply { update("token", "account") }, chapter, EmptyPatternCapture, RecordingPatternCodec())
        val old = async { repository.generate(pattern) }
        runCurrent()
        val saved = set.copy(examples = listOf(example.copy(exampleId = "saved", targetResponseEn = "I used to read.")))
        repository.restorePractice(pattern, saved)
        response.complete(HttpResponse(200, "generated"))
        assertEquals(PatternPracticeGenerationResult.Failed, old.await())
        assertEquals(PatternPracticeGenerationResult.Ready(saved), repository.generate(pattern))
        assertEquals(1, requests)
    }

    @Test fun `retry after restore cannot revive the generation that restore invalidated`() = runTest {
        val response = CompletableDeferred<HttpResponse>()
        var requests = 0
        val repository = AndroidPatternSpeakingRepository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse { requests++; return response.await() }
        }, SessionRuntime().apply { update("token", "account") }, chapter, EmptyPatternCapture, RecordingPatternCodec())
        val old = async { repository.generate(pattern) }
        runCurrent()
        val saved = set.copy(examples = listOf(example.copy(exampleId = "saved")))
        repository.restorePractice(pattern, saved)
        val resumed = async { repository.generate(pattern) }
        runCurrent()
        response.complete(HttpResponse(200, "generated"))
        assertEquals(PatternPracticeGenerationResult.Failed, old.await())
        assertEquals(PatternPracticeGenerationResult.Ready(saved), resumed.await())
        assertEquals(1, requests)
    }

    @Test fun `changed input invalidates old generation without merging the new request`() = runTest {
        val firstResponse = CompletableDeferred<HttpResponse>()
        val codec = RecordingPatternCodec()
        var requests = 0
        val repository = AndroidPatternSpeakingRepository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                requests++
                return if (requests == 1) firstResponse.await() else HttpResponse(200, "generated")
            }
        }, SessionRuntime().apply { update("token", "account") }, chapter, EmptyPatternCapture, codec)
        val old = async { repository.generate(pattern) }
        runCurrent()
        val replacement = async { repository.generate(pattern.copy(explanationZh = "固定后修复的事实")) }
        runCurrent()
        firstResponse.complete(HttpResponse(200, "generated"))
        assertEquals(PatternPracticeGenerationResult.Failed, old.await())
        assertEquals(PatternPracticeGenerationResult.Ready(set), replacement.await())
        assertEquals(2, requests)
        assertEquals(2, codec.generationCommands.distinct().size)
    }

    @Test fun `failed generation stays retryable while a delivered inapplicable result is reused`() = runTest {
        val codec = RecordingPatternCodec().apply { generated = PatternPracticeGenerationResult.NotNaturallyVerifiable }
        val transport = PatternTransport(HttpResponse(503, "unavailable"), HttpResponse(200, "not-applicable"))
        val repository = AndroidPatternSpeakingRepository(transport, SessionRuntime().apply { update("token", "account") },
            chapter, EmptyPatternCapture, codec)
        assertEquals(PatternPracticeGenerationResult.Failed, repository.generate(pattern))
        assertEquals(PatternPracticeGenerationResult.NotNaturallyVerifiable, repository.generate(pattern))
        assertEquals(PatternPracticeGenerationResult.NotNaturallyVerifiable, repository.generate(pattern))
        assertEquals(2, transport.requests.size)
        assertEquals(1, codec.generationCommands.distinct().size)
    }

    @Test fun `cancelled coroutine propagates cancellation and later retry remains available`() = runTest {
        var requests = 0
        val repository = AndroidPatternSpeakingRepository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                requests++
                if (requests == 1) throw kotlinx.coroutines.CancellationException("isolated cancellation")
                return HttpResponse(200, "generated")
            }
        }, SessionRuntime().apply { update("token", "account") }, chapter, EmptyPatternCapture, RecordingPatternCodec())
        val cancelled = async { repository.generate(pattern) }
        try {
            cancelled.await()
            org.junit.Assert.fail("Cancellation must not be converted to an ordinary failed result")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(PatternPracticeGenerationResult.Ready(set), repository.generate(pattern))
        assertEquals(2, requests)
    }

    @Test fun `wrong pattern output cannot be accepted as a generated practice set`() = runTest {
        val codec = RecordingPatternCodec().apply { generated = PatternPracticeGenerationResult.Ready(set.copy(patternId = "other")) }
        val repository = AndroidPatternSpeakingRepository(PatternTransport(HttpResponse(200, "generated")),
            SessionRuntime().apply { update("token", "account") }, chapter, EmptyPatternCapture, codec)
        assertEquals(PatternPracticeGenerationResult.Failed, repository.generate(pattern))
    }
    @Test fun `restored practice supplies exact saved example to feedback without generation`() = runTest {
        val audio = File.createTempFile("pattern-speaking", ".m4a").apply { writeText("isolated test bytes") }
        val codec = RecordingPatternCodec()
        val transport = PatternTransport(HttpResponse(200, "feedback"))
        val repository = AndroidPatternSpeakingRepository(transport, SessionRuntime().apply { update("token", "account") },
            chapter, OneShotPatternCapture(CapturedReadSpeakAudio(audio)), codec)
        repository.restorePractice(pattern, set)
        assertTrue(repository.responseFeedback("p1", "e1") is PatternPracticeFeedbackResult.Ready)
        assertEquals(listOf("/api/v1/learning/pattern-practice-feedback"), transport.requests.map { it.path })
        assertEquals("used to + verb", codec.structure)
        assertFalse(audio.exists())
    }
    @Test fun `account changes cannot submit old chapter content or accept late generation`() = runTest {
        val runtime = SessionRuntime().apply { update("token", "account") }
        var requests = 0
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                requests++; runtime.update("other-token", "other-account")
                return HttpResponse(200, "generated")
            }
        }
        val audio = File.createTempFile("pattern-speaking", ".m4a")
        try {
            val repository = AndroidPatternSpeakingRepository(transport, runtime, chapter, OneShotPatternCapture(CapturedReadSpeakAudio(audio)), RecordingPatternCodec())
            assertEquals(PatternPracticeGenerationResult.Failed, repository.generate(pattern))
            assertFalse(repository.startCapture())
            assertEquals(PatternPracticeGenerationResult.Failed, repository.generate(pattern))
            assertEquals(1, requests)
        } finally { audio.delete() }
    }
    @Test fun `generates exact locked pattern then sends raw response audio`() = runTest {
        val audio = File.createTempFile("pattern-speaking", ".m4a").apply { writeText("audio") }
        val transport = PatternTransport(HttpResponse(200, "generated"), HttpResponse(200, "feedback"))
        val codec = RecordingPatternCodec()
        val repository = AndroidPatternSpeakingRepository(
            transport,
            SessionRuntime().apply { update("token", "account") },
            chapter,
            OneShotPatternCapture(CapturedReadSpeakAudio(audio)),
            codec,
        )

        assertEquals(PatternPracticeGenerationResult.Ready(set), repository.generate(pattern))
        assertTrue(repository.startCapture())
        val feedback = repository.responseFeedback("p1", "e1")

        assertEquals(
            PatternPracticeFeedbackResult.Ready(PatternPracticeFeedback("清楚。", false, "I used to walk.")),
            feedback,
        )
        assertEquals(listOf("/api/v1/learning/pattern-practice-sets", "/api/v1/learning/pattern-practice-feedback"), transport.requests.map { it.path })
        assertEquals("RESPONSE", codec.mode)
        assertEquals("used to + verb", codec.structure)
        assertFalse(audio.exists())
    }

    private class RecordingPatternCodec : PatternSpeakingWireCodec {
        var mode: String? = null
        var structure: String? = null
        val generationCommands = mutableListOf<String?>()
        var generated: PatternPracticeGenerationResult = PatternPracticeGenerationResult.Ready(set)
        override fun generationRequest(chapterId: String, sourceTextEn: String, sourceTextZh: String, pattern: LockedSpeakingPattern, commandId: String?): String {
            generationCommands += commandId
            return "generation"
        }
        override fun generationResult(body: String): PatternPracticeGenerationResult = generated
        override fun feedbackRequest(chapterId: String, pattern: LockedSpeakingPattern, example: PatternPracticeExample, mode: String, audio: CapturedReadSpeakAudio): String {
            this.mode = mode
            structure = pattern.structure
            return "feedback"
        }
        override fun feedbackResult(body: String): PatternPracticeFeedbackResult = PatternPracticeFeedbackResult.Ready(PatternPracticeFeedback("清楚。", false, "I used to walk."))
    }

    companion object {
        private val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I used to write.", "我过去常常写字。", true)))
        private val pattern = LockedSpeakingPattern("p1", "s1", "used to + verb", "过去的习惯。")
        private val example = PatternPracticeExample("e1", "上学", "How?", "I used to walk.", "我过去常常走路。")
        private val set = PatternPracticeSet("p1", listOf(example))
    }
}

private object EmptyPatternCapture : ReadSpeakAudioCapture {
    override fun start() = true
    override fun finish(): CapturedReadSpeakAudio? = null
    override fun cancel() = Unit
}

private class OneShotPatternCapture(private val audio: CapturedReadSpeakAudio) : ReadSpeakAudioCapture {
    override fun start() = true
    override fun finish() = audio
    override fun cancel() = Unit
}

private class PatternTransport(vararg responses: HttpResponse) : HttpTransport {
    private val queue = ArrayDeque(responses.toList())
    val requests = mutableListOf<HttpRequest>()
    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return queue.removeFirst()
    }
}
