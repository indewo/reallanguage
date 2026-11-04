package com.reallanguage.app.feature.home.domain

enum class ReminderReviewAvailability { NOT_DUE, AVAILABLE, DEFERRED_BY_DAILY_LIMIT }

data class ReminderTaskFacts(
    val review: ReminderReviewAvailability,
    val hasIncompleteLearning: Boolean,
)

sealed interface ReminderTaskFactsResult {
    data class Ready(val facts: ReminderTaskFacts) : ReminderTaskFactsResult
    data object Failed : ReminderTaskFactsResult
}

fun interface ReminderTaskFactsReader {
    suspend fun read(): ReminderTaskFactsResult
}
