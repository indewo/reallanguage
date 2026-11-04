package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EnglishKnownKnowledgeRepositoryTest {
    private val item = KnownKnowledgeCandidate("PATTERN:p", KnownKnowledgeType.PATTERN, "used to + verb", "过去习惯")
    private val runtime = SessionRuntime().apply { update("fixture", "owner") }
    private val units = listOf(SemanticUnit("u", 0, "我以前常常步行。"))
    private val chapters = listOf(ChapterDraft("c", listOf("u")))
    private fun output(refs: List<String>) = listOf(EnglishChapterDraft("c", listOf(EnglishSentenceDraft("u", units[0].textZh, true, listOf(EnglishCandidate("e", "I used to walk.", refs))))))
    private class Codec(private val result: List<EnglishChapterDraft>) : EnglishGenerationWireCodec {
        override fun request(units: List<SemanticUnit>, selectedUnitIds: List<String>, chapters: List<ChapterDraft>, commandId: String) = "$commandId|${units.map { it.textZh }}|$selectedUnitIds|$chapters"
        override fun requestWithKnowledge(units: List<SemanticUnit>, selectedUnitIds: List<String>, chapters: List<ChapterDraft>, commandId: String, knownKnowledge: List<KnownKnowledgeCandidate>, sourceType: String) = request(units, selectedUnitIds, chapters, commandId) + "|$sourceType|$knownKnowledge"
        override fun chapters(body: String) = result
    }
    @Test fun `failed snapshot or unknown output never becomes a generation success`() = runTest {
        var calls = 0
        val transport = object : HttpTransport { override suspend fun execute(request: HttpRequest): HttpResponse { calls++; return HttpResponse(200, "") } }
        val failed = HttpEnglishGenerationRepository(transport, runtime, "base", Codec(output(emptyList())), { _, _ -> KnownKnowledgeSnapshotResult.Failed })
        assertEquals(EnglishGenerationResult.Failed, failed.generate(units, listOf("u"), chapters)); assertEquals(0, calls)
        val wrong = HttpEnglishGenerationRepository(transport, runtime, "base", Codec(output(listOf("PATTERN:other"))), { _, _ -> KnownKnowledgeSnapshotResult.Ready(listOf(item)) })
        assertEquals(EnglishGenerationResult.Failed, wrong.generate(units, listOf("u"), chapters))
    }
    @Test fun `retry and new repository use frozen input and identity while changed facts change digest`() = runTest {
        val requests = mutableListOf<HttpRequest>(); val snapshots = mutableListOf<Pair<String, String>>()
        val source = EnglishKnowledgeSnapshotSource { base, digest -> snapshots += base to digest; KnownKnowledgeSnapshotResult.Ready(listOf(item)) }
        val transport = object : HttpTransport { override suspend fun execute(request: HttpRequest): HttpResponse { requests += request; return HttpResponse(if(requests.size == 1) 503 else 200, "") } }
        fun repo() = HttpEnglishGenerationRepository(transport, runtime, "base", Codec(output(listOf(item.ref))), source, "AI_SCENE")
        assertEquals(EnglishGenerationResult.Failed, repo().generate(units, listOf("u"), chapters))
        assertTrue(repo().generate(units, listOf("u"), chapters) is EnglishGenerationResult.Generated)
        assertEquals(2, snapshots.size); assertEquals(snapshots[0], snapshots[1]); assertEquals("base", snapshots[0].first)
        assertEquals(requests[0].body, requests[1].body); assertTrue(requests[0].body.contains("PATTERN:p")); assertTrue(requests[0].body.contains("AI_SCENE"))
        repo().generate(units.map { it.copy(textZh = "我以前常常跑步。") }, listOf("u"), chapters)
        assertNotEquals(snapshots[0].second, snapshots.last().second)
        assertNotEquals(requests[0].body, requests.last().body)
    }
    @Test fun `concurrent repeat on one instance shares successful generation and output remains owned`() = runTest {
        var calls = 0; val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = HttpEnglishGenerationRepository(object : HttpTransport { override suspend fun execute(request: HttpRequest): HttpResponse { calls++; entered.complete(Unit); release.await(); return HttpResponse(200, "") } }, runtime, "base", Codec(output(listOf(item.ref))), { _, _ -> KnownKnowledgeSnapshotResult.Ready(listOf(item)) })
        val first = async { repo.generate(units, listOf("u"), chapters) }; entered.await()
        val second = async { repo.generate(units, listOf("u"), chapters) }; release.complete(Unit)
        assertTrue(first.await() is EnglishGenerationResult.Generated); assertEquals(first.await(), second.await()); assertEquals(1, calls)
    }
    @Test fun `late account replacement discards output and cancellation is never converted to failure`() = runTest {
        val replaced = HttpEnglishGenerationRepository(object : HttpTransport { override suspend fun execute(request: HttpRequest): HttpResponse { runtime.update("other", "other"); return HttpResponse(200, "") } }, runtime, "base", Codec(output(listOf(item.ref))), { _, _ -> KnownKnowledgeSnapshotResult.Ready(listOf(item)) })
        assertEquals(EnglishGenerationResult.Failed, replaced.generate(units, listOf("u"), chapters))
        val cancelled = HttpEnglishGenerationRepository(object : HttpTransport { override suspend fun execute(request: HttpRequest): HttpResponse = throw CancellationException() }, runtime, "base", Codec(output(emptyList())), { _, _ -> KnownKnowledgeSnapshotResult.Ready(emptyList()) })
        var propagated = false
        try { cancelled.generate(units, listOf("u"), chapters) } catch (_: CancellationException) { propagated = true }
        assertTrue(propagated)
    }
    @Test fun `a repository from before logout cannot generate after the same account logs in again`() = runTest {
        var calls = 0; var snapshots = 0
        val repo = HttpEnglishGenerationRepository(object : HttpTransport { override suspend fun execute(request: HttpRequest): HttpResponse { calls++; return HttpResponse(200, "") } }, runtime, "base", Codec(output(emptyList())), { _, _ -> snapshots++; KnownKnowledgeSnapshotResult.Ready(emptyList()) })
        runtime.clear(); runtime.update("new-login", "owner")
        assertEquals(EnglishGenerationResult.Failed, repo.generate(units, listOf("u"), chapters))
        assertEquals(0, calls); assertEquals(0, snapshots)
    }
    @Test fun `normal token refresh does not permanently invalidate the same active account repository`() = runTest {
        val repo = HttpEnglishGenerationRepository(HttpTransport { HttpResponse(200, "") }, runtime, "base", Codec(output(emptyList())), { _, _ -> KnownKnowledgeSnapshotResult.Ready(emptyList()) })
        runtime.update("refreshed-token", "owner")
        assertTrue(repo.generate(units, listOf("u"), chapters) is EnglishGenerationResult.Generated)
    }
}
