package com.reallanguage.app.feature.auth.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.feature.auth.domain.RegistrationFailureReason
import com.reallanguage.app.feature.auth.domain.RegistrationVerificationStatusResult
import com.reallanguage.app.feature.auth.domain.VerificationEmailRepository
import com.reallanguage.app.feature.auth.domain.VerificationEmailResendResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

interface VerificationEmailWireCodec {
    fun result(body: String): VerificationEmailResendResult
    fun status(body: String): RegistrationVerificationStatusResult
}

class HttpVerificationEmailRepository(
    private val transport: HttpTransport,
    private val codec: VerificationEmailWireCodec,
) : VerificationEmailRepository {
    override suspend fun status(registrationId: String): RegistrationVerificationStatusResult = try {
        val response = transport.execute(
            HttpRequest(
                method = "GET",
                path = "/api/v1/auth/registrations/$registrationId/verification-status",
                body = "",
            ),
        )
        if (response.statusCode == HTTP_OK) {
            codec.status(response.body)
        } else {
            RegistrationVerificationStatusResult.Failed(
                RegistrationFailureReason.SERVICE,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        RegistrationVerificationStatusResult.Failed(RegistrationFailureReason.NETWORK)
    } catch (_: Exception) {
        RegistrationVerificationStatusResult.Failed(RegistrationFailureReason.SERVICE)
    }

    override suspend fun resend(registrationId: String): VerificationEmailResendResult = try {
        val response = transport.execute(
            HttpRequest(
                method = "POST",
                path = "/api/v1/auth/registrations/$registrationId/verification-email",
                body = "{}",
            ),
        )
        if (response.statusCode == HTTP_OK) {
            codec.result(response.body)
        } else {
            VerificationEmailResendResult.Failed(
                reason = RegistrationFailureReason.SERVICE,
                retryable = response.statusCode == 429 || response.statusCode >= 500,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        VerificationEmailResendResult.Failed(RegistrationFailureReason.NETWORK, true)
    } catch (_: Exception) {
        VerificationEmailResendResult.Failed(RegistrationFailureReason.SERVICE, false)
    }

    private companion object { const val HTTP_OK = 200 }
}

class JsonVerificationEmailWireCodec : VerificationEmailWireCodec {
    override fun result(body: String): VerificationEmailResendResult {
        val json = JSONObject(body)
        return when (json.getString("status")) {
            "RESENT" -> VerificationEmailResendResult.Resent(json.getInt("retryAfterSeconds"))
            "COOLDOWN" -> VerificationEmailResendResult.Cooldown(json.getInt("retryAfterSeconds"))
            "NOT_AVAILABLE" -> VerificationEmailResendResult.NotAvailable
            else -> error("Unsupported verification email response")
        }
    }

    override fun status(body: String): RegistrationVerificationStatusResult =
        when (JSONObject(body).getString("status")) {
            "WAITING" -> RegistrationVerificationStatusResult.Waiting
            "VERIFIED" -> RegistrationVerificationStatusResult.Verified
            "NOT_AVAILABLE" -> RegistrationVerificationStatusResult.NotAvailable
            else -> error("Unsupported registration verification status")
        }
}
