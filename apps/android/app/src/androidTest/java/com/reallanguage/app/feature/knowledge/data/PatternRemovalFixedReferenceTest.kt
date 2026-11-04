package com.reallanguage.app.feature.knowledge.data

import android.content.ContentValues
import android.database.Cursor
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.domain.ContentFixRequest
import com.reallanguage.app.feature.formation.domain.ContentFixResult
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import com.reallanguage.app.feature.formation.domain.FixedSourceUnit
import com.reallanguage.app.feature.formation.domain.KnowledgeExampleDraft
import com.reallanguage.app.feature.formation.domain.KnowledgeExampleGenerationRepository
import com.reallanguage.app.feature.formation.domain.KnowledgeExampleGenerationResult
import com.reallanguage.app.feature.formation.domain.KnowledgeTargetDetails
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.SourceEvidence
import com.reallanguage.app.feature.knowledge.domain.PatternLoadResult
import com.reallanguage.app.feature.knowledge.domain.PatternRemovalResult
import com.reallanguage.app.feature.learning.data.AndroidChapterArchiveRepository
import com.reallanguage.app.feature.learning.data.AndroidCompleteWritingRepository
import com.reallanguage.app.feature.learning.data.AndroidLockedPatternReader
import com.reallanguage.app.feature.learning.data.AndroidLockedWritingTargetReader
import com.reallanguage.app.feature.learning.data.AndroidTargetSelfAssessmentRepository
import com.reallanguage.app.feature.learning.domain.ChapterArchiveResult
import com.reallanguage.app.feature.learning.domain.CompleteWritingSaveResult
import com.reallanguage.app.feature.learning.domain.FinalWritingBatchResult
import com.reallanguage.app.feature.learning.domain.SentenceWritingResult
import com.reallanguage.app.feature.learning.domain.WritingTargetResult
import com.reallanguage.app.feature.learning.domain.WritingTargetType
import com.reallanguage.app.feature.learning.domain.SelfAssessmentAbility
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import com.reallanguage.app.testing.explicitSourceFixRepository
import com.reallanguage.app.testing.withExplicitSource
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real fixed-source encryption and UUID SQLite; result rows are isolated persistence inputs, not AI evidence. */
class PatternRemovalFixedReferenceTest {
    private lateinit var context: IsolatedLearningDatabaseContext
    private lateinit var helper: FormationDatabase
    private val db get() = helper.writableDatabase
    private val owner = "pattern-owner-${UUID.randomUUID()}"
    private val other = "pattern-other-${UUID.randomUUID()}"
    private val runtime = SessionRuntime().apply { update("isolated-test-token", owner) }
    private var exampleCalls = 0
    private val examples = KnowledgeExampleGenerationRepository { request ->
        exampleCalls++
        KnowledgeExampleGenerationResult.Generated(KnowledgeTargetDetails(
            targetId = request.targetId,
            phonetic = null,
            pronunciationText = "used to",
            representativeScene = "谈论过去的日常习惯。",
            examples = listOf(
                KnowledgeExampleDraft("I used to walk.", "我以前常步行。", "I used to walk."),
                KnowledgeExampleDraft("She used to read.", "她以前常阅读。", "She used to read."),
                KnowledgeExampleDraft("We used to cook.", "我们以前常做饭。", "We used to cook."),
            ),
            naturalRevalidation = true,
        ))
    }

