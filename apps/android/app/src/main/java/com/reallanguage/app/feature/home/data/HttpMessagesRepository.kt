package com.reallanguage.app.feature.home.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.home.domain.AppMessage
import com.reallanguage.app.feature.home.domain.AppMessageTarget
import com.reallanguage.app.feature.home.domain.MessageKind
import com.reallanguage.app.feature.home.domain.MessageTargetState
import com.reallanguage.app.feature.home.domain.MessagesLoadResult
import com.reallanguage.app.feature.home.domain.MessagesRepository
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

class HttpMessagesRepository(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val codec: MessagesWireCodec = JsonMessagesWireCodec(),
    private val readAllCodec: MessagesReadAllWireCodec = JsonMessagesReadAllWireCodec(),
) : MessagesRepository {
    private val accountIdentity = synchronized(runtime) {
        runtime.accountId?.takeIf(String::isNotBlank)?.let { it to runtime.accountEpoch }
    }

    override suspend fun load(): MessagesLoadResult = withContext(Dispatchers.IO) {
        val token = currentToken() ?: return@withContext MessagesLoadResult.Failed
        try {
            currentCoroutineContext().ensureActive()
            val response = transport.execute(HttpRequest("GET", "/api/v1/messages", "", mapOf("Authorization" to "Bearer $token")))
            currentCoroutineContext().ensureActive()
            if (response.statusCode != 200 || currentToken() == null) return@withContext MessagesLoadResult.Failed
            val messages = codec.messages(response.body)
            currentCoroutineContext().ensureActive()
            synchronized(runtime) {
                if (ownsCurrentSession()) MessagesLoadResult.Ready(messages) else MessagesLoadResult.Failed
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MessagesLoadResult.Failed }
    }

    override suspend fun markAllRead(): Boolean = withContext(Dispatchers.IO) {
        val token = currentToken() ?: return@withContext false
        try {
            currentCoroutineContext().ensureActive()
            val response = transport.execute(
                HttpRequest("POST", "/api/v1/messages/read-all", "{}", mapOf("Authorization" to "Bearer $token")),
            )
            currentCoroutineContext().ensureActive()
            if (response.statusCode != 200 || currentToken() == null) return@withContext false
            val accepted = readAllCodec.accepted(response.body)
            currentCoroutineContext().ensureActive()
            synchronized(runtime) { accepted && ownsCurrentSession() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    private fun currentToken(): String? = synchronized(runtime) {
        runtime.accessToken.takeIf { ownsCurrentSession() }
    }

    // Call while holding the SessionRuntime monitor so owner, epoch and token form one snapshot.
    private fun ownsCurrentSession(): Boolean = accountIdentity?.let { (accountId, epoch) ->
        runtime.accountId == accountId && runtime.accountEpoch == epoch && !runtime.accessToken.isNullOrBlank()
    } == true
}

fun interface MessagesReadAllWireCodec { fun accepted(body: String): Boolean }
class JsonMessagesReadAllWireCodec : MessagesReadAllWireCodec {
    override fun accepted(body: String): Boolean = try {
        val parser = JSONTokener(body)
        val response = parser.nextValue() as? JSONObject
        if (response == null || parser.nextClean() != '\u0000') false
        else {
            val value = response.opt("updatedCount")
            value is Number && value.toDouble().let { count ->
                count.isFinite() && count >= 0.0 && count <= 9_007_199_254_740_991.0 && count % 1.0 == 0.0
            }
        }
    } catch (_: Exception) { false }
}

fun interface MessagesWireCodec { fun messages(body: String): List<AppMessage> }
class JsonMessagesWireCodec : MessagesWireCodec {
    override fun messages(body: String): List<AppMessage> {
        val values = JSONObject(body).getJSONArray("messages")
        return List(values.length()) { index ->
                val value = values.getJSONObject(index)
                val targetValue = value.optJSONObject("target")
                val target = if (targetValue?.optString("route") == "MANUAL_REVIEW_RESULT") {
                    targetValue.optString("resourceId").takeIf(String::isNotBlank)?.let { AppMessageTarget("manual-review", it) }
                } else null
                AppMessage(
                    messageId = value.getString("messageId"),
                    kind = MessageKind.valueOf(value.getString("kind")),
                    summaryZh = value.getString("summaryZh"),
                    relatedContentLabelZh = if (value.isNull("relatedContentLabelZh")) null else value.getString("relatedContentLabelZh"),
                    createdAtEpochMillis = Instant.parse(value.getString("createdAt")).toEpochMilli(),
                    read = value.getBoolean("read"),
                    target = target,
                    targetState = if (target == null) MessageTargetState.UNAVAILABLE else MessageTargetState.AVAILABLE,
                )
            }
    }
}
