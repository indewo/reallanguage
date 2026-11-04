package com.reallanguage.app.feature.home.data

import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.DeletedAccountLocalAccess
import kotlinx.coroutines.CancellationException

fun interface DeletedAccountAccessRevoker { suspend fun revoke(accountId: String): Boolean }

/** This entry is also usable at startup with a verified local deletion receipt, without another DELETE. */
class ConfirmedAccountDeletionCleanup(
    private val journal: AccountDeletionCleanupJournal,
    private val localData: LocalAccountDataCleaner,
    private val access: DeletedAccountAccessRevoker,
    private val deletedSession: DeletedAccountSessionCleaner,
) {
    suspend fun complete(deletion: ConfirmedAccountDeletion): Boolean = try {
        val pending = journal.read() as? AccountDeletionCleanupLoad.Ready
        if (pending == null || deletion !in pending.entries) false
        else if (!access.revoke(deletion.accountId) || !localData.clear(deletion.accountId)) false
        else {
            deletedSession.clear(deletion.accountId)
            journal.complete(deletion) && (journal.read() as? AccountDeletionCleanupLoad.Ready)?.entries?.none { it.accountId == deletion.accountId } == true
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}

class RuntimeDeletedAccountAccess(private val runtime: SessionRuntime) : DeletedAccountAccessRevoker {
    override suspend fun revoke(accountId: String): Boolean {
        if (accountId.isBlank()) return false
        DeletedAccountLocalAccess.revoke(accountId)
        synchronized(runtime) { if (runtime.accountId == accountId) runtime.clear() }
        return true
    }
}
