package com.reallanguage.app.feature.home.data

import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.DeletedAccountLocalAccess
import com.reallanguage.app.feature.home.domain.ReminderLocalIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RuntimeReminderLocalIdentity(private val runtime: SessionRuntime) : ReminderLocalIdentity {
    override val accountId: String? = runtime.accountId
    override suspend fun isCurrent(): Boolean = accountId != null &&
        DeletedAccountLocalAccess.allowsAccount(accountId) && runtime.accountId == accountId && runtime.accessToken != null
}

class StoredSessionReminderLocalIdentity private constructor(
    override val accountId: String,
    private val credentials: SessionCredentialStore,
    private val isUserUnlocked: () -> Boolean,
) : ReminderLocalIdentity {
    override suspend fun isCurrent(): Boolean = DeletedAccountLocalAccess.allowsAccount(accountId) &&
        currentAccount(credentials, isUserUnlocked) == accountId

    companion object {
        suspend fun load(
            credentials: SessionCredentialStore,
            isUserUnlocked: () -> Boolean,
        ): StoredSessionReminderLocalIdentity? = currentAccount(credentials, isUserUnlocked)?.let {
            StoredSessionReminderLocalIdentity(it, credentials, isUserUnlocked)
        }

        private suspend fun currentAccount(credentials: SessionCredentialStore, isUserUnlocked: () -> Boolean): String? =
            withContext(Dispatchers.IO) { try {
                if (!isUserUnlocked()) null
                else credentials.load()?.accountId?.takeIf(DeletedAccountLocalAccess::allowsAccount)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null } }
    }
}
