package com.reallanguage.app.feature.home.data

import android.content.Context
import android.content.Intent
import com.reallanguage.app.core.session.SessionLocalLifecycle
import com.reallanguage.app.feature.home.domain.*
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One process-wide critical section also covers logout and foreground preference writes. */
class AndroidDailyReminderRuntime(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : SessionLocalLifecycle {
    private val context = context.applicationContext
    private val store = AndroidDailyReminderPreferenceStore(this.context)
    val platform = AndroidDailyReminderPlatform(this.context)

    fun repository(identity: ReminderLocalIdentity) = DailyReminderRepository(store, identity,
        clock, zone, platform::permission)

    suspend fun <T> serialized(block: suspend () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    suspend fun reconcile(identity: ReminderLocalIdentity): DailyReminderScheduleDecision = serialized {
        delivery(identity).reconcile()
    }

    suspend fun receive(intent: Intent) = serialized {
        val identity = androidReminderLocalIdentity(context) ?: return@serialized
        if (intent.action == AndroidDailyReminderPlatform.ACTION_DELIVER) {
            val token = platform.readToken(intent, identity.accountId ?: return@serialized) ?: return@serialized
            delivery(identity).deliver(token)
        } else delivery(identity).reconcile()
        Unit
    }

    private fun delivery(identity: ReminderLocalIdentity) = DailyReminderDelivery(identity,
        repository(identity), AndroidReminderTaskFactsReader(context, identity), platform)

    override suspend fun signedOut(accountId: String) = invalidate(accountId)
    override suspend fun accountReplaced(previousAccountId: String) = invalidate(previousAccountId)
    override suspend fun accountDeleted(accountId: String) {
        check(clearAfterAccountDeletion(accountId)) { "Account reminder cleanup is incomplete" }
    }

    /** Mandatory deletion orchestration uses this result; an optional session observer is insufficient. */
    suspend fun clearAfterAccountDeletion(accountId: String): Boolean = serialized {
        if (accountId.isBlank()) return@serialized false
        val metadataRemoved = try {
            // clear() verifies raw key absence; read() additionally checks the reconstructed state.
            store.clear(accountId) && store.read(accountId) == DailyReminderPreferences()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        // Attempt cancellation even if preferences failed, but retain the cleanup receipt on failure.
        val platformRemoved = platform.cancelAndConfirm(accountId)
        metadataRemoved && platformRemoved
    }

    private suspend fun invalidate(accountId: String) = serialized {
        val captured = object : ReminderLocalIdentity {
            override val accountId = accountId
            override suspend fun isCurrent() = false
        }
        try {
            repository(captured).cancelForLogout()
        } finally { platform.cancel(accountId) }
        Unit
    }

    private companion object { val lock = Mutex() }
}
