package com.reallanguage.app.feature.auth.presentation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.auth.domain.RegistrationVerificationStatusResult
import com.reallanguage.app.feature.auth.domain.VerificationEmailRepository
import com.reallanguage.app.feature.auth.domain.VerificationEmailResendResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Connection policy only. No actual address, delivery, verification link or credential is used. */
@OptIn(ExperimentalCoroutinesApi::class)
class EmailVerificationConnectionTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun immediateGateRevocationRejectsLateVerifiedBeforeTheRouteRecomposes() = runTest {
        var available = true
        var callback: Continuation<RegistrationVerificationStatusResult>? = null
        val repository = Repository(read = { suspendCoroutine { callback = it } })
        val model = model(repository) { available }
        val store = ViewModelStore().also { it.put("verification", model) }
        try {
            model.startStatusMonitoring()
            runCurrent()
            available = false // intentionally no setConnectionBlocked / Compose frame yet
            checkNotNull(callback).also { callback = null }.resume(RegistrationVerificationStatusResult.Verified)
            runCurrent()
            assertNotEquals(EmailVerificationStatus.VERIFIED, model.uiState.value.status)
            model.onAction(EmailVerificationAction.ResendRequested)
            runCurrent()
            assertEquals(0, repository.sends)
        } finally {
            store.clear()
            callback?.resume(RegistrationVerificationStatusResult.Waiting)
            runCurrent()
        }
    }

    @Test fun blockedEntryStopsReadsAndRejectsResendUntilVerifiedRecovery() = runTest {
        val repository = Repository()
        val model = model(repository)
        val store = ViewModelStore().also { it.put("verification", model) }
        try {
            model.startStatusMonitoring()
            runCurrent()
            assertEquals(1, repository.reads)
            model.setConnectionBlocked(true)
            repeat(3) { model.startStatusMonitoring(); model.onAction(EmailVerificationAction.ResendRequested) }
            runCurrent()
            assertEquals(1, repository.reads)
            assertEquals(0, repository.sends)
            model.setConnectionBlocked(false)
            runCurrent()
            assertEquals("Availability alone must not auto-send mail", 0, repository.sends)
            repeat(3) { model.startStatusMonitoring() }
            runCurrent()
            assertEquals("The recovered entry may have one new monitor", 2, repository.reads)
        } finally { store.clear(); runCurrent() }
    }

    @Test fun interruptingAResendCancelsItReleasesBusyAndDoesNotReplayOnRecovery() = runTest {
        var cancellations = 0
        val repository = Repository(send = { try { awaitCancellation() } finally { cancellations++ } })
        val model = model(repository)
        val store = ViewModelStore().also { it.put("verification", model) }
        try {
            model.onAction(EmailVerificationAction.ResendRequested)
            runCurrent()
            assertTrue(model.uiState.value.resending)
            model.setConnectionBlocked(true)
            runCurrent()
            assertEquals(1, cancellations)
            assertFalse(model.uiState.value.resending)
            model.setConnectionBlocked(false)
            runCurrent()
            assertEquals(1, repository.sends)
            model.onAction(EmailVerificationAction.ResendRequested)
            runCurrent()
            assertEquals(2, repository.sends)
        } finally { store.clear(); runCurrent() }
    }

    @Test fun oldVerifiedResponseCannotFinishRegistrationBehindTheConnectionCover() = runTest {
        var callback: Continuation<RegistrationVerificationStatusResult>? = null
        val model = model(Repository(read = { suspendCoroutine { callback = it } }))
        val store = ViewModelStore().also { it.put("verification", model) }
        val effects = mutableListOf<EmailVerificationEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.toList(effects) }
        try {
            model.startStatusMonitoring()
            runCurrent()
            assertNotNull(callback)
            model.setConnectionBlocked(true)
            checkNotNull(callback).also { callback = null }.resume(RegistrationVerificationStatusResult.Verified)
            runCurrent()
            assertNotEquals(EmailVerificationStatus.VERIFIED, model.uiState.value.status)
            assertTrue(effects.isEmpty())
        } finally {
            store.clear()
            callback?.resume(RegistrationVerificationStatusResult.Waiting)
            runCurrent()
        }
    }

    @Test fun oldResendCannotBeginCooldownAfterRecoveryAndReplaceANewerRequest() = runTest {
        var callback: Continuation<VerificationEmailResendResult>? = null
        var first = true
        val repository = Repository(send = {
            if (first) { first = false; suspendCoroutine { callback = it } } else awaitCancellation()
        })
        val model = model(repository)
        val store = ViewModelStore().also { it.put("verification", model) }
        try {
            model.onAction(EmailVerificationAction.ResendRequested)
            runCurrent()
            model.setConnectionBlocked(true)
            model.setConnectionBlocked(false)
            model.onAction(EmailVerificationAction.ResendRequested)
            runCurrent()
            assertEquals(2, repository.sends)
            checkNotNull(callback).also { callback = null }.resume(VerificationEmailResendResult.Resent(60))
            runCurrent()
            assertTrue(model.uiState.value.resending)
            assertNull(model.uiState.value.resendCooldownSeconds)
        } finally {
            store.clear()
            callback?.resume(VerificationEmailResendResult.NotAvailable)
            runCurrent()
        }
    }

    private fun model(repository: Repository, available: () -> Boolean = { true }) = EmailVerificationViewModel(
        "isolated-verification", "test@example.invalid", repository,
        VerificationCountdown { emptyFlow() },
        VerificationStatusPolling { flow { emit(Unit); awaitCancellation() } },
        isConnectionAvailable = available,
    )

    private class Repository(
        val read: suspend () -> RegistrationVerificationStatusResult = { RegistrationVerificationStatusResult.Waiting },
        val send: suspend () -> VerificationEmailResendResult = { VerificationEmailResendResult.Resent(60) },
    ) : VerificationEmailRepository {
        var reads = 0
        var sends = 0
        override suspend fun status(registrationId: String): RegistrationVerificationStatusResult { reads++; return read() }
        override suspend fun resend(registrationId: String): VerificationEmailResendResult { sends++; return send() }
    }
}
