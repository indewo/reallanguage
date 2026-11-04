package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.SourceEvidence
import com.reallanguage.app.feature.learning.domain.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Isolated transport/cache contract tests: no live account, SQLite, or AI. */
class ExistingExplanationDeliveryRecoveryTest {
    private val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm today.", "我今天很平静。", true)))
    private val target = LockedExplanationTarget("t1", LearningTargetType.WORD, "calm", " 平静的 ", emptyList(),
        SourceEvidence(1, 7, 11, "calm", "calm", listOf("calm")), emptyList())
    private val context = LockedExplanationContext("c1", "s1", chapter.sentences.single().textEn, listOf(target))
    private val expression = ExpressionExplanation("word-2", "完整解释", contextualMeaningZh = " 平静的 ", usageZh = "用于描述感受。")
    private fun runtime() = SessionRuntime().apply { update("isolated-token", "owner") }

    @Test fun aConflictReadsTheOriginalPaidExpressionOnceAndRebuiltProviderUsesItsVerifiedCache() = runTest {
        val cache = DeliveryCache()
        val requests = mutableListOf<HttpRequest>()
        val provider = provider(cache, requests)
        assertEquals(ExplanationContentResult.ExpressionReady(expression), provider.explainExpression("s1", "word-2"))
        assertEquals(listOf("POST", "GET"), requests.map { it.method })
        assertEquals("/api/v1/learning/explanations/deliveries/${command()}?kind=EXPRESSION", requests.last().path)
        assertEquals("", requests.last().body)
        assertEquals("Bearer isolated-token", requests.last().headers["Authorization"])
        assertEquals(command(), requests.first().body)
        assertEquals(1, cache.writes)
        assertEquals(2, cache.reads)
        assertEquals("expression", cache.body)
        assertEquals(ExplanationContentResult.ExpressionReady(expression), provider(cache, requests).explainExpression("s1", "word-2"))
        assertEquals(2, requests.size)
    }

    @Test fun serverFailureOrLostPostResponseRecoversTheSameSentenceAndRoundCommandWithoutReposting() = runTest {
        for (throwPost in listOf(false, true)) {
            val cache = DeliveryCache()
            val requests = mutableListOf<HttpRequest>()
            val provider = provider(cache, requests, transport = { request ->
                if (request.method == "POST") {
                    if (throwPost) throw IOException("isolated lost response") else HttpResponse(503, "unavailable")
                } else HttpResponse(200, envelope(command(round = 2, existing = "已有讲解", kind = "SENTENCE"), "SENTENCE", "sentence"))
            })
            assertEquals(ExplanationContentResult.Ready("已保存句子讲解"), provider.incrementalHint("s1", 2, "已有讲解"))
            assertEquals(listOf("POST", "GET"), requests.map { it.method })
            assertEquals("/api/v1/learning/explanations/deliveries/${command(2, "已有讲解", "SENTENCE")}?kind=SENTENCE", requests.last().path)
            assertEquals(1, cache.writes); assertEquals(2, cache.reads)
        }
    }

    @Test fun allNonDeliveryStatusesStopAfterOneReadAndNeverInferThatAnotherPaidPostIsSafe() = runTest {
        listOf("NO_DELIVERY", "NO_DELIVERY_WITH_ATTEMPT", "LEGACY_UNAVAILABLE", "PROCESSING", "FAILED", "EXPIRED",
            "INVALID_DELIVERY", "BILLING_UNVERIFIED", "PENDING_SETTLEMENT", "RELEASED", "RECONCILIATION_REQUIRED", "INVALID_RESULT").forEach { status ->
            val cache = DeliveryCache(); val requests = mutableListOf<HttpRequest>()
            assertEquals(ExplanationContentResult.Failed, provider(cache, requests, transport = {
                if (it.method == "POST") HttpResponse(409, "conflict") else HttpResponse(200, status)
            }).explainExpression("s1", "word-2"))
            assertEquals(listOf("POST", "GET"), requests.map { it.method }); assertEquals(0, cache.writes)
        }
    }

    @Test fun authenticationClientErrorsAndFreshInvalidContentNeverUseDeliveryRecovery() = runTest {
        for (status in listOf(400, 401, 403, 408, 429, 200)) {
            val cache = DeliveryCache(); val requests = mutableListOf<HttpRequest>()
            assertEquals(ExplanationContentResult.Failed, provider(cache, requests, transport = { HttpResponse(status, "wrong") })
                .explainExpression("s1", "word-2"))
            assertEquals(listOf("POST"), requests.map { it.method }); assertEquals(0, cache.writes)
        }
    }

