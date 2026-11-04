package com.reallanguage.app.feature.home.data

import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.home.domain.MessageKind
import com.reallanguage.app.feature.home.domain.MessageTargetState
import com.reallanguage.app.feature.home.domain.MessagesLoadResult
import com.reallanguage.app.feature.home.domain.AppMessage
import com.reallanguage.app.feature.home.domain.AppMessageTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpMessagesRepositoryTest {
    @Test fun readsAuthenticatedControlledMessages() = runTest {
        val runtime = SessionRuntime().apply { update("token", "account") }
        val repository = HttpMessagesRepository({ request ->
            assertEquals("Bearer token", request.headers["Authorization"])
            HttpResponse(200, """{"messages":[{"messageId":"m1","kind":"MANUAL_REVIEW_RESULT","summaryZh":"复核完成","relatedContentLabelZh":"昨晚的日记","createdAt":"2026-09-02T00:00:00.000Z","read":false,"target":{"route":"MANUAL_REVIEW_RESULT","resourceId":"r1"}}]}""")
        }, runtime, MessagesWireCodec { listOf(review) })
        val result = repository.load() as MessagesLoadResult.Ready
        assertEquals(MessageKind.MANUAL_REVIEW_RESULT, result.messages.single().kind)
        assertEquals(MessageTargetState.AVAILABLE, result.messages.single().targetState)
        assertEquals("manual-review", result.messages.single().target?.route)
    }
    @Test fun rejectsUnknownTargetsAndFailsClosedWithoutSession() = runTest {
        val runtime = SessionRuntime().apply { update("token", "account") }
        val repository = HttpMessagesRepository({ HttpResponse(200, "ignored") }, runtime, MessagesWireCodec { listOf(unavailable) })
        val result = repository.load() as MessagesLoadResult.Ready
        assertEquals(MessageTargetState.UNAVAILABLE, result.messages.single().targetState)
        assertEquals(null, result.messages.single().target)
        runtime.clear()
        assertTrue(repository.load() is MessagesLoadResult.Failed)
    }
    @Test fun marksAllMessagesReadThroughAuthenticatedEndpoint() = runTest {
        val runtime = SessionRuntime().apply { update("token", "account") }
        val repository = HttpMessagesRepository({ request ->
            assertEquals("POST", request.method)
            assertEquals("/api/v1/messages/read-all", request.path)
            assertEquals("Bearer token", request.headers["Authorization"])
            HttpResponse(200, """{"updatedCount":3}""")
        }, runtime, readAllCodec = MessagesReadAllWireCodec { it == """{"updatedCount":3}""" })

        assertTrue(repository.markAllRead())
    }
    private val review = AppMessage("m1", MessageKind.MANUAL_REVIEW_RESULT, "复核完成", "昨晚的日记", 1, false, com.reallanguage.app.feature.home.domain.AppMessageTarget("manual-review", "r1"), MessageTargetState.AVAILABLE)
    private val unavailable = AppMessage("m1", MessageKind.SYSTEM, "通知", null, 1, false, null, MessageTargetState.UNAVAILABLE)
}
