package com.reallanguage.app.feature.home.data

import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.session.SessionLogoutRepository
import com.reallanguage.app.core.session.SessionLogoutResult
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.home.domain.AccountSnapshot
import com.reallanguage.app.feature.home.domain.AccountSnapshotResult
import com.reallanguage.app.feature.home.domain.LogoutResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpAccountRepositoryTest {
    @Test fun loadsTheAuthenticatedServerProfile() = runTest {
        val runtime = SessionRuntime().apply { update("token", "account") }
        val repository = HttpAccountRepository({ request ->
            assertEquals("/api/v1/account/current", request.path); assertEquals("Bearer token", request.headers["Authorization"])
            HttpResponse(200, "profile")
        }, runtime, SessionLogoutRepository { SessionLogoutResult.LoggedOut }, AccountProfileWireCodec { AccountSnapshot("learner@example.com") })
        assertEquals(AccountSnapshotResult.Ready(AccountSnapshot("learner@example.com")), repository.load())
    }
    @Test fun logoutDelegatesToTheSessionAuthorityAndPreservesFailure() = runTest {
        val runtime = SessionRuntime().apply { update("token", "account") }
        var result: SessionLogoutResult = SessionLogoutResult.Failed
        val repository = HttpAccountRepository({ HttpResponse(500, "") }, runtime, SessionLogoutRepository { result }, AccountProfileWireCodec { error("not called") })
        assertEquals(LogoutResult.Failed, repository.logout("stable-command"))
        result = SessionLogoutResult.LoggedOut
        assertEquals(LogoutResult.LoggedOut, repository.logout("stable-command"))
    }
}
