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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Explicit flow ticks and memory callbacks only; never sends mail or reads a real registration. */
@OptIn(ExperimentalCoroutinesApi::class)
class EmailVerificationRequestLifecycleTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun repeatedStartKeepsOneMonitorAndStopCancelsItsCooperativeStatusRead() = runTest {
        var cancellations = 0
        val repository = Repository(read = {
            try { awaitCancellation() } finally { cancellations++ }
        })
        withModel(repository) { model, polls, _, _ ->
            val effects = collectEffects(model)
            repeat(3) { model.startStatusMonitoring() }
            runCurrent()
            assertEquals(1, repository.reads)
            assertEquals(1, polls.starts)
            model.stopStatusMonitoring()
            runCurrent()
            assertEquals(1, cancellations)
            assertTrue(polls.ticks.tryEmit(Unit))
            runCurrent()
            assertEquals(1, repository.reads)
            model.startStatusMonitoring()
            runCurrent()
            assertEquals(2, repository.reads)
            model.stopStatusMonitoring()
            runCurrent()
            assertEquals(2, cancellations)
            assertTrue(effects.isEmpty())
        }
    }

    @Test fun stoppedMonitorsNonCooperatingVerifiedCannotCompleteRegistration() = assertOldVerifiedIsIgnored(restart = false)

    @Test fun oldVerifiedAfterRestartCannotCancelOrReplaceTheNewMonitor() = assertOldVerifiedIsIgnored(restart = true)

    @Test fun changeEmailCancelsAPendingResendAndEmitsOnlyTheExistingReturn() = runTest {
        var cancellations = 0
        val repository = Repository(send = {
            try { awaitCancellation() } finally { cancellations++ }
        })
        withModel(repository) { model, _, _, _ ->
            val effects = collectEffects(model)
            model.onAction(EmailVerificationAction.ResendRequested)
            runCurrent()
            assertEquals(1, repository.resends)
            model.onAction(EmailVerificationAction.ChangeEmailRequested)
            runCurrent()
            assertEquals("Returning cannot leave the old resend alive", 1, cancellations)
            assertEquals(listOf(EmailVerificationEffect.OpenRegistration), effects)
        }
    }

    @Test fun lateResendAfterChangeEmailCannotStartACountdownOrChangeDepartedState() = runTest {
        var callback: Continuation<VerificationEmailResendResult>? = null
        try {
            withModel(Repository(send = { suspendCoroutine { callback = it } })) { model, _, countdown, _ ->
                val effects = collectEffects(model)
                model.onAction(EmailVerificationAction.ResendRequested)
                runCurrent()
                assertTrue(callback != null)
                model.onAction(EmailVerificationAction.ChangeEmailRequested)
                runCurrent()
                val departed = model.uiState.value
                checkNotNull(callback).also { callback = null }.resume(VerificationEmailResendResult.Resent(60))
                runCurrent()
                assertTrue("A departed resend cannot create a new cooldown timer", countdown.starts.isEmpty())
                assertEquals(departed, model.uiState.value)
                assertEquals(listOf(EmailVerificationEffect.OpenRegistration), effects)
            }
        } finally {
            callback?.also { callback = null }?.resume(VerificationEmailResendResult.NotAvailable)
            runCurrent()
        }
    }

    @Test fun currentLiveMonitorCompletesExactlyOnceWithoutAnAutomaticResend() = runTest {
        val repository = Repository(read = { RegistrationVerificationStatusResult.Verified })
        withModel(repository) { model, polls, _, _ ->
            val effects = collectEffects(model)
            model.startStatusMonitoring()
            runCurrent()
            repeat(3) { model.startStatusMonitoring(); polls.ticks.tryEmit(Unit) }
            runCurrent()
            assertEquals(EmailVerificationStatus.VERIFIED, model.uiState.value.status)
            assertEquals(listOf(EmailVerificationEffect.CompleteRegistration), effects)
            assertEquals(1, repository.reads)
            assertEquals(0, repository.resends)
        }
    }

    private fun assertOldVerifiedIsIgnored(restart: Boolean) = runTest {
        var callback: Continuation<RegistrationVerificationStatusResult>? = null
        var first = true
        val repository = Repository(read = {
            if (first) {
                first = false
                suspendCoroutine { callback = it }
            } else RegistrationVerificationStatusResult.Waiting
        })
        try {
            withModel(repository) { model, polls, _, _ ->
                val effects = collectEffects(model)
                model.startStatusMonitoring()
                runCurrent()
                assertTrue(callback != null)
                assertEquals(1, repository.reads)
                model.stopStatusMonitoring()
                if (restart) model.startStatusMonitoring()
                runCurrent()
                assertEquals(if (restart) 2 else 1, repository.reads)
                val paused = model.uiState.value
                checkNotNull(callback).also { callback = null }.resume(RegistrationVerificationStatusResult.Verified)
                runCurrent()
                // Observe the fresh monitor before asserting state, so this catches both failures.
                assertTrue(polls.ticks.tryEmit(Unit))
                runCurrent()
                if (restart) {
                    assertFalse("The old callback cannot cancel the new statusJob", 2 in polls.finished)
                    assertEquals("The new monitor must still process its own next tick", 3, repository.reads)
                } else assertEquals(1, repository.reads)
                assertEquals(paused, model.uiState.value)
                assertTrue("Only the active monitor may deliver CompleteRegistration", effects.isEmpty())
                assertEquals(0, repository.resends)
            }
        } finally {
            callback?.also { callback = null }?.resume(RegistrationVerificationStatusResult.Waiting)
            runCurrent()
        }
    }

    private suspend fun TestScope.withModel(repository: Repository,
        check: suspend (EmailVerificationViewModel, Polls, Countdown, ViewModelStore) -> Unit) {
        val polls = Polls()
        val countdown = Countdown()
        val store = ViewModelStore()
        val model = EmailVerificationViewModel(REGISTRATION, "verification-lifecycle@example.invalid", repository, countdown, polls)
        store.put("isolated-verification-entry", model)
        try { check(model, polls, countdown, store) } finally {
            model.stopStatusMonitoring()
            store.clear()
            runCurrent()
        }
    }

    private fun TestScope.collectEffects(model: EmailVerificationViewModel) = mutableListOf<EmailVerificationEffect>().also { effects ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.toList(effects) }
    }

    private class Polls : VerificationStatusPolling {
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var starts = 0
        val finished = mutableSetOf<Int>()
        override fun checks(): Flow<Unit> = flow {
            val id = ++starts
            try { emit(Unit); ticks.collect { emit(Unit) } } finally { finished += id }
        }
    }
    private class Countdown : VerificationCountdown {
        val starts = mutableListOf<Int>()
        override fun remainingSeconds(fromSeconds: Int): Flow<Int> {
            starts += fromSeconds
            return emptyFlow()
        }
    }
    private class Repository(
        private val read: suspend () -> RegistrationVerificationStatusResult = { RegistrationVerificationStatusResult.Waiting },
        private val send: suspend () -> VerificationEmailResendResult = { VerificationEmailResendResult.Resent(60) },
    ) : VerificationEmailRepository {
        var reads = 0
        var resends = 0
        override suspend fun status(registrationId: String): RegistrationVerificationStatusResult {
            assertEquals(REGISTRATION, registrationId)
            reads++
            return read()
        }
        override suspend fun resend(registrationId: String): VerificationEmailResendResult {
            assertEquals(REGISTRATION, registrationId)
            resends++
            return send()
        }
    }
    private companion object { const val REGISTRATION = "isolated-verification-registration" }
}
