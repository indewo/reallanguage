package com.reallanguage.app.feature.home.domain

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DailyReminderDeliveryTest {
    @Test fun `future reconciliation schedules exactly the persisted token`() = runBlocking {
        val f = Fixture(); assertEquals(f.decision, f.delivery.reconcile())
        assertEquals(listOf("schedule"), f.events); assertEquals(f.token, f.scheduled)
    }

    @Test fun `unresolved missed time cancels old alarms and never uses future candidate`() = runBlocking {
        val f = Fixture(); f.decision = DailyReminderScheduleDecision.NeedsDecision(ReminderDecisionGap.MISSED_TIME, f.token)
        f.delivery.reconcile(); assertEquals(listOf("cancel:a"), f.events); assertNull(f.scheduled)
    }

    @Test fun `signed out reconciliation cancels only the captured account`() = runBlocking {
        val f = Fixture(); f.current = false
        f.delivery.reconcile(); assertEquals(listOf("cancel:a"), f.events); assertNull(f.scheduled)
    }

    @Test fun `delivery reads facts reserves rechecks posts and schedules next in that order`() = runBlocking {
        val f = Fixture(); assertEquals(DailyReminderDeliveryResult.POSTED, f.delivery.deliver(f.token))
        assertEquals(listOf("facts", "claim", "recheck", "post", "schedule"), f.events)
        assertEquals(1, f.posts); assertTrue(f.message!!.body.contains("未完成")); assertTrue(f.message!!.body.contains("回顾"))
    }

    @Test fun `another account or signed out token never reads or claims data`() = runBlocking {
        val f = Fixture()
        assertEquals(DailyReminderDeliveryResult.REJECTED, f.delivery.deliver(f.token.copy(accountId = "b")))
        f.current = false
        assertEquals(DailyReminderDeliveryResult.REJECTED, f.delivery.deliver(f.token))
        assertTrue(f.events.none { it == "facts" || it == "claim" || it == "post" })
    }

    @Test fun `failed facts do not invent a task or consume the daily slot`() = runBlocking {
        val f = Fixture(); f.factResult = ReminderTaskFactsResult.Failed
        assertEquals(DailyReminderDeliveryResult.FACTS_UNAVAILABLE, f.delivery.deliver(f.token))
        assertEquals(listOf("facts"), f.events)
    }

    @Test fun `duplicate and failed persistence never post`() = runBlocking {
        for ((claim, expected) in listOf(ReminderDeliveryClaim.AlreadyReserved to DailyReminderDeliveryResult.ALREADY_RESERVED,
            ReminderDeliveryClaim.StorageFailed to DailyReminderDeliveryResult.STORAGE_FAILED,
            ReminderDeliveryClaim.Rejected to DailyReminderDeliveryResult.REJECTED)) {
            val f = Fixture(); f.claim = claim
            assertEquals(expected, f.delivery.deliver(f.token)); assertEquals(0, f.posts)
        }
    }

    @Test fun `late logout or preference change prevents post even after claim`() = runBlocking {
        val f = Fixture(); f.beforeClaim = { f.current = false }
        assertEquals(DailyReminderDeliveryResult.REJECTED, f.delivery.deliver(f.token)); assertEquals(0, f.posts)
        val g = Fixture(); g.mayPost = false
        assertEquals(DailyReminderDeliveryResult.REJECTED, g.delivery.deliver(g.token)); assertEquals(0, g.posts)
    }

    @Test fun `failed platform posting is not reported as success and reserved slot is never released`() = runBlocking {
        val f = Fixture(); f.postSucceeds = false
        assertEquals(DailyReminderDeliveryResult.POST_FAILED, f.delivery.deliver(f.token))
        assertEquals(1, f.events.count { it == "claim" }); assertEquals("schedule", f.events.last())
    }

    @Test fun `missing or deferred tasks use only approved vision not a false due claim`() {
        val empty = reminderMessage(ReminderTaskFacts(ReminderReviewAvailability.NOT_DUE, false))
        val deferred = reminderMessage(ReminderTaskFacts(ReminderReviewAvailability.DEFERRED_BY_DAILY_LIMIT, false))
        assertEquals(empty, deferred); assertEquals("每日学习提醒", empty.title)
        assertEquals("坚持练习，把真正需要的英语，一句句变成能在真实场景中使用的表达。", empty.body)
        assertFalse(empty.body.contains("流利"))
    }

    @Test fun `cancellation is propagated without posting or claiming`() = runBlocking {
        val f = Fixture(); f.cancelFacts = true
        try { f.delivery.deliver(f.token); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(listOf("facts"), f.events)
    }

    private class Fixture {
        val token = DailyReminderScheduleToken("a", 1, LocalDate.parse("2026-09-08"), "Asia/Shanghai", Instant.parse("2026-09-08T12:00:00Z"))
        var decision: DailyReminderScheduleDecision = DailyReminderScheduleDecision.ScheduleFuture(token)
        var current = true; var mayPost = true; var cancelFacts = false; var postSucceeds = true
        var claim: ReminderDeliveryClaim = ReminderDeliveryClaim.Reserved
        var beforeClaim: () -> Unit = {}
        var factResult: ReminderTaskFactsResult = ReminderTaskFactsResult.Ready(ReminderTaskFacts(ReminderReviewAvailability.AVAILABLE, true))
        val events = mutableListOf<String>(); var posts = 0; var scheduled: DailyReminderScheduleToken? = null; var message: DailyReminderMessage? = null
        val delivery = DailyReminderDelivery(object : ReminderLocalIdentity {
            override val accountId = "a"
            override suspend fun isCurrent() = current
        }, object : DailyReminderDeliveryPreferences {
            override suspend fun nextSchedule() = decision
            override suspend fun claim(token: DailyReminderScheduleToken): ReminderDeliveryClaim {
                events += "claim"; beforeClaim(); return claim
            }
            override suspend fun mayPostReserved(token: DailyReminderScheduleToken): Boolean { events += "recheck"; return mayPost }
        }, ReminderTaskFactsReader {
            events += "facts"; if (cancelFacts) throw CancellationException(); factResult
        }, object : DailyReminderPlatform {
            override fun schedule(token: DailyReminderScheduleToken): Boolean { events += "schedule"; scheduled = token; return true }
            override fun cancel(accountId: String) { events += "cancel:$accountId" }
            override fun post(accountId: String, message: DailyReminderMessage): Boolean {
                events += "post"; posts++; this@Fixture.message = message; return postSucceeds
            }
        })
    }
}
