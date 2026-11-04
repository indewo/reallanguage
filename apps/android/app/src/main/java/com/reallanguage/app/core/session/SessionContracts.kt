package com.reallanguage.app.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StoredSession(
    val accountId: String,
    val refreshToken: String,
    val settingsCompleted: Boolean,
)

enum class SessionFailure { NETWORK, SERVICE }

sealed interface SessionApiResult {
    data class Authenticated(
        val accountId: String,
        val accessToken: String,
        val refreshToken: String,
        val settingsCompleted: Boolean,
    ) : SessionApiResult

    data object InvalidOrExpired : SessionApiResult
    data class Failed(val reason: SessionFailure) : SessionApiResult
}

interface SessionApi {
    suspend fun login(email: String, password: String, deviceId: String): SessionApiResult
    suspend fun refresh(refreshToken: String, deviceId: String): SessionApiResult
    suspend fun revoke(refreshToken: String): Boolean
}

interface SessionCredentialStore {
    suspend fun load(): StoredSession?
    suspend fun save(session: StoredSession)
    suspend fun clear()
}

fun interface InstallationIdentity {
    fun value(): String
}

/** Activity/Graph recreation must not detach a running service from the live login authority.
 * Tests may still inject independent runtimes, and a new process starts signed out in memory. */
internal object ProcessSessionRuntime {
    val value = SessionRuntime()
}

class SessionRuntime {
    private var loginIntentGeneration = 0L
    /** Intent is shared across Graphs, but does not itself authenticate or change the current owner. */
    @Synchronized internal fun beginLoginIntent(): Long = ++loginIntentGeneration
    @Synchronized internal fun isCurrentLoginIntent(generation: Long): Boolean = generation == loginIntentGeneration
    @Synchronized fun isCurrent(receipt: SessionSignedOutReceipt): Boolean =
        accountId == null && accessToken == null && accountEpoch == receipt.clearedEpoch &&
            identityRevision.value == receipt.clearedIdentityRevision
    @Volatile
    var accountEpoch: Long = 0L
        private set
    private val mutableIdentityRevision = MutableStateFlow(0L)
    val identityRevision: StateFlow<Long> = mutableIdentityRevision.asStateFlow()
    @Volatile
    var accessToken: String? = null
        private set
    @Volatile
    var accountId: String? = null
        private set

    @Synchronized fun update(accessToken: String, accountId: String? = null, newSession: Boolean = false) {
        val nextAccount = accountId ?: this.accountId
        if (newSession || this.accessToken == null || this.accountId != nextAccount) accountEpoch += 1
        this.accessToken = accessToken
        if (accountId != null) this.accountId = accountId
        mutableIdentityRevision.value += 1
    }

    @Synchronized fun clear() {
        accountEpoch += 1
        accessToken = null
        accountId = null
        mutableIdentityRevision.value += 1
    }
}

data class SessionSignedOutReceipt(
    val accountId: String,
    val sourceEpoch: Long,
    val clearedEpoch: Long,
    val clearedIdentityRevision: Long,
)

data class SessionRecoveryResolution(
    val result: com.reallanguage.app.core.navigation.AppStartupResult,
    val signedOutReceipt: SessionSignedOutReceipt? = null,
)

sealed interface SessionLogoutResult {
    data object LoggedOut : SessionLogoutResult
    data object Failed : SessionLogoutResult
}

fun interface SessionLogoutRepository {
    suspend fun logout(): SessionLogoutResult
}
