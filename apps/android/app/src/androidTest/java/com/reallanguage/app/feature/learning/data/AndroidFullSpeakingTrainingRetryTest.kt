package com.reallanguage.app.feature.learning.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackAction
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackResult
import com.reallanguage.app.feature.learning.domain.SpeakingTrainingMode
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual Full training repository/Android JSON, not independent evaluation or a ViewModel.
 * Synthetic bytes and one-shot finish match capture ownership; no microphone, SQL, network or AI.
 * In-memory replay is scoped to this attempt, not a claim of process-death audio recovery. */
class AndroidFullSpeakingTrainingRetryTest {
    @Test fun unknownTrainingDeliveryKeepsAudioAndReplaysItsExactOriginalCommand() = runBlocking {
        for (mode in SpeakingTrainingMode.entries) for (fault in UnknownFailure.entries) Fixture().use { f ->
            f.respond = { if (f.requests.size == 1) fault.response() else success() }
            assertTrue(f.repository.startCapture())
            assertEquals(ReadingFeedbackResult.Failed, f.feedback(mode))
            assertTrue("An unknown training delivery must retain its original audio", f.audio(0).exists())

            val result = f.feedback(mode)
            assertTrue("Explicit retry uses the original attempt, not another recording", result is ReadingFeedbackResult.Ready)
            assertEquals(2, f.requests.size)
            f.assertFrozen(0, 1)
            assertEquals(1, f.starts)
            assertEquals(1, f.finishes)
            assertFalse("Delivered feedback can release this temporary original", f.audio(0).exists())
        }
    }

    @Test fun deliveredTrainingFeedbackIsCachedForBothActionsWithoutFinishingOrRequestingAgain() = runBlocking {
        for (mode in SpeakingTrainingMode.entries) for (action in ReadingFeedbackAction.entries) Fixture().use { f ->
            f.respond = { success(action) }
            assertTrue(f.repository.startCapture())
            val delivered = f.feedback(mode)
            assertTrue(delivered is ReadingFeedbackResult.Ready)
            assertEquals(action, (delivered as ReadingFeedbackResult.Ready).feedback.action)
            assertFalse(f.audio(0).exists())

            assertEquals("Repeated observation is not another paid training attempt", delivered, f.feedback(mode))
            assertEquals(1, f.requests.size)
            assertEquals(1, f.starts)
            assertEquals(1, f.finishes)
            f.assertAudioRequest(0)
        }
    }

    @Test fun pendingTrainingCannotChangeSentenceOrModeBeforeReplayingItsOriginalFacts() = runBlocking {
        for (mode in SpeakingTrainingMode.entries) Fixture().use { f ->
            f.respond = { if (f.requests.size == 1) HttpResponse(503, "") else success() }
            assertTrue(f.repository.startCapture())
            assertEquals(ReadingFeedbackResult.Failed, f.feedback(mode))
            val otherMode = SpeakingTrainingMode.entries.single { it != mode }
            assertEquals(ReadingFeedbackResult.Failed, f.feedback(mode, "another-sentence"))
            assertEquals(ReadingFeedbackResult.Failed, f.feedback(otherMode))
            assertEquals("Mismatched retry arguments must not consume capture again", 1, f.finishes)
            assertEquals(1, f.requests.size)
            assertTrue(f.audio(0).exists())

            assertTrue(f.feedback(mode) is ReadingFeedbackResult.Ready)
            assertEquals(2, f.requests.size)
            f.assertFrozen(0, 1)
            assertEquals(1, f.starts)
            assertEquals(1, f.finishes)
        }
    }

