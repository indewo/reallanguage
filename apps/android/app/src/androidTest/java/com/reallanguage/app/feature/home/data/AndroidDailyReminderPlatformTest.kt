package com.reallanguage.app.feature.home.data

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.feature.home.domain.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AndroidDailyReminderPlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun alarmPayloadContainsNoPlainAccountAndRejectsAlteredOrOtherOwnerTokens() {
        val platform = AndroidDailyReminderPlatform(context)
        val token = token()
        val intent = platform.alarmIntent(token)
        assertFalse(intent.toUri(Intent.URI_INTENT_SCHEME).contains(token.accountId))
        assertEquals(token, platform.readToken(intent, token.accountId))
        assertNull(platform.readToken(intent, "another-account"))
        assertNull(platform.readToken(Intent(intent).putExtra("revision", -1L), token.accountId))
        assertNull(platform.readToken(Intent(intent).putExtra("date", "bad-date"), token.accountId))
        assertNull(platform.readToken(Intent(intent).setAction("untrusted"), token.accountId))
    }

    @Test fun realAlarmIsRegisteredAndCancellationDoesNotRemoveAnotherOwnersAlarm() {
        val platform = AndroidDailyReminderPlatform(context)
        val first = token(); val second = token()
        try {
            assertTrue(platform.schedule(first)); assertTrue(platform.schedule(second))
            assertNotNull(pending(platform, first)); assertNotNull(pending(platform, second))
            platform.cancel(first.accountId)
            assertNull(pending(platform, first)); assertNotNull(pending(platform, second))
        } finally { platform.cancel(first.accountId); platform.cancel(second.accountId) }
    }

    @Test fun realNotificationUsesPrivateContentHomeIntentAndCanBeCancelledWithoutTouchingAnotherOwner() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "reminder-test-${UUID.randomUUID()}"
        val platform = AndroidDailyReminderPlatform(context, channel)
        val first = token(); val second = token()
        try {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            assertEquals(ReminderPermission.GRANTED, platform.permission())
            assertTrue(platform.post(first.accountId, DailyReminderMessage("每日学习提醒", "有未完成学习。回到首页继续。")))
            assertTrue(platform.post(second.accountId, DailyReminderMessage("每日学习提醒", "有到期回顾。回到首页查看。")))
            val notification = manager.activeNotifications.single { it.tag == platform.notificationTag(first.accountId) }.notification
            assertEquals(android.app.Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals(channel, notification.channelId)
            assertNotNull(notification.contentIntent)
            assertEquals("每日学习提醒", notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
            platform.cancel(first.accountId)
            assertFalse(manager.activeNotifications.any { it.tag == platform.notificationTag(first.accountId) })
            assertTrue(manager.activeNotifications.any { it.tag == platform.notificationTag(second.accountId) })
        } finally {
            platform.cancel(first.accountId); platform.cancel(second.accountId); manager.deleteNotificationChannel(channel)
            // Revoking a runtime permission kills the instrumented app. The host
            // restores the prior permission after this isolated suite finishes.
        }
    }

    private fun pending(platform: AndroidDailyReminderPlatform, token: DailyReminderScheduleToken): PendingIntent? =
        PendingIntent.getBroadcast(context, 0, platform.alarmIntent(token), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    private fun token() = DailyReminderScheduleToken("reminder-test-${UUID.randomUUID()}", 1,
        LocalDate.parse("2026-09-09"), "Asia/Shanghai", Instant.parse("2026-09-09T12:00:00Z"))
}
