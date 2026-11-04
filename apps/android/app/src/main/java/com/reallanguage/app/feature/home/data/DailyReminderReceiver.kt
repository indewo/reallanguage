package com.reallanguage.app.feature.home.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** No separate process, token refresh, network call or learning write is used. */
class DailyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(AndroidDailyReminderPlatform.ACTION_DELIVER, Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(8_000) { AndroidDailyReminderRuntime(context).receive(intent) } }
            catch (_: CancellationException) { /* No send/claim is retried after interruption. */ }
            catch (_: Exception) { /* No credentials, learning text or guessed success enters logs. */ }
            finally { pending.finish() }
        }
    }
}
