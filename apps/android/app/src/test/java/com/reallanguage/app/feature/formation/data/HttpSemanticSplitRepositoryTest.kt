package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.SemanticSplitResult
import com.reallanguage.app.feature.formation.domain.SemanticUnit
import com.reallanguage.app.feature.formation.domain.hasMinimumSemanticGranularity
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpSemanticSplitRepositoryTest {
    @Test fun authenticatesAndKeepsStableSplitIdentity() = runTest {
        val transport = RecordingSplitTransport()
        val runtime = SessionRuntime().apply { update("token") }
        val result = HttpSemanticSplitRepository(transport, FakeSplitCodec, runtime, "split-command").split(source)
        assertEquals(SemanticSplitResult.Ready(listOf(SemanticUnit("u1", 0, "今天很忙。"))), result)
        assertEquals("/api/v1/content-formation/semantic-units/split", transport.request?.path)
        assertEquals("Bearer token", transport.request?.headers?.get("Authorization"))
        assertEquals("今天很忙。|ARTICLE|split-command", transport.request?.body)
    }

    @Test fun neverSendsSourceWithoutSession() = runTest {
        val transport = RecordingSplitTransport()
        val result = HttpSemanticSplitRepository(transport, FakeSplitCodec, SessionRuntime(), "cmd").split(source)
        assertEquals(SemanticSplitResult.Ready(listOf(SemanticUnit("deterministic-1", 0, "今天很忙。"))), result)
        assertEquals(null, transport.request)
    }

    @Test fun invalidServerSplitFallsBackToExactMinimumUnits() = runTest {
        val text = "今天我不开心，因为手机没电了。但是我已经充上电。"
        val runtime = SessionRuntime().apply { update("token") }
        val repository = HttpSemanticSplitRepository(
            transport = RecordingSplitTransport(),
            codec = object : SemanticSplitWireCodec {
                override fun request(source: ConfirmedSource, commandId: String) = "request"
                override fun result(body: String) = error("stale coarse response")
            },
            sessionRuntime = runtime,
            commandId = "cmd",
        )
        val result = repository.split(source.copy(text = text)) as SemanticSplitResult.Ready
        assertEquals(text, result.units.joinToString("") { it.textZh })
        assertEquals(listOf("今天我不开心，", "因为手机没电了。", "但是我已经充上电。"), result.units.map { it.textZh })
        assertTrue(result.units.all { it.textZh.hasMinimumSemanticGranularity() })
    }

    @Test fun clientRejectsStaleParagraphReturnedAsOneSelectableSentence() {
        assertFalse(
            "今天我很不开心，因为我发现外耗很有用，但是我容易内耗，所以我要改变。"
                .hasMinimumSemanticGranularity(),
        )
    }

    @Test fun clientAcceptsMinimumSemanticUnitsAndAnIndivisibleCondition() {
        val parts = listOf(
            "今天我很不开心，",
            "因为我发现外耗很有用，",
            "但是我容易内耗，",
            "所以我要改变。",
            "如果明天下雨，我就带伞，也带雨衣。",
        )
        assertTrue(parts.all { it.hasMinimumSemanticGranularity() })
    }

    private val source = ConfirmedSource("今天很忙。", SourceType.DIARY, SourceContentShape.ARTICLE)

}

private class RecordingSplitTransport : HttpTransport {
    var request: HttpRequest? = null
    override suspend fun execute(request: HttpRequest): HttpResponse { this.request = request; return HttpResponse(200, "ok") }
}
private object FakeSplitCodec : SemanticSplitWireCodec {
    override fun request(source: ConfirmedSource, commandId: String) = "${source.text}|${source.contentShape}|$commandId"
    override fun result(body: String) = listOf(SemanticUnit("u1", 0, "今天很忙。"))
}
