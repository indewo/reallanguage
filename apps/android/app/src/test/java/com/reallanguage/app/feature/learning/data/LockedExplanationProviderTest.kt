package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.SourceEvidence
import com.reallanguage.app.feature.learning.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LockedExplanationProviderTest {
    private val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm today.", "我今天很平静。", true)))
    private val evidence = SourceEvidence(1, 7, 11, "calm", "calm", listOf("calm"))
    private val target = LockedExplanationTarget("t1", LearningTargetType.WORD, "calm", " 平静的 ", emptyList(), evidence, emptyList())
    private val context = LockedExplanationContext("c1", "s1", chapter.sentences.single().textEn, listOf(target))
    private val expression = ExpressionExplanation("word-2", "完整解释", contextualMeaningZh = " 平静的 ", usageZh = "用于描述感受。")
    private fun runtime() = SessionRuntime().apply { update("isolated-token", "locked-owner") }

    @Test fun immutableContextIsBoundToTheDisplayedChapterSentenceAndSentWithoutChangingTheCommand() = runTest {
        val codec = LockedProviderCodec(expression)
        val transport = LockedProviderTransport()
        val result = provider(codec, transport).prepare()
        assertTrue(result is ExplanationPreparationResult.Ready)
        assertEquals(context.targets, codec.locked)
        assertEquals("entry:1:sentence", codec.command)
        assertEquals(1, transport.calls)
    }

    @Test fun unavailableWrongScopeOrMalformedLockedFactsFailBeforeHttp() = runTest {
        val invalid = listOf(null, context.copy(chapterId = "other"), context.copy(focusSentenceId = "other"),
            context.copy(textEn = "I felt tired today."), context.copy(targets = listOf(target.copy(sourceEvidence = evidence.copy(startUtf16 = 8)))))
        invalid.forEach { value ->
            val transport = LockedProviderTransport()
            assertEquals(ExplanationPreparationResult.Failed, provider(LockedProviderCodec(expression), transport, loader = { value }).prepare())
            assertEquals(0, transport.calls)
        }
    }

    @Test fun incorrectFreshMeaningIsNotReturnedOrCached() = runTest {
        val cache = LockedProviderMemoryCache()
        val transport = LockedProviderTransport()
        val codec = LockedProviderCodec(expression.copy(contextualMeaningZh = "冷静下来"))
        assertEquals(ExplanationContentResult.Failed, provider(codec, transport, cache).explainExpression("s1", "word-2"))
        assertEquals(1, transport.calls)
        assertEquals(0, cache.writes)
    }

    @Test fun anExistingWrongMeaningCacheFailsReadOnlyWithoutDeletionOrAnotherPaidCall() = runTest {
        val cache = LockedProviderMemoryCache().apply { values[expressionKey()] = "already-paid-body" }
        val transport = LockedProviderTransport()
        val codec = LockedProviderCodec(expression.copy(contextualMeaningZh = "冷静下来"))
        assertEquals(ExplanationContentResult.Failed, provider(codec, transport, cache).explainExpression("s1", "word-2"))
        assertEquals(0, transport.calls)
        assertEquals(mapOf(expressionKey() to "already-paid-body"), cache.values)
        assertEquals(0, cache.writes)
    }

    @Test fun validAndLegacyDeliveredCachesKeepTheirOriginalV1KeyAndNeedNoHttp() = runTest {
        for (value in listOf(expression, ExpressionExplanation("word-2", "旧完整解释"))) {
            val cache = LockedProviderMemoryCache().apply { values[expressionKey()] = "already-paid-body" }
            val transport = LockedProviderTransport()
            assertEquals(ExplanationContentResult.ExpressionReady(value), provider(LockedProviderCodec(value), transport, cache)
                .explainExpression("s1", "word-2"))
            assertEquals(listOf(expressionKey()), cache.readKeys)
            assertEquals(0, cache.writes); assertEquals(0, transport.calls)
        }
    }

    @Test fun oldPendingServerConflictKeepsStableCommandAndAllowsOnlyOneReadOnlyRecovery() = runTest {
        val codec = LockedProviderCodec(expression)
        val cache = LockedProviderMemoryCache()
        val transport = LockedProviderTransport().apply { status = 409 }
        assertEquals(ExplanationContentResult.Failed, provider(codec, transport, cache).explainExpression("s1", "word-2"))
        val expectedCommand = UUID.nameUUIDFromBytes("locked-owner:c1:s1:${expressionKey()}".toByteArray()).toString()
        assertEquals(expectedCommand, codec.command)
        assertEquals(2, transport.calls); assertEquals(0, cache.writes)
    }

    @Test fun savedProgressValidationUsesLockedMeaningAndCurrentExpressionWithoutHttp() = runTest {
        val transport = LockedProviderTransport()
        val provider = provider(LockedProviderCodec(expression), transport)
        assertTrue(provider.validateSavedExpression("s1", expression))
        assertFalse(provider.validateSavedExpression("s1", expression.copy(contextualMeaningZh = "不相干的含义")))
        assertFalse(provider.validateSavedExpression("other", expression))
        assertFalse(provider.validateSavedExpression("s1", expression.copy(expressionId = "unknown")))
        assertEquals(0, transport.calls)
    }

    @Test fun sameAccountLogoutAndReloginRevokesOldProviderButTokenRefreshDoesNot() = runTest {
        val session = runtime()
        val transport = LockedProviderTransport()
        val old = provider(LockedProviderCodec(expression), transport, session = session)
        session.clear(); session.update("new-token", "locked-owner")
        assertEquals(ExplanationPreparationResult.Failed, old.prepare())
        assertEquals(0, transport.calls)
        val fresh = provider(LockedProviderCodec(expression), transport, session = session)
        session.update("refreshed-token", "locked-owner")
        assertTrue(fresh.prepare() is ExplanationPreparationResult.Ready)
    }

    @Test fun relatedPhraseRemainsClickableWithoutAddingAnIndependentTarget() = runTest {
        val phraseEvidence = SourceEvidence(1, 2, 11, "felt calm", "feel calm", listOf("felt calm"))
        val related = LockedExplanationVocabulary("felt calm", "感到平静", LearningTargetType.FIXED_EXPRESSION, phraseEvidence)
        val pattern = LockedExplanationTarget("pattern", LearningTargetType.PATTERN, "subject + feel + adjective", "描述感受",
            listOf("subject", "feel", "adjective"), SourceEvidence(1, 0, 11, "I felt calm", "subject + feel + adjective", listOf("I felt calm")), listOf(related))
        val result = provider(LockedProviderCodec(expression), LockedProviderTransport(), loader = { context.copy(targets = listOf(pattern)) })
            .prepare() as ExplanationPreparationResult.Ready
        assertEquals(SelectableExpression("phrase:2:11", "felt calm", 2, 11), result.expressions.single { it.expressionId == "phrase:2:11" })
        assertTrue(result.expressions.any { it.expressionId == "word-2" })
        assertEquals(1, context.copy(targets = listOf(pattern)).targets.size)
    }

    @Test fun aPatternSourceSpanDoesNotTurnItsWholeSentenceIntoAClickablePhrase() = runTest {
        val related = LockedExplanationVocabulary("felt calm", "感到平静", LearningTargetType.FIXED_EXPRESSION,
            SourceEvidence(1, 2, 11, "felt calm", "feel calm", listOf("felt calm")))
        val pattern = LockedExplanationTarget("pattern", LearningTargetType.PATTERN, "subject + feel + adjective", "描述感受",
            listOf("subject", "feel", "adjective"), SourceEvidence(1, 0, 11, "I felt calm", "subject + feel + adjective", listOf("I felt calm")), listOf(related))
        val result = provider(LockedProviderCodec(expression), LockedProviderTransport(), loader = { context.copy(targets = listOf(pattern)) })
            .prepare() as ExplanationPreparationResult.Ready
        assertFalse(result.expressions.any { it.expressionId == "phrase:0:11" })
        assertEquals(SelectableExpression("phrase:2:11", "felt calm", 2, 11), result.expressions.single { it.expressionId == "phrase:2:11" })
        assertTrue(result.expressions.any { it.expressionId == "word-2" })
    }

    @Test fun anOldPatternWithUnknownSourceAndStructureKeepsItsDeliveredExplanationReadable() = runTest {
        val oldPattern = LockedExplanationTarget("legacy-pattern", LearningTargetType.PATTERN,
            "subject + feel + adjective", "旧句式解释", emptyList(), null, null)
        val cache = LockedProviderMemoryCache().apply { values[expressionKey()] = "already-paid-body" }
        val transport = LockedProviderTransport()
        val oldExplanation = ExpressionExplanation("word-2", "旧完整解释")
        assertEquals(ExplanationContentResult.ExpressionReady(oldExplanation),
            provider(LockedProviderCodec(oldExplanation), transport, cache,
                loader = { context.copy(targets = listOf(oldPattern)) }).explainExpression("s1", "word-2"))
        assertEquals(0, transport.calls)
        assertEquals(0, cache.writes)
        assertEquals("already-paid-body", cache.values[expressionKey()])
    }

    @Test fun aNewPatternWithSourceEvidenceStillRequiresItsRealStructure() = runTest {
        val newPattern = LockedExplanationTarget("new-pattern", LearningTargetType.PATTERN,
            "subject + feel + adjective", "描述感受", emptyList(),
            SourceEvidence(1, 0, 11, "I felt calm", "subject + feel + adjective", listOf("I felt calm")), null)
        val transport = LockedProviderTransport()
        assertEquals(ExplanationPreparationResult.Failed,
            provider(LockedProviderCodec(expression), transport, loader = { context.copy(targets = listOf(newPattern)) }).prepare())
        assertEquals(0, transport.calls)
    }

    @Test fun unicodeWordsNeverReuseOldSplitWordIdsOrRenumberUnaffectedPaidWords() = runTest {
        val text = "I’m calm."
        val unicodeChapter = chapter.copy(sentences = listOf(FixedLearningSentence("s1", text, "我很平静。", true)))
        val provider = HttpExplanationReadingProvider(LockedProviderTransport(), runtime(), unicodeChapter, "s1", "entry",
            LockedProviderCodec(expression), lockedContext = { context.copy(textEn = text, targets = emptyList()) })
        val result = provider.prepare() as ExplanationPreparationResult.Ready
        assertEquals(SelectableExpression("word-utf16:0:3", "I’m", 0, 3), result.expressions.single { it.display == "I’m" })
        assertEquals("word-2", result.expressions.single { it.display == "calm" }.expressionId)
        assertFalse(result.expressions.any { it.expressionId in setOf("word-0", "word-1") })
        assertFalse(provider.validateSavedExpression("s1", expression.copy(expressionId = "word-0")))
    }

    @Test fun lockedLoaderCancellationPropagatesWithoutHttpOrCacheMutation() = runTest {
        val cache = LockedProviderMemoryCache()
        val transport = LockedProviderTransport()
        var cancelled = false
        try { provider(LockedProviderCodec(expression), transport, cache, loader = { throw CancellationException("left") }).prepare() }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled); assertEquals(0, transport.calls); assertEquals(0, cache.writes)
    }

    @Test fun aLateResponseFromThePreviousSameAccountSessionCannotPopulateTheCache() = runTest {
        val session = runtime()
        val cache = LockedProviderMemoryCache()
        val transport = HttpTransport {
            session.clear(); session.update("new-login-token", "locked-owner")
            HttpResponse(200, "old-session-body")
        }
        assertEquals(ExplanationPreparationResult.Failed, provider(LockedProviderCodec(expression), transport, cache, session).prepare())
        assertEquals(0, cache.writes)
    }

    @Test fun aFreshLockedResponseCannotPretendToBeLegacyWhenItsRequiredMeaningGroupIsMissing() = runTest {
        val cache = LockedProviderMemoryCache()
        val transport = LockedProviderTransport()
        assertEquals(ExplanationContentResult.Failed,
            provider(LockedProviderCodec(ExpressionExplanation("word-2", "仅有旧整段解释")), transport, cache)
                .explainExpression("s1", "word-2"))
        assertEquals(1, transport.calls)
        assertEquals(0, cache.writes)
    }

    @Test fun aSavedOccurrenceCannotUseAnotherLegitimateSubstringUnderTheSameExpressionId() = runTest {
        val transport = LockedProviderTransport()
        val provider = provider(LockedProviderCodec(expression), transport)
        val saved = ExpressionExplanation("word-3", "旧完整解释")
        assertTrue(provider.validateSavedExpressionOccurrence("s1", SelectableExpression("word-3", "today", 12, 17), saved))
        assertFalse(provider.validateSavedExpressionOccurrence("s1", SelectableExpression("word-3", "calm", 7, 11), saved))
        assertEquals(0, transport.calls)
    }

    private fun expressionKey() = UUID.nameUUIDFromBytes("v1:1:word-2:".toByteArray()).toString()
    private fun provider(codec: ExplanationReadingWireCodec, transport: HttpTransport, cache: ExplanationCache? = null,
        session: SessionRuntime = runtime(), loader: suspend () -> LockedExplanationContext? = { context }) =
        HttpExplanationReadingProvider(transport, session, chapter, "s1", "entry", codec, cache, lockedContext = loader)
}

