package com.reallanguage.app.feature.auth.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.feature.auth.domain.AgreementDocument
import com.reallanguage.app.feature.auth.domain.AgreementDocumentRepository
import com.reallanguage.app.feature.auth.domain.AgreementDocumentResult
import com.reallanguage.app.feature.auth.domain.AgreementKind
import org.json.JSONObject

interface AgreementDocumentWireCodec {
    fun document(body: String): AgreementDocument
}

class HttpAgreementDocumentRepository(
    private val transport: HttpTransport,
    private val codec: AgreementDocumentWireCodec,
) : AgreementDocumentRepository {
    override suspend fun load(kind: AgreementKind): AgreementDocumentResult = try {
        val response = transport.execute(
            HttpRequest(
                method = "GET",
                path = "/api/v1/legal/documents/${kind.routeValue()}",
                body = "",
            ),
        )
        if (response.statusCode == HTTP_OK) {
            AgreementDocumentResult.Ready(codec.document(response.body))
        } else {
            AgreementDocumentResult.Failed
        }
    } catch (_: Exception) {
        AgreementDocumentResult.Failed
    }

    private companion object { const val HTTP_OK = 200 }
}

class JsonAgreementDocumentWireCodec : AgreementDocumentWireCodec {
    override fun document(body: String): AgreementDocument {
        val json = JSONObject(body)
        val kind = AgreementKind.valueOf(json.getString("kind"))
        val sectionsJson = json.getJSONArray("sections")
        val sections = buildList {
            for (index in 0 until sectionsJson.length()) add(sectionsJson.getString(index))
        }
        require(sections.isNotEmpty() && sections.none { it.isBlank() })
        return AgreementDocument(
            kind = kind,
            titleZh = json.getString("titleZh").also { require(it.isNotBlank()) },
            version = json.getString("version").also { require(it.isNotBlank()) },
            sections = sections,
        )
    }
}

private fun AgreementKind.routeValue(): String = when (this) {
    AgreementKind.USER_AGREEMENT -> "user-agreement"
    AgreementKind.PRIVACY_POLICY -> "privacy-policy"
}
