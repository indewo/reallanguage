package com.reallanguage.app.feature.settings.domain

import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Local notification preferences are not generation settings and never enter their HTTP payload. */
data class ReminderSettingsDraft(val enabled: Boolean, val time: LocalTime) {
    init { require(time.second == 0 && time.nano == 0) }
}

interface ReminderSettingsRepository {
    suspend fun load(): ReminderSettingsDraft?
    suspend fun save(expected: ReminderSettingsDraft, proposed: ReminderSettingsDraft): Boolean
}

data class SettingsSaveProgress(
    val settingsSaved: Boolean,
    val reminderSaved: Boolean,
    val settingsRequired: Boolean = true,
    val reminderRequired: Boolean = true,
) {
    val completed: Boolean get() = settingsSaved && reminderSaved
    val partiallySaved: Boolean get() = !completed &&
        (settingsRequired && settingsSaved || reminderRequired && reminderSaved)
}

/** Tracks the acknowledged sides of one immutable save plan; it is not a distributed transaction. */
class SettingsSaveCoordinator(
    settingsRequired: Boolean,
    reminderRequired: Boolean,
    private val saveSettings: suspend () -> Boolean,
    private val saveReminder: suspend () -> Boolean,
) {
    private val mutex = Mutex()
    var progress = SettingsSaveProgress(!settingsRequired, !reminderRequired, settingsRequired, reminderRequired)
        private set

    suspend fun save(): SettingsSaveProgress = mutex.withLock {
        if (!progress.settingsSaved) {
            if (!attempt(saveSettings)) return@withLock progress
            progress = progress.copy(settingsSaved = true)
        }
        if (!progress.reminderSaved && attempt(saveReminder)) {
            progress = progress.copy(reminderSaved = true)
        }
        progress
    }

    private suspend fun attempt(operation: suspend () -> Boolean): Boolean = try { operation() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}
