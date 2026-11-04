package com.reallanguage.app.feature.safety.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.safety.domain.ManualReviewOutcome
import com.reallanguage.app.feature.safety.domain.ManualReviewResult
import com.reallanguage.app.feature.safety.domain.ManualReviewResultRepository
import com.reallanguage.app.feature.safety.domain.ReviewedSafetySource
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HttpManualReviewResultRepository(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val codec: ManualReviewResultWireCodec = JsonManualReviewResultWireCodec(),
) : ManualReviewResultRepository {
    override suspend fun load(reviewRequestId: String): ManualReviewResult = withContext(Dispatchers.IO) {
        val revision = runtime.identityRevision.value
        val owner = runtime.accountId ?: return@withContext ManualReviewResult.Failed
        val token = runtime.accessToken ?: return@withContext ManualReviewResult.Failed
        try {
            val response = transport.execute(HttpRequest("GET", "/api/v1/safety/cases/manual-reviews/$reviewRequestId", "", mapOf("Authorization" to "Bearer $token")))
            if (runtime.identityRevision.value != revision || runtime.accountId != owner) return@withContext ManualReviewResult.Failed
            when (response.statusCode) {
                200 -> codec.result(response.body)
                401 -> ManualReviewResult.RequiresLogin
                404 -> ManualReviewResult.Unavailable
                else -> ManualReviewResult.Failed
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ManualReviewResult.Failed
        }
    }
}
fun interface ManualReviewResultWireCodec { fun result(body: String): ManualReviewResult }
class JsonManualReviewResultWireCodec : ManualReviewResultWireCodec {
    override fun result(body: String): ManualReviewResult {
        val value = JSONObject(body)
        val source = value.getJSONObject("source")
        return ManualReviewResult.Ready(
            ManualReviewOutcome.valueOf(value.getString("outcome")),
            ReviewedSafetySource(
                source.getString("safetyCaseId"), source.getString("text"),
                SourceType.valueOf(source.getString("sourceType")),
                SourceContentShape.valueOf(source.getString("contentShape")),
                source.getString("userReasonZh"),
            ),
        )
    }
}