    @Before fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        context = IsolatedLearningDatabaseContext(base, DATABASE_LABEL)
        val file = context.getDatabasePath(LOGICAL_DATABASE).canonicalFile
        assertEquals(base.cacheDir.canonicalFile, file.parentFile)
        assertTrue(PRIVATE_FILENAME.matches(file.name))
        assertFalse(file.exists())
        helper = FormationDatabase(context)
        db
    }

    @After fun tearDown() {
        if (::helper.isInitialized) helper.close()
        if (::context.isInitialized) {
            val file = context.getDatabasePath(LOGICAL_DATABASE).canonicalFile
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            check(file.parentFile == base.cacheDir.canonicalFile && PRIVATE_FILENAME.matches(file.name))
            context.deleteDatabase(LOGICAL_DATABASE)
            assertFalse("Only this test's UUID database is removed", file.exists())
        }
    }

    @Test
    fun removingKnownPatternAfterFixDoesNotStrandTheCurrentChapterOrRestoreRemovedKnowledge() = runBlocking {
        val first = fix("I used to walk.", "我以前常步行。", start = 2)
        seedFinalResults(first, fullSpeakingPassed = true, scenePassed = false)
        assertTrue(archive().archive(first.chapterId, "archive-first") is ChapterArchiveResult.Archived)
        val removedId = rows("select item_id from pattern_items where account_id=?", owner).single().single()!!
        assertEquals(3, count("pattern_examples", owner))
        assertEquals(1, exampleCalls)
        seedFutureQueue(first)
        seedOtherAccountPattern(removedId)

        // Selection occurs BEFORE removal. This is not a later explicit decision to learn the pattern again.
        val current = fix("She used to read.", "她以前常阅读。", start = 4, ref = "PATTERN:$removedId")
        assertEquals(listOf(listOf("PATTERN:$removedId")), rows(
            "select known_knowledge_ref from target_selections where account_id=? and chapter_id=?",
            owner, current.chapterId,
        ))
        assertEquals("Known selection reuses its existing explanation; no extra generation", 1, exampleCalls)
        val fixedBefore = fixedContent()
        val firstArchive = rows("select * from chapter_archives where account_id=? and chapter_id=?", owner, first.chapterId)
        val firstResults = rows("select * from sentence_capabilities where account_id=? and sentence_id=? order by capability_type", owner, first.sentenceId)
        val nonPatternTasks = nonPatternWork()
        val otherBefore = accountSnapshot(other)

        assertEquals(PatternRemovalResult.Removed,
            AndroidPatternRemovalRepository(context, runtime).removeFromPatternBook(removedId, "remove-first"))
        assertRemovedPatternHasNoStateOrFutureTasks()
        assertEquals(fixedBefore, fixedContent())
        assertEquals(firstArchive, rows("select * from chapter_archives where account_id=? and chapter_id=?", owner, first.chapterId))
        assertEquals(firstResults, rows("select * from sentence_capabilities where account_id=? and sentence_id=? order by capability_type", owner, first.sentenceId))
        assertEquals("Sentence-level pending work is not a removed pattern task", nonPatternTasks, nonPatternWork())
        assertEquals(otherBefore, accountSnapshot(other))

        // Ordinary module completion is supplied locally: no HTTP, capture, TTS or model is involved.
        seedFinalResults(current, fullSpeakingPassed = false, scenePassed = true)
        val beforeArchive = accountSnapshot(owner)
        val result = archive().archive(current.chapterId, "archive-current")
        if (result == ChapterArchiveResult.Failed) {
            assertEquals("A rejected archive must roll back even its earlier sentence settlement", beforeArchive, accountSnapshot(owner))
        }
        assertTrue("A fixed chapter must still archive after its previously selected known pattern was removed; result=$result",
            result is ChapterArchiveResult.Archived)

        assertRemovedPatternHasNoStateOrFutureTasks()
        assertEquals("Neither old identity nor an automatic replacement may appear in the book",
            emptyList<Any>(), (AndroidPatternBookRepository(context, runtime).loadAllPatterns() as PatternLoadResult.Ready).patterns)
        assertEquals(fixedBefore, fixedContent())
        assertEquals(firstArchive, rows("select * from chapter_archives where account_id=? and chapter_id=?", owner, first.chapterId))
        assertEquals(firstResults, rows("select * from sentence_capabilities where account_id=? and sentence_id=? order by capability_type", owner, first.sentenceId))
        assertEquals(listOf(listOf("FULL_SPEAKING", "LEARNING"), listOf("FULL_WRITING", "VERIFIED"), listOf("SCENE_RESPONSE", "VERIFIED")), rows(
            "select capability_type,status from sentence_capabilities where account_id=? and sentence_id=? order by capability_type",
            owner, current.sentenceId,
        ))
        assertEquals(listOf(listOf("COMPLETED"), listOf("COMPLETED")), rows(
            "select status from chapters where account_id=? order by chapter_id", owner,
        ))
        assertEquals(2, count("chapter_archives", owner))
        assertEquals(otherBefore, accountSnapshot(other))
        assertEquals(1, exampleCalls)

        val settled = accountSnapshot(owner)
        assertTrue(archive().archive(current.chapterId, "archive-current") is ChapterArchiveResult.AlreadyArchived)
        assertEquals(PatternRemovalResult.AlreadyRemoved,
            AndroidPatternRemovalRepository(context, runtime).removeFromPatternBook(removedId, "remove-first"))
        assertEquals("New repository replays do not resettle or rebuild removed knowledge", settled, accountSnapshot(owner))
        assertEquals(otherBefore, accountSnapshot(other))
        assertEquals(1, exampleCalls)
    }

    @Test
    fun missingKnownReferenceWithoutThisAccountsExactRemovalReceiptCannotArchive() = runBlocking {
        val prepared = prepareCurrentKnownReference()
        // Deliberately corrupt only the private fixture: absence is not proof of a user removal.
        assertEquals(1, db.delete("pattern_items", "account_id=? and item_id=?", arrayOf(owner, prepared.patternId)))
        seedFinalResults(prepared.current, fullSpeakingPassed = false, scenePassed = true)
        for (otherHasReceipt in listOf(false, true)) {
            if (otherHasReceipt) insert("pattern_removal_commands", "account_id" to other,
                "command_id" to "other-removal", "pattern_id" to prepared.patternId)
            val ownerBefore = accountSnapshot(owner)
            val otherBefore = accountSnapshot(other)
            assertEquals("Only an exact receipt belonging to this account can classify a removed reference; otherReceipt=$otherHasReceipt",
                ChapterArchiveResult.Failed, archive().archive(prepared.current.chapterId, "missing-reference"))
            assertEquals("Missing reference failure rolls back sentence settlement and every other account table", ownerBefore, accountSnapshot(owner))
            assertEquals(otherBefore, accountSnapshot(other))
            assertEquals(1, exampleCalls)
        }
    }

    @Test
    fun removedKnownPatternIsNotReturnedAsAnActiveWritingTargetButFixedContentSurvives() = runBlocking {
        val prepared = prepareCurrentKnownReference()
        val before = fixedContent()
        assertEquals(PatternRemovalResult.Removed,
            AndroidPatternRemovalRepository(context, runtime).removeFromPatternBook(prepared.patternId, "remove-writing"))
        val afterRemoval = accountSnapshot(owner)

        assertEquals("Whole-sentence writing remains; the explicitly removed pattern is no longer an active subtarget",
            emptyList<Any>(), AndroidLockedWritingTargetReader(context, runtime).read(prepared.current.chapterId))
        assertEquals(before, fixedContent())
        assertEquals(afterRemoval, accountSnapshot(owner))
        assertEquals(1, exampleCalls)
    }

    @Test
    fun removedKnownPatternIsNotReturnedForAnotherPaidPatternPracticeSet() = runBlocking {
        val prepared = prepareCurrentKnownReference()
        val before = fixedContent()
        assertEquals(PatternRemovalResult.Removed,
            AndroidPatternRemovalRepository(context, runtime).removeFromPatternBook(prepared.patternId, "remove-speaking"))
        val afterRemoval = accountSnapshot(owner)

        assertEquals("The production LR07 reader must not offer the removed pattern for practice generation",
            emptyList<Any>(), AndroidLockedPatternReader(context, runtime).read(prepared.current.chapterId, prepared.current.sentenceId))
        assertEquals(before, fixedContent())
        assertEquals(afterRemoval, accountSnapshot(owner))
        assertEquals(1, exampleCalls)
    }

    @Test
    fun removedKnownPatternCannotBeSelfAssessedAgainFromALateCurrentChapterAction() = runBlocking {
        val prepared = prepareCurrentKnownReference()
        insert("learning_checkpoints", "account_id" to owner, "chapter_id" to prepared.current.chapterId,
            "module_id" to "LR-07", "step_id" to prepared.current.sentenceId)
        val late = AndroidTargetSelfAssessmentRepository(context, runtime, prepared.current.chapterId,
            prepared.current.sentenceId, "LR-07")
        assertEquals(listOf(prepared.current.targetId), late.load().map { it.targetId })
        assertEquals(PatternRemovalResult.Removed,
            AndroidPatternRemovalRepository(context, runtime).removeFromPatternBook(prepared.patternId, "remove-assessment"))
        val afterRemoval = accountSnapshot(owner)

        assertFalse("A page loaded before removal cannot create a new assessment of the removed pattern",
            late.mark(prepared.current.targetId, SelfAssessmentAbility.PATTERN_SPEAKING))
        assertEquals(afterRemoval, accountSnapshot(owner))
        assertEquals(emptyList<Any>(), AndroidTargetSelfAssessmentRepository(context, runtime,
            prepared.current.chapterId, prepared.current.sentenceId, "LR-07").load())
        assertEquals(afterRemoval, accountSnapshot(owner))
        assertEquals(1, exampleCalls)
    }

    @Test
    fun newWritingBatchOmitsRemovedTargetAndCanBeReadRetriedThenArchived() = runBlocking {
        val prepared = prepareCurrentKnownReference()
        val fixed = fixedContent()
        assertEquals(PatternRemovalResult.Removed,
            AndroidPatternRemovalRepository(context, runtime).removeFromPatternBook(prepared.patternId, "remove-before-writing"))
        val result = FinalWritingBatchResult.Valid(listOf(SentenceWritingResult(prepared.current.sentenceId, true, emptyList())))
        assertEquals("An omitted removed subtarget must not block whole-sentence completion", CompleteWritingSaveResult.Saved,
            AndroidCompleteWritingRepository(context, runtime, "writing-after-removal").saveFinalBatch(prepared.current.chapterId, result))
        val saved = accountSnapshot(owner)
        assertEquals(result, AndroidCompleteWritingRepository(context, runtime).loadFinalBatch(prepared.current.chapterId))
        assertEquals(CompleteWritingSaveResult.Saved,
            AndroidCompleteWritingRepository(context, runtime, "writing-after-removal").saveFinalBatch(prepared.current.chapterId, result))
        assertEquals(saved, accountSnapshot(owner))
        for (table in listOf("full_speaking_result_drafts", "scene_response_result_drafts")) {
            insert(table, "account_id" to owner, "chapter_id" to prepared.current.chapterId,
                "sentence_id" to prepared.current.sentenceId, "passed" to 1)
        }
        assertTrue(archive().archive(prepared.current.chapterId, "archive-without-removed-target") is ChapterArchiveResult.Archived)
        assertEquals(0, count("pattern_items", owner))
        assertEquals(emptyList<List<String?>>(), rows("select * from learning_obligations where account_id=? and capability_type like 'PATTERN_%'", owner))
        assertEquals(fixed, fixedContent())
        assertEquals(1, exampleCalls)
    }

    @Test
    fun alreadyAcceptedWritingBatchRetainsItsExactResultAndReplayAfterRemoval() = runBlocking {
        val prepared = prepareCurrentKnownReference()
        val result = FinalWritingBatchResult.Valid(listOf(SentenceWritingResult(prepared.current.sentenceId, false,
            listOf(WritingTargetResult(prepared.current.targetId, WritingTargetType.PATTERN, true)))))
        assertEquals(CompleteWritingSaveResult.Saved,
            AndroidCompleteWritingRepository(context, runtime, "accepted-writing").saveFinalBatch(prepared.current.chapterId, result))
        val writingBefore = rows("select * from writing_result_drafts where account_id=? and chapter_id=? order by ability_type", owner, prepared.current.chapterId)
        assertEquals(PatternRemovalResult.Removed,
            AndroidPatternRemovalRepository(context, runtime).removeFromPatternBook(prepared.patternId, "remove-after-writing"))
        val after = accountSnapshot(owner)
        assertEquals(result, AndroidCompleteWritingRepository(context, runtime).loadFinalBatch(prepared.current.chapterId))
        assertEquals(CompleteWritingSaveResult.Saved,
            AndroidCompleteWritingRepository(context, runtime, "accepted-writing").saveFinalBatch(prepared.current.chapterId, result))
        assertEquals(writingBefore, rows("select * from writing_result_drafts where account_id=? and chapter_id=? order by ability_type", owner, prepared.current.chapterId))
        assertEquals(after, accountSnapshot(owner))
        val changedWhole = FinalWritingBatchResult.Valid(listOf(SentenceWritingResult(prepared.current.sentenceId, true, emptyList())))
        assertEquals(CompleteWritingSaveResult.Failed,
            AndroidCompleteWritingRepository(context, runtime, "accepted-writing").saveFinalBatch(prepared.current.chapterId, changedWhole))
        assertEquals(after, accountSnapshot(owner))
    }

    @Test
    fun missingRemovalReceiptCannotMakeWritingOmitAStillSelectedTarget() = runBlocking {
        val prepared = prepareCurrentKnownReference()
        insert("pattern_removal_commands", "account_id" to other, "command_id" to "not-owners-removal", "pattern_id" to prepared.patternId)
        val before = accountSnapshot(owner)
        val result = FinalWritingBatchResult.Valid(listOf(SentenceWritingResult(prepared.current.sentenceId, true, emptyList())))
        assertEquals(CompleteWritingSaveResult.Failed,
            AndroidCompleteWritingRepository(context, runtime, "missing-removal-writing").saveFinalBatch(prepared.current.chapterId, result))
        assertEquals(before, accountSnapshot(owner))
    }

    private suspend fun prepareCurrentKnownReference(): PreparedKnown {
        val first = fix("I used to walk.", "我以前常步行。", start = 2)
        seedFinalResults(first, fullSpeakingPassed = true, scenePassed = false)
        assertTrue(archive().archive(first.chapterId, "archive-first") is ChapterArchiveResult.Archived)
        val patternId = rows("select item_id from pattern_items where account_id=?", owner).single().single()!!
        val current = fix("She used to read.", "她以前常阅读。", start = 4, ref = "PATTERN:$patternId")
        assertEquals(1, exampleCalls)
        return PreparedKnown(current, patternId)
    }

    private suspend fun fix(english: String, chinese: String, start: Int, ref: String? = null): FixedIds {
        val target = LearningTarget(
            "target", "sentence", "used to + verb", "过去习惯", LearningTargetType.PATTERN,
            structureParts = listOf("used to", "verb"),
            sourceEvidence = SourceEvidence(1, start, start + 7, "used to", "used to", listOf("used to")),
            knownKnowledgeRef = ref,
        )
        val request = ContentFixRequest(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            listOf(EnglishChapterDraft("chapter", listOf(EnglishSentenceDraft(
                "sentence", chinese, true, listOf(EnglishCandidate("candidate", english, listOfNotNull(ref))),
            )))),
            mapOf("sentence" to "candidate"), listOf(target), setOf("target"),
        ).withExplicitSource(chinese, listOf(FixedSourceUnit("sentence", 0, chinese)))
        val result = explicitSourceFixRepository(context, runtime, examples).fix(request)
        assertTrue("The real fixed-source transaction must succeed before removal", result is ContentFixResult.Fixed)
        val chapter = (result as ContentFixResult.Fixed).firstChapterId
        val ids = rows("select unit_id,target_id from target_selections where account_id=? and chapter_id=?", owner, chapter).single()
        return FixedIds(chapter, ids[0]!!, ids[1]!!)
    }

    private fun seedFinalResults(ids: FixedIds, fullSpeakingPassed: Boolean, scenePassed: Boolean) {
        insert("writing_batch_completions", "account_id" to owner, "chapter_id" to ids.chapterId,
            "command_id" to "writing-${ids.chapterId}", "qualification" to "INDEPENDENT")
        for ((type, target) in listOf("WHOLE_WRITING" to "", "PATTERN_WRITING" to ids.targetId)) {
            insert("writing_result_drafts", "account_id" to owner, "chapter_id" to ids.chapterId,
                "sentence_id" to ids.sentenceId, "ability_type" to type, "target_id" to target, "passed" to 1)
        }
        for ((table, passed) in listOf("full_speaking_result_drafts" to fullSpeakingPassed, "scene_response_result_drafts" to scenePassed)) {
            insert(table, "account_id" to owner, "chapter_id" to ids.chapterId,
                "sentence_id" to ids.sentenceId, "passed" to if (passed) 1 else 0)
        }
    }

    private fun seedFutureQueue(ids: FixedIds) {
        insert("review_cycles", "account_id" to owner, "review_cycle_id" to "cycle", "status" to "ACTIVE", "started_local_date" to "2026-09-10")
        insert("review_chapters", "account_id" to owner, "review_cycle_id" to "cycle", "review_chapter_id" to "review",
            "original_chapter_id" to ids.chapterId, "source_type" to "DIARY", "source_title" to "隔离日记", "status" to "READY")
        val obligations = rows("select obligation_id,capability_type from learning_obligations where account_id=? and chapter_id=? order by capability_type", owner, ids.chapterId)
        assertEquals(listOf("PATTERN_SPEAKING", "SCENE_RESPONSE"), obligations.map { it[1] })
        obligations.forEachIndexed { index, row ->
            insert("review_queue_items", "account_id" to owner, "review_cycle_id" to "cycle", "queue_item_id" to "queue-$index",
                "obligation_id" to row[0]!!, "original_chapter_id" to ids.chapterId, "sentence_id" to ids.sentenceId,
                "priority_rank" to index, "entered_at" to 1)
            insert("review_chapter_items", "account_id" to owner, "review_chapter_id" to "review", "queue_item_id" to "queue-$index",
                "sentence_id" to ids.sentenceId, "ability_type" to row[1]!!)
        }
    }

    private fun seedOtherAccountPattern(id: String) {
        insert("pattern_items", "account_id" to other, "item_id" to id, "semantic_pattern_key" to "used to + verb|过去习惯",
            "structure_en" to "used to + verb", "meaning_zh" to "过去习惯", "writing_status" to "VERIFIED",
            "speaking_status" to "LEARNING", "writing_streak" to 3, "speaking_streak" to 1,
            "active_validation" to 1, "representative_scene" to "另一个隔离账号的场景", "natural_revalidation" to 1)
        repeat(3) { index -> insert("pattern_examples", "account_id" to other, "item_id" to id,
            "example_order" to index, "text_en" to "We used to cook.", "text_zh" to "我们以前常做饭。", "pronunciation_text" to "We used to cook.") }
        insert("pattern_occurrences", "account_id" to other, "item_id" to id,
            "chapter_id" to "other-chapter", "sentence_id" to "other-sentence", "is_focus" to 1)
    }

    private fun assertRemovedPatternHasNoStateOrFutureTasks() {
        for (table in listOf("pattern_items", "pattern_examples", "pattern_occurrences")) assertEquals(table, 0, count(table, owner))
        assertEquals(emptyList<List<String?>>(), rows("select * from learning_obligations where account_id=? and capability_type like 'PATTERN_%'", owner))
        assertEquals(emptyList<List<String?>>(), rows("select * from review_chapter_items where account_id=? and ability_type like 'PATTERN_%'", owner))
        assertEquals(listOf(listOf("SCENE_RESPONSE")), rows(
            "select o.capability_type from review_queue_items q join learning_obligations o on o.account_id=q.account_id and o.obligation_id=q.obligation_id where q.account_id=?", owner,
        ))
        assertEquals("Removed queue references cannot remain dangling", 1, count("review_queue_items", owner))
    }

    /** Relation metadata may be safely unlinked; the fixed content and encrypted source may not be rewritten. */
    private fun fixedContent() = mapOf(
        "source" to rows("select * from fixed_source_payloads where account_id=? order by flow_id", owner),
        "sentences" to rows("select * from fixed_sentences where account_id=? order by unit_id", owner),
        "targets" to rows("select chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary,learning_scopes,source_evidence,related_vocabulary_snapshot from target_selections where account_id=? order by target_id", owner),
    )

    private fun nonPatternWork() = mapOf(
        "obligations" to rows("select * from learning_obligations where account_id=? and capability_type not like 'PATTERN_%' order by obligation_id", owner),
        "corrections" to rows("select f.* from correction_flags f join learning_obligations o on o.account_id=f.account_id and o.obligation_id=f.obligation_id where f.account_id=? and o.capability_type not like 'PATTERN_%' order by f.obligation_id", owner),
        "queue" to rows("select q.* from review_queue_items q join learning_obligations o on o.account_id=q.account_id and o.obligation_id=q.obligation_id where q.account_id=? and o.capability_type not like 'PATTERN_%' order by q.queue_item_id", owner),
        "chapterItems" to rows("select * from review_chapter_items where account_id=? and ability_type not like 'PATTERN_%' order by queue_item_id", owner),
    )

    /** Enumerates only the newly created private schema; all account-owned tables participate in replay/rollback checks. */
    private fun accountSnapshot(account: String): Map<String, List<List<String?>>> = buildMap {
        rows("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name").forEach { row ->
            val table = row.single()!!
            check(Regex("[a-z_]+").matches(table))
            val columns = rows("pragma table_info($table)").map { it[1] }
            if ("account_id" in columns) put(table, rows("select * from $table where account_id=? order by rowid", account))
        }
    }

    private fun archive() = AndroidChapterArchiveRepository(context, runtime) { "2026-09-10" }
    private fun count(table: String, account: String) = rows("select count(*) from $table where account_id=?", account).single().single()!!.toInt()
    private fun rows(sql: String, vararg args: String): List<List<String?>> = db.rawQuery(sql, args).use { cursor ->
        buildList { while (cursor.moveToNext()) add(List(cursor.columnCount) { index ->
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_BLOB -> "sha256:" + MessageDigest.getInstance("SHA-256").digest(cursor.getBlob(index)).joinToString("") { "%02x".format(it) }
                else -> cursor.getString(index)
            }
        }) }
    }
    private fun insert(table: String, vararg fields: Pair<String, Any>) {
        db.insertOrThrow(table, null, ContentValues().apply { fields.forEach { (key, value) ->
            when (value) { is String -> put(key, value); is Int -> put(key, value); else -> error("Unsupported fixture type") }
        } })
    }
    private data class FixedIds(val chapterId: String, val sentenceId: String, val targetId: String)
    private data class PreparedKnown(val current: FixedIds, val patternId: String)

    private companion object {
        const val DATABASE_LABEL = "pattern-fixed-reference"
        const val LOGICAL_DATABASE = "real-language-learning.db"
        val PRIVATE_FILENAME = Regex("$DATABASE_LABEL-[a-f0-9-]{36}\\.db")
    }
}
