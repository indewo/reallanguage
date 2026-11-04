package com.reallanguage.app.feature.scene.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.scene.domain.SceneAnswer
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneQuestionRequest
import com.reallanguage.app.feature.scene.domain.SceneQuestionResult
import com.reallanguage.app.feature.scene.domain.SceneStart
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real Android JSON and repository, isolated in-memory HTTP only; no service, AI, audio or database. */
@OptIn(ExperimentalCoroutinesApi::class)
class SceneQuestionRevisionWireTest {
    private fun runtime() = SessionRuntime().apply { update("isolated-token", "scene-question-${UUID.randomUUID()}") }
    private fun request() = SceneQuestionRequest(SceneStart.DirectDescription("练习会议沟通"), SceneContentType.DIALOGUE,
        listOf(SceneAnswer("q1", "同事")), 4, 10, UUID.randomUUID().toString(), 1)
    private fun ready() = HttpResponse(200, """{"action":"ASK","questionId":"q2","questionZh":"在哪里沟通？","options":[]}""")

    @Test fun changedAnswerAtSameStepIsAcceptedAndEachExactBodyKeepsItsOriginalCommand() = runTest {
        val bodies = mutableListOf<String>()
        val deliveries = mutableMapOf<String, String>()
        val runtime = runtime()
        val repository = HttpSceneFormationRepositories(HttpTransport { wire ->
            assertEquals("POST", wire.method)
            assertEquals("/api/v1/sources/scenes/next-question", wire.path)
            bodies += wire.body
            val command = JSONObject(wire.body).getString("commandId")
            val previous = deliveries.putIfAbsent(command, wire.body)
            if (previous != null && previous != wire.body) HttpResponse(409, """{"error":{"code":"IDEMPOTENCY_KEY_REUSED","retryable":false}}""") else ready()
        }, runtime)
        val original = request()
        val edited = original.copy(confirmedAnswers = listOf(SceneAnswer("q1", "朋友")))
        listOf(original, edited, original, edited).forEach { assertTrue(repository.next(it) is SceneQuestionResult.Ask) }
        assertEquals(2, deliveries.size)
        assertEquals(bodies[0], bodies[2]); assertEquals(bodies[1], bodies[3])
        val before = JSONObject(bodies[0]); val after = JSONObject(bodies[1])
        assertNotEquals(before.getString("commandId"), after.getString("commandId"))
        assertEquals(before.getString("operationId"), after.getString("operationId"))
        assertEquals(before.getInt("stepIndex"), after.getInt("stepIndex"))
        assertEquals("朋友", after.getJSONArray("confirmedAnswers").getJSONObject(0).getString("answerZh"))
        assertEquals("DIRECT_DESCRIPTION", after.getJSONObject("start").getString("kind"))
        assertEquals(sceneQuestionCommandId(edited), after.getString("commandId"))
    }

    @Test fun responseLossAndRepositoryRecreationKeepTheExactSubmittedBodyWithoutAutomaticReplay() = runTest {
        val wires = mutableListOf<HttpRequest>()
        val runtime = runtime()
        val transport = HttpTransport { wire ->
            wires += wire
            if (wires.size == 1) throw IOException("isolated response loss")
            ready()
        }
        val original = request()
        val first = HttpSceneFormationRepositories(transport, runtime)
        assertEquals(SceneQuestionResult.Failed, first.next(original))
        assertEquals(1, wires.size)
        assertTrue(first.next(original) is SceneQuestionResult.Ask)
        assertTrue(HttpSceneFormationRepositories(transport, runtime).next(original.copy()) is SceneQuestionResult.Ask)
        assertEquals(3, wires.size)
        assertEquals(1, wires.map { it.body }.distinct().size)
    }

    @Test fun cancelledTransportIsNotConvertedIntoARetryableQuestionFailure() = runTest {
        val repository = HttpSceneFormationRepositories(HttpTransport { throw CancellationException("isolated cancellation") }, runtime())
        try {
            repository.next(request())
            fail("Cancellation must propagate, not return a new retry state")
        } catch (_: CancellationException) { /* Expected. */ }
    }

    @Test fun cancelledNonCooperativeResponseCannotBeDeliveredToTheOldCaller() = runTest {
        var pending: Continuation<HttpResponse>? = null
        var delivered: SceneQuestionResult? = null
        val repository = HttpSceneFormationRepositories(HttpTransport { suspendCoroutine { pending = it } }, runtime())
        val job = launch { delivered = repository.next(request()) }
        try {
            runCurrent()
            assertNotNull(pending)
            job.cancel()
            val callback = pending!!; pending = null
            callback.resume(ready())
            runCurrent()
            assertTrue(job.isCancelled)
            assertNull("A cancelled request cannot publish a late question", delivered)
        } finally {
            job.cancel()
            pending?.also { pending = null }?.resume(ready())
            runCurrent()
        }
    }

    @Test fun oldOwnerCannotReceiveAQuestionAfterReloginButOrdinaryRefreshKeepsTheAttempt() = runTest {
        listOf(false, true).forEach { newSession ->
            val runtime = runtime()
            val account = runtime.accountId!!
            val repository = HttpSceneFormationRepositories(HttpTransport {
                runtime.update("refreshed-isolated-token", account, newSession = newSession)
                ready()
            }, runtime)
            val result = repository.next(request())
            if (newSession) assertEquals(SceneQuestionResult.Failed, result)
            else assertTrue(result is SceneQuestionResult.Ask)
        }
    }
}
