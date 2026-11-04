package com.reallanguage.app.feature.settings.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.PurposeVocabulary
import com.reallanguage.app.feature.settings.domain.SubsequentSettingsLoadResult
import com.reallanguage.app.feature.settings.domain.SubsequentSettingsSaveResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpSubsequentSettingsRepositoryTest {
    @Test fun loadsTheCurrentSettingsWithTheActiveSession() = runTest {
        val transport = SubsequentRecordingTransport(HttpResponse(200, currentSettingsJson))
        val repository = HttpSubsequentSettingsRepository(
            transport,
            SubsequentFakeCodec(settings),
            SessionRuntime().apply { update("access-token", "fixture-owner") },
        )

        val result = repository.load()

        assertEquals(SubsequentSettingsLoadResult.Ready(settings), result)
        assertEquals("GET", transport.request?.method)
        assertEquals("/api/v1/settings/current/revision", transport.request?.path)
        assertEquals("Bearer access-token", transport.request?.headers?.get("Authorization"))
    }

    @Test fun savesTheCompleteSnapshotThroughTheCanonicalSettingsEndpoint() = runTest {
        val requests = mutableListOf<HttpRequest>()
        val transport = HttpTransport {
            requests += it
            HttpResponse(200, if (it.method == "GET") currentSettingsJson else "{\"status\":\"SAVED\",\"rowVersion\":2}")
        }
        val repository = HttpSubsequentSettingsRepository(
            transport,
            SubsequentFakeCodec(settings),
            SessionRuntime().apply { update("access-token", "fixture-owner") },
        )

        assertEquals(SubsequentSettingsLoadResult.Ready(settings), repository.load())
        assertEquals(SubsequentSettingsSaveResult.Saved, repository.save(settings, "command-1"))
        assertEquals(listOf("GET", "PUT", "GET"), requests.map { it.method })
        assertEquals("/api/v1/settings/current/revision", requests[1].path)
    }

    @Test fun failsSafelyWhenSignedOutOfflineOrMalformed() = runTest {
        val signedOut = HttpSubsequentSettingsRepository(
            SubsequentRecordingTransport(HttpResponse(200, currentSettingsJson)),
            SubsequentFakeCodec(settings),
            SessionRuntime(),
        )
        assertEquals(SubsequentSettingsLoadResult.Failed, signedOut.load())

        val malformed = HttpSubsequentSettingsRepository(
            SubsequentRecordingTransport(HttpResponse(200, "{}")),
            SubsequentFakeCodec(settings),
            SessionRuntime().apply { update("access-token", "fixture-owner") },
        )
        assertEquals(SubsequentSettingsLoadResult.Failed, malformed.load())
    }

    private val settings = com.reallanguage.app.feature.settings.domain.InitialSettingsDraft(
        formalityLevel = 3,
        slangEnabled = true,
        basicVocabulary = BasicVocabulary.CET_6,
        purposeVocabularies = setOf(PurposeVocabulary.BUSINESS_ENGLISH),
        styleReference = "clear and warm",
        voiceEnabled = true,
        autoPlayEnabled = false,
        speechSpeedLevel = 4,
    )

    private val currentSettingsJson = """{"formalityLevel":3,"slangEnabled":true,"basicVocabulary":"CET_6","purposeVocabularies":["BUSINESS_ENGLISH"],"styleReference":"clear and warm","voiceEnabled":true,"autoPlayEnabled":false,"speechSpeedLevel":4}"""
}

private class SubsequentFakeCodec(
    private val settings: com.reallanguage.app.feature.settings.domain.InitialSettingsDraft,
) : SubsequentSettingsWireCodec {
    override fun request(settings: com.reallanguage.app.feature.settings.domain.InitialSettingsDraft) = "settings"
    override fun current(body: String) = settings.takeIf { body.contains("formalityLevel") }
    override fun saved(body: String) = body.contains("SAVED")
    override fun rowVersion(body: String) = 2L.takeIf { body.contains("formalityLevel") }
    override fun updatedVersion(body: String) = 2L.takeIf { saved(body) }
}

private class SubsequentRecordingTransport(private val response: HttpResponse) : HttpTransport {
    var request: HttpRequest? = null
    override suspend fun execute(request: HttpRequest): HttpResponse {
        this.request = request
        return response
    }
}
