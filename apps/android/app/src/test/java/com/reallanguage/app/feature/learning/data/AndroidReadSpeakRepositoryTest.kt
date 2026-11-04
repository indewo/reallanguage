package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.ReadSpeakCompletionSignal
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackResult
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
class AndroidReadSpeakRepositoryTest {
    @Test fun `repeated manual fallback reuses its saved training without saving under another command`() = runTest {
        val file = File.createTempFile("read-speak-manual-replay", ".m4a").apply { writeText("isolated original") }
        var requests = 0
        val saved = mutableListOf<String>()
        val repository = repository(HttpTransport { requests++; HttpResponse(503, "") }, AttemptCapture(listOf(file)),
            AttemptCodec(ReadSpeakFeedbackResult.Ready("isolated")), LearningStepStore { _, _, _, _, command -> saved += command; true })
        try {
            repository.startCapture()
            assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            repeat(2) { assertEquals(ReadSpeakFeedbackResult.CompletedWithoutAudio,
                repository.feedback("s1", ReadSpeakCompletionSignal.MANUAL_CONFIRMATION)) }
            assertEquals(1, saved.size)
            assertEquals(1, requests)
        } finally { repository.cancelCapture(); file.delete() }
    }

    @Test fun `concurrent failed feedback shares the failure without queuing a paid retry`() = runTest {
        val file = File.createTempFile("read-speak-coalesce", ".m4a").apply { writeText("isolated original") }
        val response = CompletableDeferred<HttpResponse>()
        var requests = 0
        val repository = repository(HttpTransport { requests++; response.await() }, AttemptCapture(listOf(file)),
            AttemptCodec(ReadSpeakFeedbackResult.Ready("isolated")), RecordingLearningStepStore())
        try {
            repository.startCapture()
            val first = async { repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE) }
            runCurrent()
            val duplicate = async { repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE) }
            runCurrent()
            response.complete(HttpResponse(503, ""))
            assertEquals(ReadSpeakFeedbackResult.Failed, first.await())
            assertEquals(ReadSpeakFeedbackResult.Failed, duplicate.await())
            assertEquals("A tap during an outstanding request is not consent to retry its later failure", 1, requests)
            assertTrue(repository.hasPendingFeedbackRetry())
        } finally { response.complete(HttpResponse(503, "")); repository.cancelCapture(); file.delete() }
    }

    @Test fun `unknown feedback cannot become a different completion signal or sentence on retry`() = runTest {
        val file = File.createTempFile("read-speak-frozen", ".m4a").apply { writeText("isolated original") }
        var requests = 0
        val repository = repository(HttpTransport { requests++; HttpResponse(503, "") }, AttemptCapture(listOf(file)),
            AttemptCodec(ReadSpeakFeedbackResult.Ready("isolated")), RecordingLearningStepStore())
        try {
            repository.startCapture()
            assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("s1", ReadSpeakCompletionSignal.TOUCH_REACHED_END))
            assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("another-sentence", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            assertEquals(1, requests)
            assertTrue(repository.hasPendingFeedbackRetry())
            assertTrue(file.exists())
        } finally { repository.cancelCapture(); file.delete() }
    }

    @Test fun `same account reauthentication rejects old recording before HTTP and completion`() = runTest {
        val runtime = SessionRuntime().apply { update("isolated-first", "isolated-account") }
        val file = File.createTempFile("read-speak-owner", ".m4a")
        val transport = RecordingReadSpeakTransport(HttpResponse(200, "isolated response"))
        val store = RecordingLearningStepStore()
        val repository = AndroidReadSpeakRepository(
            transport, runtime, "c1", "I drink tea.", "isolated-command", AttemptCapture(listOf(file)),
            store, RecordingReadSpeakCodec(ReadSpeakFeedbackResult.Ready("隔离反馈，不是原音判定。")),
        )
        try {
            assertTrue(repository.startCapture())
            runtime.clear()
            runtime.update("isolated-new-session", "isolated-account")
            assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            assertEquals(null, transport.request)
            assertEquals(null, store.step)
            assertFalse(file.exists())
        } finally { repository.cancelCapture(); file.delete() }
    }

    @Test fun `same account reauthentication during HTTP cannot accept feedback or save completion`() = runTest {
        val runtime = SessionRuntime().apply { update("isolated-first", "isolated-account") }
        val file = File.createTempFile("read-speak-owner", ".m4a")
        val store = RecordingLearningStepStore()
        var requests = 0
        val repository = AndroidReadSpeakRepository(
            HttpTransport {
                requests++
                runtime.clear()
                runtime.update("isolated-new-session", "isolated-account")
                HttpResponse(200, "isolated response")
            }, runtime, "c1", "I drink tea.", "isolated-command", AttemptCapture(listOf(file)),
            store, RecordingReadSpeakCodec(ReadSpeakFeedbackResult.Ready("隔离迟到反馈。")),
        )
        try {
            assertTrue(repository.startCapture())
            assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            assertEquals(1, requests)
            assertEquals(null, store.step)
            assertFalse(repository.hasPendingFeedbackRetry())
            assertFalse(file.exists())
        } finally { repository.cancelCapture(); file.delete() }
    }

    @Test fun `same account reauthentication cannot revive old manual training completion`() = runTest {
        val runtime = SessionRuntime().apply { update("isolated-first", "isolated-account") }
        val transport = RecordingReadSpeakTransport(HttpResponse(200, "unused"))
        val store = RecordingLearningStepStore()
        val repository = AndroidReadSpeakRepository(
            transport, runtime, "c1", "I drink tea.", "isolated-command", FakeReadSpeakCapture(null),
            store, RecordingReadSpeakCodec(ReadSpeakFeedbackResult.Failed),
        )
        runtime.clear()
        runtime.update("isolated-new-session", "isolated-account")
        assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("s1", ReadSpeakCompletionSignal.MANUAL_CONFIRMATION))
        assertEquals(null, transport.request)
        assertEquals(null, store.step)
    }

    @Test fun `new recordings use new identities but a failed original request retries unchanged`() = runTest {
        val files = listOf("first original", "second original").map { text -> File.createTempFile("read-speak-attempt", ".m4a").apply { writeText(text) } }
        val capture = AttemptCapture(files)
        val requests = mutableListOf<String>()
        val codec = AttemptCodec(ReadSpeakFeedbackResult.Ready("请重新读完整。", false))
        val repository = repository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                requests += requireNotNull(request.body)
                return HttpResponse(if (requests.size == 1) 503 else 200, "response")
            }
        }, capture, codec, RecordingLearningStepStore())
        try {
            assertTrue(repository.startCapture())
            assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            assertTrue(files[0].exists())
            assertEquals(codec.output, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            assertEquals(requests[0], requests[1])
            assertFalse(files[0].exists())
            assertTrue(repository.startCapture())
            assertEquals(codec.output, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            assertFalse(codec.commands[0] == codec.commands.last())
            assertFalse(files[1].exists())
        } finally { repository.cancelCapture(); files.forEach(File::delete) }
    }

    @Test fun `duplicate feedback waits for the same in flight result without another request`() = runTest {
        val file = File.createTempFile("read-speak-attempt", ".m4a").apply { writeText("original") }
        val response = CompletableDeferred<HttpResponse>()
        var requests = 0
        val codec = AttemptCodec(ReadSpeakFeedbackResult.Ready("清晰。"))
        val repository = repository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse { requests++; return response.await() }
        }, AttemptCapture(listOf(file)), codec, RecordingLearningStepStore())
        try {
            repository.startCapture()
            val first = async { repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE) }
            runCurrent()
            val second = async { repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE) }
            runCurrent()
            response.complete(HttpResponse(200, "response"))
            assertEquals(codec.output, first.await())
            assertEquals(codec.output, second.await())
            assertEquals(1, requests)
        } finally { repository.cancelCapture(); file.delete() }
    }

    @Test fun `cancelled attempt cannot save a late successful response`() = runTest {
        val file = File.createTempFile("read-speak-attempt", ".m4a").apply { writeText("original") }
        val response = CompletableDeferred<HttpResponse>()
        val store = RecordingLearningStepStore()
        val repository = repository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest) = response.await()
        }, AttemptCapture(listOf(file)), AttemptCodec(ReadSpeakFeedbackResult.Ready("late")), store)
        try {
            repository.startCapture()
            val pending = async { repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE) }
            runCurrent()
            repository.cancelCapture()
            response.complete(HttpResponse(200, "response"))
            assertEquals(ReadSpeakFeedbackResult.Failed, pending.await())
            assertEquals(null, store.step)
            assertFalse(file.exists())
        } finally { repository.cancelCapture(); file.delete() }
    }

    @Test fun `new capture invalidates an older response without deleting the new original`() = runTest {
        val files = listOf("old", "new").map { text -> File.createTempFile("read-speak-attempt", ".m4a").apply { writeText(text) } }
        val response = CompletableDeferred<HttpResponse>()
        var requests = 0
        val store = RecordingLearningStepStore()
        val codec = AttemptCodec(ReadSpeakFeedbackResult.Ready("清晰。"))
        val repository = repository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse = if (++requests == 1) response.await() else HttpResponse(200, "response")
        }, AttemptCapture(files), codec, store)
        try {
            repository.startCapture()
            val old = async { repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE) }
            runCurrent()
            assertTrue(repository.startCapture())
            response.complete(HttpResponse(200, "response"))
            assertEquals(ReadSpeakFeedbackResult.Failed, old.await())
            assertEquals(null, store.step)
            assertTrue(files[1].exists())
            assertEquals(codec.output, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            assertEquals("READ_SPEAK", store.step)
        } finally { repository.cancelCapture(); files.forEach(File::delete) }
    }

    @Test fun `saved provider feedback retries only local completion when storage initially fails`() = runTest {
        val file = File.createTempFile("read-speak-attempt", ".m4a").apply { writeText("original") }
        var requests = 0
        var saves = 0
        val codec = AttemptCodec(ReadSpeakFeedbackResult.Ready("已读完整。"))
        val repository = repository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse { requests++; return HttpResponse(200, "response") }
        }, AttemptCapture(listOf(file)), codec, LearningStepStore { _, _, _, _, _ -> ++saves > 1 })
        try {
            repository.startCapture()
            assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            assertEquals(codec.output, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            assertEquals(1, requests)
            assertEquals(2, saves)
        } finally { repository.cancelCapture(); file.delete() }
    }

    @Test fun `manual confirmation after feedback fails keeps a no AI training exit`() = runTest {
        val file = File.createTempFile("read-speak-attempt", ".m4a").apply { writeText("original") }
        val codec = AttemptCodec(ReadSpeakFeedbackResult.Ready("已确认。"))
        var requests = 0
        val repository = repository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse = HttpResponse(if (++requests == 1) 503 else 200, "response")
        }, AttemptCapture(listOf(file)), codec, RecordingLearningStepStore())
        try {
            repository.startCapture()
            assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            assertTrue(repository.hasPendingFeedbackRetry())
            assertEquals(ReadSpeakFeedbackResult.CompletedWithoutAudio, repository.feedback("s1", ReadSpeakCompletionSignal.MANUAL_CONFIRMATION))
            assertEquals(1, requests)
            assertFalse(repository.hasPendingFeedbackRetry())
            assertFalse(file.exists())
        } finally { repository.cancelCapture(); file.delete() }
    }

    @Test fun `permanent request rejection releases the original and allows a fresh recording`() = runTest {
        val files = listOf("bad", "fresh").map { text -> File.createTempFile("read-speak-attempt", ".m4a").apply { writeText(text) } }
        var requests = 0
        val codec = AttemptCodec(ReadSpeakFeedbackResult.Ready("清晰。"))
        val repository = repository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest) = HttpResponse(if (++requests == 1) 400 else 200, "response")
        }, AttemptCapture(files), codec, RecordingLearningStepStore())
        try {
            repository.startCapture()
            assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            assertFalse(repository.hasPendingFeedbackRetry())
            assertFalse(files[0].exists())
            repository.startCapture()
            assertEquals(codec.output, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            assertFalse(codec.commands[0] == codec.commands[1])
        } finally { repository.cancelCapture(); files.forEach(File::delete) }
    }

    @Test fun `same account token refresh preserves the original request identity`() = runTest {
        val runtime = SessionRuntime().apply { update("token", "account") }
        val file = File.createTempFile("read-speak-attempt", ".m4a").apply { writeText("original") }
        val requests = mutableListOf<HttpRequest>()
        val codec = AttemptCodec(ReadSpeakFeedbackResult.Ready("清晰。"))
        val repository = AndroidReadSpeakRepository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse { requests += request; return HttpResponse(if (requests.size == 1) 401 else 200, "response") }
        }, runtime, "c1", "target", "cmd", AttemptCapture(listOf(file)), RecordingLearningStepStore(), codec)
        try {
            repository.startCapture()
            assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            runtime.update("refreshed-token", "account")
            assertEquals(codec.output, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            assertEquals(requests[0].body, requests[1].body)
            assertEquals("Bearer refreshed-token", requests[1].headers["Authorization"])
        } finally { repository.cancelCapture(); file.delete() }
    }

    @Test fun `repeated start does not replace an active recording or its identity`() = runTest {
        val files = listOf("first", "second").map { text -> File.createTempFile("read-speak-attempt", ".m4a").apply { writeText(text) } }
        val capture = AttemptCapture(files)
        val codec = AttemptCodec(ReadSpeakFeedbackResult.Ready("请完整读完。", false))
        val repository = repository(RecordingReadSpeakTransport(HttpResponse(200, "response")), capture, codec, RecordingLearningStepStore())
        try {
            assertTrue(repository.startCapture())
            assertTrue(repository.startCapture())
            assertEquals(1, capture.starts)
            assertEquals(codec.output, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            assertEquals(codec.output, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
            assertEquals(1, codec.commands.size)
            assertTrue(repository.startCapture())
            assertEquals(2, capture.starts)
        } finally { repository.cancelCapture(); files.forEach(File::delete) }
    }
    @Test fun `old chapter cannot start capture or submit under a different account`() = runTest {
        val runtime = SessionRuntime().apply { update("token", "account") }
        val capture = FakeReadSpeakCapture(null)
        val transport = RecordingReadSpeakTransport(HttpResponse(200, "unused"))
        val store = RecordingLearningStepStore()
        val repository = AndroidReadSpeakRepository(
            transport, runtime, "c1", "target", "cmd", capture, store,
            RecordingReadSpeakCodec(ReadSpeakFeedbackResult.Failed),
        )
        runtime.update("other-token", "other-account")
        assertFalse(repository.startCapture())
        assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("s1", ReadSpeakCompletionSignal.MANUAL_CONFIRMATION))
        assertTrue(capture.cancelled)
        assertEquals(null, store.step)
        assertEquals(null, transport.request)
    }
    @Test fun `late feedback after account changes cannot save a learning completion`() = runTest {
        val runtime = SessionRuntime().apply { update("token", "account") }
        val file = File.createTempFile("read-speak", ".m4a")
        val store = RecordingLearningStepStore()
        val repository = AndroidReadSpeakRepository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse { runtime.clear(); return HttpResponse(200, "test response") }
        }, runtime, "c1", "target", "cmd", FakeReadSpeakCapture(CapturedReadSpeakAudio(file)), store, RecordingReadSpeakCodec(ReadSpeakFeedbackResult.Ready("isolated feedback")))
        assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("s1", ReadSpeakCompletionSignal.RECOGNITION_COMPLETE))
        assertEquals(null, store.step)
        assertFalse(file.exists())
    }
    @Test fun `manual completion without captured audio records only training and never calls AI`() = runTest {
        val transport = RecordingReadSpeakTransport(HttpResponse(200, "unused"))
        val store = RecordingLearningStepStore()
        val repository = repository(transport, FakeReadSpeakCapture(null), RecordingReadSpeakCodec(ReadSpeakFeedbackResult.Failed), store)
        assertEquals(ReadSpeakFeedbackResult.CompletedWithoutAudio, repository.feedback("s1", ReadSpeakCompletionSignal.MANUAL_CONFIRMATION))
        assertEquals("READ_SPEAK", store.step)
        assertEquals(null, transport.request)
    }

    @Test fun `recognition and touch cannot invent audio feedback when no audio was captured`() = runTest {
        for (signal in listOf(ReadSpeakCompletionSignal.RECOGNITION_COMPLETE, ReadSpeakCompletionSignal.TOUCH_REACHED_END)) {
            val transport = RecordingReadSpeakTransport(HttpResponse(200, "unused"))
            val store = RecordingLearningStepStore()
            val repository = repository(transport, FakeReadSpeakCapture(null), RecordingReadSpeakCodec(ReadSpeakFeedbackResult.Failed), store)
            assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("s1", signal))
            assertEquals(null, store.step)
            assertEquals(null, transport.request)
        }
    }
    @Test fun `starts local capture then sends authenticated audio feedback request`() = runTest {
        val file = File.createTempFile("read-speak", ".m4a").apply { writeText("audio") }
        val capture = FakeReadSpeakCapture(CapturedReadSpeakAudio(file))
        val transport = RecordingReadSpeakTransport(HttpResponse(200, "response"))
        val codec = RecordingReadSpeakCodec(ReadSpeakFeedbackResult.Ready("客观反馈"))
        val store = RecordingLearningStepStore()
        val repository = repository(transport, capture, codec, store)

        assertTrue(repository.startCapture())
        val result = repository.feedback("s1", ReadSpeakCompletionSignal.TOUCH_REACHED_END)

        assertEquals(ReadSpeakFeedbackResult.Ready("客观反馈"), result)
        assertEquals("Bearer token", transport.request?.headers?.get("Authorization"))
        assertEquals("/api/v1/learning/reading-feedback", transport.request?.path)
        assertEquals("c1", codec.chapterId)
        assertEquals("I felt calm today.", codec.targetTextEn)
        assertEquals(ReadSpeakCompletionSignal.TOUCH_REACHED_END, codec.signal)
        assertEquals("READ_SPEAK", store.step)
        assertFalse(file.exists())
    }

    @Test fun `missing session cancels capture without sending audio`() = runTest {
        val file = File.createTempFile("read-speak", ".m4a")
        val capture = FakeReadSpeakCapture(CapturedReadSpeakAudio(file))
        val transport = RecordingReadSpeakTransport(HttpResponse(200, "response"))
        val repository = AndroidReadSpeakRepository(
            transport,
            SessionRuntime(),
            "c1",
            "target",
            "cmd",
            capture,
            RecordingLearningStepStore(),
            RecordingReadSpeakCodec(ReadSpeakFeedbackResult.Ready("feedback")),
        )

        assertEquals(
            ReadSpeakFeedbackResult.Failed,
            repository.feedback("s1", ReadSpeakCompletionSignal.MANUAL_CONFIRMATION),
        )
        assertTrue(capture.cancelled)
        assertEquals(null, transport.request)
        file.delete()
    }

    private fun repository(
        transport: HttpTransport,
        capture: ReadSpeakAudioCapture,
        codec: ReadSpeakWireCodec,
        store: LearningStepStore,
    ) = AndroidReadSpeakRepository(
        transport,
        SessionRuntime().apply { update("token", "account") },
        "c1",
        "I felt calm today.",
        "cmd",
        capture,
        store,
        codec,
    )
}

