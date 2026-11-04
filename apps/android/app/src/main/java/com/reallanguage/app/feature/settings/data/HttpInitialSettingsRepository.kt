package com.reallanguage.app.feature.settings.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.vocabulary.AndroidVocabularyPoolPreference
import com.reallanguage.app.feature.settings.domain.InitialSettingsCompletionResult
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

interface InitialSettingsWireCodec {
    fun request(settings: InitialSettingsDraft): String
    fun completed(body: String): Boolean
}

class HttpInitialSettingsRepository(
    private val transport: HttpTransport,
    private val codec: InitialSettingsWireCodec,
    private val sessionRuntime: SessionRuntime,
    private val vocabularyPoolPreference: AndroidVocabularyPoolPreference? = null,
) : com.reallanguage.app.feature.settings.domain.InitialSettingsRepository {
    private val accountId = sessionRuntime.accountId
    private val accountEpoch = sessionRuntime.accountEpoch
    private fun ownerIsCurrent() = accountId != null && sessionRuntime.accountId == accountId &&
        sessionRuntime.accountEpoch == accountEpoch && sessionRuntime.accessToken != null

    override suspend fun complete(settings: InitialSettingsDraft): InitialSettingsCompletionResult {
        currentCoroutineContext().ensureActive()
        if (!ownerIsCurrent()) return InitialSettingsCompletionResult.Failed(retryable = false)
        val accessToken = sessionRuntime.accessToken
            ?: return InitialSettingsCompletionResult.Failed(retryable = false)
        return try {
            val body = codec.request(settings)
            currentCoroutineContext().ensureActive()
            if (!ownerIsCurrent()) return InitialSettingsCompletionResult.Failed(retryable = false)
            val response = transport.execute(
                HttpRequest(
                    method = "PUT",
                    path = "/api/v1/settings/current/initial",
                    body = body,
                    headers = mapOf("Authorization" to "Bearer $accessToken"),
                ),
            )
            currentCoroutineContext().ensureActive()
            if (!ownerIsCurrent()) return InitialSettingsCompletionResult.Failed(retryable = false)
            val completed = response.statusCode == HTTP_OK && codec.completed(response.body)
            currentCoroutineContext().ensureActive()
            if (!ownerIsCurrent()) return InitialSettingsCompletionResult.Failed(retryable = false)
            if (completed) {
                settings.basicVocabulary?.let { pool ->
                    vocabularyPoolPreference?.write(checkNotNull(accountId), pool.name)
                }
                InitialSettingsCompletionResult.Completed
            } else {
                InitialSettingsCompletionResult.Failed(
                    retryable = response.statusCode == 429 || response.statusCode >= 500,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            InitialSettingsCompletionResult.Failed(retryable = true)
        } catch (_: Exception) {
            InitialSettingsCompletionResult.Failed(retryable = false)
        }
    }

    private companion object { const val HTTP_OK = 200 }
}

class JsonInitialSettingsWireCodec : InitialSettingsWireCodec {
    override fun request(settings: InitialSettingsDraft): String = JSONObject()
        .put("formalityLevel", settings.formalityLevel)
        .put("slangEnabled", settings.slangEnabled)
        .put("basicVocabulary", settings.basicVocabulary?.name)
        // The current product has one peer vocabulary-pool selection. Keep the legacy wire
        // array empty until the server field is removed in a versioned contract migration.
        .put("purposeVocabularies", JSONArray())
        .put("styleReference", settings.styleReference)
        .put("voiceEnabled", settings.voiceEnabled)
        .put("autoPlayEnabled", settings.autoPlayEnabled)
        .put("speechSpeedLevel", settings.speechSpeedLevel)
        .toString()

    override fun completed(body: String): Boolean =
        JSONObject(body).optString("status") == "COMPLETED"
}
