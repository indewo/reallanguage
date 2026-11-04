package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AcceptedVocabularyRequestTest {
    private val evidence = SourceEvidence(1, 8, 11, "tea", "tea", listOf("tea"))
    private val item = RelatedVocabularyItem("local-tea", "u1", "tea", "茶", LearningTargetType.WORD, evidence)
    private val pattern = LearningTarget("p1", "u1", "drink + noun", "喝某物", LearningTargetType.PATTERN,
        structureParts = listOf("drink", "noun"), relatedVocabulary = listOf("tea"),
        relatedVocabularyItems = listOf(item), relatedVocabularyItemRefs = listOf(item.itemRef))
    private val word = LearningTarget("w1", "u1", "tea", "茶", LearningTargetType.WORD, sourceEvidence = evidence)
    private val expected = AcceptedVocabularyContext("tea", "茶", LearningTargetType.WORD, evidence)
    private val chapters = listOf(EnglishChapterDraft("c1", listOf(
        EnglishSentenceDraft("u1", "我喝茶。", true, listOf(EnglishCandidate("e1", "I drink tea."))),
        EnglishSentenceDraft("u2", "我喝水。", true, listOf(EnglishCandidate("e2", "I drink water."))))))
    private val selections = mapOf("u1" to "e1", "u2" to "e2")

    @Test fun restoredPatternSeedsOnlyTheAcceptedCurrentOccurrenceIntoThePaidRequest() = runTest {
        val codec = RecordingCodec(word)
        val transport = AcceptedVocabularyTransport()
        val repo = repository(listOf(pattern), codec, transport)
        assertEquals(LearningTargetGenerationResult.Added(word), repo.generate("u1", "word:8"))
        assertEquals(expected, codec.sent(transport).accepted)
        assertEquals(1, transport.requests.size)
    }

    @Test fun requestIdentityIsStableOnReentryAndIgnoresUnrelatedSentencesButChangesWithThisMeaning() = runTest {
        suspend fun command(seed: List<LearningTarget>, result: LearningTarget = word): String {
            val codec = RecordingCodec(result)
            val transport = AcceptedVocabularyTransport()
            assertEquals(LearningTargetGenerationResult.Added(result), repository(seed, codec, transport).generate("u1", "word:8"))
            return codec.sent(transport).command
        }
        val original = command(listOf(pattern))
        assertEquals(original, command(listOf(pattern)))
        val unrelated = pattern.copy(targetId = "p2", unitId = "u2", relatedVocabularyItems = null, relatedVocabularyItemRefs = null)
        assertEquals(original, command(listOf(pattern, unrelated)))
        val changed = pattern.copy(relatedVocabularyItems = listOf(item.copy(senseZh = "茶饮")))
        assertNotEquals(original, command(listOf(changed), word.copy(explanationZh = "茶饮")))
    }

    @Test fun successfulExtractionSuppliesTheNextWordContextAndFailedRefreshDoesNotEraseIt() = runTest {
        val codec = RecordingCodec(pattern)
        val transport = AcceptedVocabularyTransport()
        val repo = repository(emptyList(), codec, transport)
        assertTrue(repo.extract() is LearningTargetExtractionResult.Extracted)
        transport.status = 503
        assertEquals(LearningTargetExtractionResult.Failed, repo.extract())
        transport.status = 200
        codec.target = word
        assertEquals(LearningTargetGenerationResult.Added(word), repo.generate("u1", "word:8"))
        assertEquals(expected, codec.sent(transport).accepted)
    }

    @Test fun conflictingSeedOrCodecWithoutContextSupportFailsBeforeAnyRequest() = runTest {
        val transport = AcceptedVocabularyTransport()
        val conflict = pattern.copy(targetId = "p2", relatedVocabularyItems = listOf(item.copy(senseZh = "茶叶")))
        assertEquals(LearningTargetGenerationResult.Failed,
            repository(listOf(pattern, conflict), RecordingCodec(word), transport).generate("u1", "word:8"))
        assertEquals(0, transport.requests.size)
        val legacyCodec = object : LearningTargetWireCodec {
            override fun request(chapters: List<EnglishChapterDraft>, selections: Map<String, String>, commandId: String,
                requested: Pair<String, String>?, requestedDisplay: String?) = "legacy:$commandId"
            override fun payload(body: String) = LearningTargetPayload(listOf(word), emptyList(), emptySet())
        }
        assertEquals(LearningTargetGenerationResult.Failed, repository(listOf(pattern), legacyCodec, transport).generate("u1", "word:8"))
        assertEquals(0, transport.requests.size)
    }

    @Test fun rejectedOutputCannotOverwriteAcceptedMeaningAndRetryKeepsTheSameCommand() = runTest {
        val codec = RecordingCodec(word.copy(explanationZh = "茶叶"))
        val transport = AcceptedVocabularyTransport()
        val repo = repository(listOf(pattern), codec, transport)
        assertEquals(LearningTargetGenerationResult.Failed, repo.generate("u1", "word:8"))
        val first = codec.sent(transport)
        codec.target = word
        assertEquals(LearningTargetGenerationResult.Added(word), repo.generate("u1", "word:8"))
        assertEquals(first, codec.sent(transport))
        assertEquals(expected, first.accepted)
    }

    @Test fun cancellationPreservesAcceptedContextAndNeverTurnsIntoACompletedGeneration() = runTest {
        val codec = RecordingCodec(word)
        val transport = AcceptedVocabularyTransport().apply { cancelNext = true }
        val repo = repository(listOf(pattern), codec, transport)
        var cancelled = false
        try { repo.generate("u1", "word:8") } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        val cancelledCommand = codec.sent(transport)
        assertEquals(LearningTargetGenerationResult.Added(word), repo.generate("u1", "word:8"))
        assertEquals(cancelledCommand, codec.sent(transport))
        assertEquals(expected, cancelledCommand.accepted)
    }

    @Test fun tappingTheSecondRepeatedWordUsesItsOwnMeaningNotTheFirstOccurrence() = runTest {
        val first = item.copy(itemRef = "first", display = "Tea", sourceEvidence = SourceEvidence(1, 0, 3, "Tea", "tea", listOf("Tea")))
        val second = item.copy(itemRef = "second", senseZh = "茶点")
        val repeatedPattern = pattern.copy(relatedVocabulary = listOf("Tea", "tea"), relatedVocabularyItems = listOf(first, second),
            relatedVocabularyItemRefs = listOf(first.itemRef, second.itemRef))
        val repeatedChapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "茶和茶点。", true,
            listOf(EnglishCandidate("e1", "Tea and tea."))))))
        val codec = RecordingCodec(word.copy(explanationZh = "茶点"))
        val transport = AcceptedVocabularyTransport()
        val repo = HttpLearningTargetRepository(transport, runtime(), repeatedChapters, mapOf("u1" to "e1"), "base", codec,
            initialTargets = listOf(repeatedPattern))
        assertTrue(repo.generate("u1", "word:8") is LearningTargetGenerationResult.Added)
        assertEquals(expected.copy(senseZh = "茶点"), codec.sent(transport).accepted)
    }

    @Test fun legacyWithoutAcceptedMeaningKeepsTheOldCommandAndWordTapsCannotGeneratePatterns() = runTest {
        val codec = RecordingCodec(word)
        val transport = AcceptedVocabularyTransport()
        val repo = repository(emptyList(), codec, transport)
        assertEquals(LearningTargetGenerationResult.Added(word), repo.generate("u1", "word:8"))
        assertNull(codec.sent(transport).accepted)
        assertEquals(UUID.nameUUIDFromBytes("base:u1:word:8".toByteArray()).toString(), codec.sent(transport).command)
        codec.target = word.copy(type = LearningTargetType.PATTERN, structureParts = listOf("tea"))
        assertEquals(LearningTargetGenerationResult.Failed, repository(emptyList(), codec, transport).generate("u1", "word:8"))
    }

    @Test fun successfulWordIsReusedWithoutTurningItsNewEvidenceIntoASecondPaidRequest() = runTest {
        val codec = RecordingCodec(word)
        val transport = AcceptedVocabularyTransport()
        val repo = repository(emptyList(), codec, transport)
        assertEquals(LearningTargetGenerationResult.Added(word), repo.generate("u1", "word:8"))
        val firstCommand = codec.sent(transport).command
        assertEquals(LearningTargetGenerationResult.Added(word), repo.generate("u1", "word:8"))
        assertEquals("A successful local target is not a new paid word request", 1, transport.requests.size)
        assertEquals(firstCommand, codec.sent(transport).command)
        val restoredTransport = AcceptedVocabularyTransport()
        assertEquals(LearningTargetGenerationResult.Added(word), repository(listOf(word), codec, restoredTransport).generate("u1", "word:8"))
        assertEquals(0, restoredTransport.requests.size)
    }

    @Test fun aResponseIdCollisionCannotRemoveAnotherSentencesAcceptedExplanation() = runTest {
        val waterEvidence = SourceEvidence(1, 8, 13, "water", "water", listOf("water"))
        val waterItem = item.copy(itemRef = "local-water", unitId = "u2", display = "water", senseZh = "水", sourceEvidence = waterEvidence)
        val saved = pattern.copy(targetId = "w1", unitId = "u2", relatedVocabulary = listOf("water"),
            relatedVocabularyItems = listOf(waterItem), relatedVocabularyItemRefs = listOf(waterItem.itemRef))
        val codec = RecordingCodec(word)
        val transport = AcceptedVocabularyTransport()
        val repo = repository(listOf(saved), codec, transport)
        assertEquals(LearningTargetGenerationResult.Failed, repo.generate("u1", "word:8"))
        codec.target = word.copy(targetId = "water-target", unitId = "u2", display = "water", explanationZh = "水", sourceEvidence = waterEvidence)
        assertTrue(repo.generate("u2", "word:8") is LearningTargetGenerationResult.Added)
        assertEquals(AcceptedVocabularyContext("water", "水", LearningTargetType.WORD, waterEvidence), codec.sent(transport).accepted)
    }

    private fun runtime() = SessionRuntime().apply { update("isolated-token", "isolated-owner") }
    private fun repository(seed: List<LearningTarget>, codec: LearningTargetWireCodec, transport: HttpTransport) =
        HttpLearningTargetRepository(transport, runtime(), chapters, selections, "base", codec, initialTargets = seed)
}

