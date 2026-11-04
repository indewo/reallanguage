package com.reallanguage.app.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.auth.domain.RegistrationFailureReason
import com.reallanguage.app.feature.auth.domain.RegistrationVerificationStatusResult
import com.reallanguage.app.feature.auth.domain.VerificationEmailRepository
import com.reallanguage.app.feature.auth.domain.VerificationEmailResendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun interface VerificationCountdown {
    fun remainingSeconds(fromSeconds: Int): Flow<Int>
}

fun interface VerificationStatusPolling {
    fun checks(): Flow<Unit>
}

class RealVerificationStatusPolling : VerificationStatusPolling {
    override fun checks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(2_000)
        }
    }
}

class RealVerificationCountdown : VerificationCountdown {
    override fun remainingSeconds(fromSeconds: Int): Flow<Int> = flow {
        for (remaining in fromSeconds downTo 0) {
            emit(remaining)
            if (remaining > 0) delay(1_000)
        }
    }
}

class EmailVerificationViewModel(
    registrationId: String,
    email: String,
    private val repository: VerificationEmailRepository,
    private val countdown: VerificationCountdown,
    private val statusPolling: VerificationStatusPolling,
    private val isConnectionAvailable: () -> Boolean = { true },
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        EmailVerificationUiState(
            email = email,
            status = EmailVerificationStatus.WAITING,
        ),
    )
    val uiState: StateFlow<EmailVerificationUiState> = mutableUiState.asStateFlow()

    private val effectChannel = Channel<EmailVerificationEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private val registrationId = registrationId
    private var countdownJob: Job? = null
    private var statusJob: Job? = null
    private var resendJob: Job? = null
    private var monitorGeneration = 0L
    private var countdownGeneration = 0L
    private var departed = false
    private var connectionBlocked = false
    private var resendGeneration = 0L
    private var hasStatusError = false
    private fun blocked() = connectionBlocked || !isConnectionAvailable()

    fun setConnectionBlocked(blocked: Boolean) {
        if (departed || connectionBlocked == blocked) return
        connectionBlocked = blocked
        if (blocked) {
            stopStatusMonitoring()
            resendGeneration++
            resendJob?.cancel()
            resendJob = null
            mutableUiState.update { it.copy(resending = false) }
        }
        // The live route may restart reads after verified recovery; sending is always explicit.
    }

    fun startStatusMonitoring() {
        if (departed || blocked() || statusJob?.isActive == true || mutableUiState.value.status == EmailVerificationStatus.VERIFIED) return
        val generation = ++monitorGeneration
        statusJob = viewModelScope.launch {
            statusPolling.checks().collect {
                if (departed || blocked() || generation != monitorGeneration) return@collect
                val result = repository.status(registrationId)
                currentCoroutineContext().ensureActive()
                if (departed || blocked() || generation != monitorGeneration) return@collect
                when (result) {
                    RegistrationVerificationStatusResult.Waiting -> if (hasStatusError) {
                        hasStatusError = false
                        mutableUiState.update { it.copy(submitError = null) }
                    }
                    RegistrationVerificationStatusResult.Verified -> {
                        countdownGeneration++
                        countdownJob?.cancel()
                        resendJob?.cancel()
                        mutableUiState.update { state ->
                            state.copy(status = EmailVerificationStatus.VERIFIED, submitError = null)
                        }
                        effectChannel.send(EmailVerificationEffect.CompleteRegistration)
                        stopStatusMonitoring()
                    }
                    RegistrationVerificationStatusResult.NotAvailable -> {
                        mutableUiState.update { state ->
                            state.copy(status = EmailVerificationStatus.FAILED)
                        }
                        stopStatusMonitoring()
                    }
                    is RegistrationVerificationStatusResult.Failed -> {
                        hasStatusError = true
                        mutableUiState.update { it.copy(submitError = when (result.reason) {
                            RegistrationFailureReason.NETWORK -> AuthSubmitError.NETWORK
                            RegistrationFailureReason.SERVICE -> AuthSubmitError.SERVICE
                        }) }
                    }
                }
            }
        }
    }

    fun stopStatusMonitoring() {
        monitorGeneration++
        statusJob?.cancel()
        statusJob = null
    }

    fun onAction(action: EmailVerificationAction) {
        if (departed) return
        when (action) {
            EmailVerificationAction.ResendRequested -> resend()
            EmailVerificationAction.ChangeEmailRequested -> {
                depart()
                effectChannel.trySend(EmailVerificationEffect.OpenRegistration)
            }
        }
    }

    private fun resend() {
        val current = mutableUiState.value
        if (blocked() || current.resending || current.status in setOf(EmailVerificationStatus.RESEND_COOLDOWN, EmailVerificationStatus.VERIFIED)) return
        mutableUiState.update { it.copy(resending = true, submitError = null) }
        val generation = ++resendGeneration
        resendJob = viewModelScope.launch {
            if (departed || blocked() || generation != resendGeneration) return@launch
            val result = try {
                repository.resend(registrationId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                VerificationEmailResendResult.Failed(
                    reason = RegistrationFailureReason.SERVICE,
                    retryable = false,
                )
            }
            currentCoroutineContext().ensureActive()
            if (departed || blocked() || generation != resendGeneration || mutableUiState.value.status == EmailVerificationStatus.VERIFIED) return@launch
            handleResult(result)
        }
    }

    private fun handleResult(result: VerificationEmailResendResult) {
        hasStatusError = false
        when (result) {
            is VerificationEmailResendResult.Resent -> startCountdown(result.retryAfterSeconds)
            is VerificationEmailResendResult.Cooldown -> startCountdown(result.retryAfterSeconds)
            VerificationEmailResendResult.NotAvailable -> mutableUiState.update {
                it.copy(
                    status = EmailVerificationStatus.FAILED,
                    resending = false,
                    resendCooldownSeconds = null,
                )
            }
            is VerificationEmailResendResult.Failed -> mutableUiState.update {
                it.copy(
                    resending = false,
                    submitError = when (result.reason) {
                        RegistrationFailureReason.NETWORK -> AuthSubmitError.NETWORK
                        RegistrationFailureReason.SERVICE -> AuthSubmitError.SERVICE
                    },
                )
            }
        }
    }

    private fun startCountdown(seconds: Int) {
        require(seconds > 0)
        countdownJob?.cancel()
        val generation = ++countdownGeneration
        mutableUiState.update {
            it.copy(
                status = EmailVerificationStatus.RESEND_COOLDOWN,
                resendCooldownSeconds = seconds,
                resending = false,
                submitError = null,
            )
        }
        countdownJob = viewModelScope.launch {
            countdown.remainingSeconds(seconds).collect { remaining ->
                if (departed || generation != countdownGeneration || mutableUiState.value.status == EmailVerificationStatus.VERIFIED) return@collect
                mutableUiState.update { state ->
                    if (remaining > 0) {
                        state.copy(
                            status = EmailVerificationStatus.RESEND_COOLDOWN,
                            resendCooldownSeconds = remaining,
                        )
                    } else {
                        state.copy(
                            status = EmailVerificationStatus.WAITING,
                            resendCooldownSeconds = null,
                        )
                    }
                }
            }
        }
    }

    private fun depart() {
        departed = true
        stopStatusMonitoring()
        countdownGeneration++
        countdownJob?.cancel()
        resendJob?.cancel()
    }

    override fun onCleared() { depart(); super.onCleared() }
}
