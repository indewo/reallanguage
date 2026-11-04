package com.reallanguage.app.core.app

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.database.Cursor
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.formation.data.AndroidFixedSourcePayloadKeys
import com.reallanguage.app.feature.formation.data.AndroidFormationPayloadKeys
import com.reallanguage.app.feature.formation.data.toJson
import com.reallanguage.app.feature.formation.domain.SourceEvidence
import com.reallanguage.app.feature.learning.data.AndroidChapterArchiveRepository
import com.reallanguage.app.feature.learning.domain.ChapterArchiveResult
import com.reallanguage.app.testing.ReviewNoJudgmentFixture
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Actual Home -> review -> ordinary writing UI -> chapter draft/exit -> new Graph -> settlement.
 * Only the fixed content, two earlier ordinary learning days and already accepted ordinary
 * module decisions are fixtures. The real archive creates the current obligations and third
 * ordinary day. No review queue/result, oral attempt, microphone input, HTTP or AI is fabricated.
 * Automatic local TTS is muted through its real control; its quality is not asserted.
 */
class ProductionReviewWritingChapterJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val fixture = ReviewNoJudgmentFixture(InstrumentationRegistry.getInstrumentation().targetContext)
    private val host = mutableStateOf<ProductionAppGraph?>(null)
    private var mounted = false
    private val requests = AtomicInteger()
    private val credentialAccess = AtomicInteger()
    private val completedWritingSessions = AtomicInteger()
    private val submittedInputs = AtomicInteger()
    private val retryIntents = AtomicInteger()
    private val auditTable = "test_review_commit_audit_${fixture.runId.replace('-', '_')}"
    private val original = "I drink tea."
    private val changedSentenceWithTheSameTeaOccurrence = "I enjoy tea."
    private val originalZh = "我喝茶。"
    private val contextEn = "This is breakfast at home."
    private val contextZh = "这是在家吃早餐的时候。"
    private val chapterId = "writing-review-chapter"
    private val sourceId = "writing-review-source"
    private val targetId = "tea-target"
    private val sentenceId = "focus-tea"

    @After fun cleanOnlyTheUuidFixture() {
        if (mounted) {
            compose.runOnIdle { host.value = null }
            compose.waitForIdle()
        }
        // These are only the freshly generated fixture account's key aliases.
        val hash = MessageDigest.getInstance("SHA-256").digest(fixture.account.toByteArray())
            .joinToString("") { "%02x".format(it) }
        AndroidFormationPayloadKeys().deleteAccount(hash)
        AndroidFixedSourcePayloadKeys().deleteAccount(hash)
        fixture.close()
    }

    @Test(timeout = 180_000)
    fun twoAbilitiesKeepTheirOwnResultsAndOnlyACompleteChapterSurvivesExitAndGraphReplacement() = runJourney(false)

    @Test(timeout = 180_000)
    fun lateChapterSqlFailureRetainsBothFinishedModulesAndTwoRetryIntentsSettleOnlyOnce() = runJourney(true)

    private fun runJourney(failLastChapterCommit: Boolean) {
        val before = awaitIo { archiveAcceptedOrdinaryContent(); protectedTables() }
        val firstGraph = graph(fixture.runtime)
        mount(firstGraph)
        openReviewFromHome()
        assertReviewPending(wholeWriting = true)
        val firstLease = requireNotNull(firstGraph.currentPatternReviewEntry())
        val reviewId = firstLease.reviewChapterId
        val originalQueue = awaitIo {
            val rows = query("select rci.ability_type,o.target_id,rci.sentence_id from review_chapter_items rci " +
                "join review_queue_items qi on qi.account_id=rci.account_id and qi.queue_item_id=rci.queue_item_id " +
                "join learning_obligations o on o.account_id=qi.account_id and o.obligation_id=qi.obligation_id " +
                "where rci.account_id=? and rci.review_chapter_id=? order by qi.entered_at",
                arrayOf(fixture.account, reviewId))
            assertEquals(listOf(listOf("FULL_WRITING", "", sentenceId),
                listOf("MEANING_WRITING", targetId, sentenceId)), rows)
            rows
        }

        // The first module's real final comparator reports whole-sentence FAIL. The
        // uncommitted draft must advance the page without touching formal records.
        completeActualWritingSession()
        assertReviewPending(wholeWriting = false)
        assertSame(firstLease, firstGraph.currentPatternReviewEntry())
        assertNoPartialCommit(before)

        systemBack()
        waitTag("review-exit-continue")
        compose.onNodeWithTag("review-exit-continue").performClick()
        assertReviewPending(wholeWriting = false)
        assertSame(firstLease, firstGraph.currentPatternReviewEntry())
        assertNoPartialCommit(before)

        systemBack()
        waitTag("review-exit-confirm")
        compose.onNodeWithTag("review-exit-confirm").performClick()
        waitTag("home-review-card")
        assertNull(firstGraph.currentPatternReviewEntry())
        assertFalse(firstLease.isActiveFor(fixture.account, fixture.runtime.accountEpoch))
        assertNoPartialCommit(before)

        // Actually destroy the old Home and every page owner, then enter through
        // Home again. No terminal route or saved internal review step is injected.
        compose.runOnIdle { host.value = null }
        compose.waitForIdle()
        val newRuntime = SessionRuntime().apply { update("isolated-review-session", fixture.account) }
        val newGraph = graph(newRuntime)
        compose.runOnIdle { host.value = newGraph }
        openReviewFromHome()
        assertReviewPending(wholeWriting = true)
        val newLease = requireNotNull(newGraph.currentPatternReviewEntry())
        assertNotSame(firstLease, newLease)
        assertEquals(reviewId, newLease.reviewChapterId)
        assertNoPartialCommit(before)

        completeActualWritingSession()
        assertReviewPending(wholeWriting = false)
        assertSame(newLease, newGraph.currentPatternReviewEntry())
        assertNoPartialCommit(before)
        val beforeFailedCommit = if (failLastChapterCommit) awaitIo {
            installLateCommitFailureAndAudit(reviewId)
            productTables()
        } else null
        completeActualWritingSession()
        if (failLastChapterCommit) {
            waitText(text(R.string.review_chapter_commit_failed))
            compose.onNodeWithText(text(R.string.review_chapter_commit_failed)).assertIsDisplayed()
            compose.onNodeWithTag("review-result-summary").assertDoesNotExist()
            compose.onNodeWithTag("review-chapter-continue").assertDoesNotExist()
            compose.onNodeWithTag("complete-writing-workspace").assertDoesNotExist()
            compose.onNodeWithText("完整书写").assertDoesNotExist()
            compose.onNodeWithText("单词与词义").assertDoesNotExist()
            assertSame("The failed chapter must keep the same live draft owner", newLease, newGraph.currentPatternReviewEntry())
            assertNoPartialCommit(before)
            awaitIo {
                assertEquals("The late failure must roll back every product table, including queue, formal state and fixed identity",
                    requireNotNull(beforeFailedCommit), productTables())
                assertTrue("The audit inserted inside the rejected transaction must roll back too", table(auditTable).isEmpty())
            }
            val sessionsBeforeRetry = completedWritingSessions.get()
            val inputsBeforeRetry = submittedInputs.get()
            assertEquals(3, sessionsBeforeRetry)
            assertEquals(9, inputsBeforeRetry)
            assertEquals(0, requests.get())
            assertEquals(0, credentialAccess.get())
            val retry = compose.onNodeWithText(text(R.string.diary_retry)).assertIsDisplayed().assertIsEnabled()
            val retryClick = requireNotNull(retry.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            awaitIo { removeLateCommitFailure(reviewId) }
            // As in the LR09 journey, dispatch two intentions through the same actual
            // visible button callback in one UI turn. Do not query a second page's button.
            compose.runOnIdle {
                repeat(2) { retryIntents.incrementAndGet(); assertTrue(retryClick()) }
            }
            waitTag("review-result-summary")
            assertEquals(2, retryIntents.get())
            assertEquals("Retry must not send the learner through another module", sessionsBeforeRetry, completedWritingSessions.get())
            assertEquals("Retry must not submit any additional writing input", inputsBeforeRetry, submittedInputs.get())
        }
        waitTag("review-result-summary")
        compose.onNodeWithText("本次回顾已完成").assertIsDisplayed()
        awaitIo { assertOnlyTheTwoCurrentAbilitiesWereSettled(before, originalQueue, reviewId) }
        if (failLastChapterCommit) awaitIo { assertOneAcceptedCommitAudit(reviewId) }
        assertNull(newGraph.currentPatternReviewEntry())
        assertFalse(newLease.isActiveFor(fixture.account, newRuntime.accountEpoch))

        val settled = awaitIo { allTables() }
        compose.onNodeWithTag("review-result-home").assertIsEnabled().performClick()
        waitTag("home-diary-card")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("home-review-card").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("home-review-card").assertDoesNotExist()
        assertFalse(awaitIo { newGraph.hasDueReview() })
        assertEquals("Returning home must not repeat settlement or consume another learning day", settled, awaitIo { allTables() })
        assertEquals(3, completedWritingSessions.get())
        assertEquals(9, submittedInputs.get())
        assertEquals("This entirely local review must not invoke settings, speech or any provider", 0, requests.get())
        assertEquals(0, credentialAccess.get())
        Log.i("RealLanguageJourney", "REVIEW_WRITING_CHAPTER run=${fixture.runId} " +
            "actualArchive=1 graphRebuilt=true writingSessions=3 inputs=9 " +
            "lateSqlFailure=$failLastChapterCommit retryIntents=${retryIntents.get()} " +
            "reviewChapters=1 processedFocus=1 wholePassed=false meaningPassed=true http=0 capture=0")
    }

    private fun checkedFailureTrigger(reviewId: String): String {
        check(UUID.fromString(fixture.runId).toString() == fixture.runId)
        check(UUID.fromString(reviewId).toString() == reviewId)
        check(fixture.account == "review-no-judgment-${fixture.runId}")
        check(listOf(fixture.account, reviewId, chapterId, sentenceId, targetId).all { it.matches(Regex("[a-z0-9-]+")) })
        val file = fixture.context.getDatabasePath("real-language-learning.db").canonicalFile
        val baseCache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        check(file.parentFile == baseCache && file.name.startsWith("${fixture.account}-") && file.name.endsWith(".db"))
        check(auditTable.matches(Regex("test_review_commit_audit_[a-f0-9_]+")))
        return "test_review_commit_failure_${fixture.runId.replace('-', '_')}".also {
            check(it.matches(Regex("test_review_commit_failure_[a-f0-9_]+")))
        }
    }

    private fun installLateCommitFailureAndAudit(reviewId: String) {
        val failureTrigger = checkedFailureTrigger(reviewId)
        val acceptedTrigger = "${auditTable}_accepted"
        assertTrue(query("select name from sqlite_master where name in (?,?,?)", arrayOf(auditTable, failureTrigger, acceptedTrigger)).isEmpty())
        val account = fixture.account
        val db = fixture.db
        db.execSQL("create table $auditTable(account_id text,review_chapter_id text,command_id text,request_digest text," +
            "focus_count integer,meaning_status text,meaning_count integer,whole_status text)")
        val insertAudit = """
            insert into $auditTable values(NEW.account_id,NEW.review_chapter_id,NEW.command_id,NEW.request_digest,
                NEW.completed_focus_count,
                (select status from vocabulary_items where account_id='$account' and display='tea' and meaning_zh='茶'),
                (select verified_success_count from vocabulary_items where account_id='$account' and display='tea' and meaning_zh='茶'),
                (select status from sentence_capabilities where account_id='$account' and sentence_id='$sentenceId' and capability_type='FULL_WRITING'));
        """.trimIndent()
        // A failed transaction must first have performed the actual meaning update,
        // daily increment and chapter-status transition. The inserted audit row and
        // all those product writes must be rolled back together by RAISE(ABORT).
        db.execSQL("""
            create trigger $failureTrigger before insert on review_chapter_results
            when NEW.account_id='$account' and NEW.review_chapter_id='$reviewId'
                and (select status from review_chapters where account_id='$account' and review_chapter_id='$reviewId')='COMPLETED'
                and (select sum(completed_focus_count) from review_sessions where account_id='$account')=1
                and (select status from vocabulary_items where account_id='$account' and display='tea' and meaning_zh='茶')='VERIFIED'
                and (select verified_success_count from vocabulary_items where account_id='$account' and display='tea' and meaning_zh='茶')=1
                and (select count(*) from learning_obligations where account_id='$account' and chapter_id='$chapterId' and capability_type='MEANING_WRITING')=0
            begin
                $insertAudit
                select raise(ABORT,'isolated late review chapter commit failure');
            end
        """.trimIndent())
        db.execSQL("""
            create trigger $acceptedTrigger after insert on review_chapter_results
            when NEW.account_id='$account' and NEW.review_chapter_id='$reviewId'
            begin
                $insertAudit
            end
        """.trimIndent())
        assertTrue(table(auditTable).isEmpty())
    }

    private fun removeLateCommitFailure(reviewId: String) {
        val trigger = checkedFailureTrigger(reviewId)
        assertEquals(listOf(listOf(trigger)), query("select name from sqlite_master where type='trigger' and name=?", arrayOf(trigger)))
        fixture.db.execSQL("drop trigger $trigger")
        assertTrue(query("select name from sqlite_master where type='trigger' and name=?", arrayOf(trigger)).isEmpty())
    }

    private fun assertOneAcceptedCommitAudit(reviewId: String) {
        checkedFailureTrigger(reviewId)
        val rows = table(auditTable)
        assertEquals("Two retry intents may produce only one accepted SQL settlement", 1, rows.size)
        val row = rows.single()
        assertEquals(fixture.account, row[0])
        assertEquals(reviewId, row[1])
        assertEquals(listOf(listOf(row[2], row[3])), query("select command_id,request_digest from review_chapter_results"))
        assertTrue(requireNotNull(row[3]).matches(Regex("[a-f0-9]{64}")))
        assertEquals(listOf("1", "VERIFIED", "1", "LEARNING"), row.drop(4))
    }

    private fun openReviewFromHome() {
        waitTag("home-review-card")
        compose.onNodeWithTag("home-review-card").performClick()
        waitTag("today-review-start")
        compose.onNodeWithText("1 个重点句 · 2 项待回顾能力").assertExists()
        compose.onNodeWithTag("today-review-start").performClick()
    }

    private fun assertReviewPending(wholeWriting: Boolean) {
        waitTag("review-chapter-continue")
        compose.onNodeWithText("单词与词义").assertExists()
        if (wholeWriting) compose.onNodeWithText("完整书写").assertExists()
        else compose.onNodeWithText("完整书写").assertDoesNotExist()
        compose.onNodeWithText("重点句 1 / 1").assertExists()
        compose.onNodeWithTag("review-context-context").performScrollTo()
        compose.onNodeWithText(contextEn).assertIsDisplayed()
        compose.onNodeWithText(contextZh).assertIsDisplayed()
        compose.onNodeWithTag("review-context-$sentenceId").performScrollTo()
        compose.onNodeWithText(original).assertIsDisplayed()
        compose.onNodeWithText(originalZh).assertIsDisplayed()
    }

    private fun completeActualWritingSession() {
        compose.onNodeWithTag("review-chapter-continue").assertIsDisplayed().performClick()
        waitTag("complete-writing-workspace")
        waitText(text(R.string.complete_writing_audible))
        assertSingleWritingPrompt()
        // This is only the audible training phase, so muting does not use help.
        compose.onNodeWithText(text(R.string.learning_mute)).performScrollTo().performClick()
        submitText(original)
        waitText(text(R.string.complete_writing_next))
        compose.onNodeWithText(text(R.string.complete_writing_next)).performClick()
        waitText(text(R.string.complete_writing_silent))
        assertSingleWritingPrompt()
        submitText(original)
        waitText(text(R.string.complete_writing_next))
        compose.onNodeWithText(text(R.string.complete_writing_next)).performClick()
        waitText(text(R.string.complete_writing_final))
        compose.onNodeWithTag("complete-writing-input-$sentenceId")
            .performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("complete-writing-input-context").assertDoesNotExist()
        compose.onNodeWithTag("complete-writing-input-single").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNode(hasText(originalZh) and hasAnyAncestor(hasTestTag("complete-writing-workspace")))
            .assertExists()
        compose.onNode(hasText(contextZh) and hasAnyAncestor(hasTestTag("complete-writing-workspace")))
            .assertDoesNotExist()
        submitText(changedSentenceWithTheSameTeaOccurrence)
        waitText(text(R.string.complete_writing_continue))
        compose.onNodeWithText(text(R.string.complete_writing_final_recorded)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.complete_writing_final_supported)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.complete_writing_evaluation_unavailable)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.complete_writing_continue)).assertIsEnabled().performClick()
        completedWritingSessions.incrementAndGet()
    }

    private fun assertSingleWritingPrompt() {
        compose.onNodeWithTag("complete-writing-input-single")
            .performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText(originalZh).assertExists()
        // ChapterWritingFrame deliberately hides the current focus English, while
        // keeping the original CONTEXT visible. It has no learning-focus-* tags.
        compose.onNodeWithText(original).assertDoesNotExist()
        compose.onNodeWithText(contextEn).assertExists()
        compose.onNodeWithTag("complete-writing-input-context").assertDoesNotExist()
    }

    private fun submitText(value: String) {
        compose.onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed().performTextInput(value)
        compose.onNode(hasSetTextAction()).assertTextContains(value)
        compose.onNodeWithText(text(R.string.audio_dictation_submit)).assertIsEnabled().assertIsDisplayed().performClick()
        submittedInputs.incrementAndGet()
    }

    private fun assertNoPartialCommit(before: Map<String, List<List<String?>>>) = awaitIo {
        assertEquals("Completed module drafts cannot escape the current review chapter", before, protectedTables())
        assertEquals(0, fixture.count("review_chapter_results"))
        assertEquals(0, fixture.count("review_sessions"))
        assertEquals(0, fixture.count("review_session_settlements"))
        assertEquals(0, fixture.count("review_working_state"))
        assertEquals(0, fixture.count("learning_module_progress"))
        assertEquals(3, fixture.count("normal_learning_days"))
    }

    private suspend fun archiveAcceptedOrdinaryContent() {
        val db = fixture.db
        db.beginTransaction()
        try {
            listOf(2L, 1L).forEach { insert("normal_learning_days", "local_date" to LocalDate.now().minusDays(it).toString()) }
            insert("formation_flows", "flow_id" to sourceId, "status" to "FIXED", "source_type" to "DIARY", "source_title" to "隔离书写回顾")
            insert("chapters", "flow_id" to sourceId, "chapter_id" to chapterId, "chapter_order" to 0, "status" to "NOT_STARTED")
            insert("fixed_sentences", "chapter_id" to chapterId, "unit_id" to "context", "sentence_order" to 0,
                "text_en" to contextEn, "text_zh" to contextZh, "role" to "CONTEXT")
            insert("fixed_sentences", "chapter_id" to chapterId, "unit_id" to sentenceId, "sentence_order" to 1,
                "text_en" to original, "text_zh" to originalZh, "role" to "FOCUS")
            insert("target_selections", "chapter_id" to chapterId, "target_id" to targetId, "unit_id" to sentenceId,
                "type" to "WORD", "display" to "tea", "explanation_zh" to "茶", "structure_parts" to "", "related_vocabulary" to "",
                "source_evidence" to SourceEvidence(1, 8, 11, "tea", "tea", listOf("tea")).toJson().toString())
            insert("writing_batch_completions", "chapter_id" to chapterId, "command_id" to "preaccepted-ordinary-writing", "qualification" to "INDEPENDENT")
            listOf("WHOLE_WRITING" to "", "MEANING_WRITING" to targetId).forEach { (ability, target) ->
                insert("writing_result_drafts", "chapter_id" to chapterId, "sentence_id" to sentenceId,
                    "ability_type" to ability, "target_id" to target, "passed" to 0)
            }
            // Explicit accepted upstream fixture only, not proof of oral quality or a
            // synthetic microphone verdict. This test never enters an oral module.
            insert("full_speaking_result_drafts", "chapter_id" to chapterId, "sentence_id" to sentenceId, "passed" to 1)
            insert("scene_response_result_drafts", "chapter_id" to chapterId, "sentence_id" to sentenceId, "passed" to 1)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        assertEquals(0, fixture.count("chapter_archives"))
        assertEquals(0, fixture.count("learning_obligations"))
        assertEquals(0, fixture.count("review_chapters"))
        assertEquals(0, fixture.count("review_chapter_results"))
        val archived = AndroidChapterArchiveRepository(fixture.context, fixture.runtime).archive(chapterId, "actual-archive-${fixture.runId}")
        assertTrue("Only real archive may create obligations for this review", archived is ChapterArchiveResult.Archived)
        assertEquals(1, fixture.count("chapter_archives"))
        assertEquals(2, fixture.count("learning_obligations"))
        assertEquals(2, fixture.count("correction_flags"))
        assertEquals(3, fixture.count("normal_learning_days"))
        assertEquals(0, fixture.count("review_queue_items"))
        assertEquals(0, fixture.count("review_chapter_results"))
    }

    private fun assertOnlyTheTwoCurrentAbilitiesWereSettled(
        before: Map<String, List<List<String?>>>, originalQueue: List<List<String?>>, reviewId: String,
    ) {
        assertEquals(2, originalQueue.size)
        assertEquals(1, fixture.count("review_chapter_results"))
        assertEquals(1, fixture.count("review_sessions"))
        assertEquals(1, fixture.count("review_session_settlements"))
        assertEquals(listOf(listOf(reviewId, "1")), query("select review_chapter_id,completed_focus_count from review_chapter_results"))
        assertEquals(listOf(listOf("1")), query("select completed_focus_count from review_sessions"))
        assertEquals(listOf(listOf("COMPLETED")), query("select status from review_chapters"))
        assertEquals(listOf(listOf("FULL_SPEAKING", "VERIFIED"), listOf("FULL_WRITING", "LEARNING"), listOf("SCENE_RESPONSE", "VERIFIED")),
            query("select capability_type,status from sentence_capabilities order by capability_type"))
        assertEquals(listOf(listOf("tea", "茶", "VERIFIED", "1")),
            query("select display,meaning_zh,status,verified_success_count from vocabulary_items"))
        assertEquals(listOf(listOf(sentenceId, "FULL_WRITING", "")),
            query("select sentence_id,capability_type,target_id from learning_obligations"))
        assertEquals(listOf(listOf("FULL_WRITING")), query("select o.capability_type from correction_flags f " +
            "join learning_obligations o on o.account_id=f.account_id and o.obligation_id=f.obligation_id"))
        assertEquals(0, fixture.count("review_working_state"))
        assertEquals(0, fixture.count("learning_module_progress"))
        listOf("formation_flows", "chapters", "fixed_sentences", "target_selections", "chapter_archives",
            "full_speaking_result_drafts", "scene_response_result_drafts", "writing_result_drafts", "writing_batch_completions").forEach {
            assertEquals("Review may not rewrite accepted ordinary content/results: $it", before[it], table(it))
        }
        assertEquals(listOf(listOf("COMPLETED")), query("select status from review_cycles"))
        assertEquals(0, fixture.count("normal_learning_days"))
    }

    private fun graph(runtime: SessionRuntime) = ProductionAppGraph(fixture.context, runtime, HttpTransport {
        requests.incrementAndGet()
        error("HTTP is prohibited in this local writing review fixture")
    }, object : SessionCredentialStore {
        override suspend fun load(): StoredSession? { credentialAccess.incrementAndGet(); error("No credential access") }
        override suspend fun save(session: StoredSession) { credentialAccess.incrementAndGet(); error("No credential access") }
        override suspend fun clear() { credentialAccess.incrementAndGet(); error("No credential access") }
    }).also { it.closeReminderForThisSession(fixture.account) }

    private fun mount(graph: ProductionAppGraph) {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        host.value = graph
        compose.setContent { RlTheme { RlPaperBackground {
            host.value?.let { activeGraph -> key(activeGraph) { HomeProductionFlow(activeGraph) } }
        } } }
        mounted = true
    }

    private fun systemBack() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    private fun text(id: Int) = fixture.context.getString(id)
    private fun waitTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun waitText(value: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun insert(name: String, vararg fields: Pair<String, Any>) {
        fixture.db.insertOrThrow(name, null, ContentValues().apply {
            put("account_id", fixture.account)
            fields.forEach { (key, value) -> when (value) {
                is Int -> put(key, value)
                is String -> put(key, value)
                else -> error("Unsupported isolated fixture value")
            } }
        })
    }

    private fun query(sql: String, args: Array<String>? = null): List<List<String?>> =
        fixture.db.rawQuery(sql, args).use { cursor -> buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column -> when (cursor.getType(column)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_BLOB -> android.util.Base64.encodeToString(cursor.getBlob(column), android.util.Base64.NO_WRAP)
                else -> cursor.getString(column)
            } })
        } }

    private fun table(name: String): List<List<String?>> {
        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
        return query("select * from \"$name\"").sortedBy { JSONObject.wrap(it).toString() }
    }

    private fun allTables(): Map<String, List<List<String?>>> = query("select name from sqlite_master where type='table' order by name")
        .map { requireNotNull(it.single()) }.associateWith(::table)

    // Only this UUID test's audit table is excluded; no additional product table
    // is omitted from the original ordinary-content protection checks.
    private fun protectedTables() = allTables().filterKeys { !it.startsWith("review_") && it != "android_metadata" && it != auditTable }

    private fun productTables() = allTables().filterKeys { it != auditTable }

    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try { compose.waitUntil(20_000) { result.get() != null }; return requireNotNull(result.get()).getOrThrow() }
        finally { job.cancel(); scope.cancel() }
    }
}
