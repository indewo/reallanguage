package com.reallanguage.app.feature.home.domain

import kotlinx.coroutines.CancellationException

/** Only minimal task facts are allowed in a lock-screen notification. */
data class DailyReminderMessage(val title: String, val body: String)

interface DailyReminderDeliveryPreferences {
    suspend fun nextSchedule(): DailyReminderScheduleDecision
    suspend fun claim(token: DailyReminderScheduleToken): ReminderDeliveryClaim
    suspend fun mayPostReserved(token: DailyReminderScheduleToken): Boolean
}

interface DailyReminderPlatform {
    fun schedule(token: DailyReminderScheduleToken): Boolean
    fun cancel(accountId: String)
    fun post(accountId: String, message: DailyReminderMessage): Boolean
}

enum class DailyReminderDeliveryResult { POSTED, ALREADY_RESERVED, REJECTED, FACTS_UNAVAILABLE, STORAGE_FAILED, POST_FAILED }

/** Calls are serialized with preference changes and logout by the Android runtime. */
class DailyReminderDelivery(
    private val identity: ReminderLocalIdentity,
    private val preferences: DailyReminderDeliveryPreferences,
    private val facts: ReminderTaskFactsReader,
    private val platform: DailyReminderPlatform,
) {
    suspend fun reconcile(): DailyReminderScheduleDecision {
        val owner = identity.accountId
        val decision = if (owner == null || !identity.isCurrent())
            DailyReminderScheduleDecision.Cancel(owner, ReminderCancelReason.SIGNED_OUT)
        else preferences.nextSchedule()
        return try {
            when (decision) {
                is DailyReminderScheduleDecision.ScheduleFuture -> {
                    if (!identity.isCurrent() || decision.token.accountId != owner) {
                        owner?.let(platform::cancel)
                        DailyReminderScheduleDecision.Cancel(owner, ReminderCancelReason.SIGNED_OUT)
                    } else if (platform.schedule(decision.token)) decision
                    else DailyReminderScheduleDecision.Cancel(owner, ReminderCancelReason.PLATFORM_UNAVAILABLE).also {
                        owner?.let(platform::cancel)
                    }
                }
                is DailyReminderScheduleDecision.Cancel -> decision.also { owner?.let(platform::cancel) }
                is DailyReminderScheduleDecision.NeedsDecision -> decision.also { owner?.let(platform::cancel) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DailyReminderScheduleDecision.Cancel(owner, ReminderCancelReason.PLATFORM_UNAVAILABLE) }
    }

    suspend fun deliver(token: DailyReminderScheduleToken): DailyReminderDeliveryResult {
        if (token.accountId != identity.accountId || !identity.isCurrent()) return DailyReminderDeliveryResult.REJECTED
        val snapshot = facts.read() as? ReminderTaskFactsResult.Ready ?: return DailyReminderDeliveryResult.FACTS_UNAVAILABLE
        if (!identity.isCurrent()) return DailyReminderDeliveryResult.REJECTED
        return when (preferences.claim(token)) {
            ReminderDeliveryClaim.Reserved -> {
                if (!identity.isCurrent() || !preferences.mayPostReserved(token)) return DailyReminderDeliveryResult.REJECTED
                val posted = try { platform.post(token.accountId, reminderMessage(snapshot.facts)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                // A failed Android post is not a reason to release/retry today's slot.
                // The durable claim still prevents duplicate alerts after a crash.
                reconcile()
                if (posted) DailyReminderDeliveryResult.POSTED else DailyReminderDeliveryResult.POST_FAILED
            }
            ReminderDeliveryClaim.AlreadyReserved -> DailyReminderDeliveryResult.ALREADY_RESERVED.also { reconcile() }
            ReminderDeliveryClaim.Rejected -> DailyReminderDeliveryResult.REJECTED
            ReminderDeliveryClaim.StorageFailed -> DailyReminderDeliveryResult.STORAGE_FAILED
        }
    }
}

fun reminderMessage(facts: ReminderTaskFacts): DailyReminderMessage = DailyReminderMessage("每日学习提醒", when {
    facts.review == ReminderReviewAvailability.AVAILABLE && facts.hasIncompleteLearning -> "有到期回顾，也有未完成学习。回到首页继续。"
    facts.review == ReminderReviewAvailability.AVAILABLE -> "有到期回顾。回到首页查看。"
    facts.hasIncompleteLearning -> "有未完成学习。回到首页继续。"
    else -> "坚持练习，把真正需要的英语，一句句变成能在真实场景中使用的表达。"
})
