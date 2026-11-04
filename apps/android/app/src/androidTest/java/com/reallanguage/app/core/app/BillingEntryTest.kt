package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.billing.domain.AiBillingRepository
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshot
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshotResult
import com.reallanguage.app.feature.billing.presentation.AiBillingViewModel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual production Entry lifecycle and effects, with in-memory reads only.
 * No Graph, stored account, DB, HTTP, recharge, refund or payment provider is invoked. */
class BillingEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun landscape() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(5_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun removingTheCompositionCancelsItsPendingReadWithoutNavigation() {
        val visible = mutableStateOf(true)
        val reads = AtomicInteger()
        val cancelled = AtomicInteger()
        val destinations = AtomicInteger()
        val factory = factory {
            reads.incrementAndGet()
            try { awaitCancellation() } finally { cancelled.incrementAndGet() }
        }
        rule.setContent { RlTheme {
            if (visible.value) BillingEntry(
                factory = factory,
                entryId = "pending",
                onAccount = { destinations.incrementAndGet() },
                onRecharge = { destinations.incrementAndGet() },
                onRefund = { destinations.incrementAndGet() },
            )
        } }
        rule.waitUntil(5_000) { reads.get() == 1 }
        rule.runOnIdle { visible.value = false }
        rule.waitForIdle()
        assertEquals("Disposal must clear the entry's read, not wait for Activity destruction", 1, cancelled.get())
        assertEquals(0, destinations.get())
    }

    @Test fun returningAndReenteringTheSameEntryReadsAgainAndCanReturnAgain() {
        val visible = mutableStateOf(true)
        val reads = AtomicInteger()
        val returns = AtomicInteger()
        val otherDestinations = AtomicInteger()
        val factory = factory { reads.incrementAndGet(); emptySummary() }
        rule.setContent { RlTheme {
            if (visible.value) BillingEntry(
                factory = factory,
                entryId = "same-entry",
                onAccount = { returns.incrementAndGet(); visible.value = false },
                onRecharge = { otherDestinations.incrementAndGet() },
                onRefund = { otherDestinations.incrementAndGet() },
            )
        } }
        rule.waitUntil(5_000) { reads.get() == 1 }
        rule.onNodeWithTag("ai-billing-back").performClick()
        rule.waitUntil(5_000) { returns.get() == 1 }
        rule.waitForIdle()
        rule.runOnIdle { visible.value = true }
        rule.waitForIdle()
        assertEquals("A same-ID reentry cannot reuse the departed ViewModel", 2, reads.get())
        rule.onNodeWithTag("ai-billing-back").performClick()
        rule.waitUntil(5_000) { returns.get() == 2 }
        assertEquals(0, otherDestinations.get())
    }

    @Test fun recompositionUsesTheLatestAccountRechargeAndRefundCallbacksWithoutReloading() {
        val generation = mutableStateOf(0)
        val reads = AtomicInteger()
        val callbacks = CopyOnWriteArrayList<Pair<String, Int>>()
        val factory = factory { reads.incrementAndGet(); emptySummary() }
        val recharge = rule.activity.getString(R.string.ai_billing_recharge)
        val refund = rule.activity.getString(R.string.ai_billing_refund)
        rule.setContent { RlTheme {
            val captured = generation.value
            BillingEntry(
                factory = factory,
                entryId = "callbacks",
                onAccount = { callbacks += "account" to captured },
                onRecharge = { callbacks += "recharge" to captured },
                onRefund = { callbacks += "refund" to captured },
            )
        } }
        rule.waitUntil(5_000) { reads.get() == 1 }
        rule.waitForIdle()
        rule.runOnIdle { generation.value = 1 }
        rule.waitForIdle()
        // These callbacks only observe effects; they do not open any payment destination.
        rule.onNodeWithText(recharge).performClick()
        rule.waitUntil(5_000) { callbacks.size == 1 }
        assertEquals(listOf("recharge" to 1), callbacks.toList())
        rule.onNodeWithText(refund).performClick()
        rule.waitUntil(5_000) { callbacks.size == 2 }
        rule.onNodeWithTag("ai-billing-back").performClick()
        rule.waitUntil(5_000) { callbacks.size == 3 }
        assertEquals(listOf("recharge" to 1, "refund" to 1, "account" to 1), callbacks.toList())
        assertEquals(1, reads.get())
    }

    @Test fun systemAndPageBackDuringLoadingReturnAccountAndCancelTheSameWay() {
        val visible = mutableStateOf(true)
        val phase = mutableStateOf(0)
        val reads = AtomicInteger()
        val cancelled = AtomicInteger()
        val returns = AtomicInteger()
        val parentBack = AtomicInteger()
        val otherDestinations = AtomicInteger()
        val factory = factory {
            reads.incrementAndGet()
            try { awaitCancellation() } finally { cancelled.incrementAndGet() }
        }
        rule.setContent { RlTheme {
            // Keep the isolated Activity alive if the Entry fails to intercept system back.
            BackHandler { parentBack.incrementAndGet() }
            if (visible.value) BillingEntry(
                factory = factory,
                entryId = "back-${phase.value}",
                onAccount = { returns.incrementAndGet(); visible.value = false },
                onRecharge = { otherDestinations.incrementAndGet() },
                onRefund = { otherDestinations.incrementAndGet() },
            )
        } }
        for (index in 0..1) {
            if (index == 1) {
                rule.runOnIdle { phase.value = 1; visible.value = true }
            }
            rule.waitUntil(5_000) { reads.get() == index + 1 }
            if (index == 0) {
                rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            } else {
                rule.onNodeWithTag("ai-billing-back").performClick()
            }
            rule.waitForIdle()
            assertEquals("Both return mechanisms use the account destination", index + 1, returns.get())
            assertEquals(0, parentBack.get())
            assertEquals(index + 1, cancelled.get())
            assertEquals(0, otherDestinations.get())
        }
    }

    private fun emptySummary() = AiBillingSnapshotResult.Ready(AiBillingSnapshot(0, emptyList()))

    private fun factory(read: suspend () -> AiBillingSnapshotResult) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AiBillingViewModel(object : AiBillingRepository {
            override suspend fun loadSnapshot() = read()
        }) as T
    }
}