private class LockedProviderCodec(private val value: ExpressionExplanation) : ExplanationReadingWireCodec {
    var command: String? = null
    var locked: List<LockedExplanationTarget>? = null
    override fun request(chapter: FixedLearningChapter, focusSentenceId: String, round: Int, existingBaseExplanationZh: String?,
        expression: SelectableExpression?, commandId: String): String { command = commandId; return "request" }
    override fun requestWithLockedTargets(chapter: FixedLearningChapter, focusSentenceId: String, round: Int, existingBaseExplanationZh: String?,
        expression: SelectableExpression?, commandId: String, lockedTargets: List<LockedExplanationTarget>?): String {
        locked = lockedTargets; return request(chapter, focusSentenceId, round, existingBaseExplanationZh, expression, commandId)
    }
    override fun sentence(body: String) = "整句讲解"
    override fun expression(body: String) = value
}
private class LockedProviderTransport : HttpTransport {
    var calls = 0
    var status = 200
    override suspend fun execute(request: HttpRequest): HttpResponse { calls++; return HttpResponse(status, "body") }
}
private class LockedProviderMemoryCache : ExplanationCache {
    val values = mutableMapOf<String, String>()
    val readKeys = mutableListOf<String>()
    var writes = 0
    override fun read(accountId: String, chapterId: String, sentenceId: String, key: String): String? { readKeys += key; return values[key] }
    override fun write(accountId: String, chapterId: String, sentenceId: String, key: String, body: String) { writes++; values[key] = body }
}
