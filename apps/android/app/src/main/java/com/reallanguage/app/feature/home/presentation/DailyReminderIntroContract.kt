package com.reallanguage.app.feature.home.presentation

enum class DailyReminderIntroError { SAVE_FAILED }

data class DailyReminderIntroUiState(
    val visible: Boolean = true,
    val saving: Boolean = false,
    val error: DailyReminderIntroError? = null,
)

sealed interface DailyReminderIntroAction {
    data object EnableRequested : DailyReminderIntroAction
    data class PermissionResult(val granted: Boolean) : DailyReminderIntroAction
    data object NotNowRequested : DailyReminderIntroAction
    data object RetryRequested : DailyReminderIntroAction
}

sealed interface DailyReminderIntroEffect {
    data object RequestNotificationPermission : DailyReminderIntroEffect
    data object CloseNotice : DailyReminderIntroEffect
}
