package com.reallanguage.app.feature.auth.presentation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.auth.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmailVerificationStatusFailureTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun failedStatusIsVisibleWithoutDeclaringAnInvalidEmailOrSendingAnotherMail() = runTest {
        for (failure in RegistrationFailureReason.entries) {
            val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            var result: RegistrationVerificationStatusResult = RegistrationVerificationStatusResult.Failed(failure)
            val model = EmailVerificationViewModel("isolated-registration", "status@example.invalid",
                object : VerificationEmailRepository {
                    override suspend fun status(registrationId: String) = result
                    override suspend fun resend(registrationId: String): VerificationEmailResendResult = error("No mail is authorized by a status read")
                }, VerificationCountdown { emptyFlow() }, VerificationStatusPolling { ticks })
            val store = ViewModelStore().also { it.put("isolated", model) }
            try {
                model.startStatusMonitoring()
                runCurrent()
                ticks.tryEmit(Unit)
                runCurrent()
                assertEquals(if (failure == RegistrationFailureReason.NETWORK) AuthSubmitError.NETWORK else AuthSubmitError.SERVICE,
                    model.uiState.value.submitError)
                assertEquals(EmailVerificationStatus.WAITING, model.uiState.value.status)
                assertEquals("status@example.invalid", model.uiState.value.email)
                assertFalse(model.uiState.value.resending)
                result = RegistrationVerificationStatusResult.Waiting
                ticks.tryEmit(Unit)
                runCurrent()
                assertNull("A successful fresh status check may clear its own service error", model.uiState.value.submitError)
            } finally { store.clear(); runCurrent() }
        }
    }
}
