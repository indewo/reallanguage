package com.reallanguage.app.core.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BalanceAwareHttpTransportTest {
    @Test
    fun `emits only the standard balance-required response and preserves it for the repository`() = runTest {
        var observed: HttpRequest? = null
        val request = HttpRequest("POST", "/api/v1/learning/explanations", "{}")
        val response = HttpResponse(
            402,
            """{"error":{"code":"AI_BALANCE_INSUFFICIENT"}}""",
        )
        val transport = BalanceAwareHttpTransport(
            delegate = HttpTransport { response },
            onBalanceRequired = { observed = it },
        )

        assertEquals(response, transport.execute(request))
        assertEquals(request, observed)
    }

    @Test
    fun `does not intercept unrelated failures`() = runTest {
        var observed: HttpRequest? = null
        val transport = BalanceAwareHttpTransport(
            delegate = HttpTransport { HttpResponse(402, "{}") },
            onBalanceRequired = { observed = it },
        )
        transport.execute(HttpRequest("POST", "/api/v1/test", "{}"))
        assertNull(observed)
    }
}
