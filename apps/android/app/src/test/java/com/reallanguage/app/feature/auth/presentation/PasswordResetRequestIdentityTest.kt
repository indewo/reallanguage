package com.reallanguage.app.feature.auth.presentation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.auth.domain.PasswordResetFailureReason
import com.reallanguage.app.feature.auth.domain.PasswordResetRepository
import com.reallanguage.app.feature.auth.domain.PasswordResetRequestResult
import com.reallanguage.app.feature.auth.domain.PasswordResetResult
import com.reallanguage.app.feature.auth.domain.PasswordResetVerificationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Existing AC-06 send/change-email actions, not new retry UI or real mail delivery.
 * The tiny repository models the existing server's command-id replay: one command returns
 * its original challenge even if a later caller supplies another email. */
@OptIn(ExperimentalCoroutinesApi::class)
class PasswordResetRequestIdentityTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun explicitResendAfterSentUsesANewCommandAndReceivesANewChallenge() = runTest {
        val repository = Repository()
        withModel(repository) { model ->
            startEmail(model)
            val firstChallenge = model.uiState.value.challengeId
            assertEquals(PasswordResetStep.WAITING_VERIFICATION, model.uiState.value.step)
            assertEquals(INITIAL_COMMAND, repository.calls.single().commandId)

            model.onAction(PasswordResetAction.SendRequested)
            runCurrent()

            assertEquals(2, repository.calls.size)
            assertEquals(listOf(EMAIL, EMAIL), repository.calls.map { it.email })
            assertNotEquals("A new email send cannot replay the acknowledged request",
                repository.calls[0].commandId, repository.calls[1].commandId)
            assertNotEquals(firstChallenge, model.uiState.value.challengeId)
            assertEquals(2, repository.deliveries.size)
            assertFalse(model.uiState.value.operationInProgress)
        }
    }

    @Test fun changingTheEmailNeverReusesACommandBoundToThePreviousEmail() = runTest {
        for (firstReplyFailed in listOf(false, true)) {
            val repository = Repository(failedReplies = if (firstReplyFailed) setOf(1) else emptySet())
            withModel(repository) { model ->
                startEmail(model)
                if (!firstReplyFailed) model.onAction(PasswordResetAction.ChangeEmailRequested)
                // Failed initial requests already remain in the editable EMAIL_CONFIRMATION step.
                assertEquals(PasswordResetStep.EMAIL_CONFIRMATION, model.uiState.value.step)
                model.onAction(PasswordResetAction.EmailChanged(OTHER_EMAIL))
                model.onAction(PasswordResetAction.SendRequested)
                runCurrent()

                assertEquals(2, repository.calls.size)
                assertEquals(listOf(EMAIL, OTHER_EMAIL), repository.calls.map { it.email })
                assertNotEquals("A command must not point at two email inputs; failed=$firstReplyFailed",
                    repository.calls[0].commandId, repository.calls[1].commandId)
                assertEquals(PasswordResetStep.WAITING_VERIFICATION, model.uiState.value.step)
                val delivery = repository.deliveries.getValue(repository.calls[1].commandId)
                assertEquals(OTHER_EMAIL, delivery.email)
                assertEquals(delivery.challengeId, model.uiState.value.challengeId)
            }
        }
    }

    @Test fun retryAfterAnUnconfirmedInitialSendRetainsItsOriginalCommand() = runTest {
        val repository = Repository(failedReplies = setOf(1))
        withModel(repository) { model ->
            startEmail(model)
            assertEquals(PasswordResetStep.EMAIL_CONFIRMATION, model.uiState.value.step)
            assertEquals(PasswordResetError.NETWORK, model.uiState.value.error)
            assertFalse(model.uiState.value.operationInProgress)
            assertEquals(1, repository.deliveries.size)

            model.onAction(PasswordResetAction.SendRequested)
            runCurrent()

            assertEquals(2, repository.calls.size)
            assertEquals(repository.calls[0], repository.calls[1])
            assertEquals(INITIAL_COMMAND, repository.calls[1].commandId)
            assertEquals(1, repository.deliveries.size)
            assertEquals(repository.deliveries.getValue(INITIAL_COMMAND).challengeId,
                model.uiState.value.challengeId)
            assertEquals(PasswordResetStep.WAITING_VERIFICATION, model.uiState.value.step)
            assertEquals(null, model.uiState.value.error)
        }
    }

    @Test fun retryOfAFailedExplicitResendRetainsThatResendsIdNotTheFirstSendsId() = runTest {
        val repository = Repository(failedReplies = setOf(2))
        withModel(repository) { model ->
            startEmail(model)
            model.onAction(PasswordResetAction.SendRequested)
            runCurrent()
            assertEquals(PasswordResetStep.WAITING_VERIFICATION, model.uiState.value.step)
            assertEquals(PasswordResetError.NETWORK, model.uiState.value.error)
            assertFalse(model.uiState.value.operationInProgress)

            model.onAction(PasswordResetAction.SendRequested)
            runCurrent()

            assertEquals(3, repository.calls.size)
            assertNotEquals(repository.calls[0].commandId, repository.calls[1].commandId)
            assertEquals("WAITING is also retained after a failed resend, not proof of a new operation",
                repository.calls[1], repository.calls[2])
            assertEquals(2, repository.deliveries.size)
            assertEquals(repository.deliveries.getValue(repository.calls[2].commandId).challengeId,
                model.uiState.value.challengeId)
            assertEquals(null, model.uiState.value.error)
        }
    }

    @Test fun repeatedSendBeforeDispatchAndWhilePendingIssuesOnlyOneRequest() = runTest {
        val reply = CompletableDeferred<Unit>()
        val repository = Repository(beforeReply = { reply.await() })
        withModel(repository) { model ->
            model.onAction(PasswordResetAction.EmailChanged(EMAIL))
            repeat(4) { model.onAction(PasswordResetAction.SendRequested) }
            assertTrue(model.uiState.value.operationInProgress)
            assertTrue(repository.calls.isEmpty())
            runCurrent()
            assertEquals(1, repository.calls.size)
            repeat(4) { model.onAction(PasswordResetAction.SendRequested) }
            runCurrent()
            assertEquals(1, repository.calls.size)

            reply.complete(Unit)
            runCurrent()
            assertEquals(1, repository.calls.size)
            assertEquals(INITIAL_COMMAND, repository.calls.single().commandId)
            assertEquals(1, repository.deliveries.size)
            assertEquals(PasswordResetStep.WAITING_VERIFICATION, model.uiState.value.step)
            assertFalse(model.uiState.value.operationInProgress)
        }
    }

    private fun TestScope.startEmail(model: PasswordResetViewModel) {
        model.onAction(PasswordResetAction.EmailChanged(EMAIL))
        model.onAction(PasswordResetAction.SendRequested)
        runCurrent()
    }

    private suspend fun TestScope.withModel(repository: Repository,
        assertions: suspend (PasswordResetViewModel) -> Unit) {
        val store = ViewModelStore()
        val model = PasswordResetViewModel(repository, { it.endsWith("@example.invalid") },
            INITIAL_COMMAND, "isolated-reset-completion-command")
        store.put("isolated-reset-identity", model)
        try { assertions(model) } finally { store.clear(); runCurrent() }
    }

    private data class Request(val email: String, val commandId: String)
    private data class Delivery(val email: String, val challengeId: String)
    private class Repository(
        private val failedReplies: Set<Int> = emptySet(),
        private val beforeReply: suspend () -> Unit = {},
    ) : PasswordResetRepository {
        val calls = mutableListOf<Request>()
        val deliveries = linkedMapOf<String, Delivery>()
        override suspend fun request(email: String, commandId: String): PasswordResetRequestResult {
            assertTrue("A submitted command must have a nonblank identity", commandId.isNotBlank())
            calls += Request(email, commandId)
            val ordinal = calls.size
            beforeReply()
            val delivery = deliveries.getOrPut(commandId) {
                Delivery(email, "isolated-challenge-${deliveries.size + 1}")
            }
            // A lost response cannot prove that the server did not already accept the send.
            return if (ordinal in failedReplies) PasswordResetRequestResult.Failed(
                PasswordResetFailureReason.NETWORK, retryable = true,
            ) else PasswordResetRequestResult.Sent(delivery.challengeId)
        }
        override suspend fun verify(challengeId: String, token: String): PasswordResetVerificationResult =
            error("Sending an email must not verify it")
        override suspend fun reset(resetToken: String, newPassword: String, commandId: String): PasswordResetResult =
            error("Sending an email must not complete a password reset")
    }

    private companion object {
        const val EMAIL = "request-identity@example.invalid"
        const val OTHER_EMAIL = "changed-identity@example.invalid"
        const val INITIAL_COMMAND = "isolated-initial-request-command"
    }
}
