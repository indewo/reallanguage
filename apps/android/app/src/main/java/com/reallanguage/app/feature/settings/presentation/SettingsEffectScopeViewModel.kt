package com.reallanguage.app.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import com.reallanguage.app.feature.settings.domain.SettingsEffectApplyResult
import com.reallanguage.app.feature.settings.domain.SettingsEffectCommand
import com.reallanguage.app.feature.settings.domain.SettingsEffectRepository
import com.reallanguage.app.feature.settings.domain.SettingsEffectScope
import com.reallanguage.app.feature.settings.domain.SettingsSourceContext
import com.reallanguage.app.feature.settings.domain.ReminderSettingsDraft
import com.reallanguage.app.feature.settings.domain.ReminderSettingsRepository
import com.reallanguage.app.feature.settings.domain.SettingsSaveCoordinator
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsEffectScopeViewModel(
    originalSettings: InitialSettingsDraft,
    proposedSettings: InitialSettingsDraft,
    sourceContext: SettingsSourceContext,
    private val repository: SettingsEffectRepository,
    private val commandId: String,
    private val reminderRepository: ReminderSettingsRepository? = null,
    private val originalReminder: ReminderSettingsDraft? = null,
    private val proposedReminder: ReminderSettingsDraft? = null,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        SettingsEffectScopeUiState(originalSettings, proposedSettings, sourceContext),
    )
    val uiState: StateFlow<SettingsEffectScopeUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<SettingsEffectScopeEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var lastCommand: SettingsEffectCommand? = null
    private var pendingSave: SettingsSaveCoordinator? = null
    private var completed = false
    private var versionConflict = false

    fun onAction(action: SettingsEffectScopeAction) {
        if (mutableUiState.value.error == SettingsEffectScopeError.VERSION_CONFLICT &&
            action != SettingsEffectScopeAction.CancelRequested) return
        when (action) {
            SettingsEffectScopeAction.ReprocessRequested -> reprocess()
            SettingsEffectScopeAction.NextContentRequested -> apply(SettingsEffectScope.NextContent)
            SettingsEffectScopeAction.CancelRequested -> if (!mutableUiState.value.applying) {
                effectChannel.trySend(SettingsEffectScopeEffect.ReturnToSettings)
            }
            SettingsEffectScopeAction.RetryRequested -> lastCommand?.let(::submit)
        }
    }

    private fun reprocess() {
        val scope = when (val context = mutableUiState.value.sourceContext) {
            is SettingsSourceContext.UnfixedContent -> SettingsEffectScope.ReprocessUnfixed(context.flowId)
            is SettingsSourceContext.FixedContent -> SettingsEffectScope.StartNewFromFixedSource(context.sourceId)
            SettingsSourceContext.NoCurrentContent -> return
        }
        apply(scope)
    }

    private fun apply(scope: SettingsEffectScope) {
        val state = mutableUiState.value
        if (state.applying || completed) return
        // A partially acknowledged operation keeps its original scope on retry.
        lastCommand?.let { submit(it); return }
        submit(
            SettingsEffectCommand(
                originalSettings = state.originalSettings,
                proposedSettings = state.proposedSettings,
                scope = scope,
                commandId = commandId,
            ),
        )
    }

    private fun submit(command: SettingsEffectCommand) {
        if (mutableUiState.value.applying || completed) return
        if ((originalReminder == null) != (proposedReminder == null) ||
            originalReminder != proposedReminder && reminderRepository == null) {
            mutableUiState.update { it.copy(error = SettingsEffectScopeError.APPLY_FAILED) }
            return
        }
        lastCommand = command
        val coordinator = pendingSave ?: SettingsSaveCoordinator(
            settingsRequired = true,
            reminderRequired = originalReminder != proposedReminder,
            saveSettings = {
                val result = repository.apply(command)
                versionConflict = result == SettingsEffectApplyResult.Conflict
                result == SettingsEffectApplyResult.Applied
            },
            saveReminder = {
                originalReminder != null && proposedReminder != null &&
                    reminderRepository?.save(originalReminder, proposedReminder) == true
            },
        ).also { pendingSave = it }
        mutableUiState.update { it.copy(applying = true, error = null) }
        viewModelScope.launch {
            val progress = coordinator.save()
            mutableUiState.update { it.copy(applying = false, saveProgress = progress,
                error = when {
                    progress.completed -> null
                    versionConflict -> SettingsEffectScopeError.VERSION_CONFLICT
                    else -> SettingsEffectScopeError.APPLY_FAILED
                }) }
            if (progress.completed) {
                completed = true
                when (command.scope) {
                    SettingsEffectScope.NextContent -> effectChannel.send(SettingsEffectScopeEffect.OpenHome)
                    is SettingsEffectScope.ReprocessUnfixed,
                    is SettingsEffectScope.StartNewFromFixedSource -> effectChannel.send(
                        SettingsEffectScopeEffect.OpenReprocessing(command.scope),
                    )
                }
            }
        }
    }
}
