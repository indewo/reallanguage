package com.reallanguage.app.feature.settings.presentation

import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import com.reallanguage.app.feature.settings.domain.SettingsEffectScope
import com.reallanguage.app.feature.settings.domain.SettingsSourceContext
import com.reallanguage.app.feature.settings.domain.SettingsSaveProgress

enum class SettingsEffectScopeError { APPLY_FAILED, VERSION_CONFLICT }

data class SettingsEffectScopeUiState(
    val originalSettings: InitialSettingsDraft,
    val proposedSettings: InitialSettingsDraft,
    val sourceContext: SettingsSourceContext,
    val applying: Boolean = false,
    val error: SettingsEffectScopeError? = null,
    val saveProgress: SettingsSaveProgress? = null,
)

sealed interface SettingsEffectScopeAction {
    data object ReprocessRequested : SettingsEffectScopeAction
    data object NextContentRequested : SettingsEffectScopeAction
    data object CancelRequested : SettingsEffectScopeAction
    data object RetryRequested : SettingsEffectScopeAction
}

sealed interface SettingsEffectScopeEffect {
    data class OpenReprocessing(val scope: SettingsEffectScope) : SettingsEffectScopeEffect
    data object ReturnToSettings : SettingsEffectScopeEffect
    data object OpenHome : SettingsEffectScopeEffect
}
