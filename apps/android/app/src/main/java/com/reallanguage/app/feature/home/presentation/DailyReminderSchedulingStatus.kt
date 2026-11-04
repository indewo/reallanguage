package com.reallanguage.app.feature.home.presentation

import com.reallanguage.app.feature.home.domain.DailyReminderScheduleDecision
import com.reallanguage.app.feature.home.domain.ReminderCancelReason
import com.reallanguage.app.feature.home.domain.ReminderDecisionGap

enum class DailyReminderSchedulingState {
    NOT_CHECKED,
    NOT_CONFIRMED,
    SYSTEM_ACCEPTED,
    DISABLED,
    SIGNED_OUT,
    PERMISSION_REQUIRED,
    SETTINGS_UNAVAILABLE,
    SYSTEM_UNAVAILABLE,
    MISSED_TIME_UNRESOLVED,
    TIME_ZONE_UNRESOLVED,
    LOCAL_TIME_UNRESOLVED,
}

data class DailyReminderSchedulingStatus(
    val state: DailyReminderSchedulingState,
    val message: String,
) {
    val systemAccepted: Boolean get() = state == DailyReminderSchedulingState.SYSTEM_ACCEPTED
}

/**
 * Only the runtime's completed reconcile result may set [confirmedByReconcile].
 * A preference or future candidate is neither an accepted alarm nor delivery evidence.
 * Saving preferences is a separate acknowledgement and is never inferred here.
 */
fun dailyReminderSchedulingStatus(
    decision: DailyReminderScheduleDecision?,
    confirmedByReconcile: Boolean = false,
): DailyReminderSchedulingStatus {
    val state = when (decision) {
        null -> DailyReminderSchedulingState.NOT_CHECKED
        is DailyReminderScheduleDecision.ScheduleFuture -> if (confirmedByReconcile)
            DailyReminderSchedulingState.SYSTEM_ACCEPTED else DailyReminderSchedulingState.NOT_CONFIRMED
        is DailyReminderScheduleDecision.NeedsDecision -> when (decision.reason) {
            ReminderDecisionGap.MISSED_TIME -> DailyReminderSchedulingState.MISSED_TIME_UNRESOLVED
            ReminderDecisionGap.TIME_ZONE_CHANGED -> DailyReminderSchedulingState.TIME_ZONE_UNRESOLVED
            ReminderDecisionGap.AMBIGUOUS_OR_MISSING_LOCAL_TIME -> DailyReminderSchedulingState.LOCAL_TIME_UNRESOLVED
        }
        is DailyReminderScheduleDecision.Cancel -> when (decision.reason) {
            ReminderCancelReason.DISABLED -> DailyReminderSchedulingState.DISABLED
            ReminderCancelReason.SIGNED_OUT -> DailyReminderSchedulingState.SIGNED_OUT
            ReminderCancelReason.PERMISSION_UNAVAILABLE -> DailyReminderSchedulingState.PERMISSION_REQUIRED
            ReminderCancelReason.STORAGE_UNAVAILABLE -> DailyReminderSchedulingState.SETTINGS_UNAVAILABLE
            ReminderCancelReason.PLATFORM_UNAVAILABLE -> DailyReminderSchedulingState.SYSTEM_UNAVAILABLE
        }
    }
    val message = when (state) {
        DailyReminderSchedulingState.NOT_CHECKED -> "尚未检查系统提醒安排。"
        DailyReminderSchedulingState.NOT_CONFIRMED -> "已有提醒时间候选，尚未确认系统安排。"
        DailyReminderSchedulingState.SYSTEM_ACCEPTED -> "系统已接受提醒安排；实际显示时间可能受系统限制影响。"
        DailyReminderSchedulingState.DISABLED -> "每日提醒已关闭。"
        DailyReminderSchedulingState.SIGNED_OUT -> "当前未登录，未安排每日提醒。"
        DailyReminderSchedulingState.PERMISSION_REQUIRED -> "尚未获得通知权限，未安排每日提醒。"
        DailyReminderSchedulingState.SETTINGS_UNAVAILABLE -> "暂时无法读取提醒设置，尚未确认提醒安排。"
        DailyReminderSchedulingState.SYSTEM_UNAVAILABLE -> "暂时无法确认系统提醒安排，请稍后重试。"
        DailyReminderSchedulingState.MISSED_TIME_UNRESOLVED -> "已错过本次提醒时间；未补发，也尚未安排下一次提醒。"
        DailyReminderSchedulingState.TIME_ZONE_UNRESOLVED -> "提醒时区需要确认，暂未安排提醒。"
        DailyReminderSchedulingState.LOCAL_TIME_UNRESOLVED -> "当前时区下，这个时间可能重复出现或不存在，暂未安排提醒。"
    }
    return DailyReminderSchedulingStatus(state, message)
}
