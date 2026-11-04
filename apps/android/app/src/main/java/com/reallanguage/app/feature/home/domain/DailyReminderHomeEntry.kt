package com.reallanguage.app.feature.home.domain

/** A failed preference save can dismiss this session's optional explanation,
 * but must never masquerade as a durable opt-out or another account's choice. */
class DailyReminderHomeEntry {
    private val closedAccounts = mutableSetOf<String>()
    fun shouldShow(accountId: String?, preferences: DailyReminderPreferences?): Boolean =
        !accountId.isNullOrBlank() && accountId !in closedAccounts && preferences?.introHandled == false
    fun closed(accountId: String) { if (accountId.isNotBlank()) closedAccounts += accountId }
}
