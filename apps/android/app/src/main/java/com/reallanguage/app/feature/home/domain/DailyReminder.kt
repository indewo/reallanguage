package com.reallanguage.app.feature.home.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class ReminderPermission { UNKNOWN, GRANTED, DENIED }
enum class ReminderDecisionGap { MISSED_TIME, TIME_ZONE_CHANGED, AMBIGUOUS_OR_MISSING_LOCAL_TIME }
enum class ReminderCancelReason { DISABLED, SIGNED_OUT, PERMISSION_UNAVAILABLE, STORAGE_UNAVAILABLE, PLATFORM_UNAVAILABLE }

data class DailyReminderPreferences(
    val introHandled: Boolean = false,
    val enabled: Boolean = false,
    val time: LocalTime = LocalTime.of(20, 0),
    val zoneId: String? = null,
    val permission: ReminderPermission = ReminderPermission.UNKNOWN,
    val revision: Long = 0,
    val claimedLocalDates: Set<LocalDate> = emptySet(),
    val introCommandId: String? = null,
    val introChoice: DailyReminderChoice? = null,
)

data class DailyReminderScheduleToken(
    val accountId: String,
    val revision: Long,
    val localDate: LocalDate,
    val zoneId: String,
    val scheduledAt: Instant,
)

sealed interface DailyReminderScheduleDecision {
    data class ScheduleFuture(val token: DailyReminderScheduleToken) : DailyReminderScheduleDecision
    data class Cancel(val accountId: String?, val reason: ReminderCancelReason) : DailyReminderScheduleDecision
    /** A future candidate is not permission to catch up the unresolved past occurrence. */
    data class NeedsDecision(val reason: ReminderDecisionGap, val futureCandidate: DailyReminderScheduleToken? = null) : DailyReminderScheduleDecision
}

sealed interface ReminderDeliveryClaim {
    /** Reserves the daily slot only. It does not mean Android delivered a notification. */
    data object Reserved : ReminderDeliveryClaim
    data object AlreadyReserved : ReminderDeliveryClaim
    data object Rejected : ReminderDeliveryClaim
    data object StorageFailed : ReminderDeliveryClaim
}

data class ReminderPreferenceChange(val saved: Boolean, val cancelScheduledForAccount: String?)

fun futureReminderDecision(accountId: String, preferences: DailyReminderPreferences, now: Instant, currentZone: ZoneId,
    permission: ReminderPermission): DailyReminderScheduleDecision {
    if (!preferences.enabled) return DailyReminderScheduleDecision.Cancel(accountId, ReminderCancelReason.DISABLED)
    if (permission != ReminderPermission.GRANTED) return DailyReminderScheduleDecision.Cancel(accountId, ReminderCancelReason.PERMISSION_UNAVAILABLE)
    if (preferences.zoneId != currentZone.id) return DailyReminderScheduleDecision.NeedsDecision(ReminderDecisionGap.TIME_ZONE_CHANGED)
    val today = now.atZone(currentZone).toLocalDate()
    fun unclaimedDate(from: LocalDate): LocalDate = generateSequence(from) { it.plusDays(1) }
        .take(preferences.claimedLocalDates.size + 1).first { it !in preferences.claimedLocalDates }
    fun candidate(date: LocalDate): DailyReminderScheduleToken? = reminderInstant(date, preferences.time, currentZone)?.let {
        DailyReminderScheduleToken(accountId, preferences.revision, date, currentZone.id, it)
    }
    val next = candidate(unclaimedDate(today)) ?: return DailyReminderScheduleDecision.NeedsDecision(ReminderDecisionGap.AMBIGUOUS_OR_MISSING_LOCAL_TIME)
    if (next.scheduledAt < now) return DailyReminderScheduleDecision.NeedsDecision(
        ReminderDecisionGap.MISSED_TIME, candidate(unclaimedDate(today.plusDays(1))),
    )
    return DailyReminderScheduleDecision.ScheduleFuture(next)
}

/** Do not silently select either offset or move a nonexistent wall-clock time. */
fun reminderInstant(date: LocalDate, time: LocalTime, zone: ZoneId): Instant? {
    val local = date.atTime(time)
    return zone.rules.getValidOffsets(local).singleOrNull()?.let(local::toInstant)
}
