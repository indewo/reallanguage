package com.reallanguage.app.feature.auth.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.feature.auth.domain.PasswordRejectionReason
import com.reallanguage.app.feature.auth.domain.RegistrationCommand
import com.reallanguage.app.feature.auth.domain.RegistrationFailureReason
import com.reallanguage.app.feature.auth.domain.RegistrationRepository
import com.reallanguage.app.feature.auth.domain.RegistrationStartResult
import java.io.IOException
import org.json.JSONObject

interface RegistrationWireCodec {
    fun request(command: RegistrationCommand): String
    fun result(body: String): RegistrationStartResult
}

class HttpRegistrationRepository(
    private val transport: HttpTransport,
    private val codec: RegistrationWireCodec,
) : RegistrationRepository {
    override suspend fun startRegistration(command: RegistrationCommand): RegistrationStartResult =
        try {
            val response = transport.execute(
                HttpRequest(
                    method = "POST",
                    path = "/api/v1/auth/registrations",
                    body = codec.request(command),
                ),
            )
            if (response.statusCode == HTTP_OK) {
                codec.result(response.body)
            } else {
                RegistrationStartResult.Failed(
                    reason = RegistrationFailureReason.SERVICE,
                    retryable = response.statusCode == 429 || response.statusCode >= 500,
                )
            }
        } catch (_: IOException) {
            RegistrationStartResult.Failed(
                reason = RegistrationFailureReason.NETWORK,
                retryable = true,
            )
        } catch (_: Exception) {
            RegistrationStartResult.Failed(
                reason = RegistrationFailureReason.SERVICE,
                retryable = false,
            )
        }

    private companion object {
        const val HTTP_OK = 200
    }
}

class JsonRegistrationWireCodec : RegistrationWireCodec {
    override fun request(command: RegistrationCommand): String = JSONObject()
        .put("email", command.email)
        .put("password", command.password)
        .put("birthYearMonth", command.birthYearMonth)
        .put("acceptedTerms", command.acceptedTerms)
        .put("acceptedPrivacyPolicy", command.acceptedPrivacyPolicy)
        .toString()

    override fun result(body: String): RegistrationStartResult {
        val json = JSONObject(body)
        return when (json.getString("status")) {
            "AGE_INELIGIBLE" -> RegistrationStartResult.AgeIneligible
            "PASSWORD_REJECTED" -> RegistrationStartResult.PasswordRejected(
                when (json.getString("reason")) {
                    "TOO_SHORT" -> PasswordRejectionReason.TOO_SHORT
                    "TOO_LONG" -> PasswordRejectionReason.TOO_LONG
                    "BLOCKED" -> PasswordRejectionReason.BLOCKED
                    else -> error("Unsupported password rejection")
                },
            )
            "EMAIL_VERIFICATION_REQUIRED" ->
                RegistrationStartResult.EmailVerificationRequired(
                    json.getString("registrationId"),
                )
            else -> error("Unsupported registration response")
        }
    }
}
