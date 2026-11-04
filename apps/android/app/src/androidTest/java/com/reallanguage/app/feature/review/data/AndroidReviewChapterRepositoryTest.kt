package com.reallanguage.app.feature.review.data

import android.content.ContentValues
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.review.domain.ReviewAbilityResultDraft
import com.reallanguage.app.feature.review.domain.ReviewChapterCommitResult
import com.reallanguage.app.feature.review.domain.ReviewChapterLoadResult
import com.reallanguage.app.feature.review.domain.ReviewQueueResult
import com.reallanguage.app.feature.review.domain.ReviewResultQualification
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidReviewChapterRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val runtime = SessionRuntime().apply { update("token", "account") }
    private val today = "2026-09-02"

    @Before fun setUp() {
        context = com.reallanguage.app.testing.IsolatedLearningDatabaseContext(
            InstrumentationRegistry.getInstrumentation().targetContext, "review-chapter-regression")
        database = FormationDatabase(context)
        database.writableDatabase
        listOf("2026-08-29", "2026-08-30", "2026-09-01").forEach(::seedDay)
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase("real-language-learning.db")
    }

    @Test fun loadsFullOriginalSceneAndAtomicallyCommitsEveryPendingAbility() = runBlocking {
        seedChapter(twoAbilities = true)
        val reviewId = currentReviewId()
        val repository = AndroidReviewChapterRepository(context, runtime) { today }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        assertEquals(listOf("Context line.", "Focus line."), snapshot.originalScene)
        assertEquals("Focus line.", snapshot.focusSentence)
        assertEquals(2, snapshot.abilities.size)

        val results = snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "e-${it.abilityId}") }
        assertTrue(repository.commitChapter(reviewId, "commit-1", results) is ReviewChapterCommitResult.Committed)
        assertEquals(0, count("learning_obligations"))
        assertEquals(1, count("review_chapter_results"))
        assertEquals(1, scalar("select completed_focus_count from review_sessions"))

        assertTrue(repository.commitChapter(reviewId, "commit-1", results) is ReviewChapterCommitResult.AlreadyCommitted)
        assertEquals(1, count("review_chapter_results"))
        assertEquals(1, scalar("select completed_focus_count from review_sessions"))
    }

    @Test fun oldRepositoryCannotReadOrCommitAChangedAccountsReview() = runBlocking {
        seedChapter(twoAbilities = true)
        val reviewId = currentReviewId()
        val snapshot = (AndroidReviewChapterRepository(context, runtime).loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        val oldRuntime = SessionRuntime().apply { update("isolated", "old-account") }
        val oldRepository = AndroidReviewChapterRepository(context, oldRuntime) { today }
        oldRuntime.update("isolated", "account")
        assertEquals(ReviewChapterLoadResult.Failed, oldRepository.loadChapter(reviewId))
        assertEquals(ReviewChapterCommitResult.Failed, oldRepository.commitChapter(reviewId, "wrong-owner", snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "isolated-evidence") }))
        assertEquals(2, count("learning_obligations"))
        assertEquals(0, count("review_chapter_results"))
    }

    @Test fun incompleteEvidenceRejectsWholeCommitWithoutPartialState() = runBlocking {
        seedChapter(twoAbilities = true)
        val reviewId = currentReviewId()
        val repository = AndroidReviewChapterRepository(context, runtime) { today }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter

        assertEquals(
            ReviewChapterCommitResult.Failed,
            repository.commitChapter(
                reviewId,
                "commit-bad",
                listOf(ReviewAbilityResultDraft(snapshot.abilities.first().abilityId, "evidence")),
            ),
        )
        assertEquals(2, count("learning_obligations"))
        assertEquals(0, count("review_chapter_results"))
        assertEquals(0, count("review_sessions"))
    }

    @Test fun committedCommandRejectsChangedJudgmentOrEvidenceButAllowsReorderedReplay() = runBlocking {
        seedChapter(twoAbilities = true)
        val reviewId = currentReviewId()
        val repository = AndroidReviewChapterRepository(context, runtime) { today }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        val drafts = snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "e-${it.abilityId}", true) }
        assertTrue(repository.commitChapter(reviewId, "immutable-results", drafts) is ReviewChapterCommitResult.Committed)
        assertEquals(ReviewChapterCommitResult.Failed, repository.commitChapter(reviewId, "immutable-results",
            drafts.map { it.copy(passed = false) }))
        assertEquals(ReviewChapterCommitResult.Failed, repository.commitChapter(reviewId, "immutable-results",
            drafts.map { it.copy(evidenceId = "different-evidence") }))
        assertTrue(repository.commitChapter(reviewId, "immutable-results", drafts.reversed()) is ReviewChapterCommitResult.AlreadyCommitted)
        assertEquals(1, count("review_chapter_results"))
        assertEquals(0, count("learning_obligations"))
    }

    @Test fun legacyCompletedResultWithoutRequestIdentityIsNotGuessedDuringUpgrade() = runBlocking {
        seedChapter(twoAbilities = false)
        val reviewId = currentReviewId()
        val repository = AndroidReviewChapterRepository(context, runtime) { today }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        val drafts = snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "isolated-evidence") }
        repository.commitChapter(reviewId, "legacy-result", drafts)
        val db = database.writableDatabase
        db.execSQL("alter table review_chapter_results rename to review_result_test_copy")
        db.execSQL("create table review_chapter_results(account_id text not null,review_chapter_id text not null,command_id text not null,local_date text not null,completed_focus_count integer not null,primary key(account_id,review_chapter_id),unique(account_id,command_id))")
        db.execSQL("insert into review_chapter_results select account_id,review_chapter_id,command_id,local_date,completed_focus_count from review_result_test_copy")
        db.execSQL("drop table review_result_test_copy")
        db.execSQL("alter table review_queue_items drop column occurrence_count")
        db.execSQL("alter table target_selections drop column source_evidence")
        com.reallanguage.app.testing.removeKnowledgeReuseSchemaForLegacyFixture(db)
        db.version = 17
        database.close()
        database = FormationDatabase(context)
        assertEquals(26, database.readableDatabase.version)
        assertEquals(1, count("review_chapter_results"))
        assertEquals(1, count("chapters"))
        assertEquals(ReviewChapterCommitResult.Failed, repository.commitChapter(reviewId, "legacy-result", drafts))
    }

    @Test fun anotherCycleCannotResetTheDailySentenceLimitAtCommit() = runBlocking {
        seedChapter(twoAbilities = false)
        val reviewId = currentReviewId()
        database.writableDatabase.execSQL("insert into review_sessions values ('account','other-session','other-cycle',?,3)", arrayOf(today))
        val repository = AndroidReviewChapterRepository(context, runtime) { today }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        assertEquals(ReviewChapterCommitResult.Failed, repository.commitChapter(reviewId, "over-limit-other-cycle",
            snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "isolated-evidence") }))
        assertEquals(0, count("review_chapter_results"))
        assertEquals(1, count("learning_obligations"))
    }

    @Test fun anotherCycleCannotOfferMoreThanTodaysRemainingSentences() = runBlocking {
        seedChapter(twoAbilities = false)
        currentReviewId()
        database.writableDatabase.execSQL("insert into review_sessions values ('account','other-session','other-cycle',?,3)", arrayOf(today))
        val queue = AndroidTodayReviewRepository(context, runtime) { today }.loadTodayQueue(3) as ReviewQueueResult.Ready
        assertTrue(queue.chapters.isEmpty())
    }

    @Test fun midnightCannotSplitOneCommitAcrossTwoLearningDates() = runBlocking {
        seedChapter(twoAbilities = false)
        val reviewId = currentReviewId()
        var dateReads = 0
        val repository = AndroidReviewChapterRepository(context, runtime) { dateReads++; if (dateReads == 1) today else "2026-09-03" }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        assertTrue(repository.commitChapter(reviewId, "midnight", snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "isolated-evidence") }) is ReviewChapterCommitResult.Committed)
        assertEquals(1, dateReads)
        assertEquals(1, scalar("select count(*) from review_chapter_results r join review_sessions s on r.account_id=s.account_id and r.local_date=s.local_date"))
    }

    @Test fun missingCapabilityCannotDiscardItsObligationOrReportChapterSuccess() = runBlocking {
        seedChapter(twoAbilities = false)
        val reviewId = currentReviewId()
        val repository = AndroidReviewChapterRepository(context, runtime) { today }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        database.writableDatabase.delete("sentence_capabilities", "account_id=?", arrayOf("account"))
        assertEquals(ReviewChapterCommitResult.Failed, repository.commitChapter(reviewId, "missing-capability",
            snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "isolated-evidence") }))
        assertEquals(1, count("learning_obligations"))
        assertEquals(0, count("review_chapter_results"))
        assertEquals(0, count("review_sessions"))
    }

    @Test fun nextChapterMustFitRemainingDailyFocusQuota() = runBlocking {
        seedChapter(twoAbilities = false)
        val reviewId = currentReviewId()
        val db = database.writableDatabase
        val cycle = db.rawQuery("select review_cycle_id from review_cycles", null).use { it.moveToFirst(); it.getString(0) }
        db.execSQL("insert into review_sessions values ('account','earlier','other-cycle',?,1)", arrayOf(today))
        // Two pending focus sentences cannot fit the one remaining slot after this chapter.
        db.insertOrThrow("chapters", null, values("account_id" to "account", "flow_id" to "flow",
            "chapter_id" to "original-two", "chapter_order" to 1, "status" to "COMPLETED"))
        db.execSQL("insert into review_chapters values ('account',?,'next-two','original-two','DIARY','待回顾','READY')", arrayOf(cycle))
        repeat(2) { index ->
            db.insertOrThrow("fixed_sentences", null, values("account_id" to "account", "chapter_id" to "original-two",
                "unit_id" to "next-s-$index", "sentence_order" to index, "text_zh" to "另一句", "text_en" to "Next sentence $index.", "role" to "FOCUS"))
            db.insertOrThrow("sentence_capabilities", null, values("account_id" to "account", "sentence_id" to "next-s-$index",
                "capability_type" to "FULL_WRITING", "status" to "LEARNING"))
            db.insertOrThrow("learning_obligations", null, values("account_id" to "account", "obligation_id" to "next-o-$index",
                "chapter_id" to "original-two", "sentence_id" to "next-s-$index", "capability_type" to "FULL_WRITING", "target_id" to ""))
            db.execSQL("insert into review_queue_items(account_id,review_cycle_id,queue_item_id,obligation_id,original_chapter_id,sentence_id,priority_rank,entered_at) values ('account',?,? ,?,'original-two',?,1,?)",
                arrayOf(cycle, "next-q-$index", "next-o-$index", "next-s-$index", index + 10))
            db.execSQL("insert into review_chapter_items values ('account','next-two',?,?, 'FULL_WRITING')",
                arrayOf("next-q-$index", "next-s-$index"))
        }
        val repository = AndroidReviewChapterRepository(context, runtime) { today }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        val drafts = snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "isolated-evidence") }
        val result = repository.commitChapter(reviewId, "last-fitting", drafts) as ReviewChapterCommitResult.Committed
        assertEquals(null, result.nextReviewChapterId)
        val replay = repository.commitChapter(reviewId, "last-fitting", drafts) as ReviewChapterCommitResult.AlreadyCommitted
        assertEquals(null, replay.nextReviewChapterId)
    }

    @Test fun dailyThreeSentenceCeilingIsEnforcedInsideCommitTransaction() = runBlocking {
        seedChapter(twoAbilities = false)
        val reviewId = currentReviewId()
        val db = database.writableDatabase
        val cycleId = db.rawQuery("select review_cycle_id from review_cycles", null).use { it.moveToFirst(); it.getString(0) }
        db.insertOrThrow("review_sessions", null, ContentValues().apply {
            put("account_id", "account"); put("review_session_id", "existing")
            put("review_cycle_id", cycleId); put("local_date", today); put("completed_focus_count", 3)
        })
        val repository = AndroidReviewChapterRepository(context, runtime) { today }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter

        assertEquals(
            ReviewChapterCommitResult.Failed,
            repository.commitChapter(
                reviewId,
                "over-limit",
                snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "evidence") },
            ),
        )
        assertEquals(1, count("learning_obligations"))
        assertEquals(0, count("review_chapter_results"))
    }

    @Test fun reviewModuleLabelsUseRealIndependentCapabilityStates() = runBlocking {
        seedPatternReview(twoOccurrences = false)
        database.writableDatabase.apply {
            execSQL("update sentence_capabilities set status='PENDING'")
            execSQL("update pattern_items set writing_status='SELF_ASSESSED',speaking_status='VERIFIED',speaking_streak=1")
            insertOrThrow("learning_obligations", null, values("account_id" to "account", "chapter_id" to "chapter",
                "obligation_id" to "pattern-speaking", "sentence_id" to "focus", "capability_type" to "PATTERN_SPEAKING", "target_id" to "target-focus"))
        }
        val reviewId = currentReviewId()
        val snapshot = (AndroidReviewChapterRepository(context, runtime).loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        val states = snapshot.abilities.associate { it.module to it.status }
        assertEquals(com.reallanguage.app.feature.review.domain.ReviewAbilityStatus.NOT_STARTED,
            states[com.reallanguage.app.feature.review.domain.ReviewAbilityModule.COMPLETE_WRITING])
        assertEquals(com.reallanguage.app.feature.review.domain.ReviewAbilityStatus.TENTATIVE,
            states[com.reallanguage.app.feature.review.domain.ReviewAbilityModule.PATTERN_WRITING])
        assertEquals(com.reallanguage.app.feature.review.domain.ReviewAbilityStatus.MASTERED,
            states[com.reallanguage.app.feature.review.domain.ReviewAbilityModule.PATTERN_SPEAKING])
    }

    @Test fun verifiedPatternCountsOneAndTwoRemainRequiredWithoutRelabelingTheirFormalStatus() = runBlocking {
        seedPatternReview(twoOccurrences = false)
        database.writableDatabase.apply {
            execSQL("update pattern_items set writing_status='VERIFIED',writing_streak=1,speaking_status='VERIFIED',speaking_streak=2")
            insertOrThrow("learning_obligations", null, values("account_id" to "account", "chapter_id" to "chapter",
                "obligation_id" to "pattern-speaking", "sentence_id" to "focus", "capability_type" to "PATTERN_SPEAKING", "target_id" to "target-focus"))
        }
        val reviewId = currentReviewId()
        val snapshot = (AndroidReviewChapterRepository(context, runtime).loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        val patterns = snapshot.abilities.filter { it.targetId == "target-focus" }
        assertEquals(2, patterns.size)
        assertTrue(patterns.all { it.status == com.reallanguage.app.feature.review.domain.ReviewAbilityStatus.MASTERED })
        assertTrue(patterns.all { it.requiresReview })
        val ui = com.reallanguage.app.feature.review.presentation.ReviewChapterUiState(reviewId, "read-only", snapshot)
        assertEquals(3, ui.pendingAbilities.size)
        database.writableDatabase.execSQL("update pattern_items set writing_streak=3,speaking_streak=3")
        val afterThird = (AndroidReviewChapterRepository(context, runtime).loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        assertEquals(1, afterThird.abilities.size)
        assertTrue(afterThird.abilities.all { it.module == com.reallanguage.app.feature.review.domain.ReviewAbilityModule.COMPLETE_WRITING })
    }

    @Test fun failedIndependentReviewCreatesOnlyTheFailedAbilityWrongFlag() = runBlocking {
        seedChapter(twoAbilities = true)
        val reviewId = currentReviewId()
        val repository = AndroidReviewChapterRepository(context, runtime) { today }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        assertTrue(repository.commitChapter(reviewId, "one-failed",
            snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "e-${it.abilityId}",
                it.module != com.reallanguage.app.feature.review.domain.ReviewAbilityModule.COMPLETE_WRITING) }) is ReviewChapterCommitResult.Committed)
        assertEquals(1, count("correction_flags"))
        assertEquals(1, scalar("select count(*) from correction_flags where obligation_id='o-FULL_WRITING'"))
        assertEquals(1, count("learning_obligations"))
    }

    @Test fun supportedWritingCompletesReviewWithoutChangingAnyFormalWritingState() = runBlocking {
        seedPatternReview(twoOccurrences = false)
        database.writableDatabase.apply {
            execSQL("update sentence_capabilities set status='PENDING'")
            execSQL("update pattern_items set writing_status='VERIFIED',writing_streak=2,speaking_streak=1")
            insertOrThrow("target_selections", null, values("account_id" to "account", "chapter_id" to "chapter",
                "target_id" to "word-focus", "unit_id" to "focus", "type" to "WORD", "display" to "line",
                "explanation_zh" to "句子", "structure_parts" to "", "related_vocabulary" to ""))
            insertOrThrow("vocabulary_items", null, values("account_id" to "account", "item_id" to "word",
                "normalized_form" to "line", "semantic_sense_key" to "句子", "display" to "line",
                "meaning_zh" to "句子", "status" to "SELF_ASSESSED", "verified_success_count" to 4))
            insertOrThrow("learning_obligations", null, values("account_id" to "account", "chapter_id" to "chapter",
                "obligation_id" to "meaning-focus", "sentence_id" to "focus", "capability_type" to "MEANING_WRITING",
                "target_id" to "word-focus"))
            insertOrThrow("correction_flags", null, values("account_id" to "account", "obligation_id" to "pattern-focus"))
        }
        val reviewId = currentReviewId()
        val repository = AndroidReviewChapterRepository(context, runtime) { today }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        assertEquals(3, snapshot.abilities.size)
        val results = snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "e-${it.abilityId}",
            false, ReviewResultQualification.SUPPORTED) }

        assertTrue(repository.commitChapter(reviewId, "supported-writing", results) is ReviewChapterCommitResult.Committed)
        assertEquals(1, scalar("select count(*) from sentence_capabilities where status='PENDING'"))
        assertEquals(1, scalar("select count(*) from vocabulary_items where status='SELF_ASSESSED' and verified_success_count=4"))
        assertEquals(1, scalar("select count(*) from pattern_items where writing_status='VERIFIED' and writing_streak=2 and speaking_status='SELF_ASSESSED' and speaking_streak=1 and active_validation=1"))
        assertEquals(3, count("learning_obligations"))
        assertEquals(1, count("correction_flags"))
        assertEquals(1, scalar("select count(*) from correction_flags where obligation_id='pattern-focus'"))
        assertEquals(1, scalar("select completed_focus_count from review_sessions"))
        assertEquals(1, scalar("select count(*) from review_chapters where status='COMPLETED'"))
        assertTrue(repository.commitChapter(reviewId, "supported-writing", results) is ReviewChapterCommitResult.AlreadyCommitted)
        assertEquals(1, count("review_chapter_results"))
        assertEquals(1, scalar("select completed_focus_count from review_sessions"))
        assertEquals(2, scalar("select writing_streak from pattern_items"))
    }

    @Test fun supportedQualificationCannotReplayAsAnIndependentFailure() = runBlocking {
        seedChapter(twoAbilities = false)
        val reviewId = currentReviewId()
        val repository = AndroidReviewChapterRepository(context, runtime) { today }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        val supported = snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "same-evidence",
            false, ReviewResultQualification.SUPPORTED) }
        assertTrue(repository.commitChapter(reviewId, "qualification-is-identity", supported) is ReviewChapterCommitResult.Committed)
        assertEquals(ReviewChapterCommitResult.Failed, repository.commitChapter(reviewId, "qualification-is-identity",
            supported.map { it.copy(qualification = ReviewResultQualification.INDEPENDENT) }))
        assertEquals(0, count("correction_flags"))
        assertEquals(1, count("review_chapter_results"))
    }

    @Test fun supportedQualificationCannotBypassSpeakingOrClaimIndependentSuccess() = runBlocking {
        seedChapter(twoAbilities = true)
        val reviewId = currentReviewId()
        val repository = AndroidReviewChapterRepository(context, runtime) { today }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        val unsupportedModule = snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "e-${it.abilityId}",
            false, ReviewResultQualification.SUPPORTED) }
        assertEquals(ReviewChapterCommitResult.Failed, repository.commitChapter(reviewId, "supported-speaking", unsupportedModule))
        val invalidSuccess = snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "e-${it.abilityId}", true,
            if (it.module == com.reallanguage.app.feature.review.domain.ReviewAbilityModule.COMPLETE_WRITING)
                ReviewResultQualification.SUPPORTED else ReviewResultQualification.INDEPENDENT) }
        assertEquals(ReviewChapterCommitResult.Failed, repository.commitChapter(reviewId, "supported-success", invalidSuccess))
        assertEquals(2, count("learning_obligations"))
        assertEquals(0, count("correction_flags"))
        assertEquals(0, count("review_chapter_results"))
        assertEquals(0, count("review_sessions"))
    }

    @Test fun supportedOccurrenceDoesNotResetAnotherIndependentPatternSuccess() = runBlocking {
        seedPatternReview(twoOccurrences = true)
        database.writableDatabase.execSQL("update pattern_items set writing_status='VERIFIED',writing_streak=1")
        val reviewId = currentReviewId()
        val repository = AndroidReviewChapterRepository(context, runtime) { today }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        val results = snapshot.abilities.map {
            val supported = it.sentenceId == "second-focus"
            ReviewAbilityResultDraft(it.abilityId, "e-${it.abilityId}", !supported,
                if (supported) ReviewResultQualification.SUPPORTED else ReviewResultQualification.INDEPENDENT)
        }
        assertTrue(repository.commitChapter(reviewId, "mixed-qualified-pattern", results) is ReviewChapterCommitResult.Committed)
        assertEquals(2, scalar("select writing_streak from pattern_items"))
        assertEquals(1, scalar("select count(*) from pattern_items where writing_status='VERIFIED'"))
        assertEquals(2, count("learning_obligations"))
        assertEquals(0, count("correction_flags"))
        assertEquals(2, scalar("select completed_focus_count from review_sessions"))
    }

    @Test fun successfulPatternReviewRemovesOldWrongFlagButKeepsRemainingPracticeObligation() = runBlocking {
        seedPatternReview(twoOccurrences = false)
        database.writableDatabase.insertOrThrow("correction_flags", null,
            values("account_id" to "account", "obligation_id" to "pattern-focus"))
        val reviewId = currentReviewId()
        val repository = AndroidReviewChapterRepository(context, runtime) { today }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        assertTrue(repository.commitChapter(reviewId, "passed-not-wrong",
            snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "e-${it.abilityId}") }) is ReviewChapterCommitResult.Committed)
        assertEquals(0, count("correction_flags"))
        assertEquals(1, count("learning_obligations"))
    }

    @Test fun repeatedPatternInOneReviewChapterCountsOnceAndUpdatesItsActualResult() = runBlocking {
        seedPatternReview(twoOccurrences = true)
        val reviewId = currentReviewId()
        val repository = AndroidReviewChapterRepository(context, runtime) { today }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        assertTrue(repository.commitChapter(reviewId, "pattern-review",
            snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "e-${it.abilityId}") }) is ReviewChapterCommitResult.Committed)
        assertEquals(1, scalar("select writing_streak from pattern_items"))
        assertEquals(1, scalar("select count(*) from pattern_items where writing_status='VERIFIED'"))
        assertEquals(2, count("learning_obligations"))
    }

    @Test fun firstIndependentPatternSuccessIsVerifiedBeforeThreeSuccessExitThreshold() = runBlocking {
        seedPatternReview(twoOccurrences = false)
        val reviewId = currentReviewId()
        val repository = AndroidReviewChapterRepository(context, runtime) { today }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        assertTrue(repository.commitChapter(reviewId, "first-pattern-success",
            snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "e-${it.abilityId}") }) is ReviewChapterCommitResult.Committed)
        assertEquals(1, scalar("select writing_streak from pattern_items"))
        assertEquals(1, scalar("select count(*) from pattern_items where writing_status='VERIFIED'"))
        assertEquals(1, count("learning_obligations"))
    }

    @Test fun writingThirdSuccessDoesNotDeactivateUnfinishedSpeaking() = runBlocking {
        seedPatternReview(twoOccurrences = false)
        database.writableDatabase.execSQL("update pattern_items set writing_streak=2,speaking_streak=1")
        val reviewId = currentReviewId()
        val repository = AndroidReviewChapterRepository(context, runtime) { today }
        val snapshot = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        assertTrue(repository.commitChapter(reviewId, "third-writing",
            snapshot.abilities.map { ReviewAbilityResultDraft(it.abilityId, "e-${it.abilityId}") }) is ReviewChapterCommitResult.Committed)
        assertEquals(1, scalar("select active_validation from pattern_items"))
    }

    private fun seedPatternReview(twoOccurrences: Boolean) {
        seedChapter(twoAbilities = false)
        val db = database.writableDatabase
        db.insertOrThrow("pattern_items", null, values("account_id" to "account", "item_id" to "pattern",
            "semantic_pattern_key" to "used to|过去习惯", "structure_en" to "used to", "meaning_zh" to "过去习惯",
            "writing_status" to "LEARNING", "speaking_status" to "SELF_ASSESSED",
            "writing_streak" to 0, "speaking_streak" to 0, "active_validation" to 1))
        val sentences = if (twoOccurrences) listOf("focus", "second-focus") else listOf("focus")
        if (twoOccurrences) db.insertOrThrow("fixed_sentences", null, values("account_id" to "account", "chapter_id" to "chapter",
            "unit_id" to "second-focus", "sentence_order" to 2, "text_zh" to "另一重点句", "text_en" to "I used to walk.", "role" to "FOCUS"))
        sentences.forEach { sentence ->
            db.insertOrThrow("target_selections", null, values("account_id" to "account", "chapter_id" to "chapter",
                "target_id" to "target-$sentence", "unit_id" to sentence, "type" to "PATTERN", "display" to "used to",
                "explanation_zh" to "过去习惯", "structure_parts" to "", "related_vocabulary" to ""))
            db.insertOrThrow("learning_obligations", null, values("account_id" to "account", "chapter_id" to "chapter",
                "obligation_id" to "pattern-$sentence", "sentence_id" to sentence,
                "capability_type" to "PATTERN_WRITING", "target_id" to "target-$sentence"))
        }
    }

    private suspend fun currentReviewId(): String {
        val queue = AndroidTodayReviewRepository(context, runtime) { today }.loadTodayQueue(3) as ReviewQueueResult.Ready
        return queue.chapters.single().reviewChapterId
    }

    private fun seedChapter(twoAbilities: Boolean) {
        val db = database.writableDatabase
        db.insertOrThrow("formation_flows", null, values(
            "account_id" to "account", "flow_id" to "flow", "status" to "FIXED",
            "source_type" to "DIARY", "source_title" to "日记场景",
        ))
        db.insertOrThrow("chapters", null, values(
            "account_id" to "account", "flow_id" to "flow", "chapter_id" to "chapter",
            "chapter_order" to 0, "status" to "COMPLETED",
        ))
        db.insertOrThrow("fixed_sentences", null, values(
            "account_id" to "account", "chapter_id" to "chapter", "unit_id" to "context",
            "sentence_order" to 0, "text_zh" to "上下文", "text_en" to "Context line.", "role" to "CONTEXT",
        ))
        db.insertOrThrow("fixed_sentences", null, values(
            "account_id" to "account", "chapter_id" to "chapter", "unit_id" to "focus",
            "sentence_order" to 1, "text_zh" to "重点句", "text_en" to "Focus line.", "role" to "FOCUS",
        ))
        seedAbility("FULL_WRITING")
        if (twoAbilities) seedAbility("FULL_SPEAKING")
    }

    private fun seedAbility(type: String) {
        val db = database.writableDatabase
        db.insertOrThrow("sentence_capabilities", null, values(
            "account_id" to "account", "sentence_id" to "focus", "capability_type" to type, "status" to "LEARNING",
        ))
        db.insertOrThrow("learning_obligations", null, values(
            "account_id" to "account", "obligation_id" to "o-$type", "chapter_id" to "chapter",
            "sentence_id" to "focus", "capability_type" to type, "target_id" to "",
        ))
    }

    private fun seedDay(date: String) {
        database.writableDatabase.insertOrThrow("normal_learning_days", null, values("account_id" to "account", "local_date" to date))
    }

    private fun count(table: String) = scalar("select count(*) from $table")
    private fun scalar(sql: String) = database.readableDatabase.rawQuery(sql, null).use { it.moveToFirst(); it.getInt(0) }
    private fun values(vararg pairs: Pair<String, Any>) = ContentValues().apply {
        pairs.forEach { (key, value) -> when (value) {
            is Int -> put(key, value)
            else -> put(key, value.toString())
        } }
    }
}
