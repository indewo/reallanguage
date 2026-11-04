package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.SemanticSplitRepository
import com.reallanguage.app.feature.formation.domain.SemanticSplitResult
import com.reallanguage.app.feature.formation.domain.SemanticUnit
import com.reallanguage.app.feature.formation.domain.hasMinimumSemanticGranularity
import com.reallanguage.app.feature.formation.domain.isExactMinimumSplitOf
import com.reallanguage.app.feature.formation.domain.toDeterministicSemanticUnits
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import java.io.IOException
import org.json.JSONObject

interface SemanticSplitWireCodec {
    fun request(source: ConfirmedSource, commandId: String): String
    fun result(body: String): List<SemanticUnit>
}

class HttpSemanticSplitRepository(
    private val transport: HttpTransport,
    private val codec: SemanticSplitWireCodec,
    private val sessionRuntime: SessionRuntime,
    private val commandId: String,
) : SemanticSplitRepository {
    override suspend fun split(source: ConfirmedSource): SemanticSplitResult {
        val dialogue = source.contentShape == SourceContentShape.DIALOGUE
        val localUnits = source.text.toDeterministicSemanticUnits(dialogue)
        if (localUnits.isEmpty()) return SemanticSplitResult.Failed
        val token = sessionRuntime.accessToken ?: return SemanticSplitResult.Ready(localUnits)
        return try {
            val response = transport.execute(HttpRequest(
                method = "POST",
                path = "/api/v1/content-formation/semantic-units/split",
                body = codec.request(source, commandId),
                headers = mapOf("Authorization" to "Bearer $token"),
            ))
            if (response.statusCode == 200) {
                val remoteUnits = codec.result(response.body)
                SemanticSplitResult.Ready(remoteUnits.takeIf { it.isExactMinimumSplitOf(source.text, dialogue) } ?: localUnits)
            } else SemanticSplitResult.Ready(localUnits)
        } catch (_: IOException) {
            SemanticSplitResult.Ready(localUnits)
        } catch (_: Exception) {
            SemanticSplitResult.Ready(localUnits)
        }
    }
}

class JsonSemanticSplitWireCodec : SemanticSplitWireCodec {
    override fun request(source: ConfirmedSource, commandId: String): String = JSONObject()
        .put("text", source.text)
        .put("contentShape", source.contentShape.name)
        .put("commandId", commandId)
        .toString()

    override fun result(body: String): List<SemanticUnit> {
        val json = JSONObject(body)
        val coverage = json.getJSONObject("coverage")
        check(coverage.getBoolean("complete"))
        check(coverage.getJSONArray("missingSpans").length() == 0)
        check(coverage.getJSONArray("duplicatedSpans").length() == 0)
        val units = json.getJSONArray("units")
        return List(units.length()) { index ->
            val unit = units.getJSONObject(index)
            SemanticUnit(
                unitId = unit.getString("unitId"),
                order = unit.getInt("order"),
                textZh = unit.getString("textZh"),
                recommendationReasonZh = if (unit.isNull("recommendationReasonZh")) null else unit.getString("recommendationReasonZh"),
            )
        }.also { parsed ->
            check(parsed.isNotEmpty())
            check(parsed.sortedBy { it.order }.map { it.order } == parsed.indices.toList())
            check(parsed.all { it.textZh.hasMinimumSemanticGranularity() })
        }
    }
}