private data class EncodedVocabularyRequest(val command: String, val accepted: AcceptedVocabularyContext?)
private class RecordingCodec(var target: LearningTarget) : LearningTargetWireCodec {
    private val encoded = mutableMapOf<String, EncodedVocabularyRequest>()
    override fun request(chapters: List<EnglishChapterDraft>, selections: Map<String, String>, commandId: String,
        requested: Pair<String, String>?, requestedDisplay: String?): String = encode(commandId, null)
    override fun requestWithAcceptedVocabulary(chapters: List<EnglishChapterDraft>, selections: Map<String, String>, commandId: String,
        requested: Pair<String, String>?, requestedDisplay: String?, reusedKnowledge: Map<String, List<KnownKnowledgeCandidate>>,
        acceptedVocabulary: AcceptedVocabularyContext?): String = encode(commandId, acceptedVocabulary)
    override fun payload(body: String) = LearningTargetPayload(listOf(target), emptyList(), emptySet())
    fun sent(transport: AcceptedVocabularyTransport) = encoded.getValue(requireNotNull(transport.requests.last().body))
    private fun encode(command: String, accepted: AcceptedVocabularyContext?): String {
        val body = "$command|$accepted"
        encoded[body] = EncodedVocabularyRequest(command, accepted)
        return body
    }
}
private class AcceptedVocabularyTransport : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    var status = 200
    var cancelNext = false
    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        if (cancelNext) { cancelNext = false; throw CancellationException("isolated navigation") }
        return HttpResponse(status, "isolated-response")
    }
}
