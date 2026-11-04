package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.*
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.safety.domain.SourceType
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Actual production pages, graph, encrypted source and SQLite. Preaccepted fixture; no AI-quality claim. */
class ProductionFormationPageJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val account = "page-journey-$id"
    private val files = File(base.cacheDir, "page-journey-$id")
    private val isolatedDb = IsolatedLearningDatabaseContext(base, "page-journey")
    private val context = object : ContextWrapper(isolatedDb) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = files
    }
    private val session = SessionRuntime().apply { update("isolated-token", account) }
    private val graph = ProductionAppGraph(context, session, HttpTransport { error("This preaccepted local fixture must not invoke AI") })
    private val units = listOf(SemanticUnit("s1", 0, "我喝茶。"), SemanticUnit("s2", 1, "我散步。"), SemanticUnit("s3", 2, "这句完整原文没有被选入章节。"))
    private val chapters = listOf(EnglishChapterDraft("chapter", listOf(
        EnglishSentenceDraft("s1", units[0].textZh, true, listOf(EnglishCandidate("e1", "I drink tea."))),
        EnglishSentenceDraft("s2", units[1].textZh, true, listOf(EnglishCandidate("e2", "I walk."))),
    )))
    private val initial = HomeDiaryRoute.LearningTargetSelection(units.joinToString("") { it.textZh }, units, chapters,
        mapOf("s1" to "e1", "s2" to "e2"), emptyList(), emptyList(), initialSelectedTargetIds = emptySet(),
        entryId = "target-page", sourceEntryId = "complete-source")

    @After fun cleanOnlyFixture() = runBlocking(Dispatchers.IO) {
        val hash = MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
        AndroidFormationPayloadKeys().deleteAccount(hash)
        AndroidFixedSourcePayloadKeys().deleteAccount(hash)
        check(files.name == "page-journey-$id"); files.deleteRecursively()
        context.deleteDatabase("real-language-learning.db")
        Unit
    }

    @Test(timeout = 60_000) fun actualPagesKeepSecondSentenceOnReturnAndFixTheCompleteSourceWithAReadableChapter() {
        Log.i("RealLanguageJourney", "mount actual target page")
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.setContent { RlTheme { HomeProductionFlow(graph, initialRoute = initial) } }
        Log.i("RealLanguageJourney", "await target page semantics")
        waitForTag("learning_targets_page")
        Log.i("RealLanguageJourney", "click first sentence continue")
        compose.onNodeWithText("继续").performClick()
        Log.i("RealLanguageJourney", "verify second sentence persistence")
        awaitSnapshot { it.targetSentenceIndex == 1 }
        compose.onNodeWithText("I walk.").assertIsDisplayed()
        compose.onNodeWithText("继续").performClick()
        Log.i("RealLanguageJourney", "await final page")
        waitForTag("final_fix_confirm")
        val final = awaitSnapshot { it.phase == FormationRecoveryPhase.FINAL_FIX }
        assertEquals(FormationRecoveryPhase.FINAL_FIX, final.phase)
        assertEquals(1, final.targetSentenceIndex)
        compose.onNodeWithText("返回修改").performClick()
        waitForTag("learning_targets_page")
        compose.onNodeWithText("I walk.").assertIsDisplayed()
        compose.onNodeWithText("继续").performClick()
        waitForTag("final_fix_confirm")
        val confirmed = awaitSnapshot { it.phase == FormationRecoveryPhase.FINAL_FIX }
        compose.onNodeWithTag("final_fix_confirm").performClick()
        waitForTag("chapter_entry_start")
        val reread = runBlocking { AndroidFixedSourceRepository(context, session).load(requireNotNull(confirmed.fixEntryId)) }
        assertTrue(reread is FixedSourceLoad.Loaded)
        val source = (reread as FixedSourceLoad.Loaded).source
        assertEquals(initial.text, source.source.text)
        assertEquals(listOf("s1", "s2", "s3"), source.units.map { it.unitId })
        assertEquals(mapOf("s1" to "e1", "s2" to "e2"), source.selectedCandidateIds)
        assertEquals(emptySet<String>(), source.selectedTargetIds)
        assertNull(runBlocking { graph.readFormation(initial.sourceEntryId) })
    }

    @Test(timeout = 30_000) fun productionEncryptedProgressAcceptsComplexDiaryAndRecordingTextBeforeOpeningTheNextStep() = runBlocking {
        val complex = "\n\r\n  " + "这".repeat(31) + "😀，如果明天下雨，我仍会按约定完成学习。"
        val routes = listOf(
            HomeDiaryRoute.SemanticSplit(complex, entryId = "complex-diary"),
            HomeDiaryRoute.SemanticSplit(complex, SourceType.RECORDING,
                sourceReturnRoute = HomeDiaryRoute.TranscriptResult("recording-complex", "transcript-complex"),
                entryId = "complex-recording"),
        )
        for (route in routes) {
            val saved = graph.prepareFormation(route)
            assertNotNull(saved)
            assertEquals(complex, saved!!.source.text)
            assertTrue(saved.sourceTitle.isNotBlank())
            assertEquals(saved, graph.readFormation(requireNotNull(route.entryId)))
        }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    // Do not block the Compose test clock on a journal lock held by a Main-dispatched
    // page observer. IO reads run independently while waitUntil advances UI work.
    private fun awaitSnapshot(predicate: (FormationRecoverySnapshot) -> Boolean): FormationRecoverySnapshot {
        val result = AtomicReference<FormationRecoverySnapshot?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch {
            while (isActive) {
                graph.readFormation(initial.sourceEntryId)?.takeIf(predicate)?.let { result.set(it); return@launch }
                delay(20)
            }
        }
        try {
            compose.waitUntil(10_000) { result.get() != null }
            return requireNotNull(result.get())
        } finally { job.cancel(); scope.cancel() }
    }
}
