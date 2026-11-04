package com.reallanguage.app.feature.home.presentation

import com.reallanguage.app.feature.home.domain.DailyReminderScheduleDecision
import com.reallanguage.app.feature.home.domain.DailyReminderScheduleToken
import com.reallanguage.app.feature.home.domain.ReminderCancelReason
import com.reallanguage.app.feature.home.domain.ReminderDecisionGap
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyReminderSchedulingStatusTest {
    private val candidate = DailyReminderScheduleToken(
        "isolated-owner", 7, LocalDate.of(2026, 9, 9), "Asia/Shanghai", Instant.parse("2026-09-09T12:00:00Z"),
    )

    @Test fun missingEvidenceRemainsNotChecked() {
        val status = dailyReminderSchedulingStatus(null)
        assertEquals(DailyReminderSchedulingState.NOT_CHECKED, status.state)
        assertEquals("尚未检查系统提醒安排。", status.message)
        assertFalse(status.systemAccepted)
    }

    @Test fun aFutureCandidateIsNotAnAcceptedSystemArrangementByDefault() {
        val status = dailyReminderSchedulingStatus(DailyReminderScheduleDecision.ScheduleFuture(candidate))
        assertEquals(DailyReminderSchedulingState.NOT_CONFIRMED, status.state)
        assertEquals("已有提醒时间候选，尚未确认系统安排。", status.message)
        assertFalse(status.systemAccepted)
    }

    @Test fun successfulRuntimeReconcileOnlyAcknowledgesSystemAcceptanceNotDelivery() {
        val status = dailyReminderSchedulingStatus(DailyReminderScheduleDecision.ScheduleFuture(candidate), confirmedByReconcile = true)
        assertEquals(DailyReminderSchedulingState.SYSTEM_ACCEPTED, status.state)
        assertEquals("系统已接受提醒安排；实际显示时间可能受系统限制影响。", status.message)
        assertTrue(status.systemAccepted)
    }

    @Test fun missedTimeNeverPromotesTomorrowCandidateOrClaimsCatchUp() {
        listOf(null, candidate).forEach { tomorrow ->
            listOf(false, true).forEach { confirmed ->
                val status = dailyReminderSchedulingStatus(
                    DailyReminderScheduleDecision.NeedsDecision(ReminderDecisionGap.MISSED_TIME, tomorrow), confirmed,
                )
                assertEquals(DailyReminderSchedulingState.MISSED_TIME_UNRESOLVED, status.state)
                assertEquals("已错过本次提醒时间；未补发，也尚未安排下一次提醒。", status.message)
                assertFalse(status.systemAccepted)
            }
        }
    }

    @Test fun timeZoneChangeDoesNotChooseAnOffsetOrDescribeAChosenFutureTime() {
        val status = dailyReminderSchedulingStatus(
            DailyReminderScheduleDecision.NeedsDecision(ReminderDecisionGap.TIME_ZONE_CHANGED, candidate), true,
        )
        assertEquals(DailyReminderSchedulingState.TIME_ZONE_UNRESOLVED, status.state)
        assertEquals("提醒时区需要确认，暂未安排提醒。", status.message)
        assertFalse(status.systemAccepted)
    }

    @Test fun repeatedOrMissingLocalTimeRemainsExplicitlyUnresolved() {
        val status = dailyReminderSchedulingStatus(
            DailyReminderScheduleDecision.NeedsDecision(ReminderDecisionGap.AMBIGUOUS_OR_MISSING_LOCAL_TIME, candidate), true,
        )
        assertEquals(DailyReminderSchedulingState.LOCAL_TIME_UNRESOLVED, status.state)
        assertEquals("当前时区下，这个时间可能重复出现或不存在，暂未安排提醒。", status.message)
        assertFalse(status.systemAccepted)
    }

    @Test fun everyCancellationReasonHasItsOwnHonestExplanation() {
        val expectations = mapOf(
            ReminderCancelReason.DISABLED to (DailyReminderSchedulingState.DISABLED to "每日提醒已关闭。"),
            ReminderCancelReason.SIGNED_OUT to (DailyReminderSchedulingState.SIGNED_OUT to "当前未登录，未安排每日提醒。"),
            ReminderCancelReason.PERMISSION_UNAVAILABLE to (DailyReminderSchedulingState.PERMISSION_REQUIRED to "尚未获得通知权限，未安排每日提醒。"),
            ReminderCancelReason.STORAGE_UNAVAILABLE to (DailyReminderSchedulingState.SETTINGS_UNAVAILABLE to "暂时无法读取提醒设置，尚未确认提醒安排。"),
            ReminderCancelReason.PLATFORM_UNAVAILABLE to (DailyReminderSchedulingState.SYSTEM_UNAVAILABLE to "暂时无法确认系统提醒安排，请稍后重试。"),
        )
        expectations.forEach { (reason, expectation) ->
            val status = dailyReminderSchedulingStatus(DailyReminderScheduleDecision.Cancel("isolated-owner", reason), true)
            assertEquals(reason.name, expectation.first, status.state)
            assertEquals(reason.name, expectation.second, status.message)
            assertFalse(status.systemAccepted)
        }
    }

    @Test fun noSchedulingExplanationClaimsPreferencePersistenceOrNotificationDelivery() {
        allDecisions().forEach { decision ->
            listOf(false, true).forEach { confirmed ->
                val message = dailyReminderSchedulingStatus(decision, confirmed).message
                listOf("已保存", "已送达", "已发送", "保证准时").forEach { unsupported ->
                    assertFalse("Scheduling alone cannot prove $unsupported", message.contains(unsupported))
                }
            }
        }
    }

    @Test fun onlyConfirmedFutureDecisionCanExposeSystemAcceptance() {
        allDecisions().forEach { decision ->
            listOf(false, true).forEach { confirmed ->
                assertEquals(
                    confirmed && decision is DailyReminderScheduleDecision.ScheduleFuture,
                    dailyReminderSchedulingStatus(decision, confirmed).systemAccepted,
                )
            }
        }
    }

    private fun allDecisions(): List<DailyReminderScheduleDecision?> = listOf<DailyReminderScheduleDecision?>(null, DailyReminderScheduleDecision.ScheduleFuture(candidate)) +
        ReminderCancelReason.entries.map { DailyReminderScheduleDecision.Cancel("isolated-owner", it) } +
        ReminderDecisionGap.entries.map { DailyReminderScheduleDecision.NeedsDecision(it, candidate) }
}
