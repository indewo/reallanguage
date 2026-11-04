package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.ChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishGenerationResult
import com.reallanguage.app.feature.formation.domain.SemanticUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpEnglishGenerationRepositoryTest {
    @Test fun `posts authenticated stable generation request and decodes candidates`() = runTest {
        val transport = RecordingTransport(HttpResponse(200, "ignored"))
        val expected = listOf(EnglishChapterDraft("c1", emptyList()))
        val codec = FakeCodec(expected)
        val result = HttpEnglishGenerationRepository(transport, SessionRuntime().apply { update("token") }, "cmd", codec)
            .generate(listOf(unit), listOf("u1"), listOf(ChapterDraft("c1", listOf("u1"))))
        assertEquals(EnglishGenerationResult.Generated(expected), result)
        assertEquals("/api/v1/content-formation/english-candidates", transport.request?.path)
        assertEquals("Bearer token", transport.request?.headers?.get("Authorization"))
        assertEquals("cmd", codec.commandId)
    }
    @Test fun `fails without a session or on a non-success response`() = runTest {
        val noSession = HttpEnglishGenerationRepository(RecordingTransport(HttpResponse(200, "")), SessionRuntime(), "cmd", FakeCodec(emptyList()))
        assertEquals(EnglishGenerationResult.Failed, noSession.generate(listOf(unit), listOf("u1"), listOf(ChapterDraft("c1", listOf("u1")))))
    }
    private val unit = SemanticUnit("u1", 0, "今天很好。")
}

private class RecordingTransport(private val response: HttpResponse) : HttpTransport {
    var request: HttpRequest? = null
    override suspend fun execute(request: HttpRequest): HttpResponse { this.request = request; return response }
}
private class FakeCodec(private val output: List<EnglishChapterDraft>) : EnglishGenerationWireCodec {
    var commandId: String? = null
    override fun request(units: List<SemanticUnit>, selectedUnitIds: List<String>, chapters: List<ChapterDraft>, commandId: String): String { this.commandId = commandId; return "{}" }
    override fun chapters(body: String) = output
}
