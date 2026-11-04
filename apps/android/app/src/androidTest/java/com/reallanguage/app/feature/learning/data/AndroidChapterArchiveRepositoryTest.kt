package com.reallanguage.app.feature.learning.data

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.ChapterArchiveResult
import com.reallanguage.app.feature.learning.domain.ChapterAbandonResult
import com.reallanguage.app.feature.learning.domain.ChapterSelectionLoadResult
import com.reallanguage.app.feature.learning.domain.ChapterSelectionBoundary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidChapterArchiveRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val runtime = SessionRuntime().apply { update("token", "account") }

    @Before
    fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val isolatedFile = File(base.cacheDir, "archive-regression-${UUID.randomUUID()}.db")
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = isolatedFile
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openDatabase(isolatedFile.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openDatabase(isolatedFile.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
            override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(isolatedFile)
        }
        database = FormationDatabase(context)
        database.writableDatabase
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("real-language-learning.db")
    }

    @Test
    fun version16UpgradePreservesChaptersAndLegacyAbandonRecordsWithoutInventingATarget() = runBlocking {
        seedChapter(complete = false)
        database.writableDatabase.apply {
            // Only this UUID-named isolated test database is rebuilt to the exact old table shape.
            execSQL("drop table chapter_abandon_commands")
            execSQL("create table chapter_abandon_commands(account_id text not null, command_id text not null, source_id text not null, has_remaining integer not null check(has_remaining in (0,1)), primary key(account_id,command_id))")
            execSQL("insert into chapter_abandon_commands values ('account','legacy','f1',1)")
            execSQL("drop table review_chapter_results")
            execSQL("create table review_chapter_results(account_id text not null,review_chapter_id text not null,command_id text not null,local_date text not null,completed_focus_count integer not null,primary key(account_id,review_chapter_id),unique(account_id,command_id))")
            execSQL("alter table review_queue_items drop column occurrence_count")
            execSQL("alter table target_selections drop column source_evidence")
            com.reallanguage.app.testing.removeKnowledgeReuseSchemaForLegacyFixture(this)
            version = 16
        }
        database.close()
        database = FormationDatabase(context)
        assertEquals(26, database.readableDatabase.version)
        assertEquals(3, count("chapters"))
        assertEquals(3, count("fixed_sentences"))
        assertEquals(1, count("chapter_abandon_commands"))
        assertEquals(1, countWhere("chapter_abandon_commands", "chapter_id is null"))
        val repository = AndroidChapterAbandonRepository(context, runtime)
        assertEquals(ChapterAbandonResult.Failed, repository.abandonUnarchivedChapter("c1", "legacy"))
        assertTrue(repository.abandonUnarchivedChapter("c1", "new-command") is ChapterAbandonResult.Abandoned)
        assertEquals(1, countWhere("chapter_abandon_commands", "chapter_id='c1'"))
    }

    @Test
    fun abandonCommandCannotBeReusedForAnotherChapterOrSource() = runBlocking {
        seedChapter(complete = false)
        val repository = AndroidChapterAbandonRepository(context, runtime)
        val result = repository.abandonUnarchivedChapter("c1", "same-command")
        assertTrue(result is ChapterAbandonResult.Abandoned)
        assertEquals(result, repository.abandonUnarchivedChapter("c1", "same-command"))
        assertEquals(ChapterAbandonResult.Failed, repository.abandonUnarchivedChapter("c2", "same-command"))
        assertEquals(ChapterAbandonResult.Failed, repository.abandonUnarchivedChapter("c3", "same-command"))
        assertEquals(1, countWhere("chapters", "chapter_id='c2'"))
        assertEquals(1, countWhere("chapters", "chapter_id='c3'"))
    }

    @Test
    fun legacyAbandonWithoutARecordedTargetCannotPretendTheCurrentChapterWasDeleted() = runBlocking {
        seedChapter(complete = false)
        insert("chapter_abandon_commands", "account_id" to "account", "command_id" to "legacy-command",
            "source_id" to "f1", "has_remaining" to 1)
        val result = AndroidChapterAbandonRepository(context, runtime).abandonUnarchivedChapter("c1", "legacy-command")
        assertEquals(ChapterAbandonResult.Failed, result)
        assertEquals(1, countWhere("chapters", "chapter_id='c1'"))
        assertEquals(1, countWhere("fixed_sentences", "chapter_id='c1'"))
    }

    @Test
    fun archiveAtomicallyCreatesFormalStateAndReplaysOriginalSummary() = runBlocking {
        seedChapter(complete = true)
        AndroidExplanationCache(context).write("account", "c1", "s1", "base", "validated-test-content")
        val repository = AndroidChapterArchiveRepository(context, runtime) { "2026-09-02" }

        val first = repository.archive("c1", "archive-c1")
        assertTrue(first is ChapterArchiveResult.Archived)
        val summary = (first as ChapterArchiveResult.Archived).summary
        assertTrue(summary.hasNextChapter)
        assertEquals(5, summary.items.size)
        assertEquals(3, count("sentence_capabilities"))
        assertEquals(3, count("learning_obligations"))
        assertEquals(2, count("correction_flags"))
        assertEquals("The same source still has an unfinished second chapter", 0, count("normal_learning_days"))
        assertEquals("1", value("select verified_success_count from vocabulary_items"))
        assertEquals("CET_4", value("select wordlist from vocabulary_wordlists"))
        assertEquals("经营社区咖啡店", value("select representative_scene from vocabulary_items"))
        assertEquals(3, count("vocabulary_examples"))
        assertEquals(0, count("target_vocabulary_details"))
        assertEquals("0", value("select writing_streak from pattern_items"))
        assertEquals("表达当下感受", value("select representative_scene from pattern_items"))
        assertEquals(3, count("pattern_examples"))
        assertEquals(0, count("target_pattern_details"))
        assertEquals(0, count("writing_result_drafts"))
        assertEquals(0, count("learning_explanation_cache"))
        assertEquals("COMPLETED", value("select status from chapters where chapter_id='c1'"))

        val replay = repository.archive("c1", "different-retry-key")
        assertEquals(summary, (replay as ChapterArchiveResult.AlreadyArchived).summary)
        assertEquals(3, count("sentence_capabilities"))
        assertEquals(0, count("normal_learning_days"))
    }

    @Test
    fun legacyCompleteSentenceTargetCanFinishWithoutEnteringVocabularyBook() = runBlocking {
        seedChapter(complete = true)
        val sentence = "Can you hear what I'm saying right now?"
        database.writableDatabase.execSQL(
            "update fixed_sentences set text_en=? where account_id='account' and chapter_id='c1' and unit_id='s1'",
            arrayOf(sentence),
        )
        database.writableDatabase.execSQL(
            "update target_selections set type='FIXED_EXPRESSION',display=? where account_id='account' and chapter_id='c1' and target_id='v1'",
            arrayOf(sentence),
        )

        val result = AndroidChapterArchiveRepository(context, runtime) { "2026-09-02" }
            .archive("c1", "archive-invalid-vocabulary")

        assertTrue(result is ChapterArchiveResult.Archived)
        assertEquals(0, count("vocabulary_items"))
        assertEquals(1, count("pattern_items"))
    }

    @Test
    fun missingRequiredFinalDraftRollsBackEverything() = runBlocking {
        seedChapter(complete = false)
        val result = AndroidChapterArchiveRepository(context, runtime) { "2026-09-02" }
            .archive("c1", "archive-c1")
        assertEquals(ChapterArchiveResult.Failed, result)
        assertEquals(0, count("chapter_archives"))
        assertEquals(0, count("sentence_capabilities"))
        assertEquals("NOT_STARTED", value("select status from chapters where chapter_id='c1'"))
    }

    @Test
    fun selectionReadsOnlyNotStartedChaptersFromTheArchivedSource() = runBlocking {
        seedChapter(complete = true)
        AndroidChapterArchiveRepository(context, runtime) { "2026-09-02" }.archive("c1", "archive-c1")

        val result = AndroidChapterSelectionRepository(context, runtime).loadRemaining(
            ChapterSelectionBoundary.ArchivedChapter("c1"),
        )

        val ready = result as ChapterSelectionLoadResult.Ready
        assertEquals("f1", ready.sourceId)
        assertEquals(listOf("c2"), ready.chapters.map { it.chapterId })
        assertEquals("下一章内容", ready.chapters.single().identityZh)
    }

    @Test
    fun selectionRejectsUnknownOrUnarchivedBoundary() = runBlocking {
        seedChapter(complete = true)
        assertEquals(
            ChapterSelectionLoadResult.Failed,
            AndroidChapterSelectionRepository(context, runtime).loadRemaining(
                ChapterSelectionBoundary.ArchivedChapter("c1"),
            ),
        )
    }

    @Test
    fun abandonHardDeletesOnlyTheUnarchivedChapterAndReplaysSafely() = runBlocking {
        seedChapter(complete = false)
        AndroidExplanationCache(context).write("account", "c1", "s1", "base", "validated-test-content")
        AndroidExplanationCache(context).write("account", "c2", "s2", "base", "other-chapter-content")
        insert("vocabulary_items", "account_id" to "account", "item_id" to "existing", "normalized_form" to "calm", "semantic_sense_key" to "prior", "display" to "calm", "meaning_zh" to "既有词义", "status" to "VERIFIED", "verified_success_count" to 2)
        val repository = AndroidChapterAbandonRepository(context, runtime)

        val first = repository.abandonUnarchivedChapter("c1", "abandon-c1")

        assertEquals(ChapterAbandonResult.Abandoned(true, "f1"), first)
        assertEquals(0, countWhere("chapters", "chapter_id='c1'"))
        assertEquals(0, countWhere("fixed_sentences", "chapter_id='c1'"))
        assertEquals(0, countWhere("target_selections", "chapter_id='c1'"))
        assertEquals(0, countWhere("writing_result_drafts", "chapter_id='c1'"))
        assertEquals(0, countWhere("learning_explanation_cache", "chapter_id='c1'"))
        assertEquals(1, countWhere("learning_explanation_cache", "chapter_id='c2'"))
        assertEquals(1, countWhere("chapters", "chapter_id='c2'"))
        assertEquals("2", value("select verified_success_count from vocabulary_items where item_id='existing'"))
        assertEquals(first, repository.abandonUnarchivedChapter("c1", "abandon-c1"))
        val selection = AndroidChapterSelectionRepository(context, runtime).loadRemaining(
            ChapterSelectionBoundary.FixedSource("f1"),
        ) as ChapterSelectionLoadResult.Ready
        assertEquals(listOf("c2"), selection.chapters.map { it.chapterId })
    }

    @Test
    fun abandoningLastUnfinishedChapterPreservesArchivedSourceIdentity() = runBlocking {
        seedChapter(complete = true)
        val archive = AndroidChapterArchiveRepository(context, runtime) { "2026-09-02" }
            .archive("c1", "archive-c1")
        assertTrue(archive is ChapterArchiveResult.Archived)
        val formalCount = count("sentence_capabilities")

        assertEquals(ChapterAbandonResult.Abandoned(false, null),
            AndroidChapterAbandonRepository(context, runtime).abandonUnarchivedChapter("c2", "abandon-c2"))

        assertEquals("Archived content must keep its source metadata", 1, countWhere("formation_flows", "flow_id='f1'"))
        assertEquals(1, countWhere("chapters", "chapter_id='c1'"))
        assertEquals(1, countWhere("chapter_archives", "chapter_id='c1'"))
        assertEquals(formalCount, count("sentence_capabilities"))
        assertEquals(0, countWhere("chapters", "chapter_id='c2'"))
        listOf("2026-08-30", "2026-08-31", "2026-09-01").forEach {
            insert("normal_learning_days", "account_id" to "account", "local_date" to it)
        }
        val queue = com.reallanguage.app.feature.review.data.AndroidTodayReviewRepository(context, runtime) { "2026-09-03" }
            .loadTodayQueue(3) as com.reallanguage.app.feature.review.domain.ReviewQueueResult.Ready
        assertEquals("Archived first chapter must still enter review", listOf("c1"), queue.chapters.map { it.originalChapterId })
    }

    @Test
    fun archivedChapterIsNeverAbandoned() = runBlocking {
        seedChapter(complete = true)
        AndroidChapterArchiveRepository(context, runtime) { "2026-09-02" }.archive("c1", "archive-c1")

        val result = AndroidChapterAbandonRepository(context, runtime)
            .abandonUnarchivedChapter("c1", "abandon-c1")

        assertEquals(ChapterAbandonResult.AlreadyArchived, result)
        assertEquals(1, countWhere("chapters", "chapter_id='c1'"))
        assertEquals(1, countWhere("chapter_archives", "chapter_id='c1'"))
    }

    @Test
    fun unexpectedFormalContributionMakesAbandonRollback() = runBlocking {
        seedChapter(complete = false)
        insert("learning_obligations", "account_id" to "account", "obligation_id" to "bad", "chapter_id" to "c1", "sentence_id" to "s1", "capability_type" to "FULL_WRITING", "target_id" to "")

        val result = AndroidChapterAbandonRepository(context, runtime)
            .abandonUnarchivedChapter("c1", "abandon-c1")

        assertEquals(ChapterAbandonResult.Failed, result)
        assertEquals(1, countWhere("chapters", "chapter_id='c1'"))
        assertEquals(1, countWhere("fixed_sentences", "chapter_id='c1'"))
        assertEquals(0, count("chapter_abandon_commands"))
    }

    @Test
    fun samePatternTwiceInOneChapterAddsOnlyOneConsecutiveSuccess() = runBlocking {
        seedChapter(complete = true)
        database.writableDatabase.execSQL("update writing_result_drafts set passed=1 where target_id='p1'")
        insert("fixed_sentences", "account_id" to "account", "chapter_id" to "c1", "unit_id" to "s1b", "sentence_order" to 1,
            "text_zh" to "她感到平静。", "text_en" to "She feels calm.", "role" to "FOCUS")
        insert("target_selections", "account_id" to "account", "chapter_id" to "c1", "target_id" to "p-extra",
            "unit_id" to "s1b", "type" to "PATTERN", "display" to "feel + adjective", "explanation_zh" to "表达感受",
            "structure_parts" to "", "related_vocabulary" to "")
        insert("writing_result_drafts", "account_id" to "account", "chapter_id" to "c1", "sentence_id" to "s1b",
            "ability_type" to "PATTERN_WRITING", "target_id" to "p-extra", "passed" to 1)
        insert("writing_result_drafts", "account_id" to "account", "chapter_id" to "c1", "sentence_id" to "s1b",
            "ability_type" to "WHOLE_WRITING", "target_id" to "", "passed" to 1)
        listOf("full_speaking_result_drafts", "scene_response_result_drafts").forEach {
            insert(it, "account_id" to "account", "chapter_id" to "c1", "sentence_id" to "s1b", "passed" to 1)
        }
        assertTrue(AndroidChapterArchiveRepository(context, runtime).archive("c1", "duplicate-pattern") is ChapterArchiveResult.Archived)
        assertEquals("1", value("select writing_streak from pattern_items"))
        assertEquals(1, count("pattern_items"))
    }

    @Test
    fun supportedWritingDoesNotDowngradeAnExistingVerifiedWordOrPattern() = runBlocking {
        seedChapter(complete = true)
        database.writableDatabase.apply {
            delete("writing_result_drafts", "chapter_id='c1'", null)
            execSQL("update writing_batch_completions set qualification='SUPPORTED'")
        }
        insert("vocabulary_items", "account_id" to "account", "item_id" to "shared-sense",
            "normalized_form" to "calm", "semantic_sense_key" to "平静的", "display" to "calm",
            "meaning_zh" to "平静的", "status" to "VERIFIED", "verified_success_count" to 2)
        insert("pattern_items", "account_id" to "account", "item_id" to "shared-pattern",
            "semantic_pattern_key" to "feel + adjective|表达感受", "structure_en" to "feel + adjective",
            "meaning_zh" to "表达感受", "writing_status" to "VERIFIED", "speaking_status" to "SELF_ASSESSED",
            "writing_streak" to 2, "speaking_streak" to 0, "active_validation" to 1)
        assertTrue(AndroidChapterArchiveRepository(context, runtime).archive("c1", "supported-shared") is ChapterArchiveResult.Archived)
        assertEquals("VERIFIED", value("select status from vocabulary_items"))
        assertEquals("2", value("select verified_success_count from vocabulary_items"))
        assertEquals("VERIFIED", value("select writing_status from pattern_items"))
        assertEquals("SELF_ASSESSED", value("select speaking_status from pattern_items"))
        assertEquals("2", value("select writing_streak from pattern_items"))
        assertEquals("shared-sense", value("select item_id from vocabulary_occurrences"))
    }

    @Test fun selfAssessmentOnlyBecomesTentativeAtArchiveAndDoesNotInventSuccessCounts() = runBlocking {
        seedChapter(complete = true)
        database.writableDatabase.execSQL("delete from writing_result_drafts")
        database.writableDatabase.execSQL("update writing_batch_completions set qualification='SUPPORTED'")
        seedSelfAssessments()
        assertEquals(0, count("vocabulary_items"))
        assertEquals(0, count("pattern_items"))
        assertTrue(AndroidChapterArchiveRepository(context, runtime).archive("c1", "self-supported") is ChapterArchiveResult.Archived)
        assertEquals("SELF_ASSESSED", value("select status from vocabulary_items"))
        assertEquals("SELF_ASSESSED", value("select writing_status from pattern_items"))
        assertEquals("SELF_ASSESSED", value("select speaking_status from pattern_items"))
        assertEquals("0", value("select verified_success_count from vocabulary_items"))
        assertEquals("0", value("select writing_streak from pattern_items"))
        assertEquals("0", value("select speaking_streak from pattern_items"))
        assertEquals(0, count("learning_self_assessments"))
    }

    @Test fun finalIndependentResultOverridesEarlierSelfAssessmentForOnlyThatAbility() = runBlocking {
        seedChapter(complete = true)
        seedSelfAssessments()
        assertTrue(AndroidChapterArchiveRepository(context, runtime).archive("c1", "self-independent") is ChapterArchiveResult.Archived)
        assertEquals("VERIFIED", value("select status from vocabulary_items"))
        assertEquals("LEARNING", value("select writing_status from pattern_items"))
        assertEquals("SELF_ASSESSED", value("select speaking_status from pattern_items"))
        assertEquals("1", value("select verified_success_count from vocabulary_items"))
        assertEquals("0", value("select writing_streak from pattern_items"))
        assertEquals("0", value("select speaking_streak from pattern_items"))
    }

    @Test fun mismatchedSelfAssessmentMustNotBeSilentlyArchivedOrDiscarded() = runBlocking {
        seedChapter(complete = true)
        database.writableDatabase.execSQL("insert into learning_self_assessments values ('account','c1','s2','v1','MEANING_WRITING')")
        assertEquals(ChapterArchiveResult.Failed, AndroidChapterArchiveRepository(context, runtime).archive("c1", "mismatch-self"))
        assertEquals(0, count("chapter_archives"))
        assertEquals(1, count("learning_self_assessments"))
    }

    private fun seedSelfAssessments() {
        listOf("v1" to "MEANING_WRITING", "p1" to "PATTERN_WRITING", "p1" to "PATTERN_SPEAKING").forEach { (target, capability) ->
            database.writableDatabase.execSQL("insert into learning_self_assessments values ('account','c1','s1',?,?)", arrayOf(target, capability))
        }
    }

    @Test
    fun version18UpgradePreservesEveryKnowledgeColumnAndOrderWhileAddingAllowedStates() = runBlocking {
        seedChapter(complete = true)
        assertTrue(AndroidChapterArchiveRepository(context, runtime).archive("c1", "before-upgrade") is ChapterArchiveResult.Archived)
        val db = database.writableDatabase
        val tables = listOf("sentence_capabilities", "vocabulary_items", "pattern_items")
        db.execSQL("update sentence_capabilities set status='LEARNING'")
        db.execSQL("update vocabulary_items set status='LEARNING'")
        db.execSQL("update pattern_items set writing_status='LEARNING',speaking_status='LEARNING'")
        fun snapshot(table: String) = database.readableDatabase.rawQuery("select rowid,* from $table order by rowid", null).use { c ->
            buildList { while (c.moveToNext()) add(List(c.columnCount) { if (c.isNull(it)) null else c.getString(it) }) }
        }
        val before = tables.associateWith(::snapshot)
        // Reconstruct the exact legacy constraint in this isolated test database only.
        tables.forEach { table ->
            val ddl = value("select sql from sqlite_master where type='table' and name='$table'")
                .replace("'PENDING','LEARNING','SELF_ASSESSED','VERIFIED'", "'LEARNING','VERIFIED'")
                .replace("'PENDING','LEARNING','VERIFIED'", "'LEARNING','VERIFIED'")
            db.execSQL("alter table $table rename to ${table}_test_old")
            db.execSQL(ddl)
            db.execSQL("insert into $table(rowid," + db.rawQuery("pragma table_info($table)", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(1)) }.joinToString(",")
            } + ") select rowid,* from ${table}_test_old")
            db.execSQL("drop table ${table}_test_old")
        }
        db.execSQL("alter table review_queue_items drop column occurrence_count")
        db.execSQL("alter table target_selections drop column source_evidence")
        com.reallanguage.app.testing.removeKnowledgeReuseSchemaForLegacyFixture(db)
        db.version = 18
        database.close()
        database = FormationDatabase(context)
        assertEquals(26, database.readableDatabase.version)
        assertEquals(before, tables.associateWith(::snapshot))
        database.writableDatabase.apply {
            execSQL("update sentence_capabilities set status='PENDING'")
            execSQL("update vocabulary_items set status='SELF_ASSESSED'")
            execSQL("update pattern_items set writing_status='PENDING',speaking_status='SELF_ASSESSED'")
        }
        assertEquals(1, count("chapter_archives"))
        assertEquals(3, count("pattern_examples"))
        assertEquals(3, count("vocabulary_examples"))
    }

    @Test
    fun supportedWritingCanArchiveWithoutInventingFailuresOrVerifiedWriting() = runBlocking {
        seedChapter(complete = true)
        database.writableDatabase.apply {
            delete("writing_result_drafts", "chapter_id='c1'", null)
            delete("writing_batch_completions", "chapter_id='c1'", null)
        }
        assertEquals(com.reallanguage.app.feature.learning.domain.CompleteWritingSaveResult.Saved,
            AndroidCompleteWritingRepository(context, runtime).saveFinalBatch("c1",
                com.reallanguage.app.feature.learning.domain.FinalWritingBatchResult.SupportedLearningOnly))
        val archive = AndroidChapterArchiveRepository(context, runtime)
        val result = archive.archive("c1", "supported")
        assertTrue("Supported practice completes the chapter without claiming a final result", result is ChapterArchiveResult.Archived)
        assertEquals("PENDING", value("select status from sentence_capabilities where capability_type='FULL_WRITING'"))
        assertEquals("PENDING", value("select status from vocabulary_items"))
        assertEquals("PENDING", value("select writing_status from pattern_items"))
        assertEquals("0", value("select verified_success_count from vocabulary_items"))
        assertEquals("Only the independently failed scene is a correction", 1, count("correction_flags"))
        assertEquals(5, count("learning_obligations"))
        assertEquals((result as ChapterArchiveResult.Archived).summary,
            (archive.archive("c1", "supported-retry") as ChapterArchiveResult.AlreadyArchived).summary)
    }

    @Test
    fun supportedBatchWithContradictoryJudgmentRowsDoesNotArchive() = runBlocking {
        seedChapter(complete = true)
        database.writableDatabase.execSQL("update writing_batch_completions set qualification='SUPPORTED'")
        assertEquals(ChapterArchiveResult.Failed,
            AndroidChapterArchiveRepository(context, runtime).archive("c1", "contradictory"))
        assertEquals(0, count("chapter_archives"))
        assertEquals(0, count("sentence_capabilities"))
    }

    @Test
    fun existingVocabularyUsesItsSemanticIdentityForUpdateAndOccurrence() = runBlocking {
        seedChapter(complete = true)
        insert("vocabulary_items", "account_id" to "account", "item_id" to "shared-sense",
            "normalized_form" to "calm", "semantic_sense_key" to "平静的", "display" to "calm",
            "meaning_zh" to "平静的", "status" to "VERIFIED", "verified_success_count" to 2)
        assertTrue(AndroidChapterArchiveRepository(context, runtime).archive("c1", "shared-word") is ChapterArchiveResult.Archived)
        assertEquals(1, count("vocabulary_items"))
        assertEquals("3", value("select verified_success_count from vocabulary_items"))
        assertEquals("shared-sense", value("select item_id from vocabulary_occurrences"))
    }

    @Test
    fun selectingARemovedPatternInNewContentCreatesANewIdentity() = runBlocking {
        seedChapter(complete = true)
        val archive = AndroidChapterArchiveRepository(context, runtime)
        assertTrue(archive.archive("c1", "first") is ChapterArchiveResult.Archived)
        val oldId = value("select item_id from pattern_items")
        val removal = com.reallanguage.app.feature.knowledge.data.AndroidPatternRemovalRepository(context, runtime)
        assertEquals(com.reallanguage.app.feature.knowledge.domain.PatternRemovalResult.Removed,
            removal.removeFromPatternBook(oldId, "remove-old"))
        seedSecondPatternChapter()
        assertTrue(archive.archive("c2", "second") is ChapterArchiveResult.Archived)
        val newId = value("select item_id from pattern_items")
        org.junit.Assert.assertNotEquals(oldId, newId)
        assertEquals("1", value("select writing_streak from pattern_items"))
        assertEquals(1, count("pattern_occurrences"))
        assertEquals(2, countWhere("target_selections", "type='PATTERN'"))
        assertEquals(2, count("chapter_archives"))
        assertEquals(com.reallanguage.app.feature.knowledge.domain.PatternRemovalResult.AlreadyRemoved,
            removal.removeFromPatternBook(oldId, "remove-old"))
        assertEquals(newId, value("select item_id from pattern_items"))
    }

    @Test
    fun existingSemanticPatternKeepsItsIdentityAndUpdatesTheSameItem() = runBlocking {
        seedChapter(complete = true)
        insert("pattern_items", "account_id" to "account", "item_id" to "imported-pattern",
            "semantic_pattern_key" to "feel + adjective|表达感受", "structure_en" to "feel + adjective",
            "meaning_zh" to "表达感受", "writing_status" to "VERIFIED", "speaking_status" to "VERIFIED",
            "writing_streak" to 2, "speaking_streak" to 1, "active_validation" to 1)
        database.writableDatabase.execSQL("update writing_result_drafts set passed=1 where target_id='p1'")
        assertTrue(AndroidChapterArchiveRepository(context, runtime).archive("c1", "shared") is ChapterArchiveResult.Archived)
        assertEquals(1, count("pattern_items"))
        assertEquals("imported-pattern", value("select item_id from pattern_items"))
        assertEquals("3", value("select writing_streak from pattern_items"))
        assertEquals("Speaking has not reached three; the whole pattern must stay active", "1", value("select active_validation from pattern_items"))
        assertEquals("imported-pattern", value("select item_id from pattern_occurrences"))
    }

    @Test
    fun removingPatternAlsoRemovesTasksWithNormalizedWhitespace() = runBlocking {
        seedChapter(complete = true)
        database.writableDatabase.execSQL("update target_selections set display=' Feel  +  adjective ' where target_id='p1'")
        assertTrue(AndroidChapterArchiveRepository(context, runtime).archive("c1", "spaced") is ChapterArchiveResult.Archived)
        val id = value("select item_id from pattern_items")
        assertEquals(2, countWhere("learning_obligations", "target_id='p1'"))
        val removal = com.reallanguage.app.feature.knowledge.data.AndroidPatternRemovalRepository(context, runtime)
        assertEquals(com.reallanguage.app.feature.knowledge.domain.PatternRemovalResult.Removed,
            removal.removeFromPatternBook(id, "remove-spaced"))
        assertEquals(0, countWhere("learning_obligations", "target_id='p1'"))
        assertEquals(1, count("correction_flags"))
        assertEquals(1, count("chapter_archives"))
        assertEquals(1, countWhere("target_selections", "target_id='p1'"))
    }

    private fun seedSecondPatternChapter() {
        insert("target_selections", "account_id" to "account", "chapter_id" to "c2", "target_id" to "p2",
            "unit_id" to "s2", "type" to "PATTERN", "display" to "feel + adjective",
            "explanation_zh" to "表达感受", "structure_parts" to "", "related_vocabulary" to "")
        insert("writing_batch_completions", "account_id" to "account", "chapter_id" to "c2", "command_id" to "write-c2", "qualification" to "INDEPENDENT")
        listOf("WHOLE_WRITING" to "", "PATTERN_WRITING" to "p2").forEach { (ability, target) ->
            insert("writing_result_drafts", "account_id" to "account", "chapter_id" to "c2", "sentence_id" to "s2",
                "ability_type" to ability, "target_id" to target, "passed" to 1)
        }
        listOf("full_speaking_result_drafts", "scene_response_result_drafts").forEach {
            insert(it, "account_id" to "account", "chapter_id" to "c2", "sentence_id" to "s2", "passed" to 1)
        }
    }

    private fun seedChapter(complete: Boolean) {
        val db = database.writableDatabase
        insert("formation_flows", "account_id" to "account", "flow_id" to "f1", "status" to "FIXED", "source_type" to "DIARY", "source_title" to "日记")
        insert("fix_commands", "account_id" to "account", "command_id" to "fix-f1", "first_chapter_id" to "c1")
        insert("chapters", "account_id" to "account", "flow_id" to "f1", "chapter_id" to "c1", "chapter_order" to 0, "status" to "NOT_STARTED")
        insert("chapters", "account_id" to "account", "flow_id" to "f1", "chapter_id" to "c2", "chapter_order" to 1, "status" to "NOT_STARTED")
        insert("chapters", "account_id" to "account", "flow_id" to "other-flow", "chapter_id" to "c3", "chapter_order" to 0, "status" to "NOT_STARTED")
        insert("fixed_sentences", "account_id" to "account", "chapter_id" to "c1", "unit_id" to "s1", "sentence_order" to 0, "text_zh" to "我很平静。", "text_en" to "I felt calm.", "role" to "FOCUS")
        insert("fixed_sentences", "account_id" to "account", "chapter_id" to "c2", "unit_id" to "s2", "sentence_order" to 0, "text_zh" to "下一章内容", "text_en" to "The next chapter.", "role" to "FOCUS")
        insert("fixed_sentences", "account_id" to "account", "chapter_id" to "c3", "unit_id" to "s3", "sentence_order" to 0, "text_zh" to "其他来源", "text_en" to "Another source.", "role" to "FOCUS")
        insert("target_selections", "account_id" to "account", "chapter_id" to "c1", "target_id" to "v1", "unit_id" to "s1", "type" to "WORD", "display" to "calm", "explanation_zh" to "平静的", "structure_parts" to "", "related_vocabulary" to "", "learning_scopes" to "CET_4")
        insert("target_vocabulary_details", "account_id" to "account", "chapter_id" to "c1", "target_id" to "v1", "phonetic" to "/kɑːm/", "pronunciation_text" to "calm", "representative_scene" to "经营社区咖啡店", "examples_json" to """[{"textEn":"Stay calm.","textZh":"保持平静。","pronunciationText":"Stay calm."},{"textEn":"She felt calm.","textZh":"她感到平静。","pronunciationText":"She felt calm."},{"textEn":"The room was calm.","textZh":"房间很安静。","pronunciationText":"The room was calm."}]""")
        insert("target_selections", "account_id" to "account", "chapter_id" to "c1", "target_id" to "p1", "unit_id" to "s1", "type" to "PATTERN", "display" to "feel + adjective", "explanation_zh" to "表达感受", "structure_parts" to "", "related_vocabulary" to "")
        insert("target_pattern_details", "account_id" to "account", "chapter_id" to "c1", "target_id" to "p1", "phonetic" to "", "pronunciation_text" to "feel calm", "representative_scene" to "表达当下感受", "examples_json" to """[{"textEn":"I feel calm.","textZh":"我感到平静。","pronunciationText":"I feel calm."},{"textEn":"She feels ready.","textZh":"她觉得准备好了。","pronunciationText":"She feels ready."},{"textEn":"They feel happy.","textZh":"他们感到高兴。","pronunciationText":"They feel happy."}]""", "natural_revalidation" to 1)
        insert("writing_batch_completions", "account_id" to "account", "chapter_id" to "c1", "command_id" to "write-c1", "qualification" to "INDEPENDENT")
        insert("writing_result_drafts", "account_id" to "account", "chapter_id" to "c1", "sentence_id" to "s1", "ability_type" to "WHOLE_WRITING", "target_id" to "", "passed" to 1)
        insert("writing_result_drafts", "account_id" to "account", "chapter_id" to "c1", "sentence_id" to "s1", "ability_type" to "MEANING_WRITING", "target_id" to "v1", "passed" to 1)
        insert("writing_result_drafts", "account_id" to "account", "chapter_id" to "c1", "sentence_id" to "s1", "ability_type" to "PATTERN_WRITING", "target_id" to "p1", "passed" to 0)
        insert("full_speaking_result_drafts", "account_id" to "account", "chapter_id" to "c1", "sentence_id" to "s1", "passed" to 1)
        if (complete) insert("scene_response_result_drafts", "account_id" to "account", "chapter_id" to "c1", "sentence_id" to "s1", "passed" to 0)
        db.setForeignKeyConstraintsEnabled(true)
    }

    private fun insert(table: String, vararg fields: Pair<String, Any>) {
        database.writableDatabase.insertOrThrow(table, null, ContentValues().apply {
            fields.forEach { (name, value) ->
                when (value) {
                    is String -> put(name, value)
                    is Int -> put(name, value)
                }
            }
        })
    }

    private fun count(table: String) = database.readableDatabase.rawQuery("select count(*) from $table", null).use { it.moveToFirst(); it.getInt(0) }
    private fun countWhere(table: String, where: String) = database.readableDatabase.rawQuery("select count(*) from $table where $where", null).use { it.moveToFirst(); it.getInt(0) }
    private fun value(sql: String) = database.readableDatabase.rawQuery(sql, null).use { it.moveToFirst(); it.getString(0) }
}
