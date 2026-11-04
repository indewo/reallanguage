package com.reallanguage.app.feature.settings.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.InitialSettingsCompletionResult
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpInitialSettingsRepositoryTest {
    @Test fun sendsTheCompleteSnapshotWithTheCurrentAccessBearer() = runTest {
        val transport = RecordingTransport(HttpResponse(200, "completed"))
        val runtime = SessionRuntime().apply { update("access-token", "isolated-settings-account") }
        val repository = HttpInitialSettingsRepository(transport, FakeCodec(), runtime)

        assertEquals(InitialSettingsCompletionResult.Completed, repository.complete(settings))
        assertEquals("PUT", transport.request?.method)
        assertEquals("/api/v1/settings/current/initial", transport.request?.path)
        assertEquals("Bearer access-token", transport.request?.headers?.get("Authorization"))
    }

    @Test fun failsSafelyWhenSignedOutOfflineOrRejected() = runTest {
        val signedOut = HttpInitialSettingsRepository(
            RecordingTransport(HttpResponse(200, "completed")),
            FakeCodec(),
            SessionRuntime(),
        )
        assertEquals(InitialSettingsCompletionResult.Failed(false), signedOut.complete(settings))

        val offline = HttpInitialSettingsRepository(
            HttpTransport { throw IOException("offline") },
            FakeCodec(),
            SessionRuntime().apply { update("access-token", "isolated-settings-account") },
        )
        assertEquals(InitialSettingsCompletionResult.Failed(true), offline.complete(settings))

        val rejected = HttpInitialSettingsRepository(
            RecordingTransport(HttpResponse(401, "unauthorized")),
            FakeCodec(),
            SessionRuntime().apply { update("access-token", "isolated-settings-account") },
        )
        assertEquals(InitialSettingsCompletionResult.Failed(false), rejected.complete(settings))
    }

    private val settings = InitialSettingsDraft(
        formalityLevel = 3,
        slangEnabled = true,
        basicVocabulary = BasicVocabulary.CET_6,
        voiceEnabled = true,
        autoPlayEnabled = false,
        speechSpeedLevel = 4,
    )
}

private class RecordingTransport(private val response: HttpResponse) : HttpTransport {
    var request: HttpRequest? = null
    override suspend fun execute(request: HttpRequest): HttpResponse {
        this.request = request
        return response
    }
}

private class FakeCodec : InitialSettingsWireCodec {
    override fun request(settings: InitialSettingsDraft) = "settings"
    override fun completed(body: String) = body == "completed"
}
