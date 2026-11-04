package com.reallanguage.app.feature.auth.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.auth.domain.RegistrationFailureReason
import com.reallanguage.app.feature.auth.domain.RegistrationVerificationStatusResult
import com.reallanguage.app.feature.auth.domain.VerificationEmailRepository
import com.reallanguage.app.feature.auth.domain.VerificationEmailResendResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmailVerificationViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `resend uses registration id and follows the server cooldown`() = runTest {
        val repository = FakeVerificationEmailRepository(
            result = VerificationEmailResendResult.Resent(retryAfterSeconds = 60),
        )
        val countdown = FakeVerificationCountdown()
        val viewModel = viewModel(repository, countdown)

        viewModel.onAction(EmailVerificationAction.ResendRequested)
        advanceUntilIdle()

        assertEquals(listOf("registration-1"), repository.registrationIds)
        assertEquals(EmailVerificationStatus.RESEND_COOLDOWN, viewModel.uiState.value.status)
        assertEquals(60, viewModel.uiState.value.resendCooldownSeconds)
        assertEquals(listOf(60), countdown.starts)

        countdown.ticks.emit(0)
        advanceUntilIdle()
        assertEquals(EmailVerificationStatus.WAITING, viewModel.uiState.value.status)
        assertNull(viewModel.uiState.value.resendCooldownSeconds)
    }

    @Test
    fun `submitting state prevents duplicate resend requests`() = runTest {
        val pending = CompletableDeferred<VerificationEmailResendResult>()
        val repository = FakeVerificationEmailRepository(pending = pending)
        val viewModel = viewModel(repository)

        viewModel.onAction(EmailVerificationAction.ResendRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.resending)

        viewModel.onAction(EmailVerificationAction.ResendRequested)
        advanceUntilIdle()
        assertEquals(1, repository.registrationIds.size)

        pending.complete(VerificationEmailResendResult.Cooldown(12))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.resending)
        assertEquals(12, viewModel.uiState.value.resendCooldownSeconds)
    }

    @Test
    fun `server cooldown starts the same visible countdown without another request`() = runTest {
        val countdown = FakeVerificationCountdown()
        val viewModel = viewModel(
            FakeVerificationEmailRepository(
                result = VerificationEmailResendResult.Cooldown(28),
            ),
            countdown,
        )

        viewModel.onAction(EmailVerificationAction.ResendRequested)
        advanceUntilIdle()

        assertEquals(EmailVerificationStatus.RESEND_COOLDOWN, viewModel.uiState.value.status)
        assertEquals(28, viewModel.uiState.value.resendCooldownSeconds)
        assertEquals(listOf(28), countdown.starts)
    }

    @Test
    fun `unavailable challenge exposes verification failure`() = runTest {
        val viewModel = viewModel(
            FakeVerificationEmailRepository(
                result = VerificationEmailResendResult.NotAvailable,
            ),
        )

        viewModel.onAction(EmailVerificationAction.ResendRequested)
        advanceUntilIdle()

        assertEquals(EmailVerificationStatus.FAILED, viewModel.uiState.value.status)
        assertFalse(viewModel.uiState.value.resending)
    }

    @Test
    fun `network failure preserves target email and remains retryable`() = runTest {
        val viewModel = viewModel(
            FakeVerificationEmailRepository(
                result = VerificationEmailResendResult.Failed(
                    reason = RegistrationFailureReason.NETWORK,
                    retryable = true,
                ),
            ),
        )

        viewModel.onAction(EmailVerificationAction.ResendRequested)
        advanceUntilIdle()

        assertEquals("learner@example.com", viewModel.uiState.value.email)
        assertEquals(AuthSubmitError.NETWORK, viewModel.uiState.value.submitError)
        assertFalse(viewModel.uiState.value.resending)
    }

    @Test
    fun `change email emits a single return-to-registration effect`() = runTest {
        val viewModel = viewModel()
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(EmailVerificationAction.ChangeEmailRequested)
        advanceUntilIdle()

        assertEquals(EmailVerificationEffect.OpenRegistration, effect.await())
        assertEquals("learner@example.com", viewModel.uiState.value.email)
    }

    @Test
    fun `verified status completes registration once`() = runTest {
        val polling = FakeVerificationStatusPolling()
        val viewModel = viewModel(
            repository = FakeVerificationEmailRepository(
                statusResult = RegistrationVerificationStatusResult.Verified,
            ),
            statusPolling = polling,
        )
        val effect = async { viewModel.effects.first() }

        viewModel.startStatusMonitoring()
        advanceUntilIdle()
        polling.ticks.emit(Unit)
        advanceUntilIdle()

        assertEquals(EmailVerificationStatus.VERIFIED, viewModel.uiState.value.status)
        assertEquals(EmailVerificationEffect.CompleteRegistration, effect.await())
    }

    private fun viewModel(
        repository: VerificationEmailRepository = FakeVerificationEmailRepository(),
        countdown: VerificationCountdown = FakeVerificationCountdown(),
        statusPolling: VerificationStatusPolling = FakeVerificationStatusPolling(),
    ) = EmailVerificationViewModel(
        registrationId = "registration-1",
        email = "learner@example.com",
        repository = repository,
        countdown = countdown,
        statusPolling = statusPolling,
    )
}

private class FakeVerificationEmailRepository(
    private val result: VerificationEmailResendResult =
        VerificationEmailResendResult.Resent(60),
    private val pending: CompletableDeferred<VerificationEmailResendResult>? = null,
    private val statusResult: RegistrationVerificationStatusResult =
        RegistrationVerificationStatusResult.Waiting,
) : VerificationEmailRepository {
    val registrationIds = mutableListOf<String>()

    override suspend fun resend(registrationId: String): VerificationEmailResendResult {
        registrationIds += registrationId
        return pending?.await() ?: result
    }

    override suspend fun status(registrationId: String): RegistrationVerificationStatusResult =
        statusResult
}

private class FakeVerificationCountdown : VerificationCountdown {
    val starts = mutableListOf<Int>()
    val ticks = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    override fun remainingSeconds(fromSeconds: Int) = ticks.also { starts += fromSeconds }
}

private class FakeVerificationStatusPolling : VerificationStatusPolling {
    val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override fun checks() = ticks
}
