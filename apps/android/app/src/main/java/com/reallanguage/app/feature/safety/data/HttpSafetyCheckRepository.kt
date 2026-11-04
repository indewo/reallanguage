package com.reallanguage.app.feature.safety.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SafetyCheckRepository
import com.reallanguage.app.feature.safety.domain.SafetyCheckResult
import java.io.IOException
import org.json.JSONObject

interface SafetyCheckWireCodec {
    fun request(source: ConfirmedSource, commandId: String): String
    fun result(body: String): SafetyCheckResult
}

class HttpSafetyCheckRepository(
    private val transport: HttpTransport,
    private val codec: SafetyCheckWireCodec,
    private val sessionRuntime: SessionRuntime,
    private val commandId: String,
) : SafetyCheckRepository {
    override suspend fun check(source: ConfirmedSource): SafetyCheckResult {
        val accessToken = sessionRuntime.accessToken ?: return SafetyCheckResult.Failed
        return try {
            val response = transport.execute(
                HttpRequest(
                    method = "POST",
                    path = "/api/v1/safety/checks",
                    body = codec.request(source, commandId),
                    headers = mapOf("Authorization" to "Bearer $accessToken"),
                ),
            )
            if (response.statusCode == HTTP_OK) {
                codec.result(response.body)
            } else {
                SafetyCheckResult.Failed
            }
        } catch (_: IOException) {
            SafetyCheckResult.Failed
        } catch (_: Exception) {
            SafetyCheckResult.Failed
        }
    }

    private companion object { const val HTTP_OK = 200 }
}

class JsonSafetyCheckWireCodec : SafetyCheckWireCodec {
    override fun request(source: ConfirmedSource, commandId: String): String = JSONObject()
        .put("text", source.text)
        .put("sourceType", source.sourceType.name)
        .put("contentShape", source.contentShape.name)
        .put("commandId", commandId)
        .toString()

    override fun result(body: String): SafetyCheckResult {
        val json = JSONObject(body)
        return when (json.getString("status")) {
            "PASSED" -> SafetyCheckResult.Passed
            "BLOCKED" -> SafetyCheckResult.Blocked(json.getString("safetyCaseId"))
            else -> error("Unsupported safety check response")
        }
    }
}