private class AttemptCapture(private val originals: List<File>) : ReadSpeakAudioCapture {
    private var index = 0
    private var current: File? = null
    var starts = 0
    override fun start(): Boolean { starts++; if (current != null) return true; current = originals.getOrNull(index++) ?: return false; return true }
    override fun finish(): CapturedReadSpeakAudio? = current?.let { current = null; CapturedReadSpeakAudio(it) }
    override fun cancel() { current?.delete(); current = null }
}

private class AttemptCodec(val output: ReadSpeakFeedbackResult) : ReadSpeakWireCodec {
    val commands = mutableListOf<String>()
    override fun request(chapterId: String, sentenceId: String, targetTextEn: String, completionSignal: ReadSpeakCompletionSignal, audio: CapturedReadSpeakAudio, commandId: String): String {
        commands += commandId
        return "$commandId|$completionSignal|${audio.file.readText()}"
    }
    override fun result(body: String) = output
}

private class RecordingLearningStepStore : LearningStepStore {
    var step: String? = null
    override fun complete(
        accountId: String,
        chapterId: String,
        sentenceId: String,
        step: String,
        commandId: String,
    ): Boolean {
        this.step = step
        return true
    }
}

private class FakeReadSpeakCapture(private val audio: CapturedReadSpeakAudio?) : ReadSpeakAudioCapture {
    var cancelled = false
    override fun start() = true
    override fun finish() = audio
    override fun cancel() { cancelled = true }
    override suspend fun awaitSpeechEnd() = false
}

private class RecordingReadSpeakTransport(private val response: HttpResponse) : HttpTransport {
    var request: HttpRequest? = null
    override suspend fun execute(request: HttpRequest): HttpResponse {
        this.request = request
        return response
    }
}

private class RecordingReadSpeakCodec(private val output: ReadSpeakFeedbackResult) : ReadSpeakWireCodec {
    var chapterId: String? = null
    var targetTextEn: String? = null
    var signal: ReadSpeakCompletionSignal? = null
    override fun request(
        chapterId: String,
        sentenceId: String,
        targetTextEn: String,
        completionSignal: ReadSpeakCompletionSignal,
        audio: CapturedReadSpeakAudio,
        commandId: String,
    ): String {
        this.chapterId = chapterId
        this.targetTextEn = targetTextEn
        signal = completionSignal
        return "request"
    }
    override fun result(body: String) = output
}
