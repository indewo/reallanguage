package com.reallanguage.app.core.session

/** Local cleanup observers. No feature-specific storage, token, or platform API crosses this port. */
interface SessionLocalLifecycle {
    suspend fun signedOut(accountId: String) { }
    suspend fun accountDeleted(accountId: String) { }
    suspend fun accountReplaced(previousAccountId: String) { }

    companion object { val None: SessionLocalLifecycle = object : SessionLocalLifecycle { } }
}
