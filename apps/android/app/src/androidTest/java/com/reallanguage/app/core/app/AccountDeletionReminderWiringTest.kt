package com.reallanguage.app.core.app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.navigation.AppStartupResult
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.home.data.*
import com.reallanguage.app.feature.home.domain.*
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real Graph, isolated preferences/database and account-keyed Android alarm/notification handles. */
class AccountDeletionReminderWiringTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val owner = "delete-reminder-$id"
    private val other = "keep-reminder-$id"
    private val files = File(base.cacheDir, "delete-reminder-$id")
    private val preferences = mutableSetOf<String>()
    private val isolated = IsolatedLearningDatabaseContext(base, "delete-reminder")
    private var failOwnerRemoval = false
    private val context = object : ContextWrapper(isolated) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(files, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(files, "no-backup")
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = "delete-reminder-$id-$name"; preferences += scoped
            val delegate = base.getSharedPreferences(scoped, mode)
            if (name != "real-language-daily-reminders") return delegate
            return object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        var removesOwner = false
                        override fun remove(key: String?): SharedPreferences.Editor {
                            if (key == "account-${AndroidDailyReminderPlatform.accountKey(owner)}") removesOwner = true
                            editor.remove(key)
                            return this
                        }
                        override fun commit(): Boolean = if (failOwnerRemoval && removesOwner) false else editor.commit()
                    }
                }
            }
        }
    }
    private val channel = "delete-reminder-$id"
    private val manager = base.getSystemService(NotificationManager::class.java)
    private val platform get() = AndroidDailyReminderPlatform(context, channel)
    private val store get() = AndroidDailyReminderPreferenceStore(context)
    private val journal get() = AndroidAccountDeletionCleanupJournal(context)
    private val saved = DailyReminderPreferences(introHandled = true, enabled = true,
        zoneId = "Asia/Shanghai", permission = ReminderPermission.GRANTED, revision = 1)

    @After fun cleanupOnlyTheIsolatedFixture() = runBlocking(Dispatchers.IO) {
        platform.cancel(owner); platform.cancel(other)
        manager.deleteNotificationChannel(channel)
        preferences.forEach { base.deleteSharedPreferences(it) }
        isolated.deleteDatabase("real-language-learning.db")
        check(files.name == "delete-reminder-$id"); files.deleteRecursively()
        Unit
    }

    @Test fun coldReceiptWithoutCurrentCredentialsRemovesOnlyItsReminderMetadata() = runBlocking {
        seedPreferences()
        assertTrue(journal.record(ConfirmedAccountDeletion(owner, "receipt")))
        val graph = ProductionAppGraph(context, SessionRuntime(), HttpTransport { error("Cleanup must not call network") }, Credentials())
        assertEquals(AppStartupResult.SignedOut, graph.resolveStartupForApp())
        assertEquals(DailyReminderPreferences(), store.read(owner))
        assertEquals(saved, store.read(other))
        assertTrue((journal.read() as AccountDeletionCleanupLoad.Ready).entries.isEmpty())
    }

    @Test fun coldReceiptWithoutCurrentCredentialsCancelsItsRealAlarmAndNotificationOnly() = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(base.packageName, Manifest.permission.POST_NOTIFICATIONS)
        seedPreferences()
        val ownerToken = token(owner); val otherToken = token(other)
        assertTrue(platform.schedule(ownerToken)); assertTrue(platform.schedule(otherToken))
        assertTrue(platform.post(owner, DailyReminderMessage("隔离提醒", "隔离测试内容")))
        assertTrue(platform.post(other, DailyReminderMessage("隔离提醒", "另一账号隔离测试内容")))
        assertNotNull(pending(ownerToken)); assertNotNull(pending(otherToken))
        assertTrue(journal.record(ConfirmedAccountDeletion(owner, "receipt")))
        val graph = ProductionAppGraph(context, SessionRuntime(), HttpTransport { error("Cleanup must not call network") }, Credentials())
        assertEquals(AppStartupResult.SignedOut, graph.resolveStartupForApp())
        val active = manager.activeNotifications
        val errors = buildList {
            if (pending(ownerToken) != null) add("Deleted owner's alarm is still registered")
            if (active.any { it.tag == platform.notificationTag(owner) }) add("Deleted owner's notification is still posted")
            if (pending(otherToken) == null) add("Another owner's alarm was removed")
            if (active.none { it.tag == platform.notificationTag(other) }) add("Another owner's notification was removed")
        }
        assertEquals(emptyList<String>(), errors)
        // Host restores the pre-suite notification permission; no non-fixture notification is cancelled.
    }

    @Test fun failedReminderDeletionCannotAcknowledgeWholeAccountCleanupOrRemoveReceipt() = runBlocking {
        seedPreferences(); failOwnerRemoval = true
        val runtime = SessionRuntime().apply { update("isolated-access", owner) }
        val credentials = Credentials(StoredSession(owner, "isolated-refresh", true))
        var calls = 0
        val graph = ProductionAppGraph(context, runtime, HttpTransport { calls++; HttpResponse(200, "{\"status\":\"DELETED\"}") }, credentials)
        assertEquals(AccountDeletionResult.Failed, graph.accountDeletionRepository().delete("delete-command"))
        assertEquals(1, calls)
        assertEquals(saved, store.read(owner))
        assertEquals(listOf(ConfirmedAccountDeletion(owner, "delete-command")), (journal.read() as AccountDeletionCleanupLoad.Ready).entries)
    }

    private fun seedPreferences() {
        assertTrue(store.compareAndSet(owner, DailyReminderPreferences(), saved))
        assertTrue(store.compareAndSet(other, DailyReminderPreferences(), saved))
    }
    private fun pending(token: DailyReminderScheduleToken) = PendingIntent.getBroadcast(context, 0,
        platform.alarmIntent(token), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    private fun token(account: String) = DailyReminderScheduleToken(account, 1,
        LocalDate.parse("2040-01-02"), "Asia/Shanghai", Instant.parse("2040-01-02T12:00:00Z"))
    private class Credentials(var value: StoredSession? = null) : SessionCredentialStore {
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) { value = session }
        override suspend fun clear() { value = null }
    }
}
