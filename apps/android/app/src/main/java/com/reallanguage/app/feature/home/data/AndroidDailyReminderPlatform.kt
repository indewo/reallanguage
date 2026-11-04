package com.reallanguage.app.feature.home.data

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.reallanguage.app.MainActivity
import com.reallanguage.app.feature.home.domain.*
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

class AndroidDailyReminderPlatform(
    private val context: Context,
    internal val channelId: String = "daily-learning-reminders",
) : DailyReminderPlatform {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun permission(): ReminderPermission {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return ReminderPermission.DENIED
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return ReminderPermission.DENIED
        if (notifications.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return ReminderPermission.DENIED
        return ReminderPermission.GRANTED
    }

    private fun alarmIdentity(accountId: String) = Intent(context, DailyReminderReceiver::class.java)
        .setAction(ACTION_DELIVER).setData(Uri.parse("reallanguage-reminder://daily/${accountKey(accountId)}"))
    internal fun alarmIntent(token: DailyReminderScheduleToken): Intent = alarmIdentity(token.accountId)
        .putExtra("revision", token.revision).putExtra("date", token.localDate.toString())
        .putExtra("zone", token.zoneId).putExtra("at", token.scheduledAt.toEpochMilli())

    internal fun readToken(intent: Intent, currentAccountId: String): DailyReminderScheduleToken? = try {
        if (currentAccountId.isBlank() || intent.action != ACTION_DELIVER || intent.data != alarmIdentity(currentAccountId).data) null
        else {
            require(intent.hasExtra("revision") && intent.hasExtra("at"))
            val revision = intent.getLongExtra("revision", -1L); require(revision >= 0)
            val date = LocalDate.parse(requireNotNull(intent.getStringExtra("date")))
            val zone = ZoneId.of(requireNotNull(intent.getStringExtra("zone")))
            val at = Instant.ofEpochMilli(intent.getLongExtra("at", Long.MIN_VALUE))
            require(at.atZone(zone).toLocalDate() == date)
            DailyReminderScheduleToken(currentAccountId, revision, date, zone.id, at)
        }
    } catch (_: Exception) { null }

    internal fun notificationTag(accountId: String): String = "daily-${accountKey(accountId)}"
    override fun schedule(token: DailyReminderScheduleToken): Boolean = try {
        val operation = PendingIntent.getBroadcast(context, 0, alarmIntent(token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, token.scheduledAt.toEpochMilli(), operation)
        true
    } catch (_: Exception) { false }

    override fun cancel(accountId: String) {
        PendingIntent.getBroadcast(context, 0, alarmIdentity(accountId), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            alarms.cancel(it); it.cancel()
        }
        notifications.cancel(notificationTag(accountId), NOTIFICATION_ID)
    }

    /** Account deletion must confirm removal, not merely issue a best-effort platform request. */
    internal fun cancelAndConfirm(accountId: String): Boolean = try {
        require(accountId.isNotBlank())
        cancel(accountId)
        val remainingAlarm = PendingIntent.getBroadcast(context, 0, alarmIdentity(accountId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        remainingAlarm == null && notifications.activeNotifications.none {
            it.tag == notificationTag(accountId) && it.id == NOTIFICATION_ID
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    override fun post(accountId: String, message: DailyReminderMessage): Boolean {
        if (permission() != ReminderPermission.GRANTED) return false
        notifications.createNotificationChannel(NotificationChannel(channelId, "每日学习提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val home = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_HOME)
            .setData(Uri.parse("reallanguage-reminder://home/${accountKey(accountId)}"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val content = PendingIntent.getActivity(context, 0, home, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(message.title).setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(content)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setOnlyAlertOnce(true).build()
        notifications.notify(notificationTag(accountId), NOTIFICATION_ID, notification)
        return true
    }

    companion object {
        const val ACTION_DELIVER = "com.reallanguage.app.DAILY_REMINDER_DELIVER"
        const val ACTION_OPEN_HOME = "com.reallanguage.app.DAILY_REMINDER_HOME"
        private const val NOTIFICATION_ID = 2001
        internal fun accountKey(accountId: String): String = MessageDigest.getInstance("SHA-256")
            .digest(accountId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
