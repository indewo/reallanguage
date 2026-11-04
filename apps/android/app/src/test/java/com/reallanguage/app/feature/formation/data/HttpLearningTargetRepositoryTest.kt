package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HttpLearningTargetRepositoryTest {
    private val chapters = listOf(EnglishChapterDraft("chapter", listOf(
        EnglishSentenceDraft("s1", "我解释了计划。", true, listOf(EnglishCandidate("e1", "I explained the plan."))),
        EnglishSentenceDraft("s2", "她听着。", true, listOf(EnglishCandidate("e2", "She listened."))),
    )))
    private val selections = mapOf("s1" to "e1", "s2" to "e2")
    private val target = LearningTarget("t1", "s1", "explained", "解释", LearningTargetType.WORD)
    private val payload = LearningTargetPayload(listOf(target), emptyList(), emptySet())
    private fun session() = SessionRuntime().apply { update("test-token", "first") }

    @Test fun `fresh pattern response rejects missing and blank structure before making it selectable`() = runTest {
        val pattern = target.copy(type = LearningTargetType.PATTERN, display = "explain + noun", explanationZh = "解释某事")
        for (parts in listOf(emptyList(), listOf(""), listOf(" "), listOf("explain", "\n\t"), listOf("\u00a0\u3000"))) {
            val transport = TargetTransport()
            val result = repository(session(), transport, payload.copy(targets = listOf(pattern.copy(structureParts = parts)))).extract()
            assertEquals("Malformed structure must not become a selected target: $parts", LearningTargetExtractionResult.Failed, result)
            assertEquals("No silent retry or additional request for malformed output", 1, transport.calls)
        }
    }

    @Test fun `fresh response preserves meaningful part order and lets ordinary words omit structure`() = runTest {
        val pattern = target.copy(type = LearningTargetType.PATTERN, display = "explain + noun", explanationZh = "解释某事",
            structureParts = listOf(" explain ", "名词"))
        val result = repository(session(), TargetTransport(), payload.copy(targets = listOf(pattern))).extract()
        assertTrue(result is LearningTargetExtractionResult.Extracted)
        assertEquals(pattern, (result as LearningTargetExtractionResult.Extracted).targets.single())
        assertTrue(repository(session(), TargetTransport()).extract() is LearningTargetExtractionResult.Extracted)
    }

    @Test fun `response evidence cannot refer to another offset sentence or accepted form`() = runTest {
        val source = SourceEvidence(1, 2, 11, "explained", "explain", listOf("explained"))
        assertTrue(repository(session(), TargetTransport(), payload.copy(targets = listOf(target.copy(sourceEvidence = source)))).extract() is LearningTargetExtractionResult.Extracted)
        for (invalid in listOf(source.copy(startUtf16 = 3), source.copy(endUtf16 = 99), source.copy(allowedForms = listOf("explain")))) {
            assertEquals(LearningTargetExtractionResult.Failed, repository(session(), TargetTransport(), payload.copy(targets = listOf(target.copy(sourceEvidence = invalid)))).extract())
        }
    }

    @Test fun `automatic word response rejects another word and multiple source words`() = runTest {
        val text = "I drink tea and coffee."
        val sourceChapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("s1", "我喝茶和咖啡。", true,
            listOf(EnglishCandidate("e1", text))))))
        for ((display, surface) in listOf("tea" to "coffee", "tea and coffee" to "tea and coffee")) {
            val invalid = target.copy(display = display, sourceEvidence = SourceEvidence(1, text.indexOf(surface),
                text.indexOf(surface) + surface.length, surface, "tea", listOf(surface)))
            val repository = HttpLearningTargetRepository(TargetTransport(), session(), sourceChapters, mapOf("s1" to "e1"),
                "command", TargetCodec(payload.copy(targets = listOf(invalid))))
            assertEquals(LearningTargetExtractionResult.Failed, repository.extract())
        }
    }

    @Test fun `automatic response rejects a complete sentence disguised as vocabulary`() = runTest {
        val text = "Can you hear what I'm saying right now?"
        val sourceChapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("s1", "你现在能听清吗？", true,
            listOf(EnglishCandidate("e1", text))))))
        val evidence = SourceEvidence(1, 0, text.length, text, text, listOf(text))
        listOf(LearningTargetType.WORD, LearningTargetType.FIXED_EXPRESSION).forEach { type ->
            val invalid = target.copy(type = type, display = text, sourceEvidence = evidence)
            val repository = HttpLearningTargetRepository(TargetTransport(), session(), sourceChapters, mapOf("s1" to "e1"),
                "command", TargetCodec(payload.copy(targets = listOf(invalid))))
            assertEquals(LearningTargetExtractionResult.Failed, repository.extract())
        }
    }

    @Test fun `automatic word response keeps surface spelling while normalized form may be a lemma`() = runTest {
        val source = SourceEvidence(1, 2, 11, "explained", "explain", listOf("explained"))
        val valid = target.copy(display = " EXPLAINED ", sourceEvidence = source)
        val result = repository(session(), TargetTransport(), payload.copy(targets = listOf(valid))).extract() as LearningTargetExtractionResult.Extracted
        assertEquals(valid, result.targets.single())
    }

    @Test fun `tapped repeated word cannot receive evidence for its other occurrence`() = runTest {
        val repeated = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("s1", "他说你好。", true,
            listOf(EnglishCandidate("e1", "hello hello"))))))
        val wrong = target.copy(display = "hello", sourceEvidence = SourceEvidence(1, 6, 11, "hello", "hello", listOf("hello")))
        val repository = HttpLearningTargetRepository(TargetTransport(), session(), repeated, mapOf("s1" to "e1"), "command", TargetCodec(payload.copy(targets = listOf(wrong))))
        assertEquals(LearningTargetGenerationResult.Failed, repository.generate("s1", "word:0"))
    }

    @Test fun `old page cannot submit old content using new account token`() = runTest {
        val runtime = session()
        val transport = TargetTransport()
        val repository = repository(runtime, transport)
        runtime.update("other-token", "second")
        assertEquals(LearningTargetExtractionResult.Failed, repository.extract())
        assertEquals(0, transport.calls)
    }

    @Test fun `late result after logout cannot become selectable content`() = runTest {
        val runtime = session()
        val repository = repository(runtime, TargetTransport { runtime.clear() })
        assertEquals(LearningTargetExtractionResult.Failed, repository.extract())
    }

    @Test fun `navigation cancellation is propagated`() = runTest {
        val repository = repository(session(), TargetTransport { throw CancellationException("left page") })
        var cancelled = false
        try { repository.extract() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test fun `adding a word rejects result belonging to another selected sentence`() = runTest {
        val repository = repository(session(), TargetTransport(), payload.copy(targets = listOf(target.copy(unitId = "s2"))))
        assertEquals(LearningTargetGenerationResult.Failed, repository.generate("s1", "word:2"))
    }

    @Test fun `adding a word rejects another word even within the same sentence`() = runTest {
        val repository = repository(session(), TargetTransport(), payload.copy(targets = listOf(target.copy(display = "plan"))))
        assertEquals(LearningTargetGenerationResult.Failed, repository.generate("s1", "word:2"))
    }

    @Test fun `provider expression cannot hijack a local word offset`() = runTest {
        val invalid = payload.copy(queryableExpressions = listOf(QueryableExpression("word:2", "s1", "plan")))
        assertEquals(LearningTargetExtractionResult.Failed, repository(session(), TargetTransport(), invalid).extract())
    }

    @Test fun `matching provider word offset remains valid`() = runTest {
        val valid = payload.copy(queryableExpressions = listOf(QueryableExpression("word:2", "s1", "explained")))
        assertTrue(repository(session(), TargetTransport(), valid).extract() is LearningTargetExtractionResult.Extracted)
    }

    @Test fun `restored provider suggestions cannot replace the authoritative tapped word`() = runTest {
        val codec = TargetCodec(payload)
        val repository = HttpLearningTargetRepository(TargetTransport(), session(), chapters, selections, "command", codec,
            initialQueryableExpressions = listOf(QueryableExpression("word:2", "s1", "plan")))
        assertTrue(repository.generate("s1", "word:2") is LearningTargetGenerationResult.Added)
        assertEquals("explained", codec.displays.single())
    }

    @Test fun `extraction rejects foreign sentence and duplicate target identifiers`() = runTest {
        for (invalid in listOf(
            payload.copy(targets = listOf(target.copy(unitId = "foreign"))),
            payload.copy(targets = listOf(target, target)),
            payload.copy(queryableExpressions = listOf(QueryableExpression("foreign", "foreign", "word"))),
        )) assertEquals(LearningTargetExtractionResult.Failed, repository(session(), TargetTransport(), invalid).extract())
    }

    @Test fun `invalid selection cannot initiate paid request`() = runTest {
        val transport = TargetTransport()
        val repository = HttpLearningTargetRepository(transport, session(), chapters, emptyMap(), "command", TargetCodec(payload))
        assertEquals(LearningTargetExtractionResult.Failed, repository.extract())
        assertEquals(0, transport.calls)
    }

    @Test fun `same account token refresh keeps request identity and valid word result`() = runTest {
        val runtime = session()
        val transport = TargetTransport()
        val codec = TargetCodec(payload)
        val repository = HttpLearningTargetRepository(transport, runtime, chapters, selections, "command", codec)
        assertEquals(LearningTargetGenerationResult.Added(target), repository.generate("s1", "word:2"))
        runtime.update("refreshed-token", "first")
        assertEquals(LearningTargetGenerationResult.Added(target), repository.generate("s1", "word:2"))
        assertEquals(codec.commands[0], codec.commands[1])
        assertEquals("Bearer refreshed-token", transport.lastRequest?.headers?.get("Authorization"))
    }

    private fun repository(runtime: SessionRuntime, transport: HttpTransport, result: LearningTargetPayload = payload) =
        HttpLearningTargetRepository(transport, runtime, chapters, selections, "command", TargetCodec(result))
}

private class TargetTransport(private val duringRequest: () -> Unit = {}) : HttpTransport {
    var calls = 0
    var lastRequest: HttpRequest? = null
    override suspend fun execute(request: HttpRequest): HttpResponse {
        calls++
        lastRequest = request
        duringRequest()
        return HttpResponse(200, "test-response")
    }
}

private class TargetCodec(private val result: LearningTargetPayload) : LearningTargetWireCodec {
    val commands = mutableListOf<String>()
    val displays = mutableListOf<String?>()
    override fun request(chapters: List<EnglishChapterDraft>, selections: Map<String, String>, commandId: String,
        requested: Pair<String, String>?, requestedDisplay: String?): String {
        commands += commandId
        displays += requestedDisplay
        return "test-request"
    }
    override fun payload(body: String) = result
}
