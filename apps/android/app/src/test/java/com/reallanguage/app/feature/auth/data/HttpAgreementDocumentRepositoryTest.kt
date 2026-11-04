package com.reallanguage.app.feature.auth.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.feature.auth.domain.AgreementDocument
import com.reallanguage.app.feature.auth.domain.AgreementDocumentResult
import com.reallanguage.app.feature.auth.domain.AgreementKind
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpAgreementDocumentRepositoryTest {
    @Test fun loadsEachAgreementFromItsVersionedAuthoritativeEndpoint() = runTest {
        val transport = RecordingAgreementTransport(HttpResponse(200, "privacy"))
        val repository = HttpAgreementDocumentRepository(transport, FakeAgreementCodec())

        assertEquals(
            AgreementDocumentResult.Ready(document),
            repository.load(AgreementKind.PRIVACY_POLICY),
        )
        assertEquals("GET", transport.request?.method)
        assertEquals("/api/v1/legal/documents/privacy-policy", transport.request?.path)
    }

    @Test fun neverInventsFallbackLegalTextOnTransportOrContractFailure() = runTest {
        val offline = HttpAgreementDocumentRepository(
            HttpTransport { throw IOException("offline") },
            FakeAgreementCodec(),
        )
        assertEquals(AgreementDocumentResult.Failed, offline.load(AgreementKind.USER_AGREEMENT))
        val malformed = HttpAgreementDocumentRepository(
            RecordingAgreementTransport(HttpResponse(200, "malformed")),
            FakeAgreementCodec(),
        )
        assertEquals(AgreementDocumentResult.Failed, malformed.load(AgreementKind.PRIVACY_POLICY))
    }

    private val document = AgreementDocument(
        kind = AgreementKind.PRIVACY_POLICY,
        titleZh = "隐私政策",
        version = "2026-09-01",
        sections = listOf("第一节"),
    )
}

private class RecordingAgreementTransport(private val response: HttpResponse) : HttpTransport {
    var request: HttpRequest? = null
    override suspend fun execute(request: HttpRequest): HttpResponse {
        this.request = request
        return response
    }
}

private class FakeAgreementCodec : AgreementDocumentWireCodec {
    override fun document(body: String): AgreementDocument {
        if (body != "privacy") error("malformed")
        return AgreementDocument(
            kind = AgreementKind.PRIVACY_POLICY,
            titleZh = "隐私政策",
            version = "2026-09-01",
            sections = listOf("第一节"),
        )
    }
}
