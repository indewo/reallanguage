package com.reallanguage.app.feature.home.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionLogoutRepository
import com.reallanguage.app.core.session.SessionLogoutResult
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.home.domain.AccountRepository
import com.reallanguage.app.feature.home.domain.AccountSnapshot
import com.reallanguage.app.feature.home.domain.AccountSnapshotResult
import com.reallanguage.app.feature.home.domain.LogoutResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HttpAccountRepository(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val sessions: SessionLogoutRepository,
    private val codec: AccountProfileWireCodec = JsonAccountProfileWireCodec(),
) : AccountRepository {
    override suspend fun load(): AccountSnapshotResult = withContext(Dispatchers.IO) {
        val token = runtime.accessToken ?: return@withContext AccountSnapshotResult.Failed
        runCatching {
            val response = transport.execute(HttpRequest("GET", "/api/v1/account/current", "", mapOf("Authorization" to "Bearer $token")))
            if (response.statusCode == 200) AccountSnapshotResult.Ready(codec.profile(response.body)) else AccountSnapshotResult.Failed
        }.getOrDefault(AccountSnapshotResult.Failed)
    }
    override suspend fun logout(commandId: String): LogoutResult = when (sessions.logout()) {
        SessionLogoutResult.LoggedOut -> LogoutResult.LoggedOut
        SessionLogoutResult.Failed -> LogoutResult.Failed
    }
}
fun interface AccountProfileWireCodec { fun profile(body: String): AccountSnapshot }
class JsonAccountProfileWireCodec : AccountProfileWireCodec {
    override fun profile(body: String) = AccountSnapshot(JSONObject(body).getString("email"))
}
