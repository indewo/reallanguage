package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class KnownLearningTargetBindingTest {
    private val runtime = SessionRuntime().apply { update("fixture", "owner") }
    private val vocabulary = KnownKnowledgeCandidate("VOCABULARY:v", KnownKnowledgeType.VOCABULARY, "work", "工作")
    private val pattern = KnownKnowledgeCandidate("PATTERN:p", KnownKnowledgeType.PATTERN, "used to + verb", "过去习惯")
    private val target = LearningTarget("t", "s", "worked", "工作", LearningTargetType.WORD, sourceEvidence = SourceEvidence(1, 4, 10, "worked", "work", listOf("worked")), knownKnowledgeRef = "VOCABULARY:v")
    private val chapters = listOf(EnglishChapterDraft("c", listOf(
        EnglishSentenceDraft("s", "她工作了。", true, listOf(EnglishCandidate("e", "She worked.", listOf(vocabulary.ref)))),
        EnglishSentenceDraft("context", "以前我常常步行。", false, listOf(EnglishCandidate("ctx", "I used to walk.", listOf(pattern.ref)))),
    )))
    private val selections = mapOf("s" to "e", "context" to "ctx")
    private var calls = 0
    private val transport = object : HttpTransport { override suspend fun execute(request: HttpRequest): HttpResponse { calls++; return HttpResponse(200, "") } }
    private class Codec(private val targets: List<LearningTarget>) : LearningTargetWireCodec {
        var sent: Map<String, List<KnownKnowledgeCandidate>>? = null
        override fun request(chapters: List<EnglishChapterDraft>, selections: Map<String, String>, commandId: String, requested: Pair<String, String>?, requestedDisplay: String?) = commandId
        override fun requestWithKnowledge(chapters: List<EnglishChapterDraft>, selections: Map<String, String>, commandId: String, requested: Pair<String, String>?, requestedDisplay: String?, reusedKnowledge: Map<String, List<KnownKnowledgeCandidate>>): String { sent = reusedKnowledge; return "$commandId|$reusedKnowledge" }
        override fun payload(body: String) = LearningTargetPayload(targets, emptyList(), setOf("DAILY_CORE"))
    }
    private fun repo(targets: List<LearningTarget>, source: KnownKnowledgeReferenceSource? = { listOf(vocabulary, pattern) }, codec: Codec = Codec(targets)) = HttpLearningTargetRepository(transport, runtime, chapters, selections, "base", codec, knowledgeSource = source)

    @Test fun `exact known scope is sent per selected sentence and inflected word remains its existing sense`() = runTest {
        val codec = Codec(listOf(target))
        val result = repo(listOf(target), codec = codec).extract() as LearningTargetExtractionResult.Extracted
        assertEquals(target, result.targets.single())
        assertEquals(listOf(vocabulary), codec.sent?.get("s")); assertEquals(listOf(pattern), codec.sent?.get("context"))
        assertEquals(1, calls)
    }
    @Test fun `every reused focus item must become exactly one correctly bound default target`() = runTest {
        for (bad in listOf(emptyList(), listOf(target.copy(knownKnowledgeRef = null)), listOf(target.copy(knownKnowledgeRef = pattern.ref)), listOf(target.copy(explanationZh = "另一词义")), listOf(target.copy(type = LearningTargetType.PATTERN)), listOf(target, target.copy(targetId = "another")))) {
            assertEquals(LearningTargetExtractionResult.Failed, repo(bad).extract())
        }
    }
    @Test fun `missing removed or wrong owned reference context fails before provider use`() = runTest {
        assertEquals(LearningTargetExtractionResult.Failed, repo(listOf(target), source = null).extract())
        assertEquals(LearningTargetExtractionResult.Failed, repo(listOf(target), source = { null }).extract())
        assertEquals(LearningTargetExtractionResult.Failed, repo(listOf(target), source = { listOf(vocabulary.copy(ref = "VOCABULARY:other"), pattern) }).extract())
        assertEquals(0, calls)
    }
    @Test fun `context-only knowledge does not create a target or satisfy a missing focus target`() = runTest {
        val contextTarget = target.copy(targetId = "context-t", unitId = "context", knownKnowledgeRef = pattern.ref, type = LearningTargetType.PATTERN)
        assertEquals(LearningTargetExtractionResult.Failed, repo(listOf(target, contextTarget)).extract())
        assertTrue(repo(listOf(target)).extract() is LearningTargetExtractionResult.Extracted)
    }
    @Test fun `a target repository from before logout cannot read or post under a new login of the same owner`() = runTest {
        var reads = 0
        val stale = repo(listOf(target), source = { reads++; listOf(vocabulary, pattern) })
        runtime.clear(); runtime.update("new-login", "owner")
        assertEquals(LearningTargetExtractionResult.Failed, stale.extract())
        assertEquals(0, reads); assertEquals(0, calls)
    }
    @Test fun `a valid current span cannot bind an unrelated normalized word to existing knowledge`() {
        val unrelated = target.copy(display = "walked", sourceEvidence = SourceEvidence(1, 4, 10, "walked", "walk", listOf("walked")))
        assertFalse(knownTargetMatches(vocabulary, unrelated))
        assertTrue(knownTargetMatches(vocabulary, target))
    }
    @Test fun `normal token refresh keeps the same target entry usable`() = runTest {
        val current = repo(listOf(target))
        runtime.update("refreshed-token", "owner")
        assertTrue(current.extract() is LearningTargetExtractionResult.Extracted)
    }
}
