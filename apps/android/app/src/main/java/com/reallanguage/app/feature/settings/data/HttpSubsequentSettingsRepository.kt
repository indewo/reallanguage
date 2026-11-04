package com.reallanguage.app.feature.settings.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.vocabulary.AndroidVocabularyPoolPreference
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import com.reallanguage.app.feature.settings.domain.PurposeVocabulary
import com.reallanguage.app.feature.settings.domain.SubsequentSettingsLoadResult
import com.reallanguage.app.feature.settings.domain.SubsequentSettingsRepository
import com.reallanguage.app.feature.settings.domain.SubsequentSettingsSaveResult
import com.reallanguage.app.feature.settings.domain.SettingsEffectApplyResult
import com.reallanguage.app.feature.settings.domain.SettingsEffectCommand
import com.reallanguage.app.feature.settings.domain.SettingsEffectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

interface SubsequentSettingsWireCodec {
    fun request(settings: InitialSettingsDraft): String
    fun current(body: String): InitialSettingsDraft?
    fun saved(body: String): Boolean
    fun rowVersion(body: String): Long? = null
    fun updateRequest(settings: InitialSettingsDraft, expectedRowVersion: Long): String = request(settings)
    fun updatedVersion(body: String): Long? = null
    fun versionConflict(body: String): Boolean = false
}

class HttpSubsequentSettingsRepository(
    private val transport: HttpTransport,
    private val codec: SubsequentSettingsWireCodec,
    private val sessionRuntime: SessionRuntime,
    private val vocabularyPoolPreference: AndroidVocabularyPoolPreference? = null,
) : SubsequentSettingsRepository {
    private data class Loaded(val owner: String, val settings: InitialSettingsDraft, val version: Long)
    private data class SaveAttempt(val owner: String, val proposed: InitialSettingsDraft, val version: Long,
        var verified: Boolean = false, var conflicted: Boolean = false)
    private val mutex = Mutex()
    private var loaded: Loaded? = null
    private val attempts = mutableMapOf<Pair<String, String>, SaveAttempt>()

    override suspend fun load(): SubsequentSettingsLoadResult = mutex.withLock {
        val owner = sessionRuntime.accountId ?: return@withLock SubsequentSettingsLoadResult.Failed
        val token = sessionRuntime.accessToken ?: return@withLock SubsequentSettingsLoadResult.Failed
        val revision = sessionRuntime.identityRevision.value
        loaded = null
        try {
            val current = read(owner, token)
            if (current != null && sameSession(owner, revision)) {
                loaded = current
                SubsequentSettingsLoadResult.Ready(current.settings)
            } else SubsequentSettingsLoadResult.Failed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SubsequentSettingsLoadResult.Failed
        }
    }

    override suspend fun save(
        settings: InitialSettingsDraft,
        commandId: String,
    ): SubsequentSettingsSaveResult = mutex.withLock {
        val owner = sessionRuntime.accountId ?: return@withLock SubsequentSettingsSaveResult.Failed
        val token = sessionRuntime.accessToken ?: return@withLock SubsequentSettingsSaveResult.Failed
        val revision = sessionRuntime.identityRevision.value
        val baseline = loaded?.takeIf { it.owner == owner } ?: return@withLock SubsequentSettingsSaveResult.Failed
        if (commandId.isBlank()) return@withLock SubsequentSettingsSaveResult.Failed
        val key = owner to commandId
        val attempt = attempts.getOrPut(key) { SaveAttempt(owner, settings, baseline.version) }
        if (attempt.proposed != settings) return@withLock SubsequentSettingsSaveResult.Failed
        if (attempt.conflicted) return@withLock SubsequentSettingsSaveResult.Conflict
        if (attempt.verified) return@withLock if (baseline.settings == settings) SubsequentSettingsSaveResult.Saved
            else SubsequentSettingsSaveResult.Failed
        try {
            val response = transport.execute(
                HttpRequest(
                    method = "PUT",
                    path = VERSIONED_PATH,
                    body = codec.updateRequest(settings, attempt.version),
                    headers = mapOf(
                        "Authorization" to "Bearer $token",
                    ),
                ),
            )
            if (!sameSession(owner, revision)) return@withLock SubsequentSettingsSaveResult.Failed
            if (response.statusCode == 409 && codec.versionConflict(response.body)) {
                attempt.conflicted = true
                return@withLock SubsequentSettingsSaveResult.Conflict
            }
            val savedVersion = response.body.takeIf { response.statusCode == HTTP_OK }?.let(codec::updatedVersion)
                ?: return@withLock SubsequentSettingsSaveResult.Failed
            val reread = read(owner, token)
            if (sameSession(owner, revision) && reread != null && reread.version == savedVersion && reread.settings == settings) {
                loaded = reread
                attempt.verified = true
                SubsequentSettingsSaveResult.Saved
            } else SubsequentSettingsSaveResult.Failed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SubsequentSettingsSaveResult.Failed
        }
    }

    private suspend fun read(owner: String, token: String): Loaded? {
        val response = transport.execute(HttpRequest("GET", VERSIONED_PATH, "", mapOf("Authorization" to "Bearer $token")))
        if (response.statusCode != HTTP_OK) return null
        val settings = codec.current(response.body) ?: return null
        settings.basicVocabulary?.let { vocabularyPoolPreference?.write(owner, it.name) }
        val version = codec.rowVersion(response.body)?.takeIf { it > 0 } ?: return null
        return Loaded(owner, settings, version)
    }

    private fun sameSession(owner: String, revision: Long) =
        sessionRuntime.accountId == owner && sessionRuntime.accessToken != null && sessionRuntime.identityRevision.value == revision

    private companion object {
        const val HTTP_OK = 200
        const val VERSIONED_PATH = "/api/v1/settings/current/revision"
    }
}

