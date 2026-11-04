package com.reallanguage.app.feature.settings.domain

sealed interface SubsequentSettingsLoadResult {
    data class Ready(val settings: InitialSettingsDraft) : SubsequentSettingsLoadResult
    data object Failed : SubsequentSettingsLoadResult
}

sealed interface SubsequentSettingsSaveResult {
    data object Saved : SubsequentSettingsSaveResult
    data object Failed : SubsequentSettingsSaveResult
    data object Conflict : SubsequentSettingsSaveResult
}

interface SubsequentSettingsRepository {
    suspend fun load(): SubsequentSettingsLoadResult
    suspend fun save(
        settings: InitialSettingsDraft,
        commandId: String,
    ): SubsequentSettingsSaveResult
}
