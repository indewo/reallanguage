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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpSceneRepositoriesTest {
    @Test fun nextQuestionUsesAuthenticatedVersionedEndpointAndStableStepIdentity() = runTest {
        val transport = FakeTransport(HttpResponse(200, """{"action":"ASK","questionId":"q1","questionZh":"谁会参加？","options":[]}"""))
        val runtime = SessionRuntime().apply { update("token", "account") }
        val repository = HttpSceneFormationRepositories(transport, runtime)
        val request = SceneQuestionRequest(SceneStart.DirectDescription("练习工作沟通"), SceneContentType.DIALOGUE, emptyList(), 5, 10, "operation", 0)
        val result = repository.next(request)
        assertTrue(result is SceneQuestionResult.Ask)
        assertEquals("/api/v1/sources/scenes/next-question", transport.request?.path)
        assertEquals("Bearer token", transport.request?.headers?.get("Authorization"))
    }
    @Test fun unauthenticatedSceneContentNeverLeavesTheDevice() = runTest {
        val transport = FakeTransport(HttpResponse(200, ""))
        val repository = HttpSceneFormationRepositories(transport, SessionRuntime())
        val result = repository.next(SceneQuestionRequest(SceneStart.DirectDescription("描述"), SceneContentType.ARTICLE, listOf(SceneAnswer("q", "回答")), 4, 10, "operation", 1))
        assertEquals(SceneQuestionResult.Failed, result)
        assertEquals(null, transport.request)
    }
    private class FakeTransport(private val response: HttpResponse) : HttpTransport {
        var request: HttpRequest? = null
        override suspend fun execute(request: HttpRequest): HttpResponse { this.request = request; return response }
    }
}