    @Test fun cancellationDuringPostPropagatesAndCannotStartARecoveryRead() = runTest {
        val requests = mutableListOf<HttpRequest>(); val cache = DeliveryCache()
        var cancelled = false
        try { provider(cache, requests, transport = { throw CancellationException("left") }).explainExpression("s1", "word-2") }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled); assertEquals(listOf("POST"), requests.map { it.method }); assertEquals(0, cache.writes)
    }

    @Test fun validOrDamagedExistingCacheRemainsReadOnlyAndNeverStartsPostOrGet() = runTest {
        for (body in listOf("expression", "wrong")) {
            val cache = DeliveryCache().apply { this.body = body }; val requests = mutableListOf<HttpRequest>()
            val result = provider(cache, requests).explainExpression("s1", "word-2")
            if (body == "expression") assertEquals(ExplanationContentResult.ExpressionReady(expression), result)
            else assertEquals(ExplanationContentResult.Failed, result)
            assertTrue(requests.isEmpty()); assertEquals(0, cache.writes); assertEquals(body, cache.body)
        }
    }

    @Test fun recoveredWrongMeaningOrExpressionIdentityIsNotWritten() = runTest {
        for (body in listOf("wrong", "wrong-id")) {
            val cache = DeliveryCache(); val requests = mutableListOf<HttpRequest>()
            assertEquals(ExplanationContentResult.Failed, provider(cache, requests, transport = {
                if (it.method == "POST") HttpResponse(409, "conflict") else HttpResponse(200, envelope(command(), "EXPRESSION", body))
            }).explainExpression("s1", "word-2"))
            assertEquals(listOf("POST", "GET"), requests.map { it.method }); assertEquals(0, cache.writes)
        }
    }

    @Test fun anExistingLegacyExplanationWithoutTheNewMeaningGroupCanBeRecoveredButIsNotNewOutput() = runTest {
        val cache = DeliveryCache(); val requests = mutableListOf<HttpRequest>()
        assertEquals(ExplanationContentResult.ExpressionReady(ExpressionExplanation("word-2", "旧完整解释")),
            provider(cache, requests, transport = {
                if (it.method == "POST") HttpResponse(409, "conflict") else HttpResponse(200, envelope(command(), "EXPRESSION", "legacy"))
            }).explainExpression("s1", "word-2"))
        assertEquals(1, cache.writes); assertEquals(2, cache.reads)
    }

    @Test fun missingOrChangedCacheReadbackCannotTurnRecoveryIntoSuccess() = runTest {
        for (readback in listOf(null, "wrong", "legacy")) {
            val cache = DeliveryCache().apply { afterWrite = { readback } }; val requests = mutableListOf<HttpRequest>()
            assertEquals(ExplanationContentResult.Failed, provider(cache, requests).explainExpression("s1", "word-2"))
            assertEquals(1, cache.writes); assertEquals(2, cache.reads)
            assertEquals(listOf("POST", "GET"), requests.map { it.method })
        }
    }

    @Test fun oldOwnerCannotReadAfterPostOrAcceptALateGetOrCacheWrite() = runTest {
        for (phase in listOf("POST", "GET", "WRITE")) {
            val session = runtime(); val cache = DeliveryCache(); val requests = mutableListOf<HttpRequest>()
            fun replaceOwner() { session.clear(); session.update("new-session-token", "owner") }
            if (phase == "WRITE") cache.onWrite = { replaceOwner() }
            val result = provider(cache, requests, session, transport = {
                if (it.method == phase) replaceOwner()
                if (it.method == "POST") HttpResponse(409, "conflict") else HttpResponse(200, envelope(command(), "EXPRESSION", "expression"))
            }).explainExpression("s1", "word-2")
            assertEquals(ExplanationContentResult.Failed, result)
            assertEquals(if (phase == "POST") listOf("POST") else listOf("POST", "GET"), requests.map { it.method })
            assertEquals(if (phase == "WRITE") 1 else 0, cache.writes)
        }
    }

    @Test fun recoveryReadCancellationOrFailureDoesNotRetryPostOrClaimSuccess() = runTest {
        for (cancel in listOf(false, true)) {
            val cache = DeliveryCache(); val requests = mutableListOf<HttpRequest>()
            var cancelled = false
            var result: ExplanationContentResult? = null
            try { result = provider(cache, requests, transport = {
                if (it.method == "POST") HttpResponse(503, "unavailable")
                else if (cancel) throw CancellationException("left") else throw IOException("read failed")
            }).explainExpression("s1", "word-2") }
            catch (_: CancellationException) { cancelled = true }
            assertEquals(cancel, cancelled)
            if (!cancel) assertEquals(ExplanationContentResult.Failed, result)
            assertEquals(listOf("POST", "GET"), requests.map { it.method }); assertEquals(0, cache.writes)
        }
    }

    @Test fun unsupportedCodecAndAbsentDurableCacheDoNotPretendToRecoverAResult() = runTest {
        val unsupported = object : ExplanationReadingWireCodec by DeliveryCodec() {
            override fun existingDeliveryResult(body: String, originalCommandId: String, expectedKind: String): String? = null
        }
        val requests = mutableListOf<HttpRequest>(); val cache = DeliveryCache()
        assertEquals(ExplanationContentResult.Failed, provider(cache, requests, codec = unsupported).explainExpression("s1", "word-2"))
        assertEquals(0, cache.writes)
        val noCacheRequests = mutableListOf<HttpRequest>()
        assertEquals(ExplanationContentResult.Failed, provider(null, noCacheRequests).explainExpression("s1", "word-2"))
        assertEquals(listOf("POST"), noCacheRequests.map { it.method })
    }

    private fun key(round: Int = 1, existing: String = "", kind: String = "EXPRESSION") =
        UUID.nameUUIDFromBytes("v1:$round:${if (kind == "EXPRESSION") "word-2" else "sentence"}:$existing".toByteArray()).toString()
    private fun command(round: Int = 1, existing: String = "", kind: String = "EXPRESSION") =
        UUID.nameUUIDFromBytes("owner:c1:s1:${key(round, existing, kind)}".toByteArray()).toString()
    private fun envelope(command: String, kind: String, result: String) = "COMPLETED|$command|EXISTING_DELIVERY|$kind|$result"
    private fun provider(cache: ExplanationCache?, requests: MutableList<HttpRequest>, session: SessionRuntime = runtime(),
        codec: ExplanationReadingWireCodec = DeliveryCodec(), transport: suspend (HttpRequest) -> HttpResponse = {
            if (it.method == "POST") HttpResponse(409, "conflict") else HttpResponse(200, envelope(command(), "EXPRESSION", "expression"))
        }) = HttpExplanationReadingProvider(HttpTransport { request -> requests += request; transport(request) }, session,
            chapter, "s1", "entry", codec, cache, lockedContext = { context })

    private inner class DeliveryCodec : ExplanationReadingWireCodec {
        override fun request(chapter: FixedLearningChapter, focusSentenceId: String, round: Int, existingBaseExplanationZh: String?,
            expression: SelectableExpression?, commandId: String) = commandId
        override fun requestWithLockedTargets(chapter: FixedLearningChapter, focusSentenceId: String, round: Int, existingBaseExplanationZh: String?,
            expression: SelectableExpression?, commandId: String, lockedTargets: List<LockedExplanationTarget>?) = commandId
        override fun sentence(body: String) = if (body == "sentence") "已保存句子讲解" else null
        override fun expression(body: String): ExpressionExplanation? = when (body) {
            "expression" -> expression
            "legacy" -> ExpressionExplanation("word-2", "旧完整解释")
            "wrong" -> expression.copy(contextualMeaningZh = "另一个意思")
            "wrong-id" -> expression.copy(expressionId = "word-3")
            else -> null
        }
        override fun existingDeliveryResult(body: String, originalCommandId: String, expectedKind: String): String? =
            body.split('|').takeIf { it.size == 5 && it[0] == "COMPLETED" && it[1] == originalCommandId &&
                it[2] == "EXISTING_DELIVERY" && it[3] == expectedKind }?.last()
    }
}

private class DeliveryCache : ExplanationCache {
    var body: String? = null
    var reads = 0
    var writes = 0
    var afterWrite: (() -> String?)? = null
    var onWrite: (() -> Unit)? = null
    override fun read(accountId: String, chapterId: String, sentenceId: String, key: String): String? {
        reads++
        return if (writes > 0 && afterWrite != null) afterWrite!!.invoke() else body
    }
    override fun write(accountId: String, chapterId: String, sentenceId: String, key: String, body: String) {
        writes++; this.body = body; onWrite?.invoke()
    }
}
