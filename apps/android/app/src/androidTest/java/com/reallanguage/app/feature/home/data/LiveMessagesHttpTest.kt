package com.reallanguage.app.feature.home.data

import android.content.Context
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.UrlConnectionSessionTransport
import com.reallanguage.app.feature.home.domain.MessageTargetState
import com.reallanguage.app.feature.home.domain.MessagesLoadResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real socket + production authentication against an isolated fixture server, never the user's server/session. */
class LiveMessagesHttpTest {
    @Test fun readAllPersistsAndANewRepositoryReadsItBackWithoutChangingAnotherAccount() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveMessagesHttp") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousResult = File(context.filesDir, "live-messages-result.json")
        if (previousResult.exists()) assertTrue("Old test evidence must not survive a failed new attempt", previousResult.delete())
        val sessionFile = File(context.filesDir, "live-messages-session.json")
        val session = try {
            assertTrue("Fresh isolated session fixture required", sessionFile.isFile && sessionFile.length() <= 16_384)
            assertEquals("Session fixture must not be group/world readable", 0, Os.stat(sessionFile.absolutePath).st_mode and 0b111111)
            JSONObject(sessionFile.readText())
        } finally {
            if (sessionFile.exists()) assertTrue("Consumed private session must be removed", sessionFile.delete())
        }
        assertEquals("REAL_MESSAGES_HTTP_V1", session.getString("fixture"))
        val baseUrl = session.getString("baseUrl")
        assertTrue("Only the emulator's isolated loopback server is allowed", Regex("http://10\\.0\\.2\\.2:[0-9]+").matches(baseUrl))
        val a = session.getJSONObject("accountA")
        val b = session.getJSONObject("accountB")
        val runtimeA = SessionRuntime().apply { update(a.getString("accessToken"), a.getString("accountId")) }
        val runtimeB = SessionRuntime().apply { update(b.getString("accessToken"), b.getString("accountId")) }
        val delegate = UrlConnectionSessionTransport(baseUrl, true)
        var requestCount = 0
        val acknowledgedCounts = mutableListOf<Int>()
        val transport = HttpTransport { request ->
            assertTrue("Only the two real message endpoints may be called", request.path in setOf("/api/v1/messages", "/api/v1/messages/read-all"))
            val response = delegate.execute(request)
            requestCount++
            assertEquals("Real production message HTTP status", 200, response.statusCode)
            if (request.method == "POST") acknowledgedCounts += JSONObject(response.body).getInt("updatedCount")
            response
        }
        try {
            val firstRepository = HttpMessagesRepository(transport, runtimeA)
            val before = firstRepository.load()
            assertTrue("Initial authoritative messages must load", before is MessagesLoadResult.Ready)
            val initial = (before as MessagesLoadResult.Ready).messages
            assertEquals(2, initial.size)
            val unread = initial.single { it.messageId == a.getString("unreadMessageId") }
            assertFalse(unread.read)
            assertEquals(MessageTargetState.AVAILABLE, unread.targetState)
            assertEquals("manual-review", unread.target?.route)
            assertEquals(a.getString("reviewTarget"), unread.target?.resourceId)
            assertTrue(initial.single { it.messageId == a.getString("alreadyReadMessageId") }.read)
            assertTrue("Read-all must accept a valid real acknowledgment", firstRepository.markAllRead())

            val reconstructed = HttpMessagesRepository(transport, runtimeA)
            val after = reconstructed.load()
            assertTrue("A new repository must independently reload saved status", after is MessagesLoadResult.Ready)
            val reloaded = (after as MessagesLoadResult.Ready).messages
            assertEquals(initial.map { it.messageId }.toSet(), reloaded.map { it.messageId }.toSet())
            assertTrue("Both messages must be read in the server response", reloaded.all { it.read })
            assertTrue("A repeated operation with zero updated rows remains valid", reconstructed.markAllRead())
            assertEquals(listOf(1, 0), acknowledgedCounts)

            val other = HttpMessagesRepository(transport, runtimeB).load()
            assertTrue("The other isolated account must load independently", other is MessagesLoadResult.Ready)
            val otherMessage = (other as MessagesLoadResult.Ready).messages.single()
            assertEquals(b.getString("unreadMessageId"), otherMessage.messageId)
            assertFalse("Account A read-all must not mark account B read", otherMessage.read)
            assertEquals(5, requestCount)
            val evidence = JSONObject().put("status", "PASSED").put("transport", "REAL_HTTP_SOCKET")
                .put("httpRequests", requestCount).put("postUpdatedCounts", org.json.JSONArray(acknowledgedCounts))
                .put("newRepositoryReadback", true).put("otherAccountStillUnread", true)
                .put("scope", "Message transport and persistence; not human review, login, push, or AI acceptance")
            context.openFileOutput("live-messages-result.json", Context.MODE_PRIVATE).use { it.write(evidence.toString().toByteArray()) }
        } finally {
            runtimeA.clear()
            runtimeB.clear()
        }
    }
}
