package com.reallanguage.app.feature.auth.data

import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.feature.auth.domain.PasswordResetRequestResult
import com.reallanguage.app.feature.auth.domain.PasswordResetResult
import com.reallanguage.app.feature.auth.domain.PasswordResetVerificationResult
import com.reallanguage.app.feature.auth.domain.RegistrationVerificationStatusResult
import com.reallanguage.app.feature.auth.domain.VerificationEmailResendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production HTTP repositories with string codecs and an isolated transport; no actual mail/HTTP. */
class AuthRecoveryHttpCancellationTest {
    @Test fun passwordResetEmailCancellationIsNotAnOrdinaryFailure() = assertCancellation(Operation.RESET_EMAIL)
    @Test fun passwordResetVerificationCancellationIsNotAnOrdinaryFailure() = assertCancellation(Operation.RESET_VERIFY)
    @Test fun passwordResetCompletionCancellationIsNotAnOrdinaryFailure() = assertCancellation(Operation.RESET_COMPLETE)
    @Test fun registrationStatusCancellationIsNotAnOrdinaryFailure() = assertCancellation(Operation.REGISTRATION_STATUS)
    @Test fun verificationEmailResendCancellationIsNotAnOrdinaryFailure() = assertCancellation(Operation.REGISTRATION_RESEND)

    @Test fun allExistingSuccessfulOperationsStillDecodeTheirOwnResultOnce() = runTest {
        var requests = 0
        var decodes = 0
        val results = Operation.entries.map { operation ->
            invoke(operation, HttpTransport { requests++; HttpResponse(200, "isolated-success") }) { decodes++ }
        }
        assertEquals(listOf(
            PasswordResetRequestResult.Sent("isolated-challenge"),
            PasswordResetVerificationResult.Verified("ISOLATED_RESET_TOKEN"),
            PasswordResetResult.Completed(false, false),
            RegistrationVerificationStatusResult.Waiting,
            VerificationEmailResendResult.Cooldown(60),
        ), results)
        assertEquals(5, requests)
        assertEquals(5, decodes)
    }

    private fun assertCancellation(operation: Operation) = runTest {
        var requests = 0
        var decodes = 0
        val cancellation = CancellationException("isolated auth recovery $operation cancelled")
        val actual = runCatching {
            invoke(operation, HttpTransport { requests++; throw cancellation }) { decodes++ }
        }.exceptionOrNull()
        assertTrue("$operation must propagate cancellation rather than return Failed", actual is CancellationException)
        assertTrue(generateSequence(actual) { it.cause }.take(16).any {
            it is CancellationException && it.message == cancellation.message
        })
        assertEquals(1, requests)
        assertEquals(0, decodes)
    }

    private suspend fun invoke(operation: Operation, transport: HttpTransport, decoded: () -> Unit): Any {
        val reset = HttpPasswordResetRepository(transport, object : PasswordResetWireCodec {
            override fun request(email: String, commandId: String) = "isolated-email-request"
            override fun verification(token: String) = "isolated-link-verification"
            override fun completion(resetToken: String, newPassword: String, commandId: String) = "isolated-completion"
            override fun requestResult(body: String): PasswordResetRequestResult {
                decoded(); return PasswordResetRequestResult.Sent("isolated-challenge")
            }
            override fun verificationResult(body: String): PasswordResetVerificationResult {
                decoded(); return PasswordResetVerificationResult.Verified("ISOLATED_RESET_TOKEN")
            }
            override fun completionResult(body: String): PasswordResetResult {
                decoded(); return PasswordResetResult.Completed(false, false)
            }
        })
        val verification = HttpVerificationEmailRepository(transport, object : VerificationEmailWireCodec {
            override fun result(body: String): VerificationEmailResendResult {
                decoded(); return VerificationEmailResendResult.Cooldown(60)
            }
            override fun status(body: String): RegistrationVerificationStatusResult {
                decoded(); return RegistrationVerificationStatusResult.Waiting
            }
        })
        return when (operation) {
            Operation.RESET_EMAIL -> reset.request("http-cancel@example.invalid", "isolated-request-command")
            Operation.RESET_VERIFY -> reset.verify("isolated-challenge", "ISOLATED_LINK_BEARER")
            Operation.RESET_COMPLETE -> reset.reset("ISOLATED_RESET_TOKEN", "ISOLATED_TEST_PASSWORD", "isolated-reset-command")
            Operation.REGISTRATION_STATUS -> verification.status("isolated-registration")
            Operation.REGISTRATION_RESEND -> verification.resend("isolated-registration")
        }
    }

    private enum class Operation { RESET_EMAIL, RESET_VERIFY, RESET_COMPLETE, REGISTRATION_STATUS, REGISTRATION_RESEND }
}
