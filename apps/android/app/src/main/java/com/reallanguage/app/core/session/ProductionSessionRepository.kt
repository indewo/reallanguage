package com.reallanguage.app.core.session

import com.reallanguage.app.core.navigation.AppStartupRepository
import com.reallanguage.app.core.navigation.AppStartupResult
import com.reallanguage.app.feature.auth.domain.LoginCommand
import com.reallanguage.app.feature.auth.domain.LoginFailureReason
import com.reallanguage.app.feature.auth.domain.LoginRepository
import com.reallanguage.app.feature.auth.domain.LoginResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProductionSessionRepository(
    private val api: SessionApi,
    private val credentials: SessionCredentialStore,
    private val runtime: SessionRuntime,
    private val installationIdentity: InstallationIdentity,
    private val localLifecycle: SessionLocalLifecycle = SessionLocalLifecycle.None,
) : LoginRepository, AppStartupRepository, SessionLogoutRepository {
    suspend fun resolveForConnection(expectedAccountId: String, expectedAccountEpoch: Long): SessionRecoveryResolution {
        val unavailable = SessionRecoveryResolution(AppStartupResult.ConnectionUnavailable)
        val identity = requestIdentity()
        if (identity.runtimeOwner != expectedAccountId || identity.runtimeEpoch != expectedAccountEpoch ||
            (identity.session != null && identity.session.accountId != expectedAccountId) ||
            !credentialMutation.withLock { matchesCurrent(identity) }) return unavailable
        val result = identity.session?.let {
            api.refresh(it.refreshToken, installationIdentity.value())
        } ?: SessionApiResult.InvalidOrExpired
        return when (result) {
            is SessionApiResult.Authenticated -> {
                if (result.accountId != expectedAccountId || !accept(result, identity)) unavailable
                else SessionRecoveryResolution(AppStartupResult.SignedIn(result.settingsCompleted))
            }
            SessionApiResult.InvalidOrExpired -> {
                var receipt: SessionSignedOutReceipt? = null
                if (!clearIfCurrent(identity) { receipt = it }) return unavailable
                val committed = receipt ?: return unavailable
                // The observer may suspend after clearing. A new login revokes this navigation proof.
                if (runtime.isCurrent(committed)) SessionRecoveryResolution(AppStartupResult.SignedOut, committed)
                else unavailable
            }
            is SessionApiResult.Failed -> unavailable
        }
    }

    override suspend fun login(command: LoginCommand): LoginResult {
        val attempt = runtime.beginLoginIntent()
        val identity = requestIdentity()
        currentCoroutineContext().ensureActive()
        if (!runtime.isCurrentLoginIntent(attempt)) return LoginResult.Failed(LoginFailureReason.SERVICE)
        val result = api.login(command.email, command.password, installationIdentity.value())
        currentCoroutineContext().ensureActive()
        return when (result) {
            is SessionApiResult.Authenticated -> {
                if (accept(result, identity, attempt)) LoginResult.Authenticated(result.accountId, result.settingsCompleted)
                else LoginResult.Failed(LoginFailureReason.SERVICE)
            }
            SessionApiResult.InvalidOrExpired -> LoginResult.InvalidCredentials
            is SessionApiResult.Failed -> LoginResult.Failed(result.reason.toLoginFailure())
        }
    }

    override suspend fun resolve(): AppStartupResult {
        val identity = requestIdentity()
        val stored = identity.session
        if (stored == null) {
            return if (clearIfCurrent(identity)) AppStartupResult.SignedOut
            else AppStartupResult.ConnectionUnavailable
        }
        return when (
            val result = api.refresh(
                refreshToken = stored.refreshToken,
                deviceId = installationIdentity.value(),
            )
        ) {
            is SessionApiResult.Authenticated -> {
                if (accept(result, identity)) AppStartupResult.SignedIn(result.settingsCompleted)
                else AppStartupResult.ConnectionUnavailable
            }
            SessionApiResult.InvalidOrExpired -> {
                if (clearIfCurrent(identity)) AppStartupResult.SignedOut
                else AppStartupResult.ConnectionUnavailable
            }
            is SessionApiResult.Failed -> AppStartupResult.ConnectionUnavailable
        }
    }

    override suspend fun logout(): SessionLogoutResult {
        val identity = requestIdentity()
        val stored = identity.session
        if (stored != null && !api.revoke(stored.refreshToken)) {
            return SessionLogoutResult.Failed
        }
        return if (clearIfCurrent(identity)) SessionLogoutResult.LoggedOut else SessionLogoutResult.Failed
    }

    suspend fun clearAfterAccountDeletion(expectedAccountId: String) {
        require(expectedAccountId.isNotBlank())
        val owned = credentialMutation.withLock {
            // A destructive operation must verify the persisted owner, never guess from runtime.
            val stored = credentials.load()
            val ownedCredentials = stored?.accountId == expectedAccountId
            if (ownedCredentials) {
                credentials.clear()
                check(credentials.load()?.accountId != expectedAccountId) { "Deleted account credentials remain" }
            }
            val ownedRuntime = synchronized(runtime) {
                (runtime.accountId == expectedAccountId).also { if (it) runtime.clear() }
            }
            ownedCredentials || ownedRuntime
        }
        if (owned) notifyLocal { localLifecycle.accountDeleted(expectedAccountId) }
    }

    private suspend fun accept(result: SessionApiResult.Authenticated, expected: RequestIdentity? = null, loginAttempt: Long? = null): Boolean {
        val previousOwner = credentialMutation.withLock {
            currentCoroutineContext().ensureActive()
            if (loginAttempt != null && !runtime.isCurrentLoginIntent(loginAttempt)) return false
            if (expected != null && !matchesCurrent(expected)) return false
            if (!DeletedAccountLocalAccess.allowsAccount(result.accountId)) return false
            val previous = localOwner()
            val written = StoredSession(
                    accountId = result.accountId,
                    refreshToken = result.refreshToken,
                    settingsCompleted = result.settingsCompleted,
            )
            try { credentials.save(written) } catch (failure: Exception) {
                if (expected != null) restoreOwnedWrite(written, expected.session)
                throw failure
            }
            val context = currentCoroutineContext()
            val accepted = synchronized(runtime) {
                (context.isActive && (loginAttempt == null || runtime.isCurrentLoginIntent(loginAttempt)) &&
                    (expected == null || (runtime.accountId == expected.runtimeOwner && runtime.accountEpoch == expected.runtimeEpoch &&
                        runtime.identityRevision.value == expected.runtimeRevision)) &&
                    DeletedAccountLocalAccess.allowsAccount(result.accountId)).also {
                    if (it) runtime.update(result.accessToken, result.accountId, newSession = loginAttempt != null || expected == null)
                }
            }
            if (!accepted) {
                // Only reverse the precise write made under this credential lock, not newer data.
                restoreOwnedWrite(written, expected?.session?.takeIf { DeletedAccountLocalAccess.allowsAccount(it.accountId) })
                context.ensureActive()
                return false
            }
            previous
        }
        if (previousOwner != null && previousOwner != result.accountId) {
            notifyLocal { localLifecycle.accountReplaced(previousOwner) }
        }
        return true
    }

    private suspend fun restoreOwnedWrite(written: StoredSession, previous: StoredSession?) = withContext(NonCancellable) {
        if (credentials.load() == written) {
            if (previous == null) credentials.clear() else credentials.save(previous)
            check(credentials.load() == previous) { "Interrupted session persistence could not be restored" }
        }
    }

    private suspend fun requestIdentity(): RequestIdentity = credentialMutation.withLock {
        val stored = credentials.load()
        synchronized(runtime) {
            RequestIdentity(stored, runtime.accountId, runtime.identityRevision.value, runtime.accountEpoch)
        }
    }

    /** The server response belongs to the credentials that made the request, not a later login. */
    private suspend fun clearIfCurrent(expected: RequestIdentity, onCommitted: (SessionSignedOutReceipt?) -> Unit = {}): Boolean {
        val cleared = credentialMutation.withLock {
            if (!matchesCurrent(expected)) return false
            if (expected.session != null) {
                credentials.clear()
                check(credentials.load() == null) { "Signed out credentials remain" }
            }
            synchronized(runtime) {
                if (runtime.identityRevision.value != expected.runtimeRevision) false
                else {
                    val account = runtime.accountId ?: expected.session?.accountId
                    val sourceEpoch = runtime.accountEpoch
                    runtime.clear()
                    onCommitted(account?.let {
                        SessionSignedOutReceipt(it, sourceEpoch, runtime.accountEpoch, runtime.identityRevision.value)
                    })
                    true
                }
            }
        }
        if (cleared) {
            (expected.session?.accountId ?: expected.runtimeOwner)?.let {
                notifyLocal { localLifecycle.signedOut(it) }
            }
        }
        return cleared
    }

    /** Caller holds credentialMutation; runtime revision also distinguishes same-account re-login. */
    private suspend fun matchesCurrent(expected: RequestIdentity): Boolean =
        credentials.load() == expected.session && synchronized(runtime) {
            runtime.accountId == expected.runtimeOwner && runtime.identityRevision.value == expected.runtimeRevision
        }

    private data class RequestIdentity(
        val session: StoredSession?,
        val runtimeOwner: String?,
        val runtimeRevision: Long,
        val runtimeEpoch: Long,
    )

    /** Optional observer lookup does not authorize deleting an unverified credential owner. */
    private suspend fun localOwner(): String? = try {
        credentials.load()?.accountId ?: runtime.accountId
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { runtime.accountId }

    /** Credential changes are already committed. An observer cannot roll them back or report failure. */
    private suspend fun notifyLocal(action: suspend () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The identity boundary still prevents late local delivery. */ }
    }

    private fun SessionFailure.toLoginFailure(): LoginFailureReason = when (this) {
        SessionFailure.NETWORK -> LoginFailureReason.NETWORK
        SessionFailure.SERVICE -> LoginFailureReason.SERVICE
    }

    private companion object {
        // Multiple graph/repository instances may share the same persistent credential location.
        // Only local operations belong here; never hold it while awaiting a network response.
        val credentialMutation = Mutex()
    }
}
