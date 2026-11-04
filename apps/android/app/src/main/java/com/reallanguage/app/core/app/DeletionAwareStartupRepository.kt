package com.reallanguage.app.core.app

import com.reallanguage.app.core.navigation.AppStartupRepository
import com.reallanguage.app.core.navigation.AppStartupResult
import com.reallanguage.app.feature.home.data.AccountDeletionCleanupJournal
import com.reallanguage.app.feature.home.data.ConfirmedAccountDeletion
import com.reallanguage.app.feature.home.data.AccountDeletionCleanupLoad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Confirmed server deletion must finish local cleanup before any session refresh. */
internal class DeletionAwareStartupRepository(
    private val journal: AccountDeletionCleanupJournal,
    private val complete: suspend (ConfirmedAccountDeletion) -> Boolean,
    private val session: AppStartupRepository,
) : AppStartupRepository {
    private val mutex = Mutex()
    override suspend fun resolve(): AppStartupResult = mutex.withLock {
        try {
            val pending = journal.read() as? AccountDeletionCleanupLoad.Ready
                ?: return@withLock AppStartupResult.ConnectionUnavailable
            for (receipt in pending.entries) {
                if (!complete(receipt)) return@withLock AppStartupResult.ConnectionUnavailable
            }
            val verified = journal.read() as? AccountDeletionCleanupLoad.Ready
                ?: return@withLock AppStartupResult.ConnectionUnavailable
            if (verified.entries.isNotEmpty()) AppStartupResult.ConnectionUnavailable else session.resolve()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { AppStartupResult.ConnectionUnavailable }
    }
}
