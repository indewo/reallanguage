package com.reallanguage.app.core.session

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Refresh before sending, never retry a business request after it has been sent. */
class SessionPreflightHttpTransport(
    private val delegate: HttpTransport,
    private val runtime: SessionRuntime,
    private val refresh: suspend (String, Long) -> Boolean,
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val auth = request.headers.keys.singleOrNull { it.equals("Authorization", true) }
            ?: return delegate.execute(request)
        val owner = synchronized(runtime) {
            val account = runtime.accountId ?: return unauthorized()
            if (request.headers[auth] != "Bearer ${runtime.accessToken}") return unauthorized()
            account to runtime.accountEpoch
        }
        val prepared = refreshLock.withLock {
            fun current() = runtime.accountId == owner.first && runtime.accountEpoch == owner.second && runtime.accessToken != null
            if (!current()) return unauthorized()
            if (runtime.needsAccessRefresh() && !refresh(owner.first, owner.second)) return unauthorized()
            synchronized(runtime) {
                if (!current()) return unauthorized()
                request.copy(headers = request.headers + (auth to "Bearer ${runtime.accessToken}"))
            }
        }
        return delegate.execute(prepared)
    }

    private fun unauthorized() = HttpResponse(401, "{\"status\":\"INVALID_OR_EXPIRED\"}")
    private companion object { val refreshLock = Mutex() }
}
