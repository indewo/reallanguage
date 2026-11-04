package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.review.data.AndroidReviewChapterRepository
import com.reallanguage.app.feature.review.domain.ReviewChapterLoadResult
import com.reallanguage.app.testing.ReviewNoJudgmentFixture
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real production navigation and SQLite; only unavailable scene generation is a transport double. */
class ProductionPatternNoJudgmentFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val fixture = ReviewNoJudgmentFixture(InstrumentationRegistry.getInstrumentation().targetContext)
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val credentialAccess = AtomicInteger()
    private val mounted = mutableStateOf(false)

    @After fun cleanOnlyUuidFixture() {
        if (mounted.value) { compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
        fixture.close()
    }

    @Test(timeout = 90_000)
    fun unavailableScenesReallyReturnToNextChapterAndFinishWithoutChangingAnyFormalKnowledge() {
        val before = awaitIo { fixture.seed(chapterCount = 2); fixture.formalSnapshot() }
        val queued = awaitIo { fixture.queue() }
        assertEquals(listOf("隔离回顾1", "隔离回顾2"), queued.map { it.sourceTitle })
        val graph = ProductionAppGraph(fixture.context, fixture.runtime, HttpTransport { request ->
            requests += request.path
            check(request.path == "/api/v1/learning/pattern-practice-sets") {
                "No microphone evaluation, explanation, or other paid operation is allowed in this isolated test"
            }
            HttpResponse(200, """{"status":"NOT_NATURALLY_VERIFIABLE"}""")
        }, object : SessionCredentialStore {
            override suspend fun load(): StoredSession? { credentialAccess.incrementAndGet(); error("No credentials in isolated review") }
            override suspend fun save(session: StoredSession) { credentialAccess.incrementAndGet(); error("No credentials in isolated review") }
            override suspend fun clear() { credentialAccess.incrementAndGet(); error("No credentials in isolated review") }
        })
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.activityRule.scenario.onActivity { configureFocusedLearningWindow(it.window) }
        mounted.value = true
        compose.setContent { RlTheme { RlPaperBackground {
            if (mounted.value) HomeProductionFlow(graph, initialRoute = HomeDiaryRoute.TodayReview)
        } } }
        waitForTag("today-review-start")
        compose.onNodeWithTag("today-review-start").performClick()
        for (index in 1..2) {
            waitForTag("review-chapter-continue")
            compose.onNodeWithText("隔离回顾$index").assertExists()
            compose.onNodeWithTag("review-chapter-continue").performClick()
            waitForText("当前句式暂时无法生成自然的独立回应场景。")
            if (index == 1) captureIfRequested()
            compose.onNodeWithText("开始回应").assertDoesNotExist()
            compose.onNodeWithText("继续").performScrollTo().performClick()
            compose.waitUntil(15_000) {
                fixture.count("review_chapter_results") == index
            }
            assertEquals(before, awaitIo { fixture.formalSnapshot() })
            assertEquals(index, fixture.scalar("select completed_focus_count from review_sessions where account_id=?"))
        }
        waitForTag("review-result-summary")
        compose.onNodeWithText("本次回顾已完成").assertIsDisplayed()
        assertEquals(1, fixture.count("review_session_settlements"))
        assertEquals(2, fixture.count("learning_obligations"))
        assertEquals(2, requests.size)
        assertEquals(0, credentialAccess.get())
        assertEquals(before, awaitIo { fixture.formalSnapshot() })
        val freshRepository = AndroidReviewChapterRepository(fixture.context, fixture.runtime)
        queued.forEach { chapter -> assertEquals(ReviewChapterLoadResult.Failed,
            awaitIo { freshRepository.loadChapter(chapter.reviewChapterId) }) }
        assertTrue(awaitIo { fixture.queue() }.isEmpty())
        compose.onNodeWithTag("review-result-home").assertIsEnabled()
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun waitForText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
    private fun captureIfRequested() {
        val phase = InstrumentationRegistry.getArguments().getString("patternNoJudgmentCapture") ?: return
        require(phase in setOf("before", "after"))
        val view = compose.activity.window.decorView
        val drawn = AtomicBoolean()
        val observer = ViewTreeObserver.OnDrawListener { drawn.set(true) }
        compose.activityRule.scenario.onActivity {
            view.viewTreeObserver.addOnDrawListener(observer)
            view.postInvalidateOnAnimation()
        }
        compose.waitUntil(10_000) { drawn.get() }
        compose.activityRule.scenario.onActivity { view.viewTreeObserver.removeOnDrawListener(observer) }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 5_000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "visual-qa").apply { mkdirs() }
        File(directory, "37-pattern-production-paper-unavailable-$phase.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try {
            compose.waitUntil(20_000) { result.get() != null }
            return requireNotNull(result.get()).getOrThrow()
        } finally { job.cancel(); scope.cancel() }
    }
}
