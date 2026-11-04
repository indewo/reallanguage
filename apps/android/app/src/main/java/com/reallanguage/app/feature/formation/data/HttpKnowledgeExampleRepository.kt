package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.KnowledgeExampleDraft
import com.reallanguage.app.feature.formation.domain.KnowledgeExampleGenerationRepository
import com.reallanguage.app.feature.formation.domain.KnowledgeExampleGenerationRequest
import com.reallanguage.app.feature.formation.domain.KnowledgeExampleGenerationResult
import com.reallanguage.app.feature.formation.domain.KnowledgeTargetDetails
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

class HttpKnowledgeExampleRepository(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
) : KnowledgeExampleGenerationRepository {
    private val ownerAccountId = runtime.accountId
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId && runtime.accessToken != null
    override suspend fun generate(request: KnowledgeExampleGenerationRequest): KnowledgeExampleGenerationResult {
        if (!ownerIsActive()) return KnowledgeExampleGenerationResult.Failed
        val token = runtime.accessToken ?: return KnowledgeExampleGenerationResult.Failed
        return try {
            val body = JSONObject()
                .put("itemId", request.targetId)
                .put("targetType", request.targetType.name)
                .put("display", request.display)
                .put("meaningZh", request.meaningZh)
                .put("commandId", request.commandId)
                .put("sourceScene", JSONArray(request.sourceScene.map {
                    JSONObject().put("textEn", it.textEn).put("textZh", it.textZh)
                }))
                .toString()
            val response = transport.execute(
                HttpRequest("POST", "/api/v1/knowledge/examples", body, mapOf("Authorization" to "Bearer $token")),
            )
            if (!ownerIsActive() || response.statusCode != 200) return KnowledgeExampleGenerationResult.Failed
            val json = JSONObject(response.body)
            if (json.getString("itemId") != request.targetId) return KnowledgeExampleGenerationResult.Failed
            val values = json.getJSONArray("examples")
            if (values.length() != 3) return KnowledgeExampleGenerationResult.Failed
            KnowledgeExampleGenerationResult.Generated(
                KnowledgeTargetDetails(
                    targetId = request.targetId,
                    phonetic = if (json.isNull("phonetic")) null else json.getString("phonetic")
                        .trim()
                        .takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) },
                    pronunciationText = json.nonBlankText("pronunciationText"),
                    representativeScene = json.nonBlankText("representativeScene"),
                    examples = List(3) { index -> values.getJSONObject(index).let {
                        KnowledgeExampleDraft(it.nonBlankText("textEn"), it.nonBlankText("textZh"), it.nonBlankText("pronunciationText"))
                    } },
                    naturalRevalidation = if (json.isNull("naturalRevalidation")) null else json.getBoolean("naturalRevalidation"),
                ),
            )
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { KnowledgeExampleGenerationResult.Failed }
    }

    private fun JSONObject.nonBlankText(key: String): String = get(key).let {
        require(it is String && it.isNotBlank())
        it
    }
}
