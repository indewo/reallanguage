package com.reallanguage.app.feature.learning.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.decodeKnowledgeRefs
import com.reallanguage.app.feature.formation.data.readKnownCandidate
import com.reallanguage.app.feature.formation.data.decodeRelatedVocabularySnapshot
import com.reallanguage.app.feature.formation.domain.KnownKnowledgeType
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.validateRelatedVocabularyGroup
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.isVocabularyBookEligible
import com.reallanguage.app.feature.learning.domain.ChapterArchiveRepository
import com.reallanguage.app.feature.learning.domain.ChapterArchiveResult
import com.reallanguage.app.feature.learning.domain.ChapterArchiveSummary
import com.reallanguage.app.feature.learning.domain.ChapterResultCategory
import com.reallanguage.app.feature.learning.domain.ChapterResultSummaryItem
import com.reallanguage.app.feature.learning.domain.ChapterReinforcementItem
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidChapterArchiveRepository(
    context: Context,
    private val runtime: SessionRuntime,
    private val localDate: () -> String = { LocalDate.now().toString() },
) : ChapterArchiveRepository {
    private val applicationContext = context.applicationContext
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId &&
        runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    override suspend fun archive(chapterId: String, commandId: String): ChapterArchiveResult = withContext(Dispatchers.IO) {
        val accountId = ownerAccountId ?: return@withContext ChapterArchiveResult.Failed
        if (!ownerIsActive() || chapterId.isBlank() || commandId.isBlank()) return@withContext ChapterArchiveResult.Failed
        try {
            val result = FormationDatabase(applicationContext).use { it.writableDatabase.archive(accountId, chapterId, commandId) }
            if (ownerIsActive()) result else ChapterArchiveResult.Failed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ChapterArchiveResult.Failed }
    }

    private fun SQLiteDatabase.archive(
        accountId: String,
        chapterId: String,
        commandId: String,
    ): ChapterArchiveResult {
        beginTransaction()
        return try {
            check(ownerIsActive())
            readArchive(accountId, chapterId)?.let {
                check(ownerIsActive())
                setTransactionSuccessful()
                return ChapterArchiveResult.AlreadyArchived(it)
            }
            val chapter = readChapter(accountId, chapterId) ?: return ChapterArchiveResult.Failed
            if (chapter.status == "COMPLETED") return ChapterArchiveResult.Failed
            val focusSentenceIds = readFocusSentenceIds(accountId, chapterId)
            if (focusSentenceIds.isEmpty()) {
                return ChapterArchiveResult.Failed
            }

            val removedTargets = removedFixedPatternTargets(accountId, chapterId)
            // Old accepted sub-results remain stored, but cannot revive an explicitly
            // removed pattern. A new batch may legitimately omit these exact targets.
            val storedWriting = readWritingDrafts(accountId, chapterId)
            val writing = storedWriting.filterKeys { key ->
                key.abilityType != "PATTERN_WRITING" || RemovedFixedPatternTarget(key.sentenceId, key.targetId) !in removedTargets
            }
            val speaking = readBooleanDrafts("full_speaking_result_drafts", accountId, chapterId)
            val scene = readBooleanDrafts("scene_response_result_drafts", accountId, chapterId)
            val fixedTargets = readTargets(accountId, chapterId)
            val targets = fixedTargets.filter { RemovedFixedPatternTarget(it.sentenceId, it.targetId) !in removedTargets }
            val selfAssessed = rawQuery(
                "select sentence_id,capability_type,target_id from learning_self_assessments where account_id=? and chapter_id=?",
                arrayOf(accountId, chapterId),
            ).use { cursor -> buildSet {
                while (cursor.moveToNext()) {
                    val key = DraftKey(cursor.getString(0), cursor.getString(1), cursor.getString(2))
                    val target = fixedTargets.singleOrNull { it.targetId == key.targetId && it.sentenceId == key.sentenceId }
                    check(target != null)
                    check(if (target.type == "PATTERN") key.abilityType in setOf("PATTERN_WRITING", "PATTERN_SPEAKING")
                        else target.type in setOf("WORD", "FIXED_EXPRESSION") && key.abilityType == "MEANING_WRITING")
                    if (RemovedFixedPatternTarget(key.sentenceId, key.targetId) !in removedTargets) add(key)
                }
            } }
            val expectedWriting = buildSet {
                focusSentenceIds.forEach { add(DraftKey(it, "WHOLE_WRITING", "")) }
                targets.forEach { target ->
                    check(target.sentenceId in focusSentenceIds)
                    val ability = when (target.type) {
                        "WORD", "FIXED_EXPRESSION" -> "MEANING_WRITING"
                        "PATTERN" -> "PATTERN_WRITING"
                        else -> error("Invalid fixed target type")
                    }
                    add(DraftKey(target.sentenceId, ability, target.targetId))
                }
            }
            val qualification = rawQuery(
                "select qualification from writing_batch_completions where account_id=? and chapter_id=?",
                arrayOf(accountId, chapterId),
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            val validWriting = when (qualification) {
                "INDEPENDENT" -> writing.keys == expectedWriting
                "SUPPORTED" -> storedWriting.isEmpty()
                else -> false
            }
            if (!validWriting ||
                speaking.keys != focusSentenceIds.toSet() || scene.keys != focusSentenceIds.toSet()) {
                return ChapterArchiveResult.Failed
            }
            val outcome = ArchiveOutcome()
            val patternBatchResults = targets.filter { it.type == "PATTERN" }.groupBy {
                normalize(it.display) + "|" + normalize(it.explanationZh)
            }.mapValues { (_, samePattern) ->
                val results = samePattern.map { writing[DraftKey(it.sentenceId, "PATTERN_WRITING", it.targetId)] }
                when {
                    results.any { it == false } -> false
                    results.all { it == true } -> true
                    else -> null
                }
            }
            val settledPatterns = mutableSetOf<String>()

            focusSentenceIds.forEach { sentenceId ->
                settleSentenceCapability(accountId, chapterId, sentenceId, "FULL_WRITING", writing[DraftKey(sentenceId, "WHOLE_WRITING", "")], outcome)
                settleSentenceCapability(accountId, chapterId, sentenceId, "FULL_SPEAKING", speaking[sentenceId], outcome)
                settleSentenceCapability(accountId, chapterId, sentenceId, "SCENE_RESPONSE", scene[sentenceId], outcome)
            }
            targets.forEach { target ->
                when (target.type) {
                    "WORD", "FIXED_EXPRESSION" -> if (target.isVocabularyBookEligible()) {
                        settleVocabulary(
                            accountId, chapterId, target,
                            writing[DraftKey(target.sentenceId, "MEANING_WRITING", target.targetId)],
                            outcome,
                            DraftKey(target.sentenceId, "MEANING_WRITING", target.targetId) in selfAssessed,
                        )
                    }
                    "PATTERN" -> settlePattern(
                        accountId, chapterId, target,
                        writing[DraftKey(target.sentenceId, "PATTERN_WRITING", target.targetId)],
                        outcome,
                        patternBatchResults[normalize(target.display) + "|" + normalize(target.explanationZh)],
                        settledPatterns.add(normalize(target.display) + "|" + normalize(target.explanationZh)),
                        targets.any { same -> same.type == "PATTERN" && normalize(same.display) == normalize(target.display) &&
                            normalize(same.explanationZh) == normalize(target.explanationZh) &&
                            DraftKey(same.sentenceId, "PATTERN_WRITING", same.targetId) in selfAssessed },
                        targets.any { same -> same.type == "PATTERN" && normalize(same.display) == normalize(target.display) &&
                            normalize(same.explanationZh) == normalize(target.explanationZh) &&
                            DraftKey(same.sentenceId, "PATTERN_SPEAKING", same.targetId) in selfAssessed },
                    )
                }
            }

            recordReusedKnowledgeOccurrences(accountId, chapterId)
            val summary = buildSummary(chapterId, focusSentenceIds.size, targets, outcome, hasNextChapter(accountId, chapterId, chapter)).copy(
                chapterNumber = chapter.order + 1,
                reinforcementItems = readReinforcementItems(accountId, chapterId),
            ).let { summary ->
                val corrections = scalar("select count(*) from learning_steps p join fixed_sentences s on s.account_id=p.account_id and s.chapter_id=p.chapter_id and s.unit_id=p.sentence_id where p.account_id=? and p.chapter_id=? and p.step='READ_WRITE_CORRECTION' and p.status='COMPLETED' and s.role='FOCUS'", accountId, chapterId)
                if (corrections == 0) summary else summary.copy(items = summary.items +
                    ChapterResultSummaryItem(ChapterResultCategory.CORRECTION, "订正", corrections, corrections))
            }
            val completedDate = localDate()
            insertOrThrow("chapter_archives", null, ContentValues().apply {
                put("account_id", accountId)
                put("chapter_id", chapterId)
                put("command_id", commandId)
                put("summary", encodeSummary(summary))
                put("completed_local_date", completedDate)
            })
            update("chapters", ContentValues().apply { put("status", "COMPLETED") }, "account_id=? and chapter_id=?", arrayOf(accountId, chapterId))
            val sourceComplete = scalar("select count(*) from chapters where account_id=? and flow_id=? and status!='COMPLETED'", accountId, chapter.flowId) == 0
            val reviewDay = scalar("select count(*) from review_sessions where account_id=? and local_date=?", accountId, completedDate) > 0
            val abandonedPart = scalar("select count(*) from chapter_abandon_commands where account_id=? and source_id=?", accountId, chapter.flowId) > 0
            if (sourceComplete && !abandonedPart && !reviewDay) {
                insertWithOnConflict("normal_learning_days", null, ContentValues().apply {
                    put("account_id", accountId)
                    put("local_date", completedDate)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            clearWorkingState(accountId, chapterId)
            check(ownerIsActive())
            setTransactionSuccessful()
            ChapterArchiveResult.Archived(summary)
        } finally {
            endTransaction()
        }
    }

    private fun SQLiteDatabase.settleSentenceCapability(
        accountId: String,
        chapterId: String,
        sentenceId: String,
        capability: String,
        passed: Boolean?,
        outcome: ArchiveOutcome,
    ) {
        val verified = passed == true
        insertOrThrow("sentence_capabilities", null, ContentValues().apply {
            put("account_id", accountId); put("sentence_id", sentenceId)
            put("capability_type", capability); put("status", resultStatus(passed))
        })
        outcome.sentenceTotal++
        if (verified) outcome.sentenceCompleted++ else addObligation(accountId, chapterId, sentenceId, capability, "", passed == false, outcome)
    }

    private fun SQLiteDatabase.settleVocabulary(
        accountId: String,
        chapterId: String,
        target: TargetRow,
        passed: Boolean?,
        outcome: ArchiveOutcome,
        selfAssessed: Boolean,
    ) {
        val normalizedForm = normalize(target.display)
        val senseKey = normalize(target.explanationZh)
        val itemId = target.knownKnowledgeRef?.let { knownTargetItemId(accountId, target, KnownKnowledgeType.VOCABULARY) } ?: rawQuery(
            "select item_id from vocabulary_items where account_id=? and normalized_form=? and semantic_sense_key=?",
            arrayOf(accountId, normalizedForm, senseKey),
        ).use { if (it.moveToFirst()) it.getString(0) else stableId("vocabulary", accountId, normalizedForm, senseKey) }
        insertWithOnConflict("vocabulary_items", null, ContentValues().apply {
            put("account_id", accountId); put("item_id", itemId); put("normalized_form", normalizedForm)
            put("semantic_sense_key", senseKey); put("display", target.display); put("meaning_zh", target.explanationZh)
            put("status", resultStatus(passed))
            put("verified_success_count", 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (passed != null) {
            execSQL(
                "update vocabulary_items set status=?, verified_success_count=verified_success_count+? where account_id=? and item_id=?",
                arrayOf<Any>(if (passed) "VERIFIED" else "LEARNING", if (passed) 1 else 0, accountId, itemId),
            )
        } else if (selfAssessed) {
            execSQL("update vocabulary_items set status='SELF_ASSESSED' where account_id=? and item_id=?", arrayOf(accountId, itemId))
        }
        insertWithOnConflict("vocabulary_occurrences", null, ContentValues().apply {
            put("account_id", accountId); put("item_id", itemId); put("chapter_id", chapterId)
            put("sentence_id", target.sentenceId); put("is_focus", 1)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        target.learningScopes.forEach { scope ->
            insertWithOnConflict("vocabulary_wordlists", null, ContentValues().apply {
                put("account_id", accountId); put("item_id", itemId); put("wordlist", scope)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
        val hasRepresentative = scalar(
            "select count(*) from vocabulary_items where account_id=? and item_id=? and representative_scene is not null",
            accountId,
            itemId,
        ) > 0
        if (!hasRepresentative) {
            readTemporaryVocabularyDetails(accountId, target.targetId)?.let { details ->
                execSQL(
                    "update vocabulary_items set phonetic=?,pronunciation_audio_id=?,representative_scene=? where account_id=? and item_id=? and representative_scene is null",
                    arrayOf(details.phonetic, details.pronunciationText, details.representativeScene, accountId, itemId),
                )
                details.examples.forEachIndexed { index, example ->
                    insertWithOnConflict("vocabulary_examples", null, ContentValues().apply {
                        put("account_id", accountId); put("item_id", itemId); put("example_order", index)
                        put("text_en", example.textEn); put("text_zh", example.textZh); put("audio_id", example.pronunciationText)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
        }
        outcome.meaningTotal++
        if (passed == true) outcome.meaningCompleted++ else addObligation(accountId, chapterId, target.sentenceId, "MEANING_WRITING", target.targetId, passed == false, outcome)
    }

    private fun SQLiteDatabase.settlePattern(
        accountId: String,
        chapterId: String,
        target: TargetRow,
        passed: Boolean?,
        outcome: ArchiveOutcome,
        batchPassed: Boolean?,
        updateBatchState: Boolean,
        selfAssessedWriting: Boolean,
        selfAssessedSpeaking: Boolean,
    ) {
        val patternKey = normalize(target.display) + "|" + normalize(target.explanationZh)
        val itemId = target.knownKnowledgeRef?.let { knownTargetItemId(accountId, target, KnownKnowledgeType.PATTERN) } ?: rawQuery(
            "select item_id from pattern_items where account_id=? and semantic_pattern_key=?",
            arrayOf(accountId, patternKey),
        ).use { if (it.moveToFirst()) it.getString(0) else UUID.randomUUID().toString() }
        insertWithOnConflict("pattern_items", null, ContentValues().apply {
            put("account_id", accountId); put("item_id", itemId); put("semantic_pattern_key", patternKey)
            put("structure_en", target.display); put("meaning_zh", target.explanationZh)
            put("writing_status", resultStatus(batchPassed))
            put("speaking_status", "PENDING"); put("writing_streak", 0)
            put("speaking_streak", 0); put("active_validation", 1)
            // Only the first formal representative owns this explanation. Conflict-ignore
            // deliberately leaves existing and legacy-null representatives unchanged.
            put("related_vocabulary_snapshot", target.relatedVocabularySnapshot)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (updateBatchState && batchPassed != null) {
            execSQL(
                "update pattern_items set writing_status=?, writing_streak=case when ?=1 then min(writing_streak+1,3) else 0 end where account_id=? and item_id=?",
                arrayOf<Any>(if (batchPassed) "VERIFIED" else "LEARNING", if (batchPassed) 1 else 0, accountId, itemId),
            )
        }
        if (updateBatchState && batchPassed == null && selfAssessedWriting) {
            execSQL("update pattern_items set writing_status='SELF_ASSESSED',active_validation=1 where account_id=? and item_id=?", arrayOf(accountId, itemId))
        }
        if (updateBatchState && selfAssessedSpeaking) {
            execSQL("update pattern_items set speaking_status='SELF_ASSESSED',active_validation=1 where account_id=? and item_id=?", arrayOf(accountId, itemId))
        }
        execSQL("update pattern_items set active_validation=case when writing_status='VERIFIED' and writing_streak>=3 and speaking_status='VERIFIED' and speaking_streak>=3 then 0 else 1 end where account_id=? and item_id=?", arrayOf(accountId, itemId))
        insertWithOnConflict("pattern_occurrences", null, ContentValues().apply {
            put("account_id", accountId); put("item_id", itemId); put("chapter_id", chapterId)
            put("sentence_id", target.sentenceId); put("is_focus", 1)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        val hasRepresentative = scalar(
            "select count(*) from pattern_items where account_id=? and item_id=? and representative_scene is not null",
            accountId,
            itemId,
        ) > 0
        if (!hasRepresentative) {
            readTemporaryPatternDetails(accountId, target.targetId)?.let { details ->
                execSQL(
                    "update pattern_items set representative_scene=?,natural_revalidation=? where account_id=? and item_id=? and representative_scene is null",
                    arrayOf<Any>(details.representativeScene, if (details.naturalRevalidation) 1 else 0, accountId, itemId),
                )
                details.examples.forEachIndexed { index, example ->
                    insertWithOnConflict("pattern_examples", null, ContentValues().apply {
                        put("account_id", accountId); put("item_id", itemId); put("example_order", index)
                        put("text_en", example.textEn); put("text_zh", example.textZh); put("pronunciation_text", example.pronunciationText)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
        }
        outcome.patternTotal += 2
        if (passed == true) outcome.patternCompleted++ else addObligation(accountId, chapterId, target.sentenceId, "PATTERN_WRITING", target.targetId, passed == false, outcome)
        addObligation(accountId, chapterId, target.sentenceId, "PATTERN_SPEAKING", target.targetId, false, outcome)
    }

    private fun SQLiteDatabase.addObligation(
        accountId: String,
        chapterId: String,
        sentenceId: String,
        capability: String,
        targetId: String,
        wrong: Boolean,
        outcome: ArchiveOutcome,
    ) {
        val obligationId = stableId("obligation", accountId, chapterId, sentenceId, capability, targetId)
        insertOrThrow("learning_obligations", null, ContentValues().apply {
            put("account_id", accountId); put("obligation_id", obligationId); put("chapter_id", chapterId)
            put("sentence_id", sentenceId); put("capability_type", capability); put("target_id", targetId)
        })
        outcome.incomplete++
        if (wrong) {
            insertOrThrow("correction_flags", null, ContentValues().apply {
                put("account_id", accountId); put("obligation_id", obligationId)
            })
            outcome.wrong++
        }
    }

    private fun buildSummary(
        chapterId: String,
        focusCount: Int,
        targets: List<TargetRow>,
        outcome: ArchiveOutcome,
        hasNext: Boolean,
    ): ChapterArchiveSummary {
        val items = buildList {
            add(ChapterResultSummaryItem(ChapterResultCategory.SENTENCE, "整句能力", outcome.sentenceCompleted, outcome.sentenceTotal))
            if (targets.any { it.type == "WORD" || it.type == "FIXED_EXPRESSION" }) add(ChapterResultSummaryItem(ChapterResultCategory.MEANING, "词义与固定搭配", outcome.meaningCompleted, outcome.meaningTotal))
            if (targets.any { it.type == "PATTERN" }) add(ChapterResultSummaryItem(ChapterResultCategory.PATTERN, "句式能力", outcome.patternCompleted, outcome.patternTotal))
            if (outcome.wrong > 0) add(ChapterResultSummaryItem(ChapterResultCategory.WRONG, "错题", outcome.wrong, outcome.wrong))
            if (outcome.incomplete > 0) add(ChapterResultSummaryItem(ChapterResultCategory.INCOMPLETE, "未完成", outcome.incomplete, outcome.incomplete))
        }
        check(focusCount > 0)
        return ChapterArchiveSummary(chapterId, items, hasNext)
    }

    private fun SQLiteDatabase.readReinforcementItems(accountId: String, chapterId: String): List<ChapterReinforcementItem> = rawQuery(
        """select o.sentence_id,o.capability_type,o.target_id,
            case when o.target_id='' then s.text_en else t.display end,
            case when o.target_id='' then null else t.explanation_zh end
            from learning_obligations o
            join fixed_sentences s on s.account_id=o.account_id and s.chapter_id=o.chapter_id and s.unit_id=o.sentence_id
            left join target_selections t on t.account_id=o.account_id and t.chapter_id=o.chapter_id and t.unit_id=o.sentence_id and t.target_id=o.target_id
            where o.account_id=? and o.chapter_id=?
            order by s.sentence_order,o.capability_type,o.target_id""".trimIndent(),
        arrayOf(accountId, chapterId),
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) {
            check(!cursor.isNull(3) && cursor.getString(3).isNotBlank())
            add(ChapterReinforcementItem(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                if (cursor.isNull(4)) null else cursor.getString(4)))
        }
    } }

    private fun SQLiteDatabase.readArchive(accountId: String, chapterId: String): ChapterArchiveSummary? = rawQuery(
        "select summary from chapter_archives where account_id=? and chapter_id=?",
        arrayOf(accountId, chapterId),
    ).use { if (it.moveToFirst()) decodeSummary(it.getString(0)) else null }

    private fun SQLiteDatabase.readChapter(accountId: String, chapterId: String): ChapterRow? = rawQuery(
        "select flow_id,chapter_order,status from chapters where account_id=? and chapter_id=?",
        arrayOf(accountId, chapterId),
    ).use { if (it.moveToFirst()) ChapterRow(it.getString(0), it.getInt(1), it.getString(2)) else null }

    private fun SQLiteDatabase.readFocusSentenceIds(accountId: String, chapterId: String) = rawQuery(
        "select unit_id from fixed_sentences where account_id=? and chapter_id=? and role='FOCUS' order by sentence_order",
        arrayOf(accountId, chapterId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun SQLiteDatabase.readTargets(accountId: String, chapterId: String) = rawQuery(
        """select t.target_id,t.unit_id,t.type,t.display,t.explanation_zh,t.learning_scopes,t.known_knowledge_ref,
            t.related_vocabulary_snapshot,t.related_vocabulary,s.text_en
            from target_selections t
            left join fixed_sentences s on s.account_id=t.account_id and s.chapter_id=t.chapter_id and s.unit_id=t.unit_id
            where t.account_id=? and t.chapter_id=? order by t.target_id""".trimIndent(),
        arrayOf(accountId, chapterId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) {
        val rawSnapshot = if (cursor.isNull(7)) null else cursor.getString(7)
        if (rawSnapshot != null) {
            val snapshot = decodeRelatedVocabularySnapshot(rawSnapshot)
            check(!cursor.isNull(9) && snapshot.unitId == cursor.getString(1) && snapshot.sourceTextEn == cursor.getString(9))
            val legacy = cursor.getString(8).let { if (it.isEmpty()) emptyList() else it.split('\u001f') }
            check(validateRelatedVocabularyGroup(snapshot.unitId, LearningTargetType.valueOf(cursor.getString(2)),
                legacy, snapshot.items, snapshot.refs, snapshot.sourceTextEn))
        }
        add(TargetRow(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4),
            cursor.getString(5).split("\u001f").filter(String::isNotBlank).toSet(), cursor.getString(6), rawSnapshot,
            if (cursor.isNull(9)) "" else cursor.getString(9)))
    } } }

    private fun SQLiteDatabase.knownTargetItemId(account: String, target: TargetRow, expectedType: KnownKnowledgeType): String {
        val ref = requireNotNull(target.knownKnowledgeRef)
        val known = readKnownCandidate(account, ref) ?: error("Known target no longer available")
        check(known.type == expectedType && known.meaningZh.trim() == target.explanationZh.trim())
        if (expectedType == KnownKnowledgeType.PATTERN) check(known.display.trim() == target.display.trim())
        return ref.substringAfter(':')
    }

    private fun SQLiteDatabase.recordReusedKnowledgeOccurrences(account: String, chapter: String) {
        rawQuery("select unit_id,role,reused_knowledge_refs from fixed_sentences where account_id=? and chapter_id=? order by sentence_order", arrayOf(account, chapter)).use { c ->
            while(c.moveToNext()) {
                if(c.isNull(2)) continue // Historical rows have no explicit reuse evidence.
                val refs = decodeKnowledgeRefs(JSONArray(c.getString(2)))
                refs.forEach { ref ->
                    val item = readKnownCandidate(account, ref) ?: return@forEach // A removed item must never be resurrected.
                    val table = if(item.type == KnownKnowledgeType.PATTERN) "pattern_occurrences" else "vocabulary_occurrences"
                    insertWithOnConflict(table, null, ContentValues().apply {
                        put("account_id", account); put("item_id", ref.substringAfter(':')); put("chapter_id", chapter)
                        put("sentence_id", c.getString(0)); put("is_focus", if(c.getString(1) == "FOCUS") 1 else 0)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
        }
    }

    private fun SQLiteDatabase.readTemporaryVocabularyDetails(accountId: String, targetId: String): VocabularyDetailsRow? = rawQuery(
        "select phonetic,pronunciation_text,representative_scene,examples_json from target_vocabulary_details where account_id=? and target_id=?",
        arrayOf(accountId, targetId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val json = JSONArray(cursor.getString(3))
        if (json.length() != 3) return@use null
        VocabularyDetailsRow(
            cursor.getString(0), cursor.getString(1), cursor.getString(2),
            List(3) { index -> json.getJSONObject(index).let {
                VocabularyExampleRow(it.getString("textEn"), it.getString("textZh"), it.getString("pronunciationText"))
            } },
        )
    }

    private fun SQLiteDatabase.readTemporaryPatternDetails(accountId: String, targetId: String): PatternDetailsRow? = rawQuery(
        "select representative_scene,examples_json,natural_revalidation from target_pattern_details where account_id=? and target_id=?",
        arrayOf(accountId, targetId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val json = JSONArray(cursor.getString(1))
        if (json.length() != 3) return@use null
        PatternDetailsRow(
            cursor.getString(0), cursor.getInt(2) == 1,
            List(3) { index -> json.getJSONObject(index).let {
                VocabularyExampleRow(it.getString("textEn"), it.getString("textZh"), it.getString("pronunciationText"))
            } },
        )
    }

    private fun SQLiteDatabase.readWritingDrafts(accountId: String, chapterId: String) = rawQuery(
        "select sentence_id,ability_type,target_id,passed from writing_result_drafts where account_id=? and chapter_id=?",
        arrayOf(accountId, chapterId),
    ).use { cursor -> buildMap { while (cursor.moveToNext()) put(DraftKey(cursor.getString(0), cursor.getString(1), cursor.getString(2)), cursor.getInt(3) == 1) } }

    private fun SQLiteDatabase.readBooleanDrafts(table: String, accountId: String, chapterId: String) = rawQuery(
        "select sentence_id,passed from $table where account_id=? and chapter_id=?",
        arrayOf(accountId, chapterId),
    ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1) == 1) } }

    private fun SQLiteDatabase.hasNextChapter(accountId: String, chapterId: String, chapter: ChapterRow) = scalar(
        "select count(*) from chapters where account_id=? and flow_id=? and chapter_id!=? and status='NOT_STARTED'",
        accountId, chapter.flowId, chapterId,
    ) > 0

    private fun SQLiteDatabase.scalar(sql: String, vararg args: String) = rawQuery(sql, args).use { it.moveToFirst(); it.getInt(0) }

    private fun SQLiteDatabase.clearWorkingState(accountId: String, chapterId: String) {
        listOf(
            "learning_steps", "writing_result_drafts", "writing_batch_completions",
            "full_speaking_result_drafts", "scene_response_result_drafts",
            "learning_checkpoints", "learning_explanation_cache", "learning_module_progress", "learning_self_assessments",
            "target_vocabulary_details",
            "target_pattern_details",
        ).forEach { delete(it, "account_id=? and chapter_id=?", arrayOf(accountId, chapterId)) }
    }

    private fun encodeSummary(summary: ChapterArchiveSummary) = JSONObject()
        .put("chapterId", summary.chapterId)
        .put("hasNextChapter", summary.hasNextChapter)
        .put("chapterNumber", summary.chapterNumber)
        .put("reinforcementItems", JSONArray(summary.reinforcementItems.map { item -> JSONObject()
            .put("sentenceId", item.sentenceId).put("capabilityType", item.capabilityType)
            .put("targetId", item.targetId).put("display", item.display)
            .apply { item.explanationZh?.let { put("explanationZh", it) } }
        }))
        .put("items", JSONArray(summary.items.map { item -> JSONObject()
            .put("category", item.category.name).put("labelZh", item.labelZh)
            .put("completedCount", item.completedCount).put("totalCount", item.totalCount)
        })).toString()

    private fun decodeSummary(value: String) = JSONObject(value).let { json ->
        val items = json.getJSONArray("items")
        ChapterArchiveSummary(
            json.getString("chapterId"),
            List(items.length()) { index -> items.getJSONObject(index).let { item ->
                ChapterResultSummaryItem(ChapterResultCategory.valueOf(item.getString("category")), item.getString("labelZh"), item.getInt("completedCount"), item.getInt("totalCount"))
            } },
            json.getBoolean("hasNextChapter"),
            if (json.has("chapterNumber") && !json.isNull("chapterNumber")) json.getInt("chapterNumber") else null,
            json.optJSONArray("reinforcementItems")?.let { pending -> List(pending.length()) { index -> pending.getJSONObject(index).let { item ->
                val explanation = when (val value = item.opt("explanationZh")) {
                    null, JSONObject.NULL -> null
                    is String -> value
                    else -> error("Invalid archived explanation")
                }
                // A legacy snapshot remains unknown. Never look up current target data here.
                ChapterReinforcementItem(item.getString("sentenceId"), item.getString("capabilityType"),
                    item.getString("targetId"), item.getString("display"), explanation)
            } } } ?: emptyList(),
        )
    }

    private fun resultStatus(passed: Boolean?) = when (passed) { true -> "VERIFIED"; false -> "LEARNING"; null -> "PENDING" }
    private fun normalize(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    private fun stableId(vararg values: String) = UUID.nameUUIDFromBytes(values.joinToString("\u001f").toByteArray()).toString()

    private data class ChapterRow(val flowId: String, val order: Int, val status: String)
    private fun TargetRow.isVocabularyBookEligible(): Boolean = LearningTarget(
        targetId, sentenceId, display, explanationZh, LearningTargetType.valueOf(type),
    ).isVocabularyBookEligible(sourceTextEn)

    private data class TargetRow(val targetId: String, val sentenceId: String, val type: String, val display: String, val explanationZh: String, val learningScopes: Set<String>, val knownKnowledgeRef: String?, val relatedVocabularySnapshot: String?, val sourceTextEn: String)
    private data class VocabularyDetailsRow(val phonetic: String?, val pronunciationText: String, val representativeScene: String, val examples: List<VocabularyExampleRow>)
    private data class VocabularyExampleRow(val textEn: String, val textZh: String, val pronunciationText: String)
    private data class PatternDetailsRow(val representativeScene: String, val naturalRevalidation: Boolean, val examples: List<VocabularyExampleRow>)
    private data class DraftKey(val sentenceId: String, val abilityType: String, val targetId: String)
    private class ArchiveOutcome(var sentenceCompleted: Int = 0, var sentenceTotal: Int = 0, var meaningCompleted: Int = 0, var meaningTotal: Int = 0, var patternCompleted: Int = 0, var patternTotal: Int = 0, var wrong: Int = 0, var incomplete: Int = 0)
}
