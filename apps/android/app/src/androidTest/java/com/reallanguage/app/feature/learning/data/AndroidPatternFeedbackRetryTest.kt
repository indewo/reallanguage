package com.reallanguage.app.feature.learning.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real repository/JSON; isolated synthetic bytes and HTTP, never real audio or AI evidence. */
class AndroidPatternFeedbackRetryTest {
    @Test fun uncertainFailuresRetryOnlyTheOriginalAudioBodyAndCommand() = runBlocking {
        for (reading in listOf(true, false)) for (failure in listOf("processing", "503", "io", "bad200")) fixture { f ->
            f.responses = { index -> if (index > 1) ready() else when (failure) {
                "processing" -> HttpResponse(409, """{"error":{"code":"AI_OPERATION_PROCESSING","retryable":true}}""")
                "503" -> HttpResponse(503, "{}")
                "io" -> throw IOException("isolated")
                else -> HttpResponse(200, "{}")
            } }
            assertTrue(f.repo.startCapture())
            assertEquals(PatternPracticeFeedbackResult.Failed, f.feedback(reading))
            assertTrue("$reading/$failure: original evidence must survive unknown outcome", f.audio.isFile)
            assertTrue(f.feedback(reading) is PatternPracticeFeedbackResult.Ready)
            assertEquals(1, f.finishes)
            assertEquals(2, f.requests.size)
            assertEquals(f.requests[0].body, f.requests[1].body)
            assertTrue(JSONObject(requireNotNull(f.requests[0].body)).getString("commandId").isNotBlank())
            assertFalse(f.audio.exists())
            assertTrue(f.feedback(reading) is PatternPracticeFeedbackResult.Ready)
            assertEquals("Delivered feedback is not another paid request", 2, f.requests.size)
        }
    }

    @Test fun terminalOutcomesCannotSilentlyBecomeNewCommands() = runBlocking {
        for (response in listOf(HttpResponse(410, "{}"), HttpResponse(409, "{}"),
            HttpResponse(409, """{"error":{"code":"AI_OPERATION_PROCESSING","retryable":"true"}}"""))) fixture { f ->
            f.responses = { response }
            assertTrue(f.repo.startCapture())
            assertEquals(PatternPracticeFeedbackResult.Failed, f.feedback())
            assertFalse(f.audio.exists())
            repeat(2) { assertEquals(PatternPracticeFeedbackResult.Failed, f.feedback()) }
            assertEquals(1, f.finishes)
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun failedResponseCannotBeReplayedAsAnotherModeOrExample() = runBlocking { fixture { f ->
        f.responses = { if (it == 1) HttpResponse(503, "{}") else ready() }
        assertTrue(f.repo.startCapture())
        assertEquals(PatternPracticeFeedbackResult.Failed, f.feedback())
        assertEquals(PatternPracticeFeedbackResult.Failed, f.feedback(reading = true))
        assertEquals(PatternPracticeFeedbackResult.Failed, f.repo.responseFeedback("p1", "e2"))
        assertEquals(1, f.finishes)
        assertEquals(1, f.requests.size)
        assertTrue(f.feedback() is PatternPracticeFeedbackResult.Ready)
    } }

    @Test fun cancelledAttemptCannotAcceptNonCooperativeLateFeedback() = runBlocking { fixture { f ->
        val result = CompletableDeferred<HttpResponse>()
        f.responses = { result.await() }
        assertTrue(f.repo.startCapture())
        val pending = async(start = CoroutineStart.UNDISPATCHED) { f.feedback() }
        try {
            assertEquals(1, f.requests.size)
            f.repo.cancelCapture()
            result.complete(ready())
            assertEquals(PatternPracticeFeedbackResult.Failed, pending.await())
        } finally { result.complete(HttpResponse(503, "{}")); pending.cancelAndJoin() }
    } }

    @Test fun concurrentFeedbackDoesNotQueueANewPaidRequestOrCapture() = runBlocking { fixture { f ->
        val result = CompletableDeferred<HttpResponse>()
        f.responses = { result.await() }
        assertTrue(f.repo.startCapture())
        val pending = async(start = CoroutineStart.UNDISPATCHED) { f.feedback() }
        try {
            assertEquals(1, f.requests.size)
            assertEquals(PatternPracticeFeedbackResult.Failed, withTimeout(500) { f.feedback() })
            assertFalse(f.repo.startCapture())
            assertEquals(1, f.starts)
            assertEquals(1, f.finishes)
            result.complete(ready())
            assertTrue(pending.await() is PatternPracticeFeedbackResult.Ready)
        } finally { result.complete(HttpResponse(503, "{}")); pending.cancelAndJoin() }
    } }

    @Test fun ordinaryRefreshAcceptsButNewLoginRejectsSubmittedResult() = runBlocking {
        for (newLogin in listOf(false, true)) fixture { f ->
            f.responses = {
                if (newLogin) f.runtime.clear()
                f.runtime.update("ISOLATED_REFRESHED", "owner")
                ready()
            }
            assertTrue(f.repo.startCapture())
            val result = f.feedback()
            if (newLogin) assertEquals(PatternPracticeFeedbackResult.Failed, result)
            else assertTrue(result is PatternPracticeFeedbackResult.Ready)
            assertEquals(1, f.requests.size)
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { f.repo.cancelCapture(); f.audio.delete() }
    }

    private class Fixture {
        val runtime = SessionRuntime().apply { update("ISOLATED_ACCESS", "owner") }
        val audio = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "pattern-retry-${UUID.randomUUID()}.m4a").apply { writeText("NOT_AUDIO") }
        var responses: suspend (Int) -> HttpResponse = { ready() }
        val requests = mutableListOf<HttpRequest>()
        var starts = 0
        var finishes = 0
        var active = false
        val repo = AndroidPatternSpeakingRepository(HttpTransport { request ->
            requests += request
            responses(requests.size)
        }, runtime, FixedLearningChapter("isolated-chapter", 1,
            listOf(FixedLearningSentence("s1", "I drink tea.", "我喝茶。", true))), object : ReadSpeakAudioCapture {
            override fun start(): Boolean { starts++; active = true; return true }
            override fun finish(): CapturedReadSpeakAudio? {
                finishes++
                return if (active) CapturedReadSpeakAudio(audio).also { active = false } else null
            }
            override fun cancel() { active = false }
        }).apply {
            restorePractice(LockedSpeakingPattern("p1", "s1", "drink tea", "喝茶"),
                PatternPracticeSet("p1", listOf(
                    PatternPracticeExample("e1", "饮食", "What do you drink?", "I drink tea.", "我喝茶。"),
                    PatternPracticeExample("e2", "饮食", "What do you drink?", "I drink green tea.", "我喝绿茶。"))))
        }
        suspend fun feedback(reading: Boolean = false) =
            if (reading) repo.readingFeedback("p1", "e1") else repo.responseFeedback("p1", "e1")
    }

    companion object {
        private fun ready() = HttpResponse(200,
            """{"status":"READY","feedbackZh":"隔离反馈","repeatRecommended":false,"transcript":"I drink tea.","confidence":0.9}""")
    }
}
