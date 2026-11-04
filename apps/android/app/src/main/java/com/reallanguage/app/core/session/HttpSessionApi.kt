package com.reallanguage.app.core.session

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import java.io.IOException
import kotlinx.coroutines.CancellationException

interface SessionWireCodec {
    fun login(email: String, password: String, deviceId: String): String
    fun refresh(refreshToken: String, deviceId: String): String
    fun revoke(refreshToken: String): String
    fun session(body: String): SessionApiResult
    fun revoked(body: String): Boolean
}

class HttpSessionApi(
    private val transport: HttpTransport,
    private val codec: SessionWireCodec,
) : SessionApi {
    override suspend fun login(
        email: String,
        password: String,
        deviceId: String,
    ): SessionApiResult = sessionRequest(
        HttpRequest(
            method = "POST",
            path = "/api/v1/auth/sessions",
            body = codec.login(email, password, deviceId),
        ),
    )

    override suspend fun refresh(refreshToken: String, deviceId: String): SessionApiResult =
        sessionRequest(
            HttpRequest(
                method = "POST",
                path = "/api/v1/auth/sessions/refresh",
                body = codec.refresh(refreshToken, deviceId),
            ),
        )

    override suspend fun revoke(refreshToken: String): Boolean = try {
        val response = transport.execute(
            HttpRequest(
                method = "DELETE",
                path = "/api/v1/auth/sessions/current",
                body = codec.revoke(refreshToken),
            ),
        )
        response.statusCode == HTTP_OK && codec.revoked(response.body)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun sessionRequest(request: HttpRequest): SessionApiResult = try {
        val response = transport.execute(request)
        when (response.statusCode) {
            HTTP_OK -> codec.session(response.body)
            HTTP_BAD_REQUEST, HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> SessionApiResult.InvalidOrExpired
            else -> SessionApiResult.Failed(SessionFailure.SERVICE)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        SessionApiResult.Failed(SessionFailure.NETWORK)
    } catch (_: Exception) {
        SessionApiResult.Failed(SessionFailure.SERVICE)
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
