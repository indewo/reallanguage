package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.AndroidFormationPayloadKeys
import com.reallanguage.app.feature.formation.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in two-process production-page evidence. Inputs below are explicitly preaccepted
 * isolated formation outputs, not AI quality evidence. No HTTP request is permitted.
 * Write intentionally keeps its exact UUID fixture for a host-controlled force-stop.
 */
class ProductionFormationProcessRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test(timeout = 60_000)
    fun writeSecondSentenceAndChangedSelectionToActualEncryptedRecovery() {
        val fixture = fixtureFor("write")
        check(!fixture.directory.exists()) { "Use a new UUID; do not overwrite a previous recovery fixture" }
        check(fixture.directory.mkdirs())
        val visible = mutableStateOf(true)
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.setContent { RlTheme {
            if (visible.value) HomeProductionFlow(fixture.graph, initialRoute = fixture.initial)
        } }
        waitForTag("learning_targets_page")
        compose.onNodeWithText("继续").performClick()
        awaitSnapshot(fixture) { it.targetSentenceIndex == 1 }
        compose.onNodeWithText("I walk.").assertIsDisplayed()
        compose.onNodeWithTag("learning_target_walk").performClick()
        val saved = awaitSnapshot(fixture) {
            it.targetSentenceIndex == 1 && it.selectedTargetIds == setOf("drink")
        }
        assertAcceptedFixture(saved, fixture)
        assertEquals(0, fixture.requests.get())
        awaitIo { fixture.marker.writeText(Process.myPid().toString()) }
        Log.i("RealLanguageProcessJourney", "WRITE_READY run=${fixture.runId} pid=${Process.myPid()} sentenceIndex=1 selectedTargets=1 httpCalls=0")
        // Do not clear the database, encrypted files, or account-scoped Keystore alias here.
    }

    @Test(timeout = 60_000)
    fun readNewGraphThroughTheActualUnfinishedEntryWithoutRegeneration() {
        val fixture = fixtureFor("read")
        check(fixture.directory.isDirectory && fixture.marker.isFile) { "Run the write phase with this UUID first" }
        val writtenPid = awaitIo {
            require(fixture.marker.length() in 1..20)
            fixture.marker.readText().toInt()
        }
        assertNotEquals("Host must stop the old target process before the read phase", writtenPid, Process.myPid())
        val before = awaitIo { requireNotNull(fixture.graph.readFormation(fixture.sourceEntryId)) }
        assertAcceptedFixture(before, fixture)
        val visible = mutableStateOf(true)
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.setContent { RlTheme {
            if (visible.value) HomeProductionFlow(fixture.graph, initialRoute = HomeDiaryRoute.IncompleteLearning())
        } }
        waitForTag("incomplete-learning-resume-${fixture.sourceEntryId}")
        compose.onNodeWithTag("incomplete-learning-resume-${fixture.sourceEntryId}").performClick()
        waitForTag("learning_targets_page")
        compose.onNodeWithText("I walk.").assertIsDisplayed()
        assertAcceptedFixture(awaitSnapshot(fixture) { it.targetSentenceIndex == 1 }, fixture)
        // The restored second sentence remains usable and can reach confirmation locally.
        compose.onNodeWithText("继续").performClick()
        waitForTag("final_fix_confirm")
        val final = awaitSnapshot(fixture) { it.phase == FormationRecoveryPhase.FINAL_FIX }
        assertAcceptedFixture(final, fixture)
        assertEquals(0, fixture.requests.get())
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        awaitIo { fixture.cleanExactRun() }
        Log.i("RealLanguageProcessJourney", "READ_VERIFIED run=${fixture.runId} oldPid=$writtenPid pid=${Process.myPid()} sentenceIndex=1 selectedTargets=1 httpCalls=0 cleanup=complete")
    }

    /** Only for a failed/aborted run, with the exact same explicit UUID. */
    @Test(timeout = 30_000)
    fun cleanupOnlyTheExplicitIsolatedRun() {
        val fixture = fixtureFor("cleanup")
        awaitIo { fixture.cleanExactRun() }
        Log.i("RealLanguageProcessJourney", "CLEANUP_COMPLETE run=${fixture.runId}")
    }

    private fun fixtureFor(phase: String): Fixture {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("formationPhase") == phase)
        val raw = requireNotNull(args.getString("formationRunId"))
        require(raw.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
        require(UUID.fromString(raw).toString() == raw)
        return Fixture(InstrumentationRegistry.getInstrumentation().targetContext, raw)
    }

    private fun assertAcceptedFixture(snapshot: FormationRecoverySnapshot, fixture: Fixture) {
        assertEquals(fixture.sourceEntryId, snapshot.sourceEntryId)
        assertEquals(1, snapshot.targetSentenceIndex)
        assertEquals(setOf("drink"), snapshot.selectedTargetIds)
        assertEquals(fixture.initial.targets, snapshot.targets)
        assertEquals(fixture.initial.selections, snapshot.selectedCandidateIds)
        assertEquals(fixture.initial.text, snapshot.source.text)
        assertEquals(listOf("s1", "s2", "s3"), snapshot.units.map { it.unitId })
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun awaitSnapshot(fixture: Fixture, predicate: (FormationRecoverySnapshot) -> Boolean): FormationRecoverySnapshot = awaitIo {
        withTimeout(15_000) {
            var found: FormationRecoverySnapshot? = null
            while (found == null) {
                found = fixture.graph.readFormation(fixture.sourceEntryId)?.takeIf(predicate)
                if (found == null) delay(20)
            }
            requireNotNull(found)
        }
    }

    /** Never runBlocking the instrumentation thread while a Compose observer owns the journal. */
    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try {
            compose.waitUntil(20_000) { result.get() != null }
            return requireNotNull(result.get()).getOrThrow()
        } finally { job.cancel(); scope.cancel() }
    }

    private class Fixture(base: Context, val runId: String) {
        val directory = File(base.cacheDir, "formation-process-$runId").canonicalFile
        private val account = "formation-process-$runId"
        private val database = File(directory, "learning.db")
        val marker = File(directory, "pid.marker")
        val sourceEntryId = "source-$runId"
        val requests = AtomicInteger()
        private val session = SessionRuntime().apply { update("isolated-preaccepted-session", account) }
        private val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = File(directory, "private")
            override fun getDatabasePath(name: String): File {
                require(name == "real-language-learning.db")
                return database
            }
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?) =
                SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
            override fun deleteDatabase(name: String) = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        }
        val graph by lazy { ProductionAppGraph(context, session, HttpTransport {
            requests.incrementAndGet()
            error("Preaccepted isolated formation output must be recovered without HTTP or AI")
        }) }
        private val units = listOf(SemanticUnit("s1", 0, "我喝茶。"), SemanticUnit("s2", 1, "我散步。"),
            SemanticUnit("s3", 2, "完整来源的这句话没有选入重点章节。"))
        private val chapters = listOf(EnglishChapterDraft("chapter-$runId", listOf(
            EnglishSentenceDraft("s1", units[0].textZh, true, listOf(EnglishCandidate("e1", "I drink tea."))),
            EnglishSentenceDraft("s2", units[1].textZh, true, listOf(EnglishCandidate("e2", "I walk."))),
        )))
        val initial = HomeDiaryRoute.LearningTargetSelection(units.joinToString("") { it.textZh }, units, chapters,
            mapOf("s1" to "e1", "s2" to "e2"),
            listOf(
                LearningTarget("drink", "s1", "drink", "喝", LearningTargetType.WORD,
                    sourceEvidence = SourceEvidence(1, 2, 7, "drink", "drink", listOf("drink"))),
                LearningTarget("walk", "s2", "walk", "散步", LearningTargetType.WORD,
                    sourceEvidence = SourceEvidence(1, 2, 6, "walk", "walk", listOf("walk"))),
            ), emptyList(), initialSelectedTargetIds = setOf("drink", "walk"),
            entryId = "target-$runId", sourceEntryId = sourceEntryId)

        init { check(directory.parentFile == base.cacheDir.canonicalFile && directory.name == "formation-process-$runId") }

        fun cleanExactRun() {
            check(directory.name == "formation-process-$runId" && directory.canonicalFile == directory)
            val hash = MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
            AndroidFormationPayloadKeys().deleteAccount(hash)
            SQLiteDatabase.deleteDatabase(database)
            check(!directory.exists() || directory.deleteRecursively())
            check(!directory.exists())
        }
    }
}
