package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.review.data.AndroidPatternIndependentReceiptStore
import com.reallanguage.app.feature.review.data.AndroidReviewChapterRepository
import com.reallanguage.app.feature.review.domain.*
import com.reallanguage.app.feature.review.presentation.*
import com.reallanguage.app.testing.ReviewNoJudgmentFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Private SQLite + actual Home/Graph/exit factory. No transport, capture or permission changes. */
class ProductionPatternReceiptLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val fixture = ReviewNoJudgmentFixture(InstrumentationRegistry.getInstrumentation().targetContext)
    private val visible = mutableStateOf(false)
    private val currentGraph = mutableStateOf<ProductionAppGraph?>(null)
    private val exitOwners = ViewModelStore()

    @After fun cleanOnlyUuidFixture() {
        if (visible.value) { compose.runOnIdle { visible.value = false }; compose.waitForIdle() }
        exitOwners.clear()
        fixture.close()
    }

    @Test fun tokenRefreshKeepsTheSameLiveEntry() {
        mountChapter()
        val graph = requireNotNull(currentGraph.value)
        val original = requireNotNull(graph.currentPatternReviewEntry())
        compose.runOnIdle { fixture.runtime.update("isolated-refreshed-token", fixture.account) }
        compose.waitForIdle()
        assertSame(original, graph.currentPatternReviewEntry())
        assertTrue(original.isActiveFor(fixture.account, fixture.runtime.accountEpoch))
    }

    @Test fun sameAccountReloginReplacesTheLiveEntryWithoutReusingItsDraftOwner() {
        mountChapter()
        val graph = requireNotNull(currentGraph.value)
        val original = requireNotNull(graph.currentPatternReviewEntry())
        compose.runOnIdle { fixture.runtime.clear(); fixture.runtime.update("isolated-new-login", fixture.account) }
        compose.waitUntil(15_000) { graph.currentPatternReviewEntry()?.let { it !== original } == true }
        val renewed = requireNotNull(graph.currentPatternReviewEntry())
        assertFalse(original.isActiveFor(fixture.account, original.accountEpoch))
        assertNotSame(original, renewed)
        assertTrue(renewed.isActiveFor(fixture.account, fixture.runtime.accountEpoch))
        compose.onNodeWithTag("review-chapter-continue").assertIsDisplayed()
        assertEquals(0, fixture.count("review_chapter_results"))
    }

    @Test fun replacingTheGraphInTheSameHostRevokesOldEntryAndCreatesANewOwner() {
        mountChapter()
        val oldGraph = requireNotNull(currentGraph.value)
        val oldLease = requireNotNull(oldGraph.currentPatternReviewEntry())
        val replacement = graph()
        compose.runOnIdle { currentGraph.value = replacement }
        compose.waitForIdle()
        assertNull("The replaced graph must not retain a live receipt entry", oldGraph.currentPatternReviewEntry())
        val newLease = requireNotNull(replacement.currentPatternReviewEntry()) { "The current graph must own the rendered chapter" }
        assertNotSame(oldLease, newLease)
        assertFalse(oldLease.isActiveFor(fixture.account, fixture.runtime.accountEpoch))
        assertEquals(0, fixture.count("review_chapter_results"))
    }

    @Test fun successfulLocalExitRevokesRegistrationBeforeNavigationConsumesTheEffect() {
        val reviewId = mountChapter()
        val graph = requireNotNull(currentGraph.value)
        val lease = requireNotNull(graph.currentPatternReviewEntry())
        val chapter = (awaitIo { AndroidReviewChapterRepository(fixture.context, fixture.runtime).loadChapter(reviewId) } as ReviewChapterLoadResult.Ready).chapter
        val ability = chapter.abilities.single()
        val identity = PatternIndependentReceiptIdentity(fixture.account, fixture.runtime.accountEpoch, reviewId, lease.entryId,
            chapter.originalChapterId, ability.sentenceId, ability.abilityId, ability.targetId)
        val receipts = AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity)
        val explicitSyntheticReceipt = PatternIndependentReceipt(1, identity, "isolated-example", "isolated-command",
            "a".repeat(64), "b".repeat(64), PatternIndependentDecision.PASS, true, true, true, .9)
        assertTrue(awaitIo { receipts.register(explicitSyntheticReceipt) })
        val exit = compose.runOnIdle {
            graph.reviewExitFactory(reviewId).create(ReviewExitViewModel::class.java).also {
                exitOwners.put("isolated-exit", it)
                it.onAction(ReviewExitAction.ConfirmExitRequested)
            }
        }
        assertEquals(ReviewExitEffect.ReturnHome, awaitIo { exit.effects.first() })
        // Deliberately do not execute the UI ReturnHome callback: this is the
        // confirmed SQL exit -> navigation acknowledgement interval.
        assertEquals(0, fixture.count("review_working_state"))
        assertFalse("A late valid HTTP delivery must not recreate discarded work", awaitIo { receipts.register(explicitSyntheticReceipt) })
        assertFalse(lease.isActiveFor(fixture.account, fixture.runtime.accountEpoch))
        assertEquals(0, fixture.count("review_working_state"))
        assertEquals(0, fixture.count("review_chapter_results"))
    }

    private fun mountChapter(): String {
        val reviewId = awaitIo { fixture.seed(); fixture.queue().single().reviewChapterId }
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.activityRule.scenario.onActivity { configureFocusedLearningWindow(it.window) }
        currentGraph.value = graph()
        visible.value = true
        compose.setContent { RlTheme { RlPaperBackground {
            if (visible.value) HomeProductionFlow(requireNotNull(currentGraph.value), initialRoute = HomeDiaryRoute.ReviewChapter(reviewId, "same-route-entry"))
        } } }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("review-chapter-continue").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("review-chapter-continue").assertIsDisplayed()
        return reviewId
    }

    private fun graph() = ProductionAppGraph(fixture.context, fixture.runtime,
        HttpTransport { error("No HTTP or AI authorized in this lifecycle fixture") },
        object : SessionCredentialStore {
            override suspend fun load(): StoredSession? = error("No credentials")
            override suspend fun save(session: StoredSession) = error("No credentials")
            override suspend fun clear() = error("No credentials")
        })
    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try { compose.waitUntil(20_000) { result.get() != null }; return requireNotNull(result.get()).getOrThrow() }
        finally { job.cancel(); scope.cancel() }
    }
}
