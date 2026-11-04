package com.reallanguage.app.feature.auth.presentation

import com.reallanguage.app.feature.auth.domain.PasswordResetRepository
import com.reallanguage.app.feature.auth.domain.PasswordRejectionReason
import com.reallanguage.app.feature.auth.domain.PasswordResetFailureReason
import com.reallanguage.app.feature.auth.domain.PasswordResetRequestResult
import com.reallanguage.app.feature.auth.domain.PasswordResetResult
import com.reallanguage.app.feature.auth.domain.PasswordResetVerificationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PasswordResetViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun showsOnlyCurrentStepAndUsesStableRequestCommand() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.onAction(PasswordResetAction.EmailChanged("learner@example.com"))
        viewModel.onAction(PasswordResetAction.SendRequested)
        advanceUntilIdle()
        assertEquals(PasswordResetStep.WAITING_VERIFICATION, viewModel.uiState.value.step)
        assertEquals("request-command", repository.requestCommandId)
        assertEquals("learner@example.com", repository.requestedEmail)
        assertEquals("challenge-1", viewModel.uiState.value.challengeId)
    }

    @Test fun acceptedVerificationMovesToNewPasswordWithoutStackingEarlierInputs() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        requestEmail(viewModel)
        viewModel.onAction(PasswordResetAction.VerificationLinkOpened("one-time-token"))
        advanceUntilIdle()
        assertEquals(PasswordResetStep.NEW_PASSWORD, viewModel.uiState.value.step)
        assertEquals("reset-token", viewModel.uiState.value.resetToken)
        assertEquals("one-time-token", repository.verificationToken)
    }

    @Test fun deepLinkCanStartVerificationAfterAppWasRecreated() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)

        viewModel.onAction(
            PasswordResetAction.VerificationLinkOpened(
                token = "one-time-token",
                challengeId = "challenge-from-link",
            ),
        )
        advanceUntilIdle()

        assertEquals(PasswordResetStep.NEW_PASSWORD, viewModel.uiState.value.step)
        assertEquals("challenge-from-link", repository.verificationChallengeId)
        assertEquals("one-time-token", repository.verificationToken)
    }

    @Test fun invalidLinkStaysAtVerificationAndCanRequestAReplacement() = runTest(dispatcher) {
        val repository = FakeRepository(
            verificationResult = PasswordResetVerificationResult.InvalidLink,
        )
        val viewModel = viewModel(repository)
        requestEmail(viewModel)
        viewModel.onAction(PasswordResetAction.VerificationLinkOpened("expired"))
        advanceUntilIdle()
        assertEquals(PasswordResetStep.WAITING_VERIFICATION, viewModel.uiState.value.step)
        assertEquals(PasswordResetError.INVALID_LINK, viewModel.uiState.value.error)
        repository.verificationResult = PasswordResetVerificationResult.Verified("replacement-token")
        viewModel.onAction(PasswordResetAction.SendRequested)
        advanceUntilIdle()
        assertEquals(2, repository.requestCount)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test fun passwordValidationPreventsServerCall() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        moveToNewPassword(viewModel)
        viewModel.onAction(PasswordResetAction.PasswordChanged("abc"))
        viewModel.onAction(PasswordResetAction.ResetRequested)
        advanceUntilIdle()
        assertEquals(PasswordResetError.PASSWORD_TOO_SHORT, viewModel.uiState.value.error)
        assertEquals(0, repository.resetCount)
    }

    @Test fun resetWithoutSessionReturnsToLogin() = runTest(dispatcher) {
        val repository = FakeRepository(
            resetResult = PasswordResetResult.Completed(sessionEstablished = false, initialSettingsComplete = false),
        )
        val viewModel = viewModel(repository)
        moveToNewPassword(viewModel)
        viewModel.onAction(PasswordResetAction.PasswordChanged("a-valid-password-value"))
        viewModel.onAction(PasswordResetAction.ResetRequested)
        advanceUntilIdle()
        assertEquals(PasswordResetStep.SUCCESS, viewModel.uiState.value.step)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(PasswordResetAction.CompleteRequested)
        advanceUntilIdle()
        assertEquals(PasswordResetEffect.OpenLogin, effect.await())
        assertEquals("reset-command", repository.resetCommandId)
    }

    @Test fun completedSessionRoutesByInitialSettingsState() = runTest(dispatcher) {
        val incomplete = viewModel(FakeRepository(
            resetResult = PasswordResetResult.Completed(true, false),
        ))
        completeReset(incomplete)
        val settingsEffect = async { incomplete.effects.first() }
        incomplete.onAction(PasswordResetAction.CompleteRequested)
        advanceUntilIdle()
        assertEquals(PasswordResetEffect.OpenInitialSettings, settingsEffect.await())

        val complete = viewModel(FakeRepository(
            resetResult = PasswordResetResult.Completed(true, true),
        ))
        completeReset(complete)
        val homeEffect = async { complete.effects.first() }
        complete.onAction(PasswordResetAction.CompleteRequested)
        advanceUntilIdle()
        assertEquals(PasswordResetEffect.OpenHome, homeEffect.await())
    }

    @Test fun failuresRemainOnCurrentStepAndAllowRetry() = runTest(dispatcher) {
        val repository = FakeRepository(
            requestResult = PasswordResetRequestResult.Failed(retryable = true),
        )
        val viewModel = viewModel(repository)
        viewModel.onAction(PasswordResetAction.EmailChanged("learner@example.com"))
        viewModel.onAction(PasswordResetAction.SendRequested)
        advanceUntilIdle()
        assertEquals(PasswordResetStep.EMAIL_CONFIRMATION, viewModel.uiState.value.step)
        assertEquals(PasswordResetError.SERVICE, viewModel.uiState.value.error)
        repository.requestResult = PasswordResetRequestResult.Sent("challenge-2")
        viewModel.onAction(PasswordResetAction.SendRequested)
        advanceUntilIdle()
        assertEquals(PasswordResetStep.WAITING_VERIFICATION, viewModel.uiState.value.step)
        assertFalse(viewModel.uiState.value.operationInProgress)
    }

    @Test fun invalidEmailDoesNotRequestAndBackReturnsToLogin() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.onAction(PasswordResetAction.EmailChanged("invalid"))
        viewModel.onAction(PasswordResetAction.SendRequested)
        assertEquals(PasswordResetError.INVALID_EMAIL, viewModel.uiState.value.error)
        assertEquals(0, repository.requestCount)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(PasswordResetAction.ReturnToLoginRequested)
        assertEquals(PasswordResetEffect.OpenLogin, effect.await())
    }

    @Test fun waitingVerificationCanReturnToEmailWithoutLosingTheAddress() = runTest(dispatcher) {
        val viewModel = viewModel(FakeRepository())
        requestEmail(viewModel)

        viewModel.onAction(PasswordResetAction.ChangeEmailRequested)

        assertEquals(PasswordResetStep.EMAIL_CONFIRMATION, viewModel.uiState.value.step)
        assertEquals("learner@example.com", viewModel.uiState.value.email)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test fun verificationServiceFailureKeepsWaitingStep() = runTest(dispatcher) {
        val repository = FakeRepository(
            verificationResult = PasswordResetVerificationResult.Failed(
                PasswordResetFailureReason.NETWORK,
                retryable = true,
            ),
        )
        val viewModel = viewModel(repository)
        requestEmail(viewModel)
        viewModel.onAction(PasswordResetAction.VerificationLinkOpened("valid-shape"))
        advanceUntilIdle()
        assertEquals(PasswordResetStep.WAITING_VERIFICATION, viewModel.uiState.value.step)
        assertEquals(PasswordResetError.NETWORK, viewModel.uiState.value.error)
    }

    @Test fun serverPasswordRejectionAndResetFailureRemainAtNewPassword() = runTest(dispatcher) {
        val repository = FakeRepository(
            resetResult = PasswordResetResult.PasswordRejected(PasswordRejectionReason.BLOCKED),
        )
        val viewModel = viewModel(repository)
        moveToNewPassword(viewModel)
        viewModel.onAction(PasswordResetAction.PasswordChanged("a-valid-password-value"))
        viewModel.onAction(PasswordResetAction.ResetRequested)
        advanceUntilIdle()
        assertEquals(PasswordResetError.PASSWORD_BLOCKED, viewModel.uiState.value.error)
        repository.resetResult = PasswordResetResult.Failed(
            PasswordResetFailureReason.SERVICE,
            retryable = true,
        )
        viewModel.onAction(PasswordResetAction.ResetRequested)
        advanceUntilIdle()
        assertEquals(PasswordResetStep.NEW_PASSWORD, viewModel.uiState.value.step)
        assertEquals(PasswordResetError.SERVICE, viewModel.uiState.value.error)
    }

    @Test fun actionsFromWrongStepAreIgnoredAndOverlongPasswordIsRejected() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.onAction(PasswordResetAction.PasswordChanged("not-yet"))
        viewModel.onAction(PasswordResetAction.CompleteRequested)
        assertEquals("", viewModel.uiState.value.newPassword)
        requestEmail(viewModel)
        viewModel.onAction(PasswordResetAction.EmailChanged("changed@example.com"))
        assertEquals("learner@example.com", viewModel.uiState.value.email)
        viewModel.onAction(PasswordResetAction.VerificationLinkOpened("valid-token"))
        advanceUntilIdle()
        viewModel.onAction(PasswordResetAction.PasswordChanged("x".repeat(65)))
        viewModel.onAction(PasswordResetAction.ResetRequested)
        assertEquals(PasswordResetError.PASSWORD_TOO_LONG, viewModel.uiState.value.error)
        assertEquals(0, repository.resetCount)
    }

    private fun viewModel(repository: FakeRepository) = PasswordResetViewModel(
        repository = repository,
        emailValidator = { it.contains('@') && it.substringAfter('@').contains('.') },
        requestCommandId = "request-command",
        resetCommandId = "reset-command",
    )

    private suspend fun TestScope.requestEmail(viewModel: PasswordResetViewModel) {
        viewModel.onAction(PasswordResetAction.EmailChanged("learner@example.com"))
        viewModel.onAction(PasswordResetAction.SendRequested)
        advanceUntilIdle()
    }

    private suspend fun TestScope.moveToNewPassword(viewModel: PasswordResetViewModel) {
        requestEmail(viewModel)
        viewModel.onAction(PasswordResetAction.VerificationLinkOpened("valid-token"))
        advanceUntilIdle()
    }

    private suspend fun TestScope.completeReset(viewModel: PasswordResetViewModel) {
        moveToNewPassword(viewModel)
        viewModel.onAction(PasswordResetAction.PasswordChanged("a-valid-password-value"))
        viewModel.onAction(PasswordResetAction.ResetRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.step == PasswordResetStep.SUCCESS)
    }

    private class FakeRepository(
        var requestResult: PasswordResetRequestResult = PasswordResetRequestResult.Sent("challenge-1"),
        var verificationResult: PasswordResetVerificationResult = PasswordResetVerificationResult.Verified("reset-token"),
        var resetResult: PasswordResetResult = PasswordResetResult.Completed(false, false),
    ) : PasswordResetRepository {
        var requestedEmail: String? = null
        var requestCommandId: String? = null
        var verificationToken: String? = null
        var verificationChallengeId: String? = null
        var resetCommandId: String? = null
        var requestCount = 0
        var resetCount = 0

        override suspend fun request(email: String, commandId: String): PasswordResetRequestResult {
            requestCount += 1
            requestedEmail = email
            requestCommandId = commandId
            return requestResult
        }

        override suspend fun verify(challengeId: String, token: String): PasswordResetVerificationResult {
            verificationChallengeId = challengeId
            verificationToken = token
            return verificationResult
        }

        override suspend fun reset(resetToken: String, newPassword: String, commandId: String): PasswordResetResult {
            resetCount += 1
            resetCommandId = commandId
            return resetResult
        }
    }
}
