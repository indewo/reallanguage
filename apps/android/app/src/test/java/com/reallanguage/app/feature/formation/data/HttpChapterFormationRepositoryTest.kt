package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.ChapterDraft
import com.reallanguage.app.feature.formation.domain.ChapterFormationResult
import com.reallanguage.app.feature.formation.domain.SemanticUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpChapterFormationRepositoryTest {
    @Test fun submitsCompleteUnitsFocusSelectionAndStableCommandWithAuthentication() = runTest {
        val transport = RecordingChapterTransport()
        val result = HttpChapterFormationRepository(transport, SessionRuntime().apply { update("token") }, "chapter-command", FakeChapterCodec).formChapters(units, listOf("u2"))
        assertEquals(ChapterFormationResult.Formed(listOf(ChapterDraft("c1", listOf("u1", "u2")))), result)
        assertEquals("Bearer token", transport.request?.headers?.get("Authorization"))
        assertEquals("u1,u2|u2|chapter-command", transport.request?.body)
    }
    @Test fun refusesToSendUnitsWithoutSession() = runTest {
        val transport = RecordingChapterTransport()
        assertEquals(ChapterFormationResult.Failed, HttpChapterFormationRepository(transport, SessionRuntime(), "cmd", FakeChapterCodec).formChapters(units, listOf("u2")))
        assertEquals(null, transport.request)
    }
    private val units = listOf(SemanticUnit("u1", 0, "第一句"), SemanticUnit("u2", 1, "第二句"))
}
private class RecordingChapterTransport : HttpTransport { var request: HttpRequest? = null; override suspend fun execute(request: HttpRequest): HttpResponse { this.request = request; return HttpResponse(200, "formed") } }
private object FakeChapterCodec : ChapterFormationWireCodec {
    override fun request(units: List<SemanticUnit>, selectedUnitIds: List<String>, commandId: String) = "${units.joinToString(",") { it.unitId }}|${selectedUnitIds.joinToString()}|$commandId"
    override fun chapters(body: String) = listOf(ChapterDraft("c1", listOf("u1", "u2")))
}
