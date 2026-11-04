package com.reallanguage.app.feature.safety.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.ManualReviewSubmissionResult
import com.reallanguage.app.feature.safety.domain.ManualReviewSubmissionStatus
import com.reallanguage.app.feature.safety.domain.SafetyCaseDetails
import com.reallanguage.app.feature.safety.domain.SafetyEdit
import com.reallanguage.app.feature.safety.domain.SafetyHandlingCommandResult
import com.reallanguage.app.feature.safety.domain.SafetyHandlingDetailsRepository
import com.reallanguage.app.feature.safety.domain.SafetyHandlingDetailsResult
import com.reallanguage.app.feature.safety.domain.SafetyHandlingRepository
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

class HttpSafetyHandlingRepository(
    private val transport: HttpTransport,
    private val sessionRuntime: SessionRuntime,
    private val commandIds: SafetyHandlingCommandIds,
    private val codec: SafetyHandlingWireCodec = JsonSafetyHandlingWireCodec(),
) : SafetyHandlingRepository, SafetyHandlingDetailsRepository {
    override suspend fun load(safetyCaseId: String): SafetyHandlingDetailsResult {
        val response = execute("/api/v1/safety/cases/$safetyCaseId/handling", "{}") ?: return SafetyHandlingDetailsResult.Failed
        return if (response.statusCode == 200) runCatching { SafetyHandlingDetailsResult.Ready(codec.details(response.body)) }.getOrDefault(SafetyHandlingDetailsResult.Failed) else SafetyHandlingDetailsResult.Failed
    }
    override suspend fun acceptDeidentified(safetyCaseId: String) = command(safetyCaseId, "accept-deidentified", commandIds.accept)
    override suspend fun returnToEdit(safetyCaseId: String) = command(safetyCaseId, "return-to-edit", commandIds.returnToEdit)
    override suspend fun submitManualReview(safetyCaseId: String): ManualReviewSubmissionResult {
        val response = execute("/api/v1/safety/cases/$safetyCaseId/manual-reviews", codec.command(commandIds.manualReview)) ?: return ManualReviewSubmissionResult.Failed
        return if (response.statusCode == 200) runCatching {
            val status = codec.reviewStatus(response.body) ?: return ManualReviewSubmissionResult.Failed
            ManualReviewSubmissionResult.Submitted(codec.reviewRequestId(response.body), status)
        }.getOrDefault(ManualReviewSubmissionResult.Failed) else ManualReviewSubmissionResult.Failed
    }
    private suspend fun command(caseId: String, action: String, commandId: String): SafetyHandlingCommandResult {
        val response = execute("/api/v1/safety/cases/$caseId/$action", codec.command(commandId)) ?: return SafetyHandlingCommandResult.Failed
        return if (response.statusCode == 200 && runCatching { codec.completed(response.body) }.getOrDefault(false)) SafetyHandlingCommandResult.Completed else SafetyHandlingCommandResult.Failed
    }
    private suspend fun execute(path: String, body: String): HttpResponse? {
        val owner = sessionRuntime.accountId ?: return null
        val revision = sessionRuntime.identityRevision.value
        val token = sessionRuntime.accessToken ?: return null
        return try {
            val response = transport.execute(HttpRequest("POST", path, body, mapOf("Authorization" to "Bearer $token")))
            response.takeIf { sessionRuntime.accountId == owner && sessionRuntime.identityRevision.value == revision }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}

interface SafetyHandlingWireCodec {
    fun reviewStatus(body: String): ManualReviewSubmissionStatus? = null
    fun command(commandId: String): String
    fun details(body: String): SafetyCaseDetails
    fun completed(body: String): Boolean
    fun reviewRequestId(body: String): String
}

class JsonSafetyHandlingWireCodec : SafetyHandlingWireCodec {
    override fun command(commandId: String) = JSONObject().put("commandId", commandId).toString()
    override fun details(body: String): SafetyCaseDetails {
        val json = JSONObject(body)
        fun source(name: String): ConfirmedSource = json.getJSONObject(name).let { ConfirmedSource(it.getString("text"), SourceType.valueOf(it.getString("sourceType")), SourceContentShape.valueOf(it.getString("contentShape"))) }
        val edits = json.getJSONArray("edits")
        return SafetyCaseDetails(json.getString("safetyCaseId"), source("originalSource"), json.getString("userReasonZh"), source("deidentifiedSource"), List(edits.length()) { index -> edits.getJSONObject(index).let { SafetyEdit(it.getString("originalText"), it.getString("replacementText"), it.getString("explanationZh")) } })
    }
    override fun completed(body: String) = JSONObject(body).getString("status") == "COMPLETED"
    override fun reviewRequestId(body: String): String {
        val json = JSONObject(body)
        require(json.opt("status") == "SUBMITTED")
        return (json.opt("reviewRequestId") as? String)?.takeIf {
            it.isNotBlank() && it == it.trim() && it.length <= 128
        } ?: error("Invalid review receipt identity")
    }
    override fun reviewStatus(body: String): ManualReviewSubmissionStatus? {
        val json = JSONObject(body)
        if (json.opt("status") != "SUBMITTED") return null
        val status = json.opt("reviewStatus") as? String ?: return null
        return ManualReviewSubmissionStatus.entries.firstOrNull { it.name == status }
    }
}

data class SafetyHandlingCommandIds(val accept: String, val returnToEdit: String, val manualReview: String)
