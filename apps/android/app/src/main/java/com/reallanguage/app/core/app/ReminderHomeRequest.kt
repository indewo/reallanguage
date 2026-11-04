package com.reallanguage.app.core.app

data class ReminderHomeRequest(val ownerKey: String, val requestId: String)
internal fun reminderHomeRequestForOwner(request: ReminderHomeRequest?, matchesOwner: (ReminderHomeRequest) -> Boolean): ReminderHomeRequest? = request?.takeIf(matchesOwner)
enum class ReminderHomeDisposition { HOME, ORDINARY_EXIT, REVIEW_EXIT, CURRENT_FLOW_BACK, WAIT_FOR_SAFE_BOUNDARY }

fun parseReminderHomeRequest(action: String?, data: String?, requestId: String): ReminderHomeRequest? {
    if (action != "com.reallanguage.app.DAILY_REMINDER_HOME" || requestId.isBlank()) return null
    val key = Regex("reallanguage-reminder://home/([a-f0-9]{64})").matchEntire(data.orEmpty())?.groupValues?.get(1) ?: return null
    return ReminderHomeRequest(key, requestId)
}
internal fun reminderHomeDisposition(route: HomeDiaryRoute): ReminderHomeDisposition = when {
    route is HomeDiaryRoute.ReviewChapter || route is HomeDiaryRoute.ReviewAbilityExecution -> ReminderHomeDisposition.REVIEW_EXIT
    learningCheckpoint(route) != null -> ReminderHomeDisposition.ORDINARY_EXIT
    route is HomeDiaryRoute.Input || route is HomeDiaryRoute.SceneQuestions || route is HomeDiaryRoute.Settings ||
        route is HomeDiaryRoute.SettingsEffect -> ReminderHomeDisposition.CURRENT_FLOW_BACK
    route in listOf(HomeDiaryRoute.Home, HomeDiaryRoute.TodayReview, HomeDiaryRoute.VocabularyBook, HomeDiaryRoute.PatternBook) ||
        route is HomeDiaryRoute.IncompleteLearning || route is HomeDiaryRoute.Messages -> ReminderHomeDisposition.HOME
    // A notification is not authorization to discard a draft, interrupt a
    // recording, or claim that an in-flight mutation was cancelled.
    else -> ReminderHomeDisposition.WAIT_FOR_SAFE_BOUNDARY
}
