package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.ExplanationContentResult
import com.reallanguage.app.feature.learning.domain.ExplanationPreparationResult
import com.reallanguage.app.feature.learning.domain.ExpressionExplanation
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.SelectableExpression
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpExplanationReadingProviderTest {
    private val chapter = FixedLearningChapter(
        "c1",
        1,
        listOf(FixedLearningSentence("s1", "I felt calm today.", "我今天很平静。", true)),
    )

    @Test fun `provider created by another account cannot send old chapter with new credentials`() = runTest {
        val runtime = SessionRuntime().apply { update("first-token", "first") }
        val transport = RecordingExplanationTransport(HttpResponse(200, "response"))
        val provider = HttpExplanationReadingProvider(transport, runtime, chapter, "s1", "cmd",
            RecordingExplanationCodec(sentenceResult = "旧账号内容"))
        runtime.update("second-token", "second")
        assertEquals(ExplanationPreparationResult.Failed, provider.prepare())
        assertNull(transport.request)
    }

    @Test fun `invalid focus fails before network instead of throwing`() = runTest {
        val transport = RecordingExplanationTransport(HttpResponse(200, "response"))
        val provider = HttpExplanationReadingProvider(transport, SessionRuntime().apply { update("token", "first") },
            chapter, "missing", "cmd", RecordingExplanationCodec(sentenceResult = "讲解"))
        assertEquals(ExplanationPreparationResult.Failed, provider.prepare())
        assertNull(transport.request)
    }

    @Test fun `cancelled provider request propagates cancellation instead of reporting failure`() = runTest {
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse = throw CancellationException("navigation left")
        }
        var cancelled = false
        try { provider(transport).prepare() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test fun `selected fixed phrase keeps exact whole span and does not remove individual words`() = runTest {
        val transport = RecordingExplanationTransport(HttpResponse(200, "response"))
        val provider = HttpExplanationReadingProvider(transport, SessionRuntime().apply { update("token", "first") },
            chapter, "s1", "cmd", RecordingExplanationCodec(sentenceResult = "讲解"),
            selectedExpressions = { listOf("felt calm", "calm today", "find someone else", "cal") })
        val result = provider.prepare() as ExplanationPreparationResult.Ready
        val phrase = result.expressions.single { it.display == "felt calm" }
        assertEquals(2, phrase.startOffset)
        assertEquals(11, phrase.endOffset)
        assertTrue(result.expressions.any { it.display == "felt" })
        assertTrue(result.expressions.none { it.display in setOf("find someone else", "cal") })
        assertEquals(chapter.sentences.single().textEn.substring(phrase.startOffset, phrase.endOffset), phrase.display)
    }

    @Test fun `selected expression read failure prevents paid request and remains retryable`() = runTest {
        val transport = RecordingExplanationTransport(HttpResponse(200, "response"))
        var failed = true
        val provider = HttpExplanationReadingProvider(transport, SessionRuntime().apply { update("token", "first") },
            chapter, "s1", "cmd", RecordingExplanationCodec(sentenceResult = "讲解"),
            selectedExpressions = { if (failed) error("database unavailable") else listOf("felt calm") })
        assertEquals(ExplanationPreparationResult.Failed, provider.prepare())
        assertNull(transport.request)
        failed = false
        assertTrue(provider.prepare() is ExplanationPreparationResult.Ready)
    }

    @Test fun `preparation sends authenticated fixed content with stable round command`() = runTest {
        val transport = RecordingExplanationTransport(HttpResponse(200, "response"))
        val codec = RecordingExplanationCodec(sentenceResult = "基础讲解")
        val provider = provider(transport, codec)

        val result = provider.prepare()

        assertTrue(result is ExplanationPreparationResult.Ready)
        assertEquals("Bearer token", transport.request?.headers?.get("Authorization"))
        assertEquals("/api/v1/learning/explanations", transport.request?.path)
        assertEquals("cmd:1:sentence", codec.commandId)
        assertEquals("I felt calm today.", codec.chapter?.sentences?.single()?.textEn)
    }

    @Test fun `incremental hint preserves the first explanation and requested round`() = runTest {
        val transport = RecordingExplanationTransport(HttpResponse(200, "response"))
        val codec = RecordingExplanationCodec(sentenceResult = "第二轮提示")

        val result = provider(transport, codec).incrementalHint("s1", 2, "基础讲解")

        assertEquals(ExplanationContentResult.Ready("第二轮提示"), result)
        assertEquals(2, codec.round)
        assertEquals("基础讲解", codec.existingBaseExplanationZh)
        assertEquals("cmd:2:sentence", codec.commandId)
    }

    @Test fun `expression request maps the deterministic local token and nullable phonetic`() = runTest {
        val transport = RecordingExplanationTransport(HttpResponse(200, "response"))
        val codec = RecordingExplanationCodec(
            expressionResult = ExpressionExplanation("word-1", "感到", null),
        )

        val result = provider(transport, codec).explainExpression("s1", "word-1")

        assertTrue(result is ExplanationContentResult.ExpressionReady)
        result as ExplanationContentResult.ExpressionReady
        assertEquals("感到", result.explanation.explanationZh)
        assertNull(result.explanation.phonetic)
        assertEquals("felt", codec.requestedExpression?.display)
        assertEquals("cmd:1:word-1", codec.commandId)
    }

    @Test fun `missing session and invalid focus boundaries fail without network access`() = runTest {
        val transport = RecordingExplanationTransport(HttpResponse(200, "{}"))
        val noSession = HttpExplanationReadingProvider(
            transport,
            SessionRuntime(),
            chapter,
            "s1",
            "cmd",
            RecordingExplanationCodec(),
        )
        assertEquals(ExplanationPreparationResult.Failed, noSession.prepare())
        assertEquals(ExplanationContentResult.Failed, provider(transport).incrementalHint("other", 2, "base"))
        assertEquals(ExplanationContentResult.Failed, provider(transport).explainExpression("s1", "unknown"))
    }

    private fun provider(
        transport: HttpTransport,
        codec: ExplanationReadingWireCodec = RecordingExplanationCodec(),
    ) = HttpExplanationReadingProvider(
        transport,
        SessionRuntime().apply { update("token") },
        chapter,
        "s1",
        "cmd",
        codec,
    )
}

private class RecordingExplanationCodec(
    private val sentenceResult: String? = null,
    private val expressionResult: ExpressionExplanation? = null,
) : ExplanationReadingWireCodec {
    var chapter: FixedLearningChapter? = null
    var round: Int? = null
    var existingBaseExplanationZh: String? = null
    var requestedExpression: SelectableExpression? = null
    var commandId: String? = null

    override fun request(
        chapter: FixedLearningChapter,
        focusSentenceId: String,
        round: Int,
        existingBaseExplanationZh: String?,
        expression: SelectableExpression?,
        commandId: String,
    ): String {
        this.chapter = chapter
        this.round = round
        this.existingBaseExplanationZh = existingBaseExplanationZh
        this.requestedExpression = expression
        this.commandId = commandId
        return "request"
    }

    override fun sentence(body: String) = sentenceResult
    override fun expression(body: String) = expressionResult
}

private class RecordingExplanationTransport(private val response: HttpResponse) : HttpTransport {
    var request: HttpRequest? = null
    override suspend fun execute(request: HttpRequest): HttpResponse {
        this.request = request
        return response
    }
}
