package com.reallanguage.app.feature.home.data

import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.session.SessionRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpMessagesReadAllResponseTest {
    @Test fun successfulStatusWithRejectedBodyCannotConfirmRead() = runTest {
        val rejectedBodies = listOf("", "{}", "{\"updatedCount\":null}", "{\"updatedCount\":\"3\"}", "{\"updatedCount\":-1}")
        for (body in rejectedBodies) {
            var decodedBody: String? = null
            val repository = repository(HttpResponse(200, body), MessagesReadAllWireCodec {
                decodedBody = it
                false
            })

            assertFalse("Rejected acknowledgment: $body", repository.markAllRead())
            assertEquals(body, decodedBody)
        }
    }

    @Test fun malformedBodyDecoderFailureDoesNotTurnHttp200IntoSuccess() = runTest {
        val repository = repository(HttpResponse(200, "not-json"), MessagesReadAllWireCodec {
            throw IllegalArgumentException("Invalid acknowledgment")
        })

        assertFalse(repository.markAllRead())
    }

    @Test fun zeroAndPositiveCountsAcceptedByCodecConfirmWithoutMatchingPageSize() = runTest {
        for (count in listOf(0, 3, 201)) {
            val body = "{\"updatedCount\":$count}"
            var calls = 0
            val repository = repository(HttpResponse(200, body), MessagesReadAllWireCodec {
                assertEquals(body, it)
                calls += 1
                true
            })

            assertTrue(repository.markAllRead())
            assertEquals(1, calls)
        }
    }

    @Test fun unsuccessfulStatusDoesNotDecodeOrConfirmRead() = runTest {
        val repository = repository(HttpResponse(503, "unavailable"), MessagesReadAllWireCodec {
            error("Failed HTTP response must not be decoded")
        })

        assertFalse(repository.markAllRead())
    }

    @Test fun accountChangeDuringAcknowledgmentDecodeCannotConfirmRead() = runTest {
        val runtime = session()
        val repository = HttpMessagesRepository({ HttpResponse(200, "{\"updatedCount\":1}") }, runtime,
            readAllCodec = MessagesReadAllWireCodec {
                runtime.update("other-token", "other-account")
                true
            })

        assertFalse(repository.markAllRead())
    }

    @Test fun acknowledgmentDecodeCancellationPropagates() = runTest {
        val cancelled = CancellationException("Acknowledgment cancelled")
        val repository = repository(HttpResponse(200, "{\"updatedCount\":1}"), MessagesReadAllWireCodec { throw cancelled })

        val actual = runCatching { repository.markAllRead() }.exceptionOrNull()
        assertTrue("Acknowledgment cancellation must propagate", actual is CancellationException)
        assertEquals(cancelled.message, actual?.message)
        assertTrue("Coroutine stack recovery must preserve the original cancellation cause",
            generateSequence(actual) { it.cause }.any { it === cancelled })
    }

    private fun repository(response: HttpResponse, codec: MessagesReadAllWireCodec) =
        HttpMessagesRepository({ response }, session(), readAllCodec = codec)

    private fun session() = SessionRuntime().apply { update("test-token", "test-account") }
}
