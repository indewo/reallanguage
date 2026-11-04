package com.reallanguage.app.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.auth.domain.EmailSyntaxValidator
import com.reallanguage.app.feature.auth.domain.PasswordRejectionReason
import com.reallanguage.app.feature.auth.domain.PasswordResetFailureReason
import com.reallanguage.app.feature.auth.domain.PasswordResetRepository
import com.reallanguage.app.feature.auth.domain.PasswordResetRequestResult
import com.reallanguage.app.feature.auth.domain.PasswordResetResult
import com.reallanguage.app.feature.auth.domain.PasswordResetVerificationResult
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PasswordResetViewModel(
    private val repository: PasswordResetRepository,
    private val emailValidator: EmailSyntaxValidator,
    private val requestCommandId: String,
    private val resetCommandId: String,
) : ViewModel() {
    private var currentRequestId = requestCommandId
    private var requestEmail: String? = null
    private var requestAcknowledged = false
    private var departed = false
    private var operationGeneration = 0L
    private var operationJob: Job? = null
    private val mutableUiState = MutableStateFlow(PasswordResetUiState())
    val uiState: StateFlow<PasswordResetUiState> = mutableUiState.asStateFlow()

    private val effectChannel = Channel<PasswordResetEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: PasswordResetAction) {
        if (departed) return
        when (action) {
            is PasswordResetAction.EmailChanged -> updateEmail(action.value)
            PasswordResetAction.SendRequested -> requestReset()
            PasswordResetAction.ChangeEmailRequested -> changeEmail()
            is PasswordResetAction.VerificationLinkOpened -> verify(
                action.token,
                action.challengeId,
            )
            is PasswordResetAction.PasswordChanged -> updatePassword(action.value)
            PasswordResetAction.ResetRequested -> resetPassword()
            PasswordResetAction.CompleteRequested -> complete()
            PasswordResetAction.ReturnToLoginRequested -> {
                depart()
                emit(PasswordResetEffect.OpenLogin)
            }
        }
    }

    private fun changeEmail() {
        if (mutableUiState.value.step != PasswordResetStep.WAITING_VERIFICATION) return
        cancelOperation()
        mutableUiState.update {
            it.copy(
                step = PasswordResetStep.EMAIL_CONFIRMATION,
                operationInProgress = false,
                error = null,
            )
        }
    }

    private fun updateEmail(value: String) {
        if (mutableUiState.value.step != PasswordResetStep.EMAIL_CONFIRMATION) return
        mutableUiState.update { it.copy(email = value, error = null) }
    }

    private fun updatePassword(value: String) {
        if (mutableUiState.value.step != PasswordResetStep.NEW_PASSWORD) return
        mutableUiState.update { it.copy(newPassword = value, error = null) }
    }

    private fun requestReset() {
        val state = mutableUiState.value
        if (state.step !in setOf(
                PasswordResetStep.EMAIL_CONFIRMATION,
                PasswordResetStep.WAITING_VERIFICATION,
            ) || state.operationInProgress
        ) return
        if (!emailValidator.isValid(state.email)) {
            mutableUiState.update { it.copy(error = PasswordResetError.INVALID_EMAIL) }
            return
        }
        if (requestAcknowledged || (requestEmail != null && requestEmail != state.email)) {
            currentRequestId = UUID.randomUUID().toString()
        }
        requestEmail = state.email
        requestAcknowledged = false
        val commandId = currentRequestId
        mutableUiState.update { it.copy(operationInProgress = true, error = null) }
        launchOperation { generation ->
            val result = callOrServiceFailure {
                repository.request(state.email, commandId)
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent(generation)) return@launchOperation
            when (result) {
                is PasswordResetRequestResult.Sent -> {
                    requestAcknowledged = true
                    mutableUiState.update {
                    it.copy(
                        step = PasswordResetStep.WAITING_VERIFICATION,
                        challengeId = result.challengeId,
                        operationInProgress = false,
                        error = null,
                    )
                    }
                }
                is PasswordResetRequestResult.Failed -> mutableUiState.update {
                    it.copy(operationInProgress = false, error = result.reason.toUiError())
                }
            }
        }
    }

    private fun verify(token: String, challengeIdFromLink: String?) {
        val state = mutableUiState.value
        val challengeId = challengeIdFromLink ?: state.challengeId
        if (state.step !in setOf(
                PasswordResetStep.EMAIL_CONFIRMATION,
                PasswordResetStep.WAITING_VERIFICATION,
            ) || state.operationInProgress || challengeId == null || token.isBlank()
        ) return
        mutableUiState.update {
            it.copy(
                step = PasswordResetStep.WAITING_VERIFICATION,
                challengeId = challengeId,
                operationInProgress = true,
                error = null,
            )
        }
        launchOperation { generation ->
            val result = try {
                repository.verify(challengeId, token)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                PasswordResetVerificationResult.Failed(
                    PasswordResetFailureReason.SERVICE,
                    retryable = false,
                )
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent(generation)) return@launchOperation
            when (result) {
                is PasswordResetVerificationResult.Verified -> mutableUiState.update {
                    it.copy(
                        step = PasswordResetStep.NEW_PASSWORD,
                        resetToken = result.resetToken,
                        operationInProgress = false,
                        error = null,
                    )
                }
                PasswordResetVerificationResult.InvalidLink -> mutableUiState.update {
                    it.copy(operationInProgress = false, error = PasswordResetError.INVALID_LINK)
                }
                is PasswordResetVerificationResult.Failed -> mutableUiState.update {
                    it.copy(operationInProgress = false, error = result.reason.toUiError())
                }
            }
        }
    }

    private fun resetPassword() {
        val state = mutableUiState.value
        val resetToken = state.resetToken
        if (state.step != PasswordResetStep.NEW_PASSWORD ||
            state.operationInProgress || resetToken == null
        ) return
        val normalizedPassword = Normalizer.normalize(state.newPassword, Normalizer.Form.NFC)
        val passwordLength = normalizedPassword.codePointCount(0, normalizedPassword.length)
        val validationError = when {
            passwordLength < MINIMUM_PASSWORD_CODE_POINTS -> PasswordResetError.PASSWORD_TOO_SHORT
            passwordLength > MAXIMUM_PASSWORD_CODE_POINTS -> PasswordResetError.PASSWORD_TOO_LONG
            else -> null
        }
        if (validationError != null) {
            mutableUiState.update { it.copy(error = validationError) }
            return
        }
        mutableUiState.update { it.copy(operationInProgress = true, error = null) }
        launchOperation { generation ->
            val result = try {
                repository.reset(resetToken, normalizedPassword, resetCommandId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                PasswordResetResult.Failed(PasswordResetFailureReason.SERVICE, retryable = false)
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent(generation)) return@launchOperation
            when (result) {
                is PasswordResetResult.Completed -> mutableUiState.update {
                    it.copy(
                        step = PasswordResetStep.SUCCESS,
                        newPassword = "",
                        resetToken = null,
                        operationInProgress = false,
                        error = null,
                        completionDestination = when {
                            !result.sessionEstablished -> PasswordResetCompletionDestination.LOGIN
                            !result.initialSettingsComplete -> PasswordResetCompletionDestination.INITIAL_SETTINGS
                            else -> PasswordResetCompletionDestination.HOME
                        },
                    )
                }
                is PasswordResetResult.PasswordRejected -> mutableUiState.update {
                    it.copy(
                        operationInProgress = false,
                        error = when (result.reason) {
                            PasswordRejectionReason.TOO_SHORT -> PasswordResetError.PASSWORD_TOO_SHORT
                            PasswordRejectionReason.TOO_LONG -> PasswordResetError.PASSWORD_TOO_LONG
                            PasswordRejectionReason.BLOCKED -> PasswordResetError.PASSWORD_BLOCKED
                        },
                    )
                }
                is PasswordResetResult.Failed -> mutableUiState.update {
                    it.copy(operationInProgress = false, error = result.reason.toUiError())
                }
            }
        }
    }

    private fun complete() {
        if (mutableUiState.value.step != PasswordResetStep.SUCCESS) return
        when (mutableUiState.value.completionDestination) {
            PasswordResetCompletionDestination.LOGIN -> emit(PasswordResetEffect.OpenLogin)
            PasswordResetCompletionDestination.INITIAL_SETTINGS -> emit(PasswordResetEffect.OpenInitialSettings)
            PasswordResetCompletionDestination.HOME -> emit(PasswordResetEffect.OpenHome)
            null -> Unit
        }
        depart()
    }

    private fun isCurrent(generation: Long) = !departed && generation == operationGeneration

    private fun launchOperation(block: suspend (Long) -> Unit) {
        val generation = ++operationGeneration
        operationJob = viewModelScope.launch {
            if (!isCurrent(generation)) return@launch
            try { block(generation) } finally {
                if (isCurrent(generation)) mutableUiState.update { it.copy(operationInProgress = false) }
            }
        }
    }

    private fun cancelOperation() {
        operationGeneration++
        operationJob?.cancel()
        operationJob = null
    }

    private fun depart() {
        departed = true
        cancelOperation()
        mutableUiState.update { it.copy(newPassword = "", resetToken = null, operationInProgress = false) }
    }

    override fun onCleared() { depart(); super.onCleared() }

    private fun emit(effect: PasswordResetEffect) {
        effectChannel.trySend(effect)
    }

    private suspend fun callOrServiceFailure(
        call: suspend () -> PasswordResetRequestResult,
    ): PasswordResetRequestResult = try {
        call()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        PasswordResetRequestResult.Failed(
            PasswordResetFailureReason.SERVICE,
            retryable = false,
        )
    }

    private fun PasswordResetFailureReason.toUiError(): PasswordResetError = when (this) {
        PasswordResetFailureReason.NETWORK -> PasswordResetError.NETWORK
        PasswordResetFailureReason.SERVICE -> PasswordResetError.SERVICE
    }

    private companion object {
        const val MINIMUM_PASSWORD_CODE_POINTS = 4
        const val MAXIMUM_PASSWORD_CODE_POINTS = 64
    }
}
