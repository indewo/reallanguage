package com.reallanguage.app.feature.home.data

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.core.session.androidSessionCredentialStore
import com.reallanguage.app.feature.home.domain.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DailyReminderRuntimePersistenceTest {
    @Test fun encryptedLocalIdentityToDailyClaimAndRealNotificationSurvivesRecreationAndLogoutCancels() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val prefix = "daily-runtime-${UUID.randomUUID()}"
        val privateRoot = File(target.cacheDir, prefix)
        val files = mutableSetOf<String>()
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(privateRoot, "no-backup")
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = "$prefix-$name"; files += scoped
                return target.getSharedPreferences(scoped, mode)
            }
            override fun getDatabasePath(name: String): File = target.getDatabasePath("$prefix-$name")
        }
        val account = "$prefix-account"
        val credentials = androidSessionCredentialStore(isolated)
        val clock = Clock.fixed(Instant.parse("2040-01-02T12:00:00Z"), ZoneId.of("UTC"))
        fun runtime() = AndroidDailyReminderRuntime(isolated, clock) { ZoneId.of("Asia/Shanghai") }
        val manager = target.getSystemService(NotificationManager::class.java)
        val priorChannel = manager.getNotificationChannel("daily-learning-reminders")
        try {
            instrumentation.uiAutomation.grantRuntimePermission(target.packageName, Manifest.permission.POST_NOTIFICATIONS)
            credentials.save(StoredSession(account, "isolated-non-network-test-token", true))
            val first = runtime(); val identity = requireNotNull(androidReminderLocalIdentity(isolated))
            assertEquals(account, identity.accountId)
            val repository = first.repository(identity)
            assertEquals(DailyReminderIntroResult.Saved, repository.recordChoice(DailyReminderChoice.ENABLED, "intro"))
            val token = (repository.nextSchedule() as DailyReminderScheduleDecision.ScheduleFuture).token
            assertEquals(clock.instant(), token.scheduledAt)
            val intent = first.platform.alarmIntent(token)
            val recreated = runtime()
            recreated.receive(intent)
            val posted = manager.activeNotifications.single { it.tag == recreated.platform.notificationTag(account) }
            val saved = requireNotNull(runtime().repository(requireNotNull(androidReminderLocalIdentity(isolated))).load())
            assertEquals(setOf(token.localDate), saved.claimedLocalDates)
            assertEquals(token.revision + 1, saved.revision)
            recreated.receive(intent)
            assertEquals(posted.postTime, manager.activeNotifications.single { it.tag == recreated.platform.notificationTag(account) }.postTime)
            credentials.clear()
            runtime().signedOut(account)
            assertFalse(manager.activeNotifications.any { it.tag == recreated.platform.notificationTag(account) })
            recreated.receive(intent)
            assertFalse(manager.activeNotifications.any { it.tag == recreated.platform.notificationTag(account) })
            assertFalse(isolated.getDatabasePath("real-language-learning.db").exists())
        } finally {
            runtime().platform.cancel(account); credentials.clear()
            files.forEach { target.deleteSharedPreferences(it) }
            check(privateRoot.name == prefix); privateRoot.deleteRecursively()
            if (priorChannel == null) manager.deleteNotificationChannel("daily-learning-reminders")
            // Host restores the original permission after the isolated suite.
        }
    }
}
