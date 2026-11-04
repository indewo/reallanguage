package com.reallanguage.app.feature.home.domain

/** Local notification ownership only; never an authorization token for network access. */
interface ReminderLocalIdentity {
    val accountId: String?
    suspend fun isCurrent(): Boolean
}
