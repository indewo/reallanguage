package com.reallanguage.app.feature.home.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.home.domain.DailyReminderChoice
import com.reallanguage.app.feature.home.domain.DailyReminderIntroRepository
import com.reallanguage.app.feature.home.domain.DailyReminderIntroResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DailyReminderIntroViewModel(
    private val repository: DailyReminderIntroRepository,
    private val commandId: String,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(DailyReminderIntroUiState())
    val uiState: StateFlow<DailyReminderIntroUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<DailyReminderIntroEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var lastChoice: DailyReminderChoice? = null
    private var permissionPending = false

    fun onAction(action: DailyReminderIntroAction) {
        if (!mutableUiState.value.visible) return
        when (action) {
            DailyReminderIntroAction.EnableRequested -> {
                if (!mutableUiState.value.saving && !permissionPending) {
                    permissionPending = true
                    effectChannel.trySend(DailyReminderIntroEffect.RequestNotificationPermission)
                }
            }
            is DailyReminderIntroAction.PermissionResult -> {
                if (!permissionPending) return
                permissionPending = false
                save(if (action.granted) DailyReminderChoice.ENABLED else DailyReminderChoice.DISMISSED)
            }
            DailyReminderIntroAction.NotNowRequested -> {
                permissionPending = false
                save(DailyReminderChoice.DISMISSED)
            }
            DailyReminderIntroAction.RetryRequested -> lastChoice?.let(::save)
        }
    }

    private fun save(choice: DailyReminderChoice) {
        if (mutableUiState.value.saving) return
        lastChoice = choice
        mutableUiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = try {
                repository.recordChoice(choice, commandId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                DailyReminderIntroResult.Failed
            }
            when (result) {
                DailyReminderIntroResult.Saved -> {
                    mutableUiState.update { it.copy(visible = false, saving = false) }
                    effectChannel.send(DailyReminderIntroEffect.CloseNotice)
                }
                DailyReminderIntroResult.Failed -> {
                    // Declining an optional reminder must not lock the user out
                    // of Home when storage fails. Keep the failure fact: this
                    // session's dismissal is not a persisted preference.
                    mutableUiState.update {
                        it.copy(visible = choice != DailyReminderChoice.DISMISSED,
                            saving = false, error = DailyReminderIntroError.SAVE_FAILED)
                    }
                    if (choice == DailyReminderChoice.DISMISSED) {
                        effectChannel.send(DailyReminderIntroEffect.CloseNotice)
                    }
                }
            }
        }
    }
}
