package com.reallanguage.app.feature.auth.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.feature.auth.domain.PasswordRejectionReason
import com.reallanguage.app.feature.auth.domain.PasswordResetFailureReason
import com.reallanguage.app.feature.auth.domain.PasswordResetRepository
import com.reallanguage.app.feature.auth.domain.PasswordResetRequestResult
import com.reallanguage.app.feature.auth.domain.PasswordResetResult
import com.reallanguage.app.feature.auth.domain.PasswordResetVerificationResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

interface PasswordResetWireCodec {
    fun request(email: String, commandId: String): String
    fun requestResult(body: String): PasswordResetRequestResult
    fun verification(token: String): String
    fun verificationResult(body: String): PasswordResetVerificationResult
    fun completion(resetToken: String, newPassword: String, commandId: String): String
    fun completionResult(body: String): PasswordResetResult
}

class HttpPasswordResetRepository(
    private val transport: HttpTransport,
    private val codec: PasswordResetWireCodec,
) : PasswordResetRepository {
    override suspend fun request(email: String, commandId: String): PasswordResetRequestResult =
        execute(
            request = HttpRequest(
                method = "POST",
                path = "/api/v1/auth/password-resets",
                body = codec.request(email, commandId),
            ),
            onSuccess = codec::requestResult,
            onFailure = { reason, retryable -> PasswordResetRequestResult.Failed(reason, retryable) },
        )

    override suspend fun verify(
        challengeId: String,
        token: String,
    ): PasswordResetVerificationResult = execute(
        request = HttpRequest(
            method = "POST",
            path = "/api/v1/auth/password-resets/$challengeId/verification",
            body = codec.verification(token),
        ),
        onSuccess = codec::verificationResult,
        onFailure = { reason, retryable -> PasswordResetVerificationResult.Failed(reason, retryable) },
    )

    override suspend fun reset(
        resetToken: String,
        newPassword: String,
        commandId: String,
    ): PasswordResetResult = execute(
        request = HttpRequest(
            method = "POST",
            path = "/api/v1/auth/password-resets/completion",
            body = codec.completion(resetToken, newPassword, commandId),
        ),
        onSuccess = codec::completionResult,
        onFailure = { reason, retryable -> PasswordResetResult.Failed(reason, retryable) },
    )

    private suspend fun <T> execute(
        request: HttpRequest,
        onSuccess: (String) -> T,
        onFailure: (PasswordResetFailureReason, Boolean) -> T,
    ): T = try {
        val response = transport.execute(request)
        if (response.statusCode == HTTP_OK) {
            onSuccess(response.body)
        } else {
            onFailure(
                PasswordResetFailureReason.SERVICE,
                response.statusCode == 429 || response.statusCode >= 500,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        onFailure(PasswordResetFailureReason.NETWORK, true)
    } catch (_: Exception) {
        onFailure(PasswordResetFailureReason.SERVICE, false)
    }

    private companion object { const val HTTP_OK = 200 }
}

class JsonPasswordResetWireCodec : PasswordResetWireCodec {
    override fun request(email: String, commandId: String): String = JSONObject()
        .put("email", email)
        .put("commandId", commandId)
        .toString()

    override fun requestResult(body: String): PasswordResetRequestResult {
        val json = JSONObject(body)
        require(json.getString("status") == "SENT")
        return PasswordResetRequestResult.Sent(json.getString("challengeId"))
    }

    override fun verification(token: String): String = JSONObject()
        .put("bearer", token)
        .toString()

    override fun verificationResult(body: String): PasswordResetVerificationResult {
        val json = JSONObject(body)
        return when (json.getString("status")) {
            "VERIFIED" -> PasswordResetVerificationResult.Verified(json.getString("resetToken"))
            "INVALID_OR_EXPIRED" -> PasswordResetVerificationResult.InvalidLink
            else -> error("Unsupported password reset verification response")
        }
    }

    override fun completion(resetToken: String, newPassword: String, commandId: String): String =
        JSONObject()
            .put("resetToken", resetToken)
            .put("newPassword", newPassword)
            .put("commandId", commandId)
            .toString()

    override fun completionResult(body: String): PasswordResetResult {
        val json = JSONObject(body)
        return when (json.getString("status")) {
            "COMPLETED" -> PasswordResetResult.Completed(
                sessionEstablished = json.getBoolean("sessionEstablished"),
                initialSettingsComplete = json.getBoolean("initialSettingsComplete"),
            )
            "PASSWORD_REJECTED" -> PasswordResetResult.PasswordRejected(
                when (json.getString("reason")) {
                    "TOO_SHORT" -> PasswordRejectionReason.TOO_SHORT
                    "TOO_LONG" -> PasswordRejectionReason.TOO_LONG
                    "BLOCKED" -> PasswordRejectionReason.BLOCKED
                    else -> error("Unsupported password rejection")
                },
            )
            "INVALID_OR_EXPIRED" -> PasswordResetResult.Failed(
                PasswordResetFailureReason.SERVICE,
                retryable = false,
            )
            else -> error("Unsupported password reset completion response")
        }
    }
}
