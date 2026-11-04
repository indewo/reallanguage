package com.reallanguage.app.core.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.reallanguage.app.feature.home.domain.DailyReminderScheduleDecision
import com.reallanguage.app.feature.home.domain.ReminderCancelReason
import com.reallanguage.app.feature.home.presentation.dailyReminderSchedulingStatus
import kotlinx.coroutines.CancellationException

@Composable
internal fun ReminderSchedulingEntry(
    accountId: String?,
    currentAccount: () -> String?,
    reconcile: suspend () -> DailyReminderScheduleDecision,
    content: @Composable (String?) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestAccount by rememberUpdatedState(currentAccount)
    val latestReconcile by rememberUpdatedState(reconcile)
    var message by remember(accountId) { mutableStateOf<String?>(null) }
    var activation by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) to 0)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!activation.first) activation = true to (activation.second + 1)
                Lifecycle.Event.ON_PAUSE -> activation = false to activation.second
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Capture the effect's state, not a live getter: an initial inactive effect must not read
    // the next resume and issue the same platform reconciliation twice before cancellation.
    val observedActivation = activation
    LaunchedEffect(accountId, observedActivation) {
        message = null
        if (!observedActivation.first || accountId == null || latestAccount() != accountId) return@LaunchedEffect
        val decision = try { latestReconcile() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DailyReminderScheduleDecision.Cancel(accountId, ReminderCancelReason.PLATFORM_UNAVAILABLE) }
        // A suspended platform read must never publish the previous account's state into a new session.
        if (latestAccount() == accountId) {
            message = dailyReminderSchedulingStatus(decision, confirmedByReconcile = true).message
        }
    }
    content(message)
}
