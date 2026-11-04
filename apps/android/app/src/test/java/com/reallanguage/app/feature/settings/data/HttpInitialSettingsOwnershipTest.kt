package com.reallanguage.app.feature.settings.data

import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.InitialSettingsCompletionResult
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real runtime epochs, but only in-memory transport/codec fixtures. No credential store or HTTP. */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpInitialSettingsOwnershipTest {
    @Test fun oldAccountRepositoryCannotBorrowTheReplacementAccountsToken() = runTest {
        assertOldRepositoryIsRejected("settings-owner-b")
    }

    @Test fun explicitSameAccountNewSessionCannotReviveTheOldRepository() = runTest {
        assertOldRepositoryIsRejected(ACCOUNT)
    }

    @Test fun lateHttpSuccessAfterAccountReplacementIsRejectedBeforeAckDecode() =
        assertLateResponseIsRejected("settings-owner-b")

    @Test fun lateHttpSuccessAfterSameAccountNewSessionIsRejectedBeforeAckDecode() =
        assertLateResponseIsRejected(ACCOUNT)

    @Test fun revocationDuringRequestEncodingCannotSendTheOldDraft() = runTest {
        val runtime = session()
        var requests = 0
        val repository = HttpInitialSettingsRepository(
            HttpTransport { requests++; HttpResponse(200, "completed") },
            Codec(encode = { runtime.update("isolated-b", "settings-owner-b", newSession = true) }),
            runtime,
        )
        assertEquals(InitialSettingsCompletionResult.Failed(false), repository.complete(settings))
        assertEquals("Encoding must not leave a check-to-send ownership gap", 0, requests)
    }

    @Test fun revocationDuringAckDecodeCannotPublishCompletion() = runTest {
        val runtime = session()
        val repository = HttpInitialSettingsRepository(
            HttpTransport { HttpResponse(200, "completed") },
            Codec(decode = { runtime.update("isolated-new-a", ACCOUNT, newSession = true); true }),
            runtime,
        )
        assertEquals(InitialSettingsCompletionResult.Failed(false), repository.complete(settings))
    }

    @Test fun transportAndCodecCancellationRetainTheirTypeAndOriginalMessage() = runTest {
        for (duringDecode in listOf(false, true)) {
            val cancellation = CancellationException("isolated initial-settings cancellation $duringDecode")
            val repository = HttpInitialSettingsRepository(
                HttpTransport { if (!duringDecode) throw cancellation else HttpResponse(200, "completed") },
                Codec(decode = { throw cancellation }),
                session(),
            )
            val actual = runCatching { repository.complete(settings) }.exceptionOrNull()
            assertTrue("Cancellation cannot become an ordinary Failed result", actual is CancellationException)
            assertTrue(generateSequence(actual) { it.cause }.take(16).any {
                it is CancellationException && it.message == cancellation.message
            })
        }
    }

    @Test fun ordinarySameOwnerTokenRefreshBeforeAndDuringSaveStillSucceeds() = runTest {
        val runtime = session()
        val epoch = runtime.accountEpoch
        var requests = 0
        val repository = HttpInitialSettingsRepository(
            HttpTransport { request ->
                requests++
                assertEquals("PUT", request.method)
                assertEquals("/api/v1/settings/current/initial", request.path)
                assertEquals("Bearer isolated-refreshed-a", request.headers["Authorization"])
                runtime.update("isolated-response-refresh", ACCOUNT)
                HttpResponse(200, "completed")
            },
            Codec(decode = { runtime.update("isolated-codec-refresh", ACCOUNT); true }),
            runtime,
        )
        runtime.update("isolated-refreshed-a", ACCOUNT)
        assertEquals(InitialSettingsCompletionResult.Completed, repository.complete(settings))
        assertEquals(epoch, runtime.accountEpoch)
        assertEquals(1, requests)
    }

    @Test fun newlyConstructedRepositoryCanSaveAfterSameAccountNewSession() = runTest {
        val runtime = session()
        runtime.update("isolated-new-a", ACCOUNT, newSession = true)
        var requests = 0
        val repository = HttpInitialSettingsRepository(HttpTransport { request ->
            requests++
            assertEquals("Bearer isolated-new-a", request.headers["Authorization"])
            HttpResponse(200, "completed")
        }, Codec(), runtime)
        assertEquals(InitialSettingsCompletionResult.Completed, repository.complete(settings))
        assertEquals(1, requests)
    }

    private suspend fun assertOldRepositoryIsRejected(nextAccount: String) {
        val runtime = session()
        val epoch = runtime.accountEpoch
        var requests = 0
        val repository = HttpInitialSettingsRepository(
            HttpTransport { requests++; HttpResponse(200, "completed") }, Codec(), runtime,
        )
        // ProductionSessionRepository.accept uses newSession=true for login, false for refresh.
        runtime.update("isolated-new-session", nextAccount, newSession = true)
        assertTrue(runtime.accountEpoch > epoch)
        assertEquals(InitialSettingsCompletionResult.Failed(false), repository.complete(settings))
        assertEquals(0, requests)
    }

    private fun assertLateResponseIsRejected(nextAccount: String) = runTest {
        val runtime = session()
        val response = CompletableDeferred<HttpResponse>()
        var requests = 0
        var decodes = 0
        val repository = HttpInitialSettingsRepository(HttpTransport { request ->
            requests++
            assertEquals("Bearer isolated-original-a", request.headers["Authorization"])
            response.await()
        }, Codec(decode = { decodes++; true }), runtime)
        val result = async { repository.complete(settings) }
        try {
            runCurrent()
            assertEquals(1, requests)
            runtime.update("isolated-new-session", nextAccount, newSession = true)
        } finally {
            response.complete(HttpResponse(200, "completed"))
        }
        assertEquals(InitialSettingsCompletionResult.Failed(false), result.await())
        assertEquals("Old-account response bodies must not reach the codec", 0, decodes)
    }

    private fun session() = SessionRuntime().apply { update("isolated-original-a", ACCOUNT, newSession = true) }
    private class Codec(
        private val encode: () -> Unit = {},
        private val decode: () -> Boolean = { true },
    ) : InitialSettingsWireCodec {
        override fun request(settings: InitialSettingsDraft): String { encode(); return "isolated-settings" }
        override fun completed(body: String): Boolean = decode()
    }
    private val settings = InitialSettingsDraft(
        formalityLevel = 3, slangEnabled = false, basicVocabulary = BasicVocabulary.CET_4,
        voiceEnabled = true, autoPlayEnabled = false, speechSpeedLevel = 3,
    )
    private companion object { const val ACCOUNT = "settings-owner-a" }
}