    @Test fun concurrentTrainingTapsDoNotFinishAgainOrQueueAnAutomaticRetry() = runBlocking {
        for (mode in SpeakingTrainingMode.entries) Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.respond = { entered.complete(Unit); release.await(); HttpResponse(503, "") }
            assertTrue(f.repository.startCapture())
            val first = async(start = CoroutineStart.UNDISPATCHED) { f.feedback(mode) }
            try {
                entered.await()
                repeat(2) { assertEquals(ReadingFeedbackResult.Failed, f.feedback(mode)) }
                assertEquals("Only the admitted call can finish the microphone", 1, f.finishes)
                assertEquals(1, f.requests.size)
                assertFalse("A pending submission cannot be replaced by a new capture", f.repository.startCapture())
                assertEquals(1, f.starts)
            } finally { release.complete(Unit); first.join() }
            assertEquals(ReadingFeedbackResult.Failed, first.await())
            assertEquals("Unlock does not automatically retry a paid request", 1, f.requests.size)
        }
    }

    @Test fun trainingTransportCancellationIsNotAnOrdinaryFailedResult() = runBlocking {
        for (mode in SpeakingTrainingMode.entries) Fixture().use { f ->
            val marker = "isolated-full-training-cancellation"
            f.respond = { throw CancellationException(marker) }
            assertTrue(f.repository.startCapture())
            val cancelled = try { f.feedback(mode); null } catch (failure: CancellationException) { failure }
            assertNotNull("Cancellation must propagate to the caller", cancelled)
            assertTrue(generateSequence<Throwable>(cancelled) { it.cause }.any { it.message == marker })
            assertEquals(1, f.requests.size)
            assertEquals(1, f.finishes)
        }
    }

    @Test fun explicitCaptureCancellationRejectsLateTrainingSuccessAndNextAttemptUsesNewCommand() = runBlocking {
        for (mode in SpeakingTrainingMode.entries) Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            // This transport deliberately remains alive after repository cancellation.
            f.respond = { entered.complete(Unit); release.await(); success() }
            assertTrue(f.repository.startCapture())
            val stale = async(start = CoroutineStart.UNDISPATCHED) { f.feedback(mode) }
            try {
                entered.await()
                f.repository.cancelCapture()
            } finally { release.complete(Unit); stale.join() }
            assertEquals("A cancelled attempt cannot publish its late feedback", ReadingFeedbackResult.Failed, stale.await())
            assertFalse(f.audio(0).exists())
            assertEquals(1, f.requests.size)
            f.respond = { success() }
            assertTrue(f.repository.startCapture())
            assertTrue(f.feedback(mode) is ReadingFeedbackResult.Ready)
            assertEquals(2, f.requests.size)
            assertNotEquals(f.command(0), f.command(1))
            assertEquals(2, f.starts)
            assertEquals(2, f.finishes)
            f.assertAudioRequest(1)
        }
    }

    @Test fun ordinaryRefreshKeepsTrainingAuthorizedButNewOwnerCannotReceiveItsLateFeedback() = runBlocking {
        for (mode in SpeakingTrainingMode.entries) for (change in IdentityChange.entries) Fixture().use { f ->
            val originalEpoch = f.runtime.accountEpoch
            f.respond = {
                when (change) {
                    IdentityChange.REFRESH -> f.runtime.update("isolated-refreshed-token", f.account)
                    IdentityChange.NEW_SAME_ACCOUNT -> f.runtime.update("isolated-new-token", f.account, newSession = true)
                    IdentityChange.OTHER_ACCOUNT -> f.runtime.update("isolated-new-token", "other-${UUID.randomUUID()}")
                }
                success()
            }
            assertTrue(f.repository.startCapture())
            val result = f.feedback(mode)
            if (change == IdentityChange.REFRESH) {
                assertEquals(originalEpoch, f.runtime.accountEpoch)
                assertTrue(result is ReadingFeedbackResult.Ready)
            } else {
                assertNotEquals(originalEpoch, f.runtime.accountEpoch)
                assertEquals(ReadingFeedbackResult.Failed, result)
            }
            assertEquals(1, f.requests.size)
            assertEquals(1, f.starts)
            assertEquals(1, f.finishes)
            f.assertAudioRequest(0)
        }
    }

    private enum class IdentityChange { REFRESH, NEW_SAME_ACCOUNT, OTHER_ACCOUNT }
    private enum class UnknownFailure {
        RESPONSE_LOST, SERVICE_UNAVAILABLE, MALFORMED_SUCCESS;
        fun response(): HttpResponse = when (this) {
            RESPONSE_LOST -> throw IOException("Isolated unknown training delivery")
            SERVICE_UNAVAILABLE -> HttpResponse(503, "")
            MALFORMED_SUCCESS -> HttpResponse(200, "not-json")
        }
    }

    private class Fixture : Closeable {
        private val parent = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        private val directory = File(parent, "full-training-retry-${UUID.randomUUID()}").apply { check(mkdir()) }
        val account = "training-owner-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-original-token", account) }
        val requests = mutableListOf<HttpRequest>()
        private val files = mutableListOf<File>()
        private var liveAudio: File? = null
        var starts = 0
            private set
        var finishes = 0
            private set
        var respond: suspend (HttpRequest) -> HttpResponse = { success() }
        val repository = AndroidFullSentenceSpeakingRepository(
            context = null,
            transport = HttpTransport { request ->
                check(request.method == "POST" && request.path == PATH)
                requests += request
                respond(request)
            },
            runtime = runtime,
            chapterId = CHAPTER,
            targetTextEn = TEXT,
            capture = object : ReadSpeakAudioCapture {
                override fun start(): Boolean {
                    starts++
                    liveAudio = File(directory, "synthetic-$starts.m4a").apply {
                        writeBytes(SYNTHETIC_AUDIO); files += this
                    }
                    return true
                }
                override fun finish(): CapturedReadSpeakAudio? {
                    finishes++
                    val current = liveAudio ?: return null
                    liveAudio = null
                    return CapturedReadSpeakAudio(current)
                }
                override fun cancel() { liveAudio?.delete(); liveAudio = null }
            },
            codec = JsonFullSentenceSpeakingWireCodec(),
        )
        suspend fun feedback(mode: SpeakingTrainingMode, sentenceId: String = SENTENCE) =
            repository.trainingFeedback(sentenceId, mode)
        fun audio(index: Int) = files[index]
        fun command(index: Int): String = JSONObject(requireNotNull(requests[index].body)).getString("commandId")
        fun assertFrozen(first: Int, retry: Int) {
            assertAudioRequest(first); assertAudioRequest(retry)
            assertEquals(command(first), command(retry))
            assertTrue("Same technical attempt must retain its byte-identical wire body", requests[first].body == requests[retry].body)
        }
        fun assertAudioRequest(index: Int) {
            val json = JSONObject(requireNotNull(requests[index].body))
            assertEquals(CHAPTER, json.getString("chapterId"))
            assertEquals(SENTENCE, json.getString("sentenceId"))
            assertEquals(TEXT, json.getString("targetTextEn"))
            assertEquals("MANUAL_CONFIRMATION", json.getString("completionSignal"))
            assertEquals("audio/mp4", json.getString("audioMimeType"))
            assertTrue(json.getString("commandId").isNotBlank())
            assertArrayEquals(SYNTHETIC_AUDIO, Base64.getDecoder().decode(json.getString("audioBase64")))
        }
        override fun close() {
            check(directory.name.matches(Regex("full-training-retry-[a-f0-9-]{36}")))
            check(directory.canonicalFile.parentFile == parent)
            check(files.all { it.canonicalFile.parentFile == directory.canonicalFile })
            repository.cancelCapture()
            runtime.clear()
            files.forEach { if (it.exists()) check(it.delete()) }
            check(directory.listFiles()?.isEmpty() == true)
            check(directory.delete())
        }
    }

    private companion object {
        const val PATH = "/api/v1/learning/reading-feedback"
        const val CHAPTER = "isolated-training-chapter"
        const val SENTENCE = "isolated-training-sentence"
        const val TEXT = "I felt calm."
        val SYNTHETIC_AUDIO = byteArrayOf(0x46, 0x41, 0x4b, 0x45, 0x01)
        fun success(action: ReadingFeedbackAction = ReadingFeedbackAction.CONTINUE) = HttpResponse(200,
            """{"feedbackZh":"内容已收到，请注意连读。","transcript":"I felt calm.","contentComplete":true,"feedbackConfidence":0.9,"action":"${action.name}"}""")
    }
}
