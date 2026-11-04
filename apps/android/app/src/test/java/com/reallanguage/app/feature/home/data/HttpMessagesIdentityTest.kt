package com.reallanguage.app.feature.home.data

import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.home.domain.AppMessage
import com.reallanguage.app.feature.home.domain.AppMessageTarget
import com.reallanguage.app.feature.home.domain.MessageKind
import com.reallanguage.app.feature.home.domain.MessageTargetState
import com.reallanguage.app.feature.home.domain.MessagesLoadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpMessagesIdentityTest {
    @Test fun responseFromPreviousAccountIsRejectedBeforeDecoding() = runTest {
        val runtime = accountSession()
        var decodes = 0
        val repository = HttpMessagesRepository({ request ->
            assertEquals("Bearer account-a-token", request.headers["Authorization"])
            runtime.update("account-b-token", "account-b")
            HttpResponse(200, "account-a-response")
        }, runtime, MessagesWireCodec {
            decodes += 1
            listOf(review)
        })

        assertEquals(MessagesLoadResult.Failed, repository.load())
        assertEquals(0, decodes)
    }

    @Test fun identityChangeDuringDecodeCannotPublishPreviousAccountMessages() = runTest {
        val runtime = accountSession()
        val repository = HttpMessagesRepository({ HttpResponse(200, "account-a-response") }, runtime,
            MessagesWireCodec {
                runtime.update("account-b-token", "account-b")
                listOf(review)
            })

        assertEquals(MessagesLoadResult.Failed, repository.load())
    }

    @Test fun previousAccountRepositoryCannotBorrowNewAccountTokenForReadOrMutation() = runTest {
        val runtime = accountSession()
        var requests = 0
        val repository = HttpMessagesRepository({
            requests += 1
            HttpResponse(200, "response")
        }, runtime, MessagesWireCodec { listOf(review) })
        runtime.update("account-b-token", "account-b")

        val loaded = repository.load()
        val marked = repository.markAllRead()

        assertEquals(0, requests)
        assertEquals(MessagesLoadResult.Failed, loaded)
        assertFalse(marked)
    }

    @Test fun signingOutAndBackIntoSameAccountDoesNotRevivePreviousRepository() = runTest {
        val runtime = accountSession()
        var requests = 0
        val repository = HttpMessagesRepository({
            requests += 1
            HttpResponse(200, "response")
        }, runtime, MessagesWireCodec { listOf(review) })
        runtime.clear()
        runtime.update("new-account-a-token", "account-a")

        val loaded = repository.load()
        val marked = repository.markAllRead()

        assertEquals(0, requests)
        assertEquals(MessagesLoadResult.Failed, loaded)
        assertFalse(marked)
    }

    @Test fun lateReadAcknowledgmentCannotSucceedForReplacedAccount() = runTest {
        val runtime = accountSession()
        val repository = HttpMessagesRepository({ request ->
            assertEquals("POST", request.method)
            assertEquals("Bearer account-a-token", request.headers["Authorization"])
            runtime.update("account-b-token", "account-b")
            HttpResponse(200, """{"updatedCount":1}""")
        }, runtime)

        assertFalse(repository.markAllRead())
    }

    @Test fun loadingCancellationPropagatesInsteadOfBecomingFailureState() = runTest {
        val cancelled = CancellationException("message load cancelled")
        val repository = HttpMessagesRepository({ throw cancelled }, accountSession(),
            MessagesWireCodec { error("cancelled responses must not be decoded") })

        assertCancellation(cancelled, runCatching { repository.load() }.exceptionOrNull())
    }

    @Test fun readMutationCancellationPropagatesInsteadOfBecomingFalse() = runTest {
        val cancelled = CancellationException("message read cancelled")
        val repository = HttpMessagesRepository({ throw cancelled }, accountSession())

        assertCancellation(cancelled, runCatching { repository.markAllRead() }.exceptionOrNull())
    }

    @Test fun ordinarySameAccountTokenRefreshPreservesRepositoryOwnership() = runTest {
        val runtime = accountSession()
        val epoch = runtime.accountEpoch
        val authorizations = mutableListOf<String?>()
        val repository = HttpMessagesRepository({ request ->
            authorizations += request.headers["Authorization"]
            if (request.method == "GET") runtime.update("latest-account-a-token", "account-a")
            HttpResponse(200, if (request.method == "GET") "response" else """{"updatedCount":1}""")
        }, runtime, MessagesWireCodec { listOf(review) },
            MessagesReadAllWireCodec { it == """{"updatedCount":1}""" })
        runtime.update("refreshed-account-a-token", "account-a")

        assertEquals(MessagesLoadResult.Ready(listOf(review)), repository.load())
        assertTrue(repository.markAllRead())
        assertEquals(epoch, runtime.accountEpoch)
        assertEquals(listOf("Bearer refreshed-account-a-token", "Bearer latest-account-a-token"), authorizations)
    }

    private fun accountSession() = SessionRuntime().apply { update("account-a-token", "account-a") }

    private fun assertCancellation(expected: CancellationException, actual: Throwable?) {
        assertTrue("Cancellation must propagate, not become a failure result", actual is CancellationException)
        assertEquals(expected.message, actual?.message)
        assertTrue("Recovered coroutine exception must retain the original cancellation cause",
            generateSequence(actual) { it.cause }.any { it === expected })
    }

    private val review = AppMessage(
        "account-a-message", MessageKind.MANUAL_REVIEW_RESULT, "复核完成", "测试日记", 1L, false,
        AppMessageTarget("manual-review", "account-a-review"), MessageTargetState.AVAILABLE,
    )
}
