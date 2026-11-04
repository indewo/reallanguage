package com.reallanguage.app.feature.settings.domain

sealed interface SettingsSourceContext {
    data class UnfixedContent(val flowId: String, val sourceLabelZh: String) : SettingsSourceContext
    data class FixedContent(val sourceId: String, val sourceLabelZh: String) : SettingsSourceContext
    data object NoCurrentContent : SettingsSourceContext
}

sealed interface SettingsEffectScope {
    data class ReprocessUnfixed(val flowId: String) : SettingsEffectScope
    data class StartNewFromFixedSource(val sourceId: String) : SettingsEffectScope
    data object NextContent : SettingsEffectScope
}

data class SettingsEffectCommand(
    val originalSettings: InitialSettingsDraft,
    val proposedSettings: InitialSettingsDraft,
    val scope: SettingsEffectScope,
    val commandId: String,
    val preserveExistingFixedContent: Boolean = true,
)

sealed interface SettingsEffectApplyResult {
    data object Applied : SettingsEffectApplyResult
    data object Failed : SettingsEffectApplyResult
    data object Conflict : SettingsEffectApplyResult
}

fun interface SettingsEffectRepository {
    suspend fun apply(command: SettingsEffectCommand): SettingsEffectApplyResult
}
