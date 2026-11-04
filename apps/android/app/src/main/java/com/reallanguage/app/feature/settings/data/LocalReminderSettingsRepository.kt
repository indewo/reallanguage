package com.reallanguage.app.feature.settings.data

import com.reallanguage.app.feature.home.data.DailyReminderRepository
import com.reallanguage.app.feature.settings.domain.ReminderSettingsDraft
import com.reallanguage.app.feature.settings.domain.ReminderSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalReminderSettingsRepository(private val repository: DailyReminderRepository) : ReminderSettingsRepository {
    override suspend fun load(): ReminderSettingsDraft? = withContext(Dispatchers.IO) {
        repository.load()?.let { ReminderSettingsDraft(it.enabled, it.time) }
    }

    override suspend fun save(expected: ReminderSettingsDraft, proposed: ReminderSettingsDraft): Boolean = withContext(Dispatchers.IO) {
        repository.saveSettings(expected.enabled, expected.time, proposed.enabled, proposed.time).saved && load() == proposed
    }
}
