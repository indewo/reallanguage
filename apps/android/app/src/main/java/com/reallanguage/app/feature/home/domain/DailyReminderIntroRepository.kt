package com.reallanguage.app.feature.home.domain

enum class DailyReminderChoice { ENABLED, DISMISSED }

sealed interface DailyReminderIntroResult {
    data object Saved : DailyReminderIntroResult
    data object Failed : DailyReminderIntroResult
}

fun interface DailyReminderIntroRepository {
    suspend fun recordChoice(
        choice: DailyReminderChoice,
        commandId: String,
    ): DailyReminderIntroResult
}
