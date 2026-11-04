package com.reallanguage.app.core.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.database.Cursor
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.formation.data.*
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.home.data.AndroidIncompleteLearningRepository
import com.reallanguage.app.feature.home.domain.IncompleteLearningLoadResult
import com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore
import com.reallanguage.app.feature.learning.domain.FixedChapterLoadResult
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import com.reallanguage.app.feature.safety.domain.SourceType
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Empty diary -> actual choices -> fixed chapter -> its first LR02 entry, through the real
 * ProductionGraph, VM, wire codecs, encrypted journal/source and SQLite. All provider responses
 * are explicit local HTTP fixtures, not AI-quality evidence. No chapter, target, final result,
 * learning progress or explanation is seeded. The entry-only case is 8 POST + 2 GET;
 * the two-focus reading/resume case is 13 POST + 4 GET. No microphone or real AI.
 * Optional process selectors use diaryProcessRunId + diaryProcessPhase=write/read/cleanup:
 * write retains 12 POST + 3 GET evidence; after an externally verified force-stop, read
 * restores before any POST and finishes with exactly 1 POST + 1 GET. Only a verified
 * read or the explicitly selected cleanup method removes the test-owned UUID resources.
 * Optional diaryProcessAwaitKill=true is write-only. After WRITE_READY is verified,
 * it holds this test thread for at most 30 seconds so the external runner can verify
 * the live PID and force-stop it. A natural timeout is failure, not kill evidence.
 */
class ProductionDiaryToLearningEntryJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentationArguments = InstrumentationRegistry.getArguments()
    private val processArguments = DiaryProcessArguments.read(instrumentationArguments)
    private val processAwaitKill = if (!instrumentationArguments.containsKey("diaryProcessAwaitKill")) false else {
        require(processArguments?.phase == "write" && instrumentationArguments.getString("diaryProcessAwaitKill") == "true") {
            "diaryProcessAwaitKill accepts only explicit true in the write phase; omit it otherwise"
        }
        true
    }
    private val runId = processArguments?.runId ?: UUID.randomUUID().toString()
    private val account = "diary-learning-entry-$runId"
    private val processFiles = processArguments?.let { DiaryJourneyProcessFiles(base, runId, account) }
    private val directory = processFiles?.directory ?: File(base.cacheDir, "diary-learning-entry-$runId")
    private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
    private val isolated = processFiles?.databaseContext ?: IsolatedLearningDatabaseContext(base, "diary-learning-entry-$runId")
    private val context = object : ContextWrapper(isolated) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(directory, "private")
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val privateName = "diary-learning-entry-$runId-$name"
            processFiles?.recordPreference(privateName)
            preferences.add(privateName)
            return base.getSharedPreferences(privateName, mode)
        }
    }
    private val session = SessionRuntime().apply { update("isolated-not-a-real-session", account) }
    private val otherSession = SessionRuntime().apply { update("isolated-other-token", "other-diary-learning-$runId") }
    private val credentialsTouched = AtomicInteger()
    private val requests = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private val fixtureFailure = AtomicReference<Throwable?>(null)
    private data class MountedGraph(val graph: ProductionAppGraph, val initialRoute: HomeDiaryRoute)
    private val host = mutableStateOf<MountedGraph?>(null)
    @Volatile private var extendReadingJourney = false
    @Volatile private var processReadVerified = false
    @Volatile private var processReadMayRequestFinalHint = false
    private var mounted = false
    private var originalMicrophonePermission: Int? = null
    private val graph by lazy { newGraph(session) }
    private fun newGraph(runtime: SessionRuntime) = ProductionAppGraph(context, runtime, HttpTransport { request ->
        try { fixtureResponse(request) }
        catch (failure: Throwable) {
            fixtureFailure.compareAndSet(null, failure)
            HttpResponse(500, "{\"error\":\"isolated-fixture-contract\"}")
        }
    }, object : SessionCredentialStore {
        override suspend fun load(): StoredSession? { credentialsTouched.incrementAndGet(); error("No credential read") }
        override suspend fun save(session: StoredSession) { credentialsTouched.incrementAndGet(); error("No credential save") }
        override suspend fun clear() { credentialsTouched.incrementAndGet(); error("No credential clear") }
    }).also { it.closeReminderForThisSession(account) }
    // A separately constructed reader opens the real encrypted files, never UI state or a stub.
    private val recoveryReader by lazy { AndroidFormationRecoveryRuntime(context, session) }
    private val units = listOf(
        SemanticUnit("u1", 0, "我今天待在家里。"),
        SemanticUnit("u2", 1, "我喝了茶。"),
        SemanticUnit("u3", 2, "我开始读书。"),
        SemanticUnit("u4", 3, "晚上我打算写笔记。"),
    )
    private val diary = units.joinToString("") { it.textZh }
    private val draftChapterId = "chapter-$runId"
    private val chosen = linkedMapOf("u1" to "u1-only", "u2" to "u2-second", "u3" to "u3-second")
    private val selectedEnglish = listOf("I stayed at home today.", "I drank tea.", "I started to read.")
    private val alternatives = mapOf("u2" to "I had some tea.", "u3" to "I began reading.")
    private val word = LearningTarget("tea", "u2", "tea", "茶", LearningTargetType.WORD,
        sourceEvidence = SourceEvidence(1, 8, 11, "tea", "tea", listOf("tea")),
        candidateExample = CandidateExample("She drinks tea.", "她喝茶。"),
        relatedVocabularyItems = emptyList(), relatedVocabularyItemRefs = emptyList())
    private val pattern = LearningTarget("start", "u3", "start to + verb", "开始做某事", LearningTargetType.PATTERN,
        structureParts = listOf("start to", "verb"),
        sourceEvidence = SourceEvidence(1, 2, 17, "started to read", "start to + verb", listOf("started to read")),
        candidateExample = CandidateExample("We started to walk.", "我们开始步行。"),
        relatedVocabularyItems = emptyList(), relatedVocabularyItemRefs = emptyList())
    private val targets = listOf(word, pattern)
    private val formalExamples = mapOf(
        "tea" to listOf("I like tea." to "我喜欢茶。", "She drinks tea." to "她喝茶。", "We made tea." to "我们沏了茶。"),
        "start" to listOf("I started to walk." to "我开始步行。", "She started to read." to "她开始阅读。", "We started to work." to "我们开始工作。"),
    )
    private val baseExplanation = "隔离接口夹具：这句话说明已经喝了茶，tea 在本句中的中文义为茶。"
    private val secondBaseExplanation = "隔离接口夹具：这句话说明开始读书，start to + verb 表示开始做某事。"
    private val fixedIdentity = AtomicReference<FixedIdentity?>(null)
    private data class FixedIdentity(val flow: String, val chapter: String, val context: String,
        val firstFocus: String, val secondFocus: String, val word: String, val pattern: String)

    @After fun cleanOnlyThisUuidFixture() {
        if (mounted) {
            compose.runOnIdle { host.value = null }
            compose.waitForIdle()
        }
        if (processFiles != null) {
            // A write or failed read is evidence for the next explicit phase. Never erase it
            // from @After, including when an assertion, fixture or missing marker fails.
            originalMicrophonePermission?.let { assertEquals(it,
                ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO)) }
            return
        }
        runBlocking(Dispatchers.IO) {
            val dbFile = context.getDatabasePath("real-language-learning.db").canonicalFile
            check(dbFile.parentFile == base.cacheDir.canonicalFile)
            check(dbFile.name.startsWith("diary-learning-entry-$runId-") && dbFile.name.endsWith(".db"))
            context.deleteDatabase("real-language-learning.db")
            val ownerHash = MessageDigest.getInstance("SHA-256").digest(account.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            AndroidFormationPayloadKeys().deleteAccount(ownerHash)
            AndroidFixedSourcePayloadKeys().deleteAccount(ownerHash)
            synchronized(preferences) { preferences.toList() }.forEach { name ->
                check(name.startsWith("diary-learning-entry-$runId-")); base.deleteSharedPreferences(name)
            }
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            check(directory.name == "diary-learning-entry-$runId")
            check(!directory.exists() || directory.deleteRecursively())
        }
        originalMicrophonePermission?.let { assertEquals("No microphone permission change is permitted", it,
            ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO)) }
    }

    @Test(timeout = 180_000)
    fun typedDiaryChoicesReachTheSameFixedChapterAndItsFirstLockedLearningSentence() {
        assumeTrue(processArguments == null)
        runJourney(false)
    }

    @Test(timeout = 240_000)
    fun secondFocusReadingResumesFromHomeWithoutRepeatingPaidHintsThenOpensFirstReadSpeak() {
        assumeTrue(processArguments == null)
        runJourney(true)
    }

    @Test(timeout = 240_000)
    fun writeSecondFocusReadingForProcessResume() {
        assumeTrue(processArguments?.phase == "write")
        awaitIo { requireNotNull(processFiles).beginFreshWrite() }
        runJourney(true)
        check(requireNotNull(processFiles).readyFile.isFile)
        if (processAwaitKill) {
            // runJourney already unmounted the page tree, checked every budget/state and
            // atomically wrote/read WRITE_READY. No work is scheduled by this hold.
            check(Looper.myLooper() != Looper.getMainLooper())
            assertWritePhaseBudget()
            assertNoRecordingFiles()
            Log.i("RealLanguageProcessJourney", "DIARY_WRITE_HOLD_READY run=$runId pid=${Process.myPid()}")
            CountDownLatch(1).await(30, TimeUnit.SECONDS)
            fail("Expected external force-stop of the verified live write PID within 30 seconds; natural timeout is not kill evidence")
        }
    }

    @Test(timeout = 150_000)
    fun readSecondFocusReadingFromHomeAfterForceStop() {
        assumeTrue(processArguments?.phase == "read")
        val files = requireNotNull(processFiles)
        val ready = awaitIo { files.openExistingRead() }
        assertNotEquals("Root must force-stop the write process before this instrumentation", ready.writePid, Process.myPid())
        originalMicrophonePermission = ready.microphonePermission
        assertEquals(ready.microphonePermission, ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO))
        extendReadingJourney = true
        val paused = awaitIo {
            val rows = allRows()
            assertEquals("The read phase must open the exact untouched write database", ready.tableDigests, diaryJourneyTableDigests(rows))
            val value = ready.identity
            val expectedIdentity = FixedIdentity(value.getValue("flow"), value.getValue("chapter"), value.getValue("context"),
                value.getValue("firstFocus"), value.getValue("secondFocus"), value.getValue("word"), value.getValue("pattern"))
            val source = (AndroidFixedSourceRepository(context, session).load(expectedIdentity.flow) as? FixedSourceLoad.Loaded)?.source
                ?: error("The actual encrypted complete source must survive; do not reconstruct it")
            assertEquals(value.getValue("source"), source.sourceEntryId)
            assertEquals(expectedIdentity, assertFixedContent(source))
            assertEquals(SourceType.DIARY, source.source.sourceType)
            assertEquals(FixedSourceOrigin.Diary, source.origin)
            assertEquals(FixedSourceLoad.Missing, AndroidFixedSourceRepository(context, otherSession).load(expectedIdentity.flow))
            assertNull(graph.readFormation(source.sourceEntryId))
            val first = requireNotNull(readingProgress(session, expectedIdentity, expectedIdentity.firstFocus))
            val second = requireNotNull(readingProgress(session, expectedIdentity, expectedIdentity.secondFocus))
            assertEquals("3", first["reads"]); assertEquals(explanationText(expectedIdentity, expectedIdentity.firstFocus, 3), first["hint"])
            assertEquals("1", second["reads"]); assertEquals(explanationText(expectedIdentity, expectedIdentity.secondFocus, 2), second["hint"])
            assertFalse(first.containsKey("pendingHintRound")); assertFalse(second.containsKey("pendingHintRound"))
            assertIncompletePointer(session, expectedIdentity, "LR-02", "explanation:${expectedIdentity.secondFocus}")
            PausedReading(expectedIdentity, source, rows, first, second)
        }
        fixedIdentity.set(paused.identity)
        processReadVerified = true
        assertEquals(0, observedRequests().size)
        mountGraph(graph, HomeDiaryRoute.Home)
        clickTag("home-incomplete-card")
        clickTag("incomplete-learning-resume-${paused.identity.chapter}")
        assertReadingPage(paused.identity, paused.identity.secondFocus)
        assertEquals(paused.second, awaitReadingProgress(session, paused.identity, paused.identity.secondFocus, 1))
        compose.onNodeWithText(explanationText(paused.identity, paused.identity.secondFocus, 2)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(explanationText(paused.identity, paused.identity.firstFocus, 3)).assertDoesNotExist()
        awaitIo {
            assertEquals(paused.first, readingProgress(session, paused.identity, paused.identity.firstFocus))
            assertEquals("Cold resume is read-only before an explicit new reading action", ready.tableDigests, diaryJourneyTableDigests(allRows()))
            assertEquals(paused.source, (AndroidFixedSourceRepository(context, session).load(paused.identity.flow) as FixedSourceLoad.Loaded).source)
        }
        assertProcessReadBudget(completed = false)
        // Only after the original second-round hint is actually visible may one new third-round delivery occur.
        processReadMayRequestFinalHint = true
        finishReadingInFirstReadSpeak(paused.identity, paused.source, paused.rows, paused.first, session, graph)
        compose.runOnIdle { host.value = null }
        compose.waitForIdle()
        assertNoRecordingFiles()
        assertEquals(0, credentialsTouched.get())
        awaitIo { files.cleanExactRun() }
        Log.i("RealLanguageProcessJourney", "DIARY_READ_VERIFIED run=$runId oldPid=${ready.writePid} pid=${Process.myPid()} reads=3+3 module=LR03 fixturePost=1 fixtureGet=1 aggregatePost=13 aggregateGet=4 realAi=0 microphone=0 cleanup=complete")
    }

    @Test(timeout = 30_000)
    fun cleanupOnlyExplicitDiaryProcessRun() {
        assumeTrue(processArguments?.phase == "cleanup")
        originalMicrophonePermission = ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO)
        awaitIo { requireNotNull(processFiles).cleanExactRun() }
        Log.i("RealLanguageProcessJourney", "DIARY_CLEANUP_COMPLETE run=$runId")
    }

    private data class PausedReading(val identity: FixedIdentity, val source: FixedSourceProjection,
        val rows: Map<String, List<List<String?>>>, val first: Map<String, String>, val second: Map<String, String>)

    private fun runJourney(continueThroughReading: Boolean) {
        extendReadingJourney = continueThroughReading
        originalMicrophonePermission = ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO)
        val emptyDatabase = awaitIo { allRows() }
        assertTrue(emptyDatabase.filterKeys { it != "android_metadata" }.values.all { it.isEmpty() })
        mountGraph(graph, HomeDiaryRoute.Input())
        waitTag("diary-text-input")
        // The real empty field also contains its placeholder Text semantics.
        compose.onNodeWithTag("diary-text-input")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            .performTextInput(diary)
        compose.onNodeWithTag("diary-text-input")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(diary)))
        compose.onNodeWithTag("diary-submit").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()

        waitTag("focus_confirm")
        compose.onNodeWithTag("focus_confirm").assertIsNotEnabled()
        val split = awaitSnapshot { it.phase == FormationRecoveryPhase.FOCUS_SELECTION && it.units.size == 4 }
        assertEquals(diary, split.source.text)
        assertEquals(SourceType.DIARY, split.source.sourceType)
        assertEquals(FormationSourceOrigin.Diary, split.origin)
        assertEquals(units, split.units)
        assertTrue(split.selectedUnitIds.isEmpty())
        awaitIo { assertEquals(FormationRecoveryLoad.Missing,
            AndroidFormationRecoveryRuntime(context, otherSession).repository.load(split.sourceEntryId)) }
        listOf("u2", "u3").forEach { unit ->
            compose.onNodeWithTag("focus_sentence_$unit").performScrollTo().assertIsDisplayed().performClick()
        }
        val focus = awaitSnapshot { it.phase == FormationRecoveryPhase.FOCUS_SELECTION && it.selectedUnitIds == listOf("u2", "u3") }
        assertEquals(split.sourceEntryId, focus.sourceEntryId)
        compose.onNodeWithTag("focus_confirm").assertIsDisplayed().assertIsEnabled().performClick()

        // The compact phone layout does not render the wide-only ledger container; the
        // acceptance bar is the shared, user-visible identity for both layouts.
        waitTag("english_confirmation_acceptance")
        // Entry preparation precedes the VM's initial single-candidate progress write.
        val english = awaitSnapshot { it.phase == FormationRecoveryPhase.ENGLISH_CONFIRMATION &&
            it.selectedCandidateIds["u1"] == "u1-only" }
        assertEquals(listOf("u1", "u2", "u3"), english.englishChapters.single().sentences.map { it.unitId })
        assertEquals(listOf(false, true, true), english.englishChapters.single().sentences.map { it.isFocus })
        assertEquals("Only the unique context expression is selected automatically", mapOf("u1" to "u1-only"), english.selectedCandidateIds)
        compose.onNodeWithText("继续选择学习目标").assertIsNotEnabled()
        listOf("u2", "u3").forEachIndexed { index, unit ->
            compose.onNode(hasText(selectedEnglish[index + 1]) and hasClickAction())
                .performScrollTo().assertIsDisplayed().performClick()
            awaitSnapshot { it.phase == FormationRecoveryPhase.ENGLISH_CONFIRMATION && it.selectedCandidateIds[unit] == chosen[unit] }
        }
        val confirmedEnglish = awaitSnapshot { it.phase == FormationRecoveryPhase.ENGLISH_CONFIRMATION && it.selectedCandidateIds == chosen }
        assertEquals(units, confirmedEnglish.units)
        compose.onNodeWithText("继续选择学习目标").assertIsDisplayed().assertIsEnabled().performClick()

        waitTag("learning_target_tea")
        compose.onNodeWithTag("learning_target_start").assertDoesNotExist()
        compose.onNodeWithText(selectedEnglish[1]).assertIsDisplayed()
        val first = awaitSnapshot { it.phase == FormationRecoveryPhase.LEARNING_TARGET_SELECTION && it.targets.size == 2 && it.targetSentenceIndex == 0 }
        assertEquals(targets, first.targets)
        assertEquals(setOf("tea", "start"), first.selectedTargetIds)
        assertEquals(chosen, first.selectedCandidateIds)
        assertEquals(split.sourceEntryId, first.sourceEntryId)
        compose.onNodeWithText("继续").assertIsDisplayed().assertIsEnabled().performClick()
        waitTag("learning_target_start")
        compose.onNodeWithTag("learning_target_tea").assertDoesNotExist()
        compose.onNodeWithText(selectedEnglish[2]).assertIsDisplayed()
        val second = awaitSnapshot { it.phase == FormationRecoveryPhase.LEARNING_TARGET_SELECTION && it.targetSentenceIndex == 1 }
        assertEquals(first.targets, second.targets)
        assertEquals(first.selectedTargetIds, second.selectedTargetIds)
        compose.onNodeWithText("继续").assertIsDisplayed().assertIsEnabled().performClick()
        waitTag("final_fix_confirm")
        val final = awaitSnapshot { it.phase == FormationRecoveryPhase.FINAL_FIX }
        assertEquals(1, final.targetSentenceIndex)
        assertEquals(chosen, final.selectedCandidateIds)
        assertEquals(targets, final.targets)
        val actualSource = requireNotNull(final.toFixedSourceProjection(requireNotNull(final.fixEntryId)))
        val beforeFix = awaitIo { allRows() }
        assertEquals("Formation must not silently create formal learning rows", emptyDatabase.filterKeys { it != "english_generation_knowledge_snapshots" },
            beforeFix.filterKeys { it != "english_generation_knowledge_snapshots" })
        assertEquals(5, observedRequests().count { it.method == "POST" })
        assertEquals(1, observedRequests().count { it.method == "GET" })
        compose.onNodeWithTag("final_fix_confirm").assertIsDisplayed().assertIsEnabled().performClick()

        waitTag("chapter_entry_start")
        val identity = awaitIo { assertFixedContent(actualSource) }
        fixedIdentity.set(identity)
        awaitIo { withTimeout(15_000) {
            while (graph.readFormation(final.sourceEntryId) != null) delay(20)
        } }
        assertEquals(7, observedRequests().count { it.method == "POST" })
        val fixedRows = awaitIo { allRows() }
        awaitIo {
            assertEquals(FixedSourceLoad.Missing, AndroidFixedSourceRepository(context, otherSession).load(identity.flow))
            assertEquals(FixedChapterLoadResult.Failed, AndroidFixedChapterRepository(context, otherSession).load(identity.chapter))
            assertEquals("Another account's read must not modify any product table", fixedRows, allRows())
        }
        val fixedWrites = setOf("english_generation_knowledge_snapshots", "formation_flows", "chapters", "fixed_sentences",
            "target_selections", "target_vocabulary_details", "target_pattern_details", "fixed_source_payloads", "fix_commands", "learning_checkpoints")
        assertEquals(emptyDatabase.filterKeys { it !in fixedWrites }, fixedRows.filterKeys { it !in fixedWrites })
        compose.onNodeWithTag("chapter_entry_start").assertIsDisplayed().assertIsEnabled().performClick()
        // On compact phones the explanation panel itself can extend beyond the viewport while
        // its real text is visible. Verify the mounted panel, then verify the user-visible copy.
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("explanation_content").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explanation_content").assertExists()
        compose.onNodeWithText(baseExplanation).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("learning-focus-${identity.firstFocus}").assertExists()
        compose.onNodeWithTag("learning-focus-${identity.context}").assertDoesNotExist()
        compose.onNodeWithTag("learning-focus-${identity.secondFocus}").assertDoesNotExist()
        awaitIo { withTimeout(15_000) {
            while (graph.learningCheckpointRepository.read(identity.chapter) !=
                OrdinaryLearningCheckpoint(identity.chapter, "LR-02", "explanation:${identity.firstFocus}")) delay(20)
        } }
        compose.waitUntil(10_000) { assertFixtureHealthy(); observedRequests().size == 10 }
        compose.waitForIdle()
        assertFixtureBudget()
        awaitIo {
            assertEquals(identity, assertFixedContent(actualSource))
            val end = allRows()
            assertEquals("Opening LR02 may only add its checkpoint and validated delivered explanation",
                fixedRows.filterKeys { it !in setOf("learning_checkpoints", "learning_explanation_cache") },
                end.filterKeys { it !in setOf("learning_checkpoints", "learning_explanation_cache") })
            FormationDatabase(context).use { helper ->
                helper.readableDatabase.rawQuery("select account_id,chapter_id,sentence_id,response_body from learning_explanation_cache", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(account, it.getString(0)); assertEquals(identity.chapter, it.getString(1))
                    assertEquals(identity.firstFocus, it.getString(2)); assertEquals(baseExplanation, JSONObject(it.getString(3)).getString("explanationZh"))
                    assertFalse(it.moveToNext())
                }
            }
        }
        assertEquals(0, credentialsTouched.get())
        Log.i("RealLanguageJourney", "DIARY_TO_FIXED_LR02 run=$runId sourceUnits=4 fixedSentences=3 focus=2 targets=2 formalExamples=6 fixturePost=8 fixtureGet=2 realAi=0 microphone=0")
        if (continueThroughReading) continueTwoFocusReadingAndResume(identity, actualSource, fixedRows)
    }

    private fun continueTwoFocusReadingAndResume(identity: FixedIdentity, source: FixedSourceProjection,
        fixedRows: Map<String, List<List<String?>>>) {
        // Real manual reading confirmations are not a claim of measured pronunciation.
        // No completion, progress payload or explanation cache is inserted by this test.
        var firstCompleted = emptyMap<String, String>()
        for (reads in 1..3) firstCompleted = confirmReading(session, identity, identity.firstFocus, reads)
        clickTag("explanation_understood")
        assertReadingPage(identity, identity.secondFocus)
        awaitCheckpoint(graph, identity, "LR-02", "explanation:${identity.secondFocus}")
        val secondPaused = confirmReading(session, identity, identity.secondFocus, 1)
        assertEquals("1", secondPaused["reads"])
        assertEquals(explanationText(identity, identity.secondFocus, 2), secondPaused["hint"])
        assertFalse(secondPaused.containsKey("pendingHintRound"))
        val beforePause = awaitIo { allRows() }
        val requestsBeforePause = observedRequests()
        assertEquals(12, requestsBeforePause.count { it.method == "POST" })
        assertEquals(3, requestsBeforePause.count { it.method == "GET" })

        compose.onNodeWithText(base.getString(R.string.learning_exit_open))
            .assertIsDisplayed().assertIsEnabled().performClick()
        waitTag("learning-exit-pause")
        clickTag("learning-exit-pause")
        waitTag("home-incomplete-card")
        awaitIo {
            assertIncompletePointer(session, identity, "LR-02", "explanation:${identity.secondFocus}")
            assertEquals("Pausing must not change stored reading, fixed facts or results", beforePause, allRows())
        }

        if (processArguments?.phase == "write") {
            compose.runOnIdle { host.value = null }
            compose.waitForIdle()
            assertNoRecordingFiles()
            assertWritePhaseBudget()
            awaitIo {
                assertEquals(beforePause, allRows())
                val ids = mapOf("flow" to identity.flow, "source" to source.sourceEntryId, "chapter" to identity.chapter,
                    "context" to identity.context, "firstFocus" to identity.firstFocus, "secondFocus" to identity.secondFocus,
                    "word" to identity.word, "pattern" to identity.pattern)
                requireNotNull(processFiles).writeReady(DiaryProcessReady(Process.myPid(), requireNotNull(originalMicrophonePermission),
                    ids, diaryJourneyTableDigests(beforePause), synchronized(preferences) { preferences.toSet() }))
            }
            Log.i("RealLanguageProcessJourney", "DIARY_WRITE_READY run=$runId pid=${Process.myPid()} firstReads=3 secondReads=1 fixturePost=12 fixtureGet=3 realAi=0 microphone=0 retained=true")
            return
        }

        // Destroy the actual mounted page tree before a new Graph/runtime opens Home.
        compose.runOnIdle { host.value = null }
        compose.waitForIdle()
        val resumedSession = SessionRuntime().apply { update("isolated-not-a-real-session", account) }
        val resumedGraph = newGraph(resumedSession)
        assertNotSame(graph, resumedGraph)
        awaitCheckpoint(resumedGraph, identity, "LR-02", "explanation:${identity.secondFocus}")
        compose.runOnIdle { host.value = MountedGraph(resumedGraph, HomeDiaryRoute.Home) }
        clickTag("home-incomplete-card")
        clickTag("incomplete-learning-resume-${identity.chapter}")
        assertReadingPage(identity, identity.secondFocus)
        val secondRestored = awaitReadingProgress(resumedSession, identity, identity.secondFocus, 1)
        assertEquals(secondPaused, secondRestored)
        compose.onNodeWithText(explanationText(identity, identity.secondFocus, 2))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(explanationText(identity, identity.firstFocus, 3)).assertDoesNotExist()
        awaitIo {
            assertEquals(firstCompleted, readingProgress(resumedSession, identity, identity.firstFocus))
            assertEquals("Read-only recovery must preserve every product table", beforePause, allRows())
            assertEquals(source, (AndroidFixedSourceRepository(context, resumedSession).load(identity.flow) as FixedSourceLoad.Loaded).source)
            assertEquals(FixedSourceLoad.Missing, AndroidFixedSourceRepository(context, otherSession).load(identity.flow))
        }
        assertEquals("Recovery must not repeat any already-paid POST", requestsBeforePause.filter { it.method == "POST" },
            observedRequests().filter { it.method == "POST" })
        assertEquals(4, observedRequests().count { it.method == "GET" })

        finishReadingInFirstReadSpeak(identity, source, fixedRows, firstCompleted, resumedSession, resumedGraph)
        Log.i("RealLanguageJourney", "DIARY_TWO_FOCUS_READS_RESUMED_TO_LR03 run=$runId reads=3+3 pausedSecondReads=1 graphRebuilt=true fixturePost=13 fixtureGet=4 realAi=0 microphone=0")
    }

    private fun finishReadingInFirstReadSpeak(identity: FixedIdentity, source: FixedSourceProjection,
        fixedRows: Map<String, List<List<String?>>>, firstCompleted: Map<String, String>,
        resumedSession: SessionRuntime, resumedGraph: ProductionAppGraph) {
        confirmReading(resumedSession, identity, identity.secondFocus, 2)
        val secondCompleted = confirmReading(resumedSession, identity, identity.secondFocus, 3)
        clickTag("explanation_understood")
        waitTag("read_speak_status")
        compose.onNodeWithText(base.getString(R.string.read_speak_ready)).assertIsDisplayed()
        compose.onNodeWithText(base.getString(R.string.read_speak_start)).assertDoesNotExist()
        compose.onNodeWithTag("read_speak_manual_finished").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("learning-focus-${identity.firstFocus}").assertExists()
        compose.onNodeWithTag("learning-focus-${identity.secondFocus}").assertDoesNotExist()
        compose.onNodeWithTag("learning-focus-${identity.context}").assertDoesNotExist()
        awaitCheckpoint(resumedGraph, identity, "LR-03", identity.firstFocus)
        awaitIo {
            // LR03's existing collector saves a blank working snapshot on entry. This is
            // not completed reading, feedback or captured audio; verify its exact defaults.
            withTimeout(15_000) {
                while (AndroidLearningProgressStore(context, resumedSession, identity.chapter, identity.firstFocus, "LR-03").read() == null) delay(20)
            }
            val resolved = resumedGraph.resolveResumeRoute(identity.chapter, "LR-03", identity.firstFocus)
            assertTrue(resolved is HomeDiaryRoute.ReadSpeak)
            resolved as HomeDiaryRoute.ReadSpeak
            assertEquals(identity.firstFocus, resolved.focusSentenceId)
            assertEquals(baseExplanation, resolved.baseExplanationZh)
            assertEquals(selectedEnglish, resolved.chapter.sentences.map { it.textEn })
            assertEquals(listOf(identity.context, identity.firstFocus, identity.secondFocus), resolved.chapter.sentences.map { it.sentenceId })
            assertTrue(resolved.expressions.any { it.display == "tea" && it.startOffset == 8 && it.endOffset == 11 })
            assertFalse(resolved.expressions.any { it.display.contains("started") || it.display.contains("read") })
            assertEquals(firstCompleted, readingProgress(resumedSession, identity, identity.firstFocus))
            assertEquals(secondCompleted, readingProgress(resumedSession, identity, identity.secondFocus))
            assertEquals(identity, assertFixedContent(source))
            assertEquals("Only working progress, checkpoint and delivered explanation cache may change through LR03 entry",
                fixedRows.filterKeys { it !in readingTables }, allRows().filterKeys { it !in readingTables })
            assertReadingRowsAndCaches(identity)
            assertIncompletePointer(resumedSession, identity, "LR-03", identity.firstFocus)
            assertTrue((AndroidIncompleteLearningRepository(context, otherSession).loadOrdinaryLearning()
                as IncompleteLearningLoadResult.Ready).entries.isEmpty())
        }
        if (processArguments?.phase == "read") assertProcessReadBudget(completed = true)
        else assertFixtureBudget(readingCompleted = true)
        assertNoRecordingFiles()
    }

    private fun mountGraph(graph: ProductionAppGraph, initialRoute: HomeDiaryRoute) {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        host.value = MountedGraph(graph, initialRoute)
        compose.setContent { RlTheme { RlPaperBackground {
            host.value?.let { current -> key(current.graph) {
                HomeProductionFlow(current.graph, initialRoute = current.initialRoute)
            } }
        } } }
        mounted = true
    }

    private fun confirmReading(runtime: SessionRuntime, identity: FixedIdentity, sentence: String, reads: Int): Map<String, String> {
        clickTag("explanation_read_finished")
        val saved = awaitReadingProgress(runtime, identity, sentence, reads)
        assertReadingPage(identity, sentence)
        compose.onNodeWithText(explanationText(identity, sentence, minOf(reads + 1, 3)))
            .performScrollTo().assertIsDisplayed()
        if (reads == 3) {
            waitTag("explanation_understood")
            compose.onNodeWithTag("explanation_read_finished").assertDoesNotExist()
        } else compose.onNodeWithTag("explanation_understood").assertDoesNotExist()
        return saved
    }

    private fun assertReadingPage(identity: FixedIdentity, sentence: String) {
        // The old LR02 page has the same content tag until the asynchronous route switch.
        compose.waitUntil(20_000) {
            assertFixtureHealthy()
            compose.onAllNodesWithTag("learning-focus-$sentence").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(explanationText(identity, sentence, 1)).fetchSemanticsNodes().isNotEmpty()
        }
        waitTag("explanation_content")
        compose.onNodeWithTag("learning-focus-$sentence").assertExists()
        listOf(identity.context, identity.firstFocus, identity.secondFocus).filter { it != sentence }.forEach {
            compose.onNodeWithTag("learning-focus-$it").assertDoesNotExist()
        }
        compose.onNodeWithText(explanationText(identity, sentence, 1)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("语音已在设置中关闭，可继续阅读文字。").assertExists()
    }

    private suspend fun readingProgress(runtime: SessionRuntime, identity: FixedIdentity, sentence: String) =
        AndroidLearningProgressStore(context, runtime, identity.chapter, sentence, "LR-02").read()

    private fun awaitReadingProgress(runtime: SessionRuntime, identity: FixedIdentity, sentence: String, reads: Int): Map<String, String> = awaitIo {
        withTimeout(15_000) {
            fun ready(value: Map<String, String>?): Boolean = value != null && value["reads"] == reads.toString() &&
                value["hint"] == explanationText(identity, sentence, minOf(reads + 1, 3)) &&
                !value.containsKey("pendingHintRound") && value["explaining"] == "false"
            var value: Map<String, String>? = null
            while (!ready(value)) {
                value = readingProgress(runtime, identity, sentence)
                if (!ready(value)) delay(20)
            }
            requireNotNull(value)
        }
    }

    private fun awaitCheckpoint(graph: ProductionAppGraph, identity: FixedIdentity, module: String, step: String) = awaitIo {
        withTimeout(15_000) {
            while (graph.learningCheckpointRepository.read(identity.chapter) != OrdinaryLearningCheckpoint(identity.chapter, module, step)) delay(20)
        }
    }

    private suspend fun assertIncompletePointer(runtime: SessionRuntime, identity: FixedIdentity, module: String, step: String) {
        val result = AndroidIncompleteLearningRepository(context, runtime).loadOrdinaryLearning()
        assertTrue(result is IncompleteLearningLoadResult.Ready)
        val entry = (result as IncompleteLearningLoadResult.Ready).entries.single()
        assertEquals(identity.chapter, entry.learningId)
        assertEquals(module, entry.savedStepId); assertEquals(step, entry.checkpointStepId)
    }

    private fun assertReadingRowsAndCaches(identity: FixedIdentity) = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        db.rawQuery("select account_id,chapter_id,sentence_id,module_id,payload from learning_module_progress", null).use { cursor ->
            val seen = mutableSetOf<Pair<String, String>>()
            while (cursor.moveToNext()) {
                assertEquals(account, cursor.getString(0)); assertEquals(identity.chapter, cursor.getString(1))
                val sentence = cursor.getString(2)
                val module = cursor.getString(3)
                assertTrue(seen.add(sentence to module))
                val payload = JSONObject(cursor.getString(4))
                when (module) {
                    "LR-02" -> assertEquals("3", payload.getString("reads"))
                    "LR-03" -> {
                        assertEquals(identity.firstFocus, sentence)
                        assertEquals(mapOf("version" to "1", "completed" to "false", "highlightedWordCount" to "0",
                            "feedbackZh" to "", "interrupted" to "false"),
                            payload.keys().asSequence().associateWith { payload.getString(it) })
                    }
                    else -> fail("No downstream module may acquire progress in this connection test")
                }
            }
            assertEquals(setOf(identity.firstFocus to "LR-02", identity.secondFocus to "LR-02", identity.firstFocus to "LR-03"), seen)
        }
        val expected = listOf(identity.firstFocus, identity.secondFocus).flatMap { sentence -> (1..3).map { round ->
            val existing = if (round == 1) "" else explanationText(identity, sentence, 1)
            val key = UUID.nameUUIDFromBytes("v1:$round:sentence:$existing".toByteArray(Charsets.UTF_8)).toString()
            (sentence to key) to explanationText(identity, sentence, round)
        } }.toMap()
        val observed = mutableMapOf<Pair<String, String>, String>()
        db.rawQuery("select account_id,chapter_id,sentence_id,cache_key,response_body from learning_explanation_cache", null).use { cursor ->
            while (cursor.moveToNext()) {
                assertEquals(account, cursor.getString(0)); assertEquals(identity.chapter, cursor.getString(1))
                val response = JSONObject(cursor.getString(4)); assertEquals("SENTENCE", response.getString("kind"))
                assertNull(observed.put(cursor.getString(2) to cursor.getString(3), response.getString("explanationZh")))
            }
        }
        assertEquals(expected, observed)
        // Merely arriving at LR03 cannot manufacture completed training or oral evidence.
        listOf("learning_steps", "full_speaking_result_drafts", "scene_response_result_drafts", "chapter_archives", "sentence_capabilities").forEach { table ->
            db.rawQuery("select count(*) from $table", null).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0)) }
        }
    }

    private fun assertNoRecordingFiles() {
        val files = if (directory.exists()) directory.walkTopDown().filter(File::isFile).toList() else emptyList()
        assertTrue("No microphone artifact may be created", files.none { it.extension.lowercase() in setOf("m4a", "wav", "aac", "mp3", "ogg", "amr", "pcm") })
        assertEquals(originalMicrophonePermission, ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO))
    }

    private fun clickTag(tag: String) {
        compose.waitUntil(20_000) { assertFixtureHealthy(); compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun explanationText(identity: FixedIdentity, sentence: String, round: Int): String {
        require(sentence in setOf(identity.firstFocus, identity.secondFocus) && round in 1..3)
        val first = sentence == identity.firstFocus
        if (round == 1) return if (first) baseExplanation else secondBaseExplanation
        return if (first) "隔离第${round}轮提示：仍是喝茶这句，tea 表示茶。"
            else "隔离第${round}轮提示：仍是开始读书这句，start to + verb 表示开始做某事。"
    }

    @Synchronized private fun fixtureResponse(request: HttpRequest): HttpResponse {
        requests.add(request)
        val readPhase = processArguments?.phase == "read"
        val writePhase = processArguments?.phase == "write"
        check(requests.size <= when { readPhase -> 2; writePhase -> 15; extendReadingJourney -> 17; else -> 10 }) { "No extra generation, automatic retry, settings read or recording request is allowed" }
        if (readPhase) {
            check(processReadVerified) { "No HTTP before WRITE_READY, owner, source and SQL verification" }
            if (request.method == "POST") {
                check(processReadMayRequestFinalHint && request.path == "/api/v1/learning/explanations") { "Read recovery may not regenerate a delivered result" }
                val value = JSONObject(requireNotNull(request.body))
                check(value.getString("focusSentenceId") == requireNotNull(fixedIdentity.get()).secondFocus && value.getInt("round") == 3)
            } else check(request.method == "GET" && request.path == "/api/v1/settings/current/revision")
        }
        assertEquals("Bearer isolated-not-a-real-session", request.headers["Authorization"])
        val thisPathCalls = requests.count { it.method == request.method && it.path == request.path }
        val pathBudget = when (request.path) {
            EXAMPLES -> 2
            "/api/v1/learning/explanations" -> when { readPhase -> 1; writePhase -> 5; extendReadingJourney -> 6; else -> 1 }
            "/api/v1/settings/current/revision" -> when { readPhase -> 1; writePhase -> 2; extendReadingJourney -> 3; else -> 1 }
            else -> 1
        }
        check(thisPathCalls <= pathBudget) { "The same endpoint must not be silently retried" }
        if (request.method == "GET") {
            assertEquals("", request.body)
            return when (request.path) {
                "/api/v1/settings/current" -> HttpResponse(200, JSONObject().put("basicVocabulary", "CET_4")
                    .put("purposeVocabularies", JSONArray()).toString())
                "/api/v1/settings/current/revision" -> {
                    check(fixedIdentity.get() != null)
                    val settings = JSONObject().put("formalityLevel", 3).put("slangEnabled", false)
                        .put("basicVocabulary", "CET_4").put("purposeVocabularies", JSONArray()).put("styleReference", "")
                        .put("voiceEnabled", false).put("autoPlayEnabled", false).put("speechSpeedLevel", 3)
                    HttpResponse(200, JSONObject().put("rowVersion", 1).put("settings", settings).toString())
                }
                else -> error("No other GET is authorized")
            }
        }
        assertEquals("POST", request.method)
        val body = JSONObject(requireNotNull(request.body))
        assertTrue(body.getString("commandId").isNotBlank())
        val result = when (request.path) {
            "/api/v1/safety/checks" -> {
                assertEquals(diary, body.getString("text")); assertEquals("DIARY", body.getString("sourceType"))
                assertEquals("ARTICLE", body.getString("contentShape")); JSONObject().put("status", "PASSED")
            }
            "/api/v1/content-formation/semantic-units/split" -> {
                assertEquals(diary, body.getString("text")); assertEquals("ARTICLE", body.getString("contentShape"))
                JSONObject().put("units", unitsJson()).put("coverage", JSONObject().put("complete", true)
                    .put("missingSpans", JSONArray()).put("duplicatedSpans", JSONArray()))
            }
            "/api/v1/content-formation/chapters/context-selection" -> {
                assertSourceUnits(body.getJSONArray("units"))
                assertEquals(listOf("u2", "u3"), body.getJSONArray("selectedUnitIds").strings())
                JSONObject().put("chapters", JSONArray().put(JSONObject().put("chapterId", draftChapterId)
                    .put("unitIds", JSONArray(listOf("u1", "u2", "u3")))))
            }
            "/api/v1/content-formation/english-candidates" -> {
                assertSourceUnits(body.getJSONArray("units")); assertEquals("DIARY", body.getString("sourceType"))
                assertEquals(listOf("u2", "u3"), body.getJSONArray("selectedUnitIds").strings())
                assertEquals(0, body.getJSONArray("knownKnowledge").length())
                val chapter = body.getJSONArray("chapters").getJSONObject(0)
                assertEquals(draftChapterId, chapter.getString("chapterId"))
                assertEquals(listOf("u1", "u2", "u3"), chapter.getJSONArray("unitIds").strings())
                englishResponse()
            }
            "/api/v1/content-formation/learning-targets" -> {
                assertFalse(body.has("requestedExpression"))
                assertEquals(1, body.getJSONArray("chapters").length())
                val chapter = body.getJSONArray("chapters").getJSONObject(0)
                assertEquals(draftChapterId, chapter.getString("chapterId"))
                assertChosenSentences(chapter.getJSONArray("sentences"), "unitId")
                JSONObject().put("targets", JSONArray(targets.map(::targetJson)))
                    .put("queryableExpressions", JSONArray()).put("learningScopes", JSONArray())
            }
            EXAMPLES -> examplesResponse(body)
            "/api/v1/learning/explanations" -> {
                val identity = requireNotNull(fixedIdentity.get())
                val chapter = body.getJSONObject("chapter")
                assertEquals(identity.chapter, chapter.getString("chapterId"))
                assertChosenSentences(chapter.getJSONArray("sentences"), "sentenceId",
                    listOf(identity.context, identity.firstFocus, identity.secondFocus))
                val sentence = body.getString("focusSentenceId")
                val round = body.getInt("round")
                if (!extendReadingJourney) { assertEquals(identity.firstFocus, sentence); assertEquals(1, round) }
                assertTrue(sentence in setOf(identity.firstFocus, identity.secondFocus)); assertTrue(round in 1..3)
                assertFalse(body.has("requestedExpression"))
                if (round == 1) assertFalse(body.has("existingBaseExplanationZh"))
                else assertEquals(explanationText(identity, sentence, 1), body.getString("existingBaseExplanationZh"))
                assertEquals("One paid delivery per exact focus and hint round; resume must use its cache", 1,
                    requests.count { previous -> previous.method == "POST" && previous.path == request.path &&
                        JSONObject(requireNotNull(previous.body)).let { it.getString("focusSentenceId") == sentence && it.getInt("round") == round } })
                val locked = body.getJSONArray("lockedTargets")
                assertEquals(1, locked.length())
                val target = locked.getJSONObject(0)
                val expected = if (sentence == identity.firstFocus) word else pattern
                assertEquals(if (expected == word) identity.word else identity.pattern, target.getString("targetId"))
                assertEquals(expected.type.name, target.getString("type")); assertEquals(expected.structureParts, target.getJSONArray("structureParts").strings())
                assertEquals(expected.display, target.getString("display")); assertEquals(expected.explanationZh, target.getString("explanationZh"))
                assertEvidence(target.getJSONObject("sourceEvidence"), requireNotNull(expected.sourceEvidence))
                JSONObject().put("kind", "SENTENCE").put("explanationZh", explanationText(identity, sentence, round))
            }
            else -> error("No endpoint outside this one diary-to-learning connection is authorized")
        }
        return HttpResponse(200, result.toString())
    }

    private fun englishResponse() = JSONObject().put("chapters", JSONArray().put(JSONObject()
        .put("chapterId", draftChapterId).put("sentences", JSONArray(units.take(3).mapIndexed { index, unit ->
            val candidates = JSONArray()
            alternatives[unit.unitId]?.let { candidates.put(JSONObject().put("candidateId", "${unit.unitId}-first")
                .put("english", it).put("reusedKnowledgeRefs", JSONArray())) }
            candidates.put(JSONObject().put("candidateId", chosen.getValue(unit.unitId)).put("english", selectedEnglish[index])
                .put("reusedKnowledgeRefs", JSONArray()))
            JSONObject().put("sourceUnitId", unit.unitId).put("textZh", unit.textZh).put("isFocus", index > 0).put("candidates", candidates)
        }))))

    private fun targetJson(target: LearningTarget): JSONObject = JSONObject().put("targetId", target.targetId)
        .put("unitId", target.unitId).put("type", target.type.name).put("display", target.display).put("explanationZh", target.explanationZh)
        .put("sourceEvidence", requireNotNull(target.sourceEvidence).toJson()).put("structureParts", JSONArray(target.structureParts))
        .put("relatedVocabulary", JSONArray()).put("relatedVocabularyItems", JSONArray()).put("relatedVocabularyItemRefs", JSONArray())
        .put("candidateExample", JSONObject().put("textEn", requireNotNull(target.candidateExample).textEn)
            .put("textZh", requireNotNull(target.candidateExample).textZh))

    private fun examplesResponse(body: JSONObject): JSONObject {
        val target = targets.single { it.targetId == body.getString("itemId") }
        assertEquals(if (target.type == LearningTargetType.WORD) "VOCABULARY" else "PATTERN", body.getString("targetType"))
        assertEquals(target.display, body.getString("display")); assertEquals(target.explanationZh, body.getString("meaningZh"))
        val scene = body.getJSONArray("sourceScene")
        assertEquals(3, scene.length())
        repeat(3) { index ->
            assertEquals(selectedEnglish[index], scene.getJSONObject(index).getString("textEn"))
            assertEquals(units[index].textZh, scene.getJSONObject(index).getString("textZh"))
        }
        return JSONObject().put("itemId", target.targetId).put("phonetic", JSONObject.NULL)
            .put("pronunciationText", target.display).put("representativeScene", selectedEnglish[if (target == word) 1 else 2])
            .put("naturalRevalidation", true).put("examples", examplesJson(target.targetId))
    }

    private fun examplesJson(targetId: String) = JSONArray(formalExamples.getValue(targetId).map { (en, zh) ->
        JSONObject().put("textEn", en).put("textZh", zh).put("pronunciationText", en)
    })
    private fun unitsJson() = JSONArray(units.map { JSONObject().put("unitId", it.unitId).put("order", it.order)
        .put("textZh", it.textZh).put("recommendationReasonZh", JSONObject.NULL) })
    private fun assertSourceUnits(array: JSONArray) {
        assertEquals(4, array.length())
        units.forEachIndexed { index, unit ->
            val value = array.getJSONObject(index)
            assertEquals(unit.unitId, value.getString("unitId")); assertEquals(unit.order, value.getInt("order"))
            assertEquals(unit.textZh, value.getString("textZh"))
        }
    }
    private fun assertChosenSentences(array: JSONArray, identityKey: String, ids: List<String> = listOf("u1", "u2", "u3")) {
        assertEquals(3, array.length())
        repeat(3) { index ->
            val value = array.getJSONObject(index)
            assertEquals(ids[index], value.getString(identityKey)); assertEquals(selectedEnglish[index], value.getString("textEn"))
            assertEquals(units[index].textZh, value.getString("textZh")); assertEquals(index > 0, value.getBoolean("isFocus"))
        }
    }
    private fun assertEvidence(json: JSONObject, expected: SourceEvidence) {
        assertEquals(expected.version, json.getInt("version")); assertEquals(expected.startUtf16, json.getInt("startUtf16"))
        assertEquals(expected.endUtf16, json.getInt("endUtf16")); assertEquals(expected.surfaceForm, json.getString("surfaceForm"))
        assertEquals(expected.normalizedForm, json.getString("normalizedForm")); assertEquals(expected.allowedForms, json.getJSONArray("allowedForms").strings())
    }

    private suspend fun assertFixedContent(source: FixedSourceProjection): FixedIdentity {
        val loaded = AndroidFixedSourceRepository(context, session).load(source.formationFlowId)
        assertTrue(loaded is FixedSourceLoad.Loaded)
        assertEquals(source, (loaded as FixedSourceLoad.Loaded).source)
        assertEquals(diary, loaded.source.source.text)
        assertEquals(units.map { FixedSourceUnit(it.unitId, it.order, it.textZh) }, loaded.source.units)
        assertEquals(listOf("u2", "u3"), loaded.source.selectedUnitIds)
        assertEquals(chosen, loaded.source.selectedCandidateIds)
        assertEquals(setOf("tea", "start"), loaded.source.selectedTargetIds)
        fun persistent(kind: String, id: String) = UUID.nameUUIDFromBytes("${source.formationFlowId}:$kind:$id".toByteArray(Charsets.UTF_8)).toString()
        val identity = FixedIdentity(source.formationFlowId, persistent("chapter", draftChapterId), persistent("sentence", "u1"),
            persistent("sentence", "u2"), persistent("sentence", "u3"), persistent("target", "tea"), persistent("target", "start"))
        val chapter = AndroidFixedChapterRepository(context, session).load(identity.chapter)
        assertTrue(chapter is FixedChapterLoadResult.Loaded)
        val sentences = (chapter as FixedChapterLoadResult.Loaded).chapter.sentences
        assertEquals(listOf(identity.context, identity.firstFocus, identity.secondFocus), sentences.map { it.sentenceId })
        assertEquals(selectedEnglish, sentences.map { it.textEn }); assertEquals(units.take(3).map { it.textZh }, sentences.map { it.textZh })
        assertEquals(listOf(false, true, true), sentences.map { it.isFocus })
        FormationDatabase(context).use { helper ->
            val db = helper.readableDatabase
            assertEquals(1L, db.rawQuery("select count(*) from chapters", null).use { it.moveToFirst(); it.getLong(0) })
            db.rawQuery("select f.account_id,c.flow_id,f.command_id,f.first_chapter_id from fix_commands f join formation_flows c on c.account_id=f.account_id", null).use {
                assertTrue(it.moveToFirst()); assertEquals(account, it.getString(0)); assertEquals(identity.flow, it.getString(1))
                assertEquals(identity.flow, it.getString(2)); assertEquals(identity.chapter, it.getString(3)); assertFalse(it.moveToNext())
            }
            db.rawQuery("select account_id,target_id,unit_id,type,display,explanation_zh,source_evidence,structure_parts from target_selections order by target_id", null).use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    count++
                    assertEquals(account, cursor.getString(0))
                    val target = targets.single { persistent("target", it.targetId) == cursor.getString(1) }
                    assertEquals(persistent("sentence", target.unitId), cursor.getString(2)); assertEquals(target.type.name, cursor.getString(3))
                    assertEquals(target.display, cursor.getString(4)); assertEquals(target.explanationZh, cursor.getString(5))
                    assertEvidence(JSONObject(cursor.getString(6)), requireNotNull(target.sourceEvidence))
                    assertEquals(target.structureParts.joinToString("\u001f"), cursor.getString(7))
                }
                assertEquals(2, count)
            }
            targets.forEach { target ->
                val table = if (target.type == LearningTargetType.WORD) "target_vocabulary_details" else "target_pattern_details"
                db.rawQuery("select account_id,chapter_id,target_id,examples_json from $table", null).use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals(account, cursor.getString(0)); assertEquals(identity.chapter, cursor.getString(1))
                    assertEquals(persistent("target", target.targetId), cursor.getString(2))
                    val examples = JSONArray(cursor.getString(3))
                    assertEquals(3, examples.length())
                    formalExamples.getValue(target.targetId).forEachIndexed { index, pair ->
                        val value = examples.getJSONObject(index)
                        assertEquals(pair.first, value.getString("textEn")); assertEquals(pair.second, value.getString("textZh"))
                        assertEquals(pair.first, value.getString("pronunciationText"))
                    }
                    assertFalse(cursor.moveToNext())
                }
            }
        }
        return identity
    }

    private fun allRows(): Map<String, List<List<String?>>> = FormationDatabase(context).use { helper ->
        val db = helper.writableDatabase
        val tables = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        tables.associateWith { table ->
            check(Regex("[a-z_]+").matches(table))
            db.rawQuery("select * from $table order by rowid", null).use { cursor -> buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { column -> when (cursor.getType(column)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_BLOB -> Base64.getEncoder().encodeToString(cursor.getBlob(column))
                    else -> cursor.getString(column)
                } })
            } }
        }
    }
    private fun observedRequests() = synchronized(requests) { requests.toList() }
    private fun assertFixtureHealthy() { fixtureFailure.get()?.let { throw AssertionError("Isolated HTTP fixture rejected the actual production request", it) } }
    private fun assertFixtureBudget(readingCompleted: Boolean = false) {
        assertFixtureHealthy()
        val requests = observedRequests()
        val postCount = if (readingCompleted) 13 else 8
        assertEquals(postCount, requests.count { it.method == "POST" }); assertEquals(if (readingCompleted) 4 else 2, requests.count { it.method == "GET" })
        assertEquals(mapOf(
            "/api/v1/safety/checks" to 1, "/api/v1/content-formation/semantic-units/split" to 1,
            "/api/v1/content-formation/chapters/context-selection" to 1, "/api/v1/content-formation/english-candidates" to 1,
            "/api/v1/content-formation/learning-targets" to 1, EXAMPLES to 2, "/api/v1/learning/explanations" to if (readingCompleted) 6 else 1,
            "/api/v1/settings/current" to 1, "/api/v1/settings/current/revision" to if (readingCompleted) 3 else 1,
        ), requests.groupingBy { it.path }.eachCount())
        val posts = requests.filter { it.method == "POST" }
        assertEquals(postCount, posts.map { JSONObject(requireNotNull(it.body)).getString("commandId") }.toSet().size)
        assertEquals(setOf("tea", "start"), posts.filter { it.path == EXAMPLES }.map { JSONObject(requireNotNull(it.body)).getString("itemId") }.toSet())
        assertEquals(0, credentialsTouched.get())
    }
    private fun assertWritePhaseBudget() {
        assertFixtureHealthy()
        val calls = observedRequests()
        assertEquals(12, calls.count { it.method == "POST" }); assertEquals(3, calls.count { it.method == "GET" })
        assertEquals(mapOf("/api/v1/safety/checks" to 1, "/api/v1/content-formation/semantic-units/split" to 1,
            "/api/v1/content-formation/chapters/context-selection" to 1, "/api/v1/content-formation/english-candidates" to 1,
            "/api/v1/content-formation/learning-targets" to 1, EXAMPLES to 2, "/api/v1/learning/explanations" to 5,
            "/api/v1/settings/current" to 1, "/api/v1/settings/current/revision" to 2), calls.groupingBy { it.path }.eachCount())
        assertEquals(12, calls.filter { it.method == "POST" }.map { JSONObject(requireNotNull(it.body)).getString("commandId") }.toSet().size)
        assertEquals(0, credentialsTouched.get())
    }
    private fun assertProcessReadBudget(completed: Boolean) {
        assertFixtureHealthy()
        val calls = observedRequests()
        assertEquals(if (completed) 1 else 0, calls.count { it.method == "POST" })
        assertEquals(1, calls.count { it.method == "GET" })
        assertEquals(1, calls.count { it.method == "GET" && it.path == "/api/v1/settings/current/revision" })
        if (completed) {
            val request = calls.single { it.method == "POST" }
            assertEquals("/api/v1/learning/explanations", request.path)
            val value = JSONObject(requireNotNull(request.body))
            assertEquals(requireNotNull(fixedIdentity.get()).secondFocus, value.getString("focusSentenceId"))
            assertEquals(3, value.getInt("round"))
        }
        assertEquals(0, credentialsTouched.get())
    }
    private fun awaitSnapshot(predicate: (FormationRecoverySnapshot) -> Boolean): FormationRecoverySnapshot = awaitIo {
        withTimeout(20_000) {
            var result: FormationRecoverySnapshot? = null
            while (result == null) {
                val list = recoveryReader.repository.list() as? FormationRecoveryList.Listed
                list?.items?.singleOrNull()?.let { item ->
                    val loaded = recoveryReader.repository.load(item.sourceEntryId) as? FormationRecoveryLoad.Loaded
                    loaded?.snapshot?.takeIf(predicate)?.let { result = it }
                }
                if (result == null) delay(20)
            }
            requireNotNull(result)
        }
    }
    private fun waitTag(tag: String) {
        compose.waitUntil(20_000) { assertFixtureHealthy(); compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try {
            compose.waitUntil(25_000) { assertFixtureHealthy(); result.get() != null }
            return requireNotNull(result.get()).getOrThrow()
        } finally { job.cancel(); scope.cancel() }
    }
    private fun JSONArray.strings() = List(length()) { getString(it) }
    private companion object {
        const val EXAMPLES = "/api/v1/knowledge/examples"
        val readingTables = setOf("learning_module_progress", "learning_checkpoints", "learning_explanation_cache")
    }
}