class JsonSubsequentSettingsWireCodec : SubsequentSettingsWireCodec {
    private val initial = JsonInitialSettingsWireCodec()

    override fun request(settings: InitialSettingsDraft): String = initial.request(settings)

    override fun updateRequest(settings: InitialSettingsDraft, expectedRowVersion: Long): String =
        JSONObject().put("settings", JSONObject(request(settings))).put("expectedRowVersion", expectedRowVersion).toString()

    override fun current(body: String): InitialSettingsDraft? = runCatching {
        val json = JSONObject(body).getJSONObject("settings")
        val booleanKeys = listOf("slangEnabled", "voiceEnabled", "autoPlayEnabled")
        require(booleanKeys.all { json.get(it) is Boolean })
        require(listOf("formalityLevel", "speechSpeedLevel").all { key ->
            val value = json.get(key)
            value is Number && value.toDouble() in 1.0..5.0 && value.toDouble() == value.toInt().toDouble()
        })
        require(json.get("basicVocabulary") is String && json.get("styleReference") is String)
        val purposeValues = json.getJSONArray("purposeVocabularies")
        require(purposeValues.length() <= 3)
        val purposes = (0 until purposeValues.length()).map { index ->
            val value = purposeValues.get(index)
            require(value is String)
            PurposeVocabulary.valueOf(value)
        }
        require(purposes.distinct().size == purposes.size)
        InitialSettingsDraft(
            formalityLevel = json.getInt("formalityLevel"),
            slangEnabled = json.getBoolean("slangEnabled"),
            basicVocabulary = BasicVocabulary.valueOf(json.getString("basicVocabulary")),
            // Legacy purpose pools are accepted only to read already-published records. The
            // current model has one vocabulary pool and normalizes the retired field away.
            purposeVocabularies = emptySet(),
            styleReference = json.getString("styleReference"),
            voiceEnabled = json.getBoolean("voiceEnabled"),
            autoPlayEnabled = json.getBoolean("autoPlayEnabled"),
            speechSpeedLevel = json.getInt("speechSpeedLevel"),
        ).takeIf { draft ->
            draft.formalityLevel in 1..5 &&
                draft.speechSpeedLevel in 1..5 &&
                (!draft.autoPlayEnabled!! || draft.voiceEnabled == true)
        }
    }.getOrNull()

    override fun rowVersion(body: String): Long? = runCatching {
        val value = JSONObject(body).get("rowVersion")
        require(value is Number && value.toDouble() in 1.0..9_007_199_254_740_991.0)
        require(value.toDouble() == value.toLong().toDouble())
        value.toLong()
    }.getOrNull()

    override fun saved(body: String): Boolean = updatedVersion(body) != null

    override fun updatedVersion(body: String): Long? = runCatching {
        require(JSONObject(body).get("status") == "SAVED")
        rowVersion(body)
    }.getOrNull()

    override fun versionConflict(body: String): Boolean = runCatching {
        val error = JSONObject(body).getJSONObject("error")
        error.get("code") == "SETTINGS_VERSION_CONFLICT" &&
            rowVersion(error.getJSONObject("details").toString()) != null
    }.getOrDefault(false)
}

class HttpSettingsEffectRepository(
    private val settings: SubsequentSettingsRepository,
) : SettingsEffectRepository {
    override suspend fun apply(command: SettingsEffectCommand): SettingsEffectApplyResult =
        when (settings.save(command.proposedSettings, command.commandId)) {
            SubsequentSettingsSaveResult.Saved -> SettingsEffectApplyResult.Applied
            SubsequentSettingsSaveResult.Failed -> SettingsEffectApplyResult.Failed
            SubsequentSettingsSaveResult.Conflict -> SettingsEffectApplyResult.Conflict
        }
}
