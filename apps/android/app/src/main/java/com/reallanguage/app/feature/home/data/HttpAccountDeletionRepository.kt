package com.reallanguage.app.feature.home.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.home.domain.AccountDeletionRepository
import com.reallanguage.app.feature.home.domain.AccountDeletionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONTokener

fun interface LocalAccountDataCleaner { suspend fun clear(accountId: String): Boolean }
fun interface DeletedAccountSessionCleaner { suspend fun clear(accountId: String) }
fun interface AccountDeletionWireCodec {
    fun command(commandId: String): String
    fun confirmsDeletion(responseBody: String): Boolean = false
}

class JsonAccountDeletionWireCodec : AccountDeletionWireCodec {
    override fun command(commandId: String): String =
        JSONObject().put("commandId", commandId).toString()
    override fun confirmsDeletion(responseBody: String): Boolean = try {
        val parser = JSONTokener(responseBody)
        val value = parser.nextValue() as? JSONObject
        parser.nextClean() == '\u0000' && value?.opt("status") == "DELETED"
    } catch (_: Exception) { false }
}

class HttpAccountDeletionRepository(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val localData: LocalAccountDataCleaner,
    private val deletedSession: DeletedAccountSessionCleaner,
    private val codec: AccountDeletionWireCodec = JsonAccountDeletionWireCodec(),
    private val journal: AccountDeletionCleanupJournal = UnavailableAccountDeletionCleanupJournal,
    private val access: DeletedAccountAccessRevoker = RuntimeDeletedAccountAccess(runtime),
) : AccountDeletionRepository {
    private val owner = runtime.accountId
    private val ownerEpoch = runtime.accountEpoch
    private val operationLock = Mutex()
    private var confirmedButUnrecorded: ConfirmedAccountDeletion? = null
    override suspend fun delete(commandId: String): AccountDeletionResult = withContext(Dispatchers.IO) {
        operationLock.withLock {
            try { deleteOwned(commandId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { AccountDeletionResult.Failed }
        }
    }

    private suspend fun deleteOwned(commandId: String): AccountDeletionResult {
        if (commandId.isBlank() || commandId.length > 128) return AccountDeletionResult.Failed
        val pending = journal.read() as? AccountDeletionCleanupLoad.Ready ?: return AccountDeletionResult.Failed
        val matching = pending.entries.filter { it.commandId == commandId }
        if (matching.size > 1) return AccountDeletionResult.Failed
        matching.singleOrNull()?.let { deletion ->
            if (owner != null && deletion.accountId != owner) return AccountDeletionResult.Failed
            return finish(deletion)
        }
        if (pending.entries.any { it.accountId == owner }) return AccountDeletionResult.Failed
        val remembered = confirmedButUnrecorded
        val deletion = if (remembered != null) {
            if (remembered.commandId != commandId) return AccountDeletionResult.Failed
            remembered
        } else {
            val account = owner ?: return AccountDeletionResult.Failed
            val token = runtime.accessToken ?: return AccountDeletionResult.Failed
            if (runtime.accountId != account || runtime.accountEpoch != ownerEpoch) return AccountDeletionResult.Failed
            val response = transport.execute(HttpRequest("DELETE", "/api/v1/account/current", codec.command(commandId),
                mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")))
            if (response.statusCode != 200 || !codec.confirmsDeletion(response.body)) return AccountDeletionResult.Failed
            ConfirmedAccountDeletion(account, commandId).also { confirmedButUnrecorded = it }
        }
        // Once the explicit result arrived, cancellation must not silently discard the cleanup receipt.
        val recorded = withContext(NonCancellable) {
            val saved = journal.record(deletion) &&
                (journal.read() as? AccountDeletionCleanupLoad.Ready)?.entries?.contains(deletion) == true
            if (!saved) access.revoke(deletion.accountId)
            saved
        }
        if (!recorded) return AccountDeletionResult.Failed
        confirmedButUnrecorded = null
        return finish(deletion)
    }

    private suspend fun finish(deletion: ConfirmedAccountDeletion): AccountDeletionResult {
        val complete = ConfirmedAccountDeletionCleanup(journal, localData, access, deletedSession).complete(deletion)
        // A late result from the deleted account must never navigate a newly signed-in account to login.
        val current = runtime.accountId
        return if (complete && (current == null || current == deletion.accountId)) AccountDeletionResult.Deleted else AccountDeletionResult.Failed
    }
}
