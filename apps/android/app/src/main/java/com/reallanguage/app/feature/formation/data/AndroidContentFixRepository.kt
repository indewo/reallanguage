package com.reallanguage.app.feature.formation.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.ContentFixRepository
import com.reallanguage.app.feature.formation.domain.ContentFixRequest
import com.reallanguage.app.feature.formation.domain.ContentFixResult
import com.reallanguage.app.feature.formation.domain.KnowledgeExampleGenerationRepository
import com.reallanguage.app.feature.formation.domain.KnowledgeExampleGenerationRequest
import com.reallanguage.app.feature.formation.domain.KnowledgeExampleGenerationResult
import com.reallanguage.app.feature.formation.domain.KnowledgeSourceSentence
import com.reallanguage.app.feature.formation.domain.KnowledgeTargetType
import com.reallanguage.app.feature.formation.domain.isValidForTarget
import com.reallanguage.app.feature.formation.domain.isValidKnowledgeRef
import com.reallanguage.app.feature.formation.domain.isValidFor
import com.reallanguage.app.feature.formation.domain.hasValidRelatedVocabularyItems
import com.reallanguage.app.feature.formation.domain.hasConsistentVocabularyExplanations
import com.reallanguage.app.feature.formation.domain.isVocabularyBookEligible
import com.reallanguage.app.feature.learning.domain.FixedChapterLoadResult
import com.reallanguage.app.feature.learning.domain.FixedChapterRepository
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ContentFixPlanValidator {
    fun validate(request: ContentFixRequest): Boolean {
        val sentences = request.chapters.flatMap { it.sentences }
        val focusIds = sentences.filter { it.isFocus }.mapTo(mutableSetOf()) { it.unitId }
        return request.formationFlowId.isNotBlank() && request.commandId.isNotBlank() &&
            request.chapters.isNotEmpty() && request.chapters.all { it.sentences.size in 1..4 } &&
            focusIds.size in 1..3 && sentences.map { it.unitId }.distinct().size == sentences.size &&
            sentences.all { sentence -> sentence.candidates.any { it.candidateId == request.selectedCandidateIds[sentence.unitId] } } &&
            sentences.all { sentence -> sentence.candidates.first { it.candidateId == request.selectedCandidateIds[sentence.unitId] }.reusedKnowledgeRefs.let { refs -> refs.size <= 16 && refs.distinct().size == refs.size && refs.all(::isValidKnowledgeRef) } } &&
            request.selectedTargetIds.all { selected -> request.targets.any { it.targetId == selected && it.unitId in focusIds } } &&
            request.targets.hasConsistentVocabularyExplanations() &&
            request.targets.all { target ->
                val sentence = sentences.singleOrNull { it.unitId == target.unitId && it.isFocus }
                val english = sentence?.candidates?.singleOrNull { it.candidateId == request.selectedCandidateIds[target.unitId] }?.textEn
                val sourceValid = target.sourceEvidence?.let { evidence ->
                    english != null && evidence.isValidForTarget(english, target.display, target.type)
                } ?: true
                sourceValid && english != null && target.isVocabularyBookEligible(english) &&
                    target.hasValidRelatedVocabularyItems(english)
            }
    }
}

class AndroidContentFixRepository(
    context: Context,
    private val runtime: SessionRuntime,
    private val validator: ContentFixPlanValidator = ContentFixPlanValidator(),
    private val knowledgeExamples: KnowledgeExampleGenerationRepository? = null,
    private val sourceCipher: FixedSourcePayloadCodec = AesGcmFixedSourcePayloadCodec(),
    exampleRecoveryKeys: FormationPayloadKeys = AndroidFormationPayloadKeys(),
    exampleRecoveryCommitter: FormationAtomicFileCommitter? = null,
) : ContentFixRepository {
    private val database = FormationDatabase(context.applicationContext)
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId && runtime.accessToken != null && runtime.accountEpoch == ownerAccountEpoch
    private val examplesRecovery = ownerAccountId?.takeIf { knowledgeExamples != null }?.let {
        ContentFixExamplesRecovery(context.applicationContext, it, ::ownerIsActive, exampleRecoveryKeys, exampleRecoveryCommitter)
    }
    override suspend fun fix(request: ContentFixRequest): ContentFixResult = withContext(Dispatchers.IO) {
        if (!ownerIsActive()) return@withContext ContentFixResult.Failed
        try { fixOwned(request) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ContentFixResult.Failed }
    }

    private suspend fun fixOwned(request: ContentFixRequest): ContentFixResult {
        val accountId = ownerAccountId ?: return ContentFixResult.Failed
        if (!validator.validate(request)) return ContentFixResult.Failed
        val source = request.sourceProjection?.takeIf { it.isValidFor(request) } ?: return ContentFixResult.Failed
        val sourceIdentity = FixedSourcePayloadIdentity(fixedSourceAccountHash(accountId), request.formationFlowId, source.sourceEntryId, source.sourceRevision)
        fun SQLiteDatabase.hasSameSource(): Boolean = readFixedSource(accountId, request.formationFlowId, sourceCipher) == source
        val prior = runCatching {
            database.readableDatabase.rawQuery(
                "select first_chapter_id from fix_commands where account_id=? and command_id=?",
                arrayOf(accountId, request.commandId),
            ).use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrElse { return ContentFixResult.Failed }
        if (!ownerIsActive()) return ContentFixResult.Failed
        if (prior != null) return if (database.readableDatabase.hasSameSource() &&
            database.readableDatabase.matchesFixedSelection(accountId, request, prior) && ownerIsActive()) {
            examplesRecovery?.complete(request)
            if (ownerIsActive()) ContentFixResult.Fixed(prior) else ContentFixResult.Failed
        } else ContentFixResult.Failed
        val flowExists = database.readableDatabase.rawQuery(
            "select 1 from formation_flows where account_id=? and flow_id=? union all select 1 from fixed_source_payloads where account_id=? and flow_id=? limit 1",
            arrayOf(accountId, request.formationFlowId, accountId, request.formationFlowId),
        ).use { it.moveToFirst() }
        if (flowExists || !ownerIsActive()) return ContentFixResult.Failed
        // Confirm a readable authenticated source before any paid examples or formal rows are created.
        val encryptedSource = sourceCipher.encrypt(sourceIdentity, source) ?: return ContentFixResult.Failed
        if (!ownerIsActive() || sourceCipher.decrypt(sourceIdentity, encryptedSource) != source || !ownerIsActive()) return ContentFixResult.Failed
        fun SQLiteDatabase.checkReferences() {
            request.chapters.flatMap { it.sentences }.forEach { sentence ->
                val selected = sentence.candidates.first { it.candidateId == request.selectedCandidateIds[sentence.unitId] }
                selected.reusedKnowledgeRefs.forEach { check(readKnownCandidate(accountId, it) != null) }
            }
            request.targets.filter { it.targetId in request.selectedTargetIds }.forEach { target ->
                target.knownKnowledgeRef?.let { ref ->
                    val sentence = request.chapters.flatMap { it.sentences }.single { it.unitId == target.unitId && it.isFocus }
                    val selected = sentence.candidates.single { it.candidateId == request.selectedCandidateIds[target.unitId] }
                    check(ref in selected.reusedKnowledgeRefs && target.sourceEvidence != null)
                    check(knownTargetMatches(readKnownCandidate(accountId, ref) ?: error("Known target unavailable"), target))
                }
            }
        }
        database.readableDatabase.checkReferences()
        val plans = request.targets.filter { it.targetId in request.selectedTargetIds && it.knownKnowledgeRef == null }.map { target ->
            val chapter = request.chapters.first { candidate -> candidate.sentences.any { it.unitId == target.unitId } }
            val sourceScene = chapter.sentences.map { sentence ->
                val selected = sentence.candidates.first { it.candidateId == request.selectedCandidateIds[sentence.unitId] }
                KnowledgeSourceSentence(selected.textEn, sentence.textZh)
            }
            KnowledgeExampleGenerationRequest(target.targetId,
                if (target.type.name == "PATTERN") KnowledgeTargetType.PATTERN else KnowledgeTargetType.VOCABULARY,
                target.display, target.explanationZh, sourceScene, "${request.commandId}:${target.targetId}")
        }
        val generatedDetails = if (knowledgeExamples == null || plans.isEmpty()) emptyMap() else
            examplesRecovery?.resolve(request, plans, knowledgeExamples) ?: run {
                // Another instance may have committed and consumed the cache after our first SQL check.
                // Only the complete, exact fixed transaction is an alternative proof; never regenerate.
                currentCoroutineContext().ensureActive()
                if (!ownerIsActive()) return ContentFixResult.Failed
                val committed = database.readableDatabase.rawQuery(
                    "select first_chapter_id from fix_commands where account_id=? and command_id=?",
                    arrayOf(accountId, request.commandId),
                ).use { if (it.moveToFirst()) it.getString(0) else null }
                return if (committed != null && database.readableDatabase.hasSameSource() &&
                    database.readableDatabase.matchesFixedSelection(accountId, request, committed) && ownerIsActive())
                    ContentFixResult.Fixed(committed) else ContentFixResult.Failed
            }
        currentCoroutineContext().ensureActive()
        check(ownerIsActive())
        fun persistentId(kind: String, id: String) = java.util.UUID.nameUUIDFromBytes(
            "${request.formationFlowId}:$kind:$id".toByteArray(Charsets.UTF_8),
        ).toString()
        val persisted = request.copy(
            chapters = request.chapters.map { chapter -> chapter.copy(
                chapterId = persistentId("chapter", chapter.chapterId),
                sentences = chapter.sentences.map { it.copy(unitId = persistentId("sentence", it.unitId)) },
            ) },
            selectedCandidateIds = request.selectedCandidateIds.mapKeys { persistentId("sentence", it.key) },
            targets = request.targets.map { it.copy(
                targetId = persistentId("target", it.targetId), unitId = persistentId("sentence", it.unitId),
                relatedVocabularyItems = it.relatedVocabularyItems?.map { item ->
                    item.copy(unitId = persistentId("sentence", item.unitId))
                },
            ) },
            selectedTargetIds = request.selectedTargetIds.mapTo(mutableSetOf()) { persistentId("target", it) },
        )
        val persistedDetails = generatedDetails.mapKeys { persistentId("target", it.key) }
        val confirmedRequest = request
        val result = runCatching { database.writableDatabase.inTransaction {
            currentCoroutineContext().ensureActive()
            check(ownerIsActive())
            val request = persisted
            val generatedDetails = persistedDetails
            checkReferences()
            rawQuery("select first_chapter_id from fix_commands where account_id=? and command_id=?", arrayOf(accountId, request.commandId)).use { cursor ->
                if (cursor.moveToFirst()) {
                    check(hasSameSource() && matchesFixedSelection(accountId, confirmedRequest, cursor.getString(0)) && ownerIsActive())
                    return@inTransaction cursor.getString(0)
                }
            }
            request.chapters.forEachIndexed { chapterOrder, chapter ->
                insertOrThrow("chapters", null, ContentValues().apply { put("account_id", accountId); put("flow_id", request.formationFlowId); put("chapter_id", chapter.chapterId); put("chapter_order", chapterOrder); put("status", "NOT_STARTED") })
                chapter.sentences.forEachIndexed { sentenceOrder, sentence ->
                    val selected = sentence.candidates.first { it.candidateId == request.selectedCandidateIds[sentence.unitId] }
                    insertOrThrow("fixed_sentences", null, ContentValues().apply { put("account_id", accountId); put("chapter_id", chapter.chapterId); put("unit_id", sentence.unitId); put("sentence_order", sentenceOrder); put("text_zh", sentence.textZh); put("text_en", selected.textEn); put("role", if (sentence.isFocus) "FOCUS" else "CONTEXT"); put("reused_knowledge_refs", JSONArray(selected.reusedKnowledgeRefs).toString()) })
                }
            }
            request.targets.filter { it.targetId in request.selectedTargetIds }.forEach { target ->
                val chapterId = request.chapters.first { chapter -> chapter.sentences.any { it.unitId == target.unitId } }.chapterId
                val sentence = request.chapters.flatMap { it.sentences }.single { it.unitId == target.unitId }
                val english = sentence.candidates.single { it.candidateId == request.selectedCandidateIds[target.unitId] }.textEn
                insertOrThrow("target_selections", null, ContentValues().apply { put("account_id", accountId); put("chapter_id", chapterId); put("target_id", target.targetId); put("unit_id", target.unitId); put("type", target.type.name); put("display", target.display); put("explanation_zh", target.explanationZh); put("structure_parts", target.structureParts.joinToString("\u001f")); put("related_vocabulary", target.relatedVocabulary.joinToString("\u001f")); put("learning_scopes", target.learningScopes.sorted().joinToString("\u001f")); put("source_evidence", target.sourceEvidence?.toJson()?.toString()); put("known_knowledge_ref", target.knownKnowledgeRef); put("related_vocabulary_snapshot", encodeRelatedVocabularySnapshot(target, english)) })
                generatedDetails[target.targetId]?.let { details ->
                    val detailTable = if (target.type.name == "PATTERN") "target_pattern_details" else "target_vocabulary_details"
                    insertOrThrow(detailTable, null, ContentValues().apply {
                        put("account_id", accountId); put("chapter_id", chapterId); put("target_id", target.targetId)
                        put("phonetic", details.phonetic); put("pronunciation_text", details.pronunciationText)
                        put("representative_scene", details.representativeScene)
                        put("examples_json", JSONArray(details.examples.map { example -> JSONObject()
                            .put("textEn", example.textEn).put("textZh", example.textZh)
                            .put("pronunciationText", example.pronunciationText)
                        }).toString())
                        if (target.type.name == "PATTERN") put("natural_revalidation", if (details.naturalRevalidation == true) 1 else 0)
                    })
                }
            }
            insertOrThrow("formation_flows", null, ContentValues().apply {
                put("account_id", accountId)
                put("flow_id", request.formationFlowId)
                put("status", "FIXED")
                put("source_type", request.sourceType)
                put("source_title", request.sourceTitle)
            })
            insertOrThrow("fixed_source_payloads", null, ContentValues().apply {
                put("account_id", accountId); put("flow_id", source.formationFlowId)
                put("source_entry_id", source.sourceEntryId); put("source_revision", source.sourceRevision)
                put("payload_version", 1); put("envelope", encryptedSource)
            })
            check(hasSameSource())
            val first = request.chapters.first().chapterId
            insertOrThrow("fix_commands", null, ContentValues().apply { put("account_id", accountId); put("command_id", request.commandId); put("first_chapter_id", first) })
            check(ownerIsActive())
            currentCoroutineContext().ensureActive()
            first
        } }.fold({ if (ownerIsActive()) ContentFixResult.Fixed(it) else ContentFixResult.Failed }, {
            if (it is CancellationException) throw it
            ContentFixResult.Failed
        })
        if (result is ContentFixResult.Fixed) {
            if (!database.readableDatabase.hasSameSource() ||
                !database.readableDatabase.matchesFixedSelection(accountId, confirmedRequest, result.firstChapterId) || !ownerIsActive())
                return ContentFixResult.Failed
            examplesRecovery?.complete(confirmedRequest)
        }
        return if (ownerIsActive()) result else ContentFixResult.Failed
    }
}

class AndroidFixedChapterRepository(context: Context, private val runtime: SessionRuntime) : FixedChapterRepository {
    private val applicationContext = context.applicationContext
    private val ownerAccountId = runtime.accountId
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId && runtime.accessToken != null
    override suspend fun load(chapterId: String): FixedChapterLoadResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!ownerIsActive()) return@withContext FixedChapterLoadResult.Failed
        try {
          FormationDatabase(applicationContext).use { helper ->
            val chapter = helper.readableDatabase.rawQuery(
                "select c.chapter_order,s.unit_id,s.text_en,s.text_zh,s.role from chapters c join fixed_sentences s on s.account_id=c.account_id and s.chapter_id=c.chapter_id where c.account_id=? and c.chapter_id=? order by s.sentence_order",
                arrayOf(ownerAccountId, chapterId),
            ).use { cursor ->
                check(cursor.moveToFirst())
                val number = cursor.getInt(0) + 1
                val sentences = buildList { do {
                    add(FixedLearningSentence(cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4) == "FOCUS"))
                } while (cursor.moveToNext()) }
                check(sentences.any { it.isFocus })
                FixedLearningChapter(chapterId, number, sentences)
            }
            check(ownerIsActive())
            FixedChapterLoadResult.Loaded(chapter)
          }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { FixedChapterLoadResult.Failed }
    }
}

private inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try { val result = block(); setTransactionSuccessful(); result } finally { endTransaction() }
}
internal class FormationDatabase(context: Context) : SQLiteOpenHelper(
    context,
    "real-language-learning.db",
    null,
    26,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("create table formation_flows(account_id text not null, flow_id text not null, status text not null check(status='FIXED'), source_type text not null check(source_type in ('DIARY','QUICK_RECORDING','AI_SCENE')), source_title text not null, primary key(account_id,flow_id))")
        db.execSQL("create table chapters(account_id text not null, flow_id text not null, chapter_id text not null, chapter_order integer not null, status text not null, primary key(account_id,chapter_id))")
        db.execSQL("create table fixed_sentences(account_id text not null, chapter_id text not null, unit_id text not null, sentence_order integer not null, text_zh text not null, text_en text not null, role text not null check(role in ('FOCUS','CONTEXT')), reused_knowledge_refs text, primary key(account_id,chapter_id,unit_id))")
        db.execSQL("create table target_selections(account_id text not null, chapter_id text not null, target_id text not null, unit_id text not null, type text not null, display text not null, explanation_zh text not null, structure_parts text not null, related_vocabulary text not null, learning_scopes text not null default '', source_evidence text, known_knowledge_ref text, related_vocabulary_snapshot text, primary key(account_id,target_id))")
        db.execSQL("create table fix_commands(account_id text not null, command_id text not null, first_chapter_id text not null, primary key(account_id,command_id))")
        createLearningSteps(db)
        createWritingDrafts(db)
        createSceneResponseDrafts(db)
        createLearningCheckpoints(db)
        createChapterArchives(db)
        createChapterAbandon(db)
        createReview(db)
        createExplanationCache(db)
        createModuleProgress(db)
        createSelfAssessments(db)
        createEnglishKnowledgeSnapshots(db)
        createFixedSourcePayloads(db)
        createSceneRecommendationCache(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 26) createSceneRecommendationCache(db)
        if (oldVersion < 24) createFixedSourcePayloads(db)
        if (oldVersion < 23) {
            db.execSQL("alter table fixed_sentences add column reused_knowledge_refs text")
            db.execSQL("alter table target_selections add column known_knowledge_ref text")
            createEnglishKnowledgeSnapshots(db)
        }
        if (oldVersion < 22) db.execSQL("alter table target_selections add column source_evidence text")
        if (oldVersion < 21) createSelfAssessments(db)
        if (oldVersion < 16) createModuleProgress(db)
        if (oldVersion < 2) createLearningSteps(db)
        if (oldVersion < 3) createWritingDrafts(db)
        if (oldVersion < 4) createSceneResponseDrafts(db)
        if (oldVersion < 5) createLearningCheckpoints(db)
        if (oldVersion < 6) createChapterArchives(db)
        if (oldVersion < 7) createChapterAbandon(db)
        if (oldVersion < 8) {
            db.execSQL("alter table formation_flows add column source_type text not null default 'DIARY'")
            db.execSQL("alter table formation_flows add column source_title text not null default '日记'")
            createReview(db)
        }
        if (oldVersion < 9) migratePatternSpeakingStatus(db)
        if (oldVersion < 10) createReviewSettlement(db)
        if (oldVersion < 11) {
            db.execSQL("alter table target_selections add column learning_scopes text not null default ''")
            createVocabularyKnowledge(db)
        }
        if (oldVersion < 12) {
            db.execSQL("alter table vocabulary_items add column phonetic text")
            db.execSQL("alter table vocabulary_items add column pronunciation_audio_id text")
            db.execSQL("alter table vocabulary_items add column representative_scene text")
            createVocabularyDetails(db)
        }
        if (oldVersion < 13) createTemporaryKnowledgeDetails(db)
        if (oldVersion < 14) {
            db.execSQL("alter table pattern_items add column representative_scene text")
            db.execSQL("alter table pattern_items add column natural_revalidation integer not null default 1 check(natural_revalidation in (0,1))")
            createPatternKnowledgeDetails(db)
        }
        if (oldVersion < 15) createExplanationCache(db)
        // Versions before 7 create the current table above. Existing tables need only this
        // nullable identity column: no guesses, record deletion, or learning-data rewrite.
        if (oldVersion in 7..16) db.execSQL("alter table chapter_abandon_commands add column chapter_id text")
        if (oldVersion in 8..17) db.execSQL("alter table review_chapter_results add column request_digest text")
        if (oldVersion < 19) migrateKnowledgeStates(db)
        if (oldVersion in 8..19) db.execSQL("alter table review_queue_items add column occurrence_count integer check(occurrence_count>=0)")
        if (oldVersion < 25) {
            // Some older migrations create the current pattern table; add only when absent.
            // Nullable, no UPDATE/backfill: old English-only references do not prove a meaning.
            for (table in listOf("target_selections", "pattern_items")) {
                val exists = db.rawQuery("pragma table_info($table)", null).use { cursor ->
                    var found = false
                    while (cursor.moveToNext()) if (cursor.getString(1) == "related_vocabulary_snapshot") found = true
                    found
                }
                if (!exists) db.execSQL("alter table $table add column related_vocabulary_snapshot text")
            }
        }
    }

    private fun createFixedSourcePayloads(db: SQLiteDatabase) {
        db.execSQL("create table if not exists fixed_source_payloads(account_id text not null, flow_id text not null, source_entry_id text not null, source_revision integer not null check(source_revision>=0), payload_version integer not null check(payload_version=1), envelope blob not null, primary key(account_id,flow_id))")
    }

    private fun createSceneRecommendationCache(db: SQLiteDatabase) {
        db.execSQL("create table if not exists scene_recommendation_cache_state(account_id text not null primary key, source_signature text not null, refreshed_at_epoch_ms integer not null)")
        db.execSQL("create table if not exists scene_recommendation_cache_items(account_id text not null, display_order integer not null check(display_order between 0 and 3), recommendation_id text not null, short_topic_zh text not null, why_relevant_zh text not null, source_refs_json text not null, source_completed_local_date text not null, primary key(account_id,display_order), unique(account_id,recommendation_id))")
    }

    private fun createEnglishKnowledgeSnapshots(db: SQLiteDatabase) {
        db.execSQL("create table if not exists english_generation_knowledge_snapshots(account_id text not null, base_command_id text not null, request_digest text not null, entries_json text not null, primary key(account_id,base_command_id))")
    }

    private fun createExplanationCache(db: SQLiteDatabase) {
        db.execSQL("create table if not exists learning_explanation_cache(account_id text not null, chapter_id text not null, sentence_id text not null, cache_key text not null, response_body text not null, primary key(account_id,chapter_id,sentence_id,cache_key))")
    }

    private fun createSelfAssessments(db: SQLiteDatabase) {
        db.execSQL("create table if not exists learning_self_assessments(account_id text not null, chapter_id text not null, sentence_id text not null, target_id text not null, capability_type text not null check(capability_type in ('MEANING_WRITING','PATTERN_WRITING','PATTERN_SPEAKING')), primary key(account_id,chapter_id,target_id,capability_type))")
    }

    private fun createLearningSteps(db: SQLiteDatabase) {
        db.execSQL("create table if not exists learning_steps(account_id text not null, chapter_id text not null, sentence_id text not null, step text not null, status text not null check(status='COMPLETED'), command_id text not null, primary key(account_id,chapter_id,sentence_id,step), unique(account_id,command_id))")
    }

    private fun createModuleProgress(db: SQLiteDatabase) {
        db.execSQL("create table if not exists learning_module_progress(account_id text not null, chapter_id text not null, sentence_id text not null, module_id text not null, payload text not null, primary key(account_id,chapter_id,sentence_id,module_id))")
    }

    private fun createWritingDrafts(db: SQLiteDatabase) {
        db.execSQL("create table if not exists writing_result_drafts(account_id text not null, chapter_id text not null, sentence_id text not null, ability_type text not null check(ability_type in ('WHOLE_WRITING','MEANING_WRITING','PATTERN_WRITING')), target_id text not null, passed integer not null check(passed in (0,1)), primary key(account_id,chapter_id,sentence_id,ability_type,target_id))")
        db.execSQL("create table if not exists writing_batch_completions(account_id text not null, chapter_id text not null, command_id text not null, qualification text not null check(qualification in ('INDEPENDENT','SUPPORTED')), primary key(account_id,chapter_id), unique(account_id,command_id))")
    }

    private fun createSceneResponseDrafts(db: SQLiteDatabase) {
        db.execSQL("create table if not exists scene_response_tasks(account_id text not null, chapter_id text not null, task_id text not null, sentence_id text not null, sentence_order integer not null, background_en text not null, context_lines text not null, target_sentence_en text not null, primary key(account_id,chapter_id,sentence_id), unique(account_id,task_id))")
        db.execSQL("create table if not exists scene_response_result_drafts(account_id text not null, chapter_id text not null, sentence_id text not null, passed integer not null check(passed in (0,1)), primary key(account_id,chapter_id,sentence_id))")
    }

    private fun createLearningCheckpoints(db: SQLiteDatabase) {
        db.execSQL("create table if not exists learning_checkpoints(account_id text not null, chapter_id text not null, module_id text not null, step_id text not null, primary key(account_id,chapter_id))")
    }

    private fun createChapterArchives(db: SQLiteDatabase) {
        db.execSQL("create table if not exists full_speaking_result_drafts(account_id text not null, chapter_id text not null, sentence_id text not null, passed integer not null check(passed in (0,1)), primary key(account_id,chapter_id,sentence_id))")
        db.execSQL("create table if not exists chapter_archives(account_id text not null, chapter_id text not null, command_id text not null, summary text not null, completed_local_date text not null, primary key(account_id,chapter_id), unique(account_id,command_id))")
        createSentenceCapabilities(db)
        db.execSQL("create table if not exists learning_obligations(account_id text not null, obligation_id text not null, chapter_id text not null, sentence_id text not null, capability_type text not null, target_id text not null, primary key(account_id,obligation_id))")
        db.execSQL("create table if not exists correction_flags(account_id text not null, obligation_id text not null, primary key(account_id,obligation_id))")
        db.execSQL("create table if not exists normal_learning_days(account_id text not null, local_date text not null, primary key(account_id,local_date))")
        createVocabularyItems(db)
        db.execSQL("create table if not exists vocabulary_occurrences(account_id text not null, item_id text not null, chapter_id text not null, sentence_id text not null, is_focus integer not null check(is_focus in (0,1)), primary key(account_id,item_id,chapter_id,sentence_id))")
        createVocabularyKnowledge(db)
        createVocabularyDetails(db)
        createTemporaryKnowledgeDetails(db)
        createPatternItems(db)
        db.execSQL("create table if not exists pattern_occurrences(account_id text not null, item_id text not null, chapter_id text not null, sentence_id text not null, is_focus integer not null check(is_focus in (0,1)), primary key(account_id,item_id,chapter_id,sentence_id))")
        createPatternKnowledgeDetails(db)
    }

    private fun createSentenceCapabilities(db: SQLiteDatabase) {
        db.execSQL("create table if not exists sentence_capabilities(account_id text not null, sentence_id text not null, capability_type text not null check(capability_type in ('FULL_WRITING','FULL_SPEAKING','SCENE_RESPONSE')), status text not null check(status in ('PENDING','LEARNING','VERIFIED')), primary key(account_id,sentence_id,capability_type))")
    }

    private fun createVocabularyItems(db: SQLiteDatabase) {
        db.execSQL("create table if not exists vocabulary_items(account_id text not null, item_id text not null, normalized_form text not null, semantic_sense_key text not null, display text not null, meaning_zh text not null, status text not null check(status in ('PENDING','LEARNING','SELF_ASSESSED','VERIFIED')), verified_success_count integer not null check(verified_success_count>=0), phonetic text, pronunciation_audio_id text, representative_scene text, primary key(account_id,item_id), unique(account_id,normalized_form,semantic_sense_key))")
    }

    private fun createPatternItems(db: SQLiteDatabase) {
        db.execSQL("create table if not exists pattern_items(account_id text not null, item_id text not null, semantic_pattern_key text not null, structure_en text not null, meaning_zh text not null, writing_status text not null check(writing_status in ('PENDING','LEARNING','SELF_ASSESSED','VERIFIED')), speaking_status text not null check(speaking_status in ('PENDING','LEARNING','SELF_ASSESSED','VERIFIED')), writing_streak integer not null check(writing_streak between 0 and 3), speaking_streak integer not null check(speaking_streak between 0 and 3), active_validation integer not null check(active_validation in (0,1)), representative_scene text, natural_revalidation integer not null default 1 check(natural_revalidation in (0,1)), related_vocabulary_snapshot text, primary key(account_id,item_id), unique(account_id,semantic_pattern_key))")
    }

    private fun migrateKnowledgeStates(db: SQLiteDatabase) {
        // SQLiteOpenHelper runs the upgrade in one transaction. Keep every column and
        // original rowid (entry order); no status is guessed or upgraded to verified.
        listOf("sentence_capabilities", "vocabulary_items", "pattern_items").forEach { table ->
            db.execSQL("alter table $table rename to ${table}_before_v19")
            when (table) {
                "sentence_capabilities" -> createSentenceCapabilities(db)
                "vocabulary_items" -> createVocabularyItems(db)
                "pattern_items" -> createPatternItems(db)
            }
            val previousColumns = db.rawQuery("pragma table_info(${table}_before_v19)", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
            val columns = db.rawQuery("pragma table_info($table)", null).use { cursor ->
                buildList { while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    if (name in previousColumns) add(name)
                } }.joinToString(",")
            }
            db.execSQL("insert into $table(rowid,$columns) select rowid,$columns from ${table}_before_v19")
            db.execSQL("drop table ${table}_before_v19")
        }
    }

    private fun createVocabularyKnowledge(db: SQLiteDatabase) {
        db.execSQL("create table if not exists vocabulary_wordlists(account_id text not null, item_id text not null, wordlist text not null, primary key(account_id,item_id,wordlist))")
    }

    private fun createVocabularyDetails(db: SQLiteDatabase) {
        db.execSQL("create table if not exists vocabulary_examples(account_id text not null, item_id text not null, example_order integer not null check(example_order between 0 and 2), text_en text not null, text_zh text not null, audio_id text not null, primary key(account_id,item_id,example_order))")
    }

    private fun createTemporaryKnowledgeDetails(db: SQLiteDatabase) {
        db.execSQL("create table if not exists target_vocabulary_details(account_id text not null, chapter_id text not null, target_id text not null, phonetic text, pronunciation_text text not null, representative_scene text not null, examples_json text not null, primary key(account_id,target_id))")
    }

    private fun createPatternKnowledgeDetails(db: SQLiteDatabase) {
        db.execSQL("create table if not exists pattern_examples(account_id text not null, item_id text not null, example_order integer not null check(example_order between 0 and 2), text_en text not null, text_zh text not null, pronunciation_text text not null, primary key(account_id,item_id,example_order))")
        db.execSQL("create table if not exists target_pattern_details(account_id text not null, chapter_id text not null, target_id text not null, phonetic text, pronunciation_text text not null, representative_scene text not null, examples_json text not null, natural_revalidation integer not null check(natural_revalidation in (0,1)), primary key(account_id,target_id))")
        db.execSQL("create table if not exists pattern_removal_commands(account_id text not null, command_id text not null, pattern_id text not null, primary key(account_id,command_id))")
    }

    private fun createChapterAbandon(db: SQLiteDatabase) {
        db.execSQL("create table if not exists chapter_abandon_commands(account_id text not null, command_id text not null, source_id text not null, has_remaining integer not null check(has_remaining in (0,1)), chapter_id text, primary key(account_id,command_id))")
    }

    private fun createReview(db: SQLiteDatabase) {
        db.execSQL("create table if not exists review_cycles(account_id text not null, review_cycle_id text not null, status text not null check(status in ('ACTIVE','COMPLETED')), started_local_date text not null, primary key(account_id,review_cycle_id))")
        db.execSQL("create table if not exists review_queue_items(account_id text not null, review_cycle_id text not null, queue_item_id text not null, obligation_id text not null, original_chapter_id text not null, sentence_id text not null, priority_rank integer not null, entered_at integer not null, occurrence_count integer check(occurrence_count>=0), primary key(account_id,queue_item_id), unique(account_id,review_cycle_id,obligation_id))")
        db.execSQL("create index if not exists review_queue_order on review_queue_items(account_id,review_cycle_id,priority_rank,entered_at)")
        db.execSQL("create table if not exists review_chapters(account_id text not null, review_cycle_id text not null, review_chapter_id text not null, original_chapter_id text not null, source_type text not null, source_title text not null, status text not null check(status in ('READY','COMPLETED')), primary key(account_id,review_chapter_id), unique(account_id,review_cycle_id,original_chapter_id))")
        db.execSQL("create table if not exists review_chapter_items(account_id text not null, review_chapter_id text not null, queue_item_id text not null, sentence_id text not null, ability_type text not null, primary key(account_id,review_chapter_id,queue_item_id))")
        db.execSQL("create table if not exists review_sessions(account_id text not null, review_session_id text not null, review_cycle_id text not null, local_date text not null, completed_focus_count integer not null check(completed_focus_count between 0 and 3), primary key(account_id,review_session_id), unique(account_id,review_cycle_id,local_date))")
        db.execSQL("create table if not exists review_working_state(account_id text not null, review_chapter_id text not null, ability_id text not null, evidence_id text not null, primary key(account_id,review_chapter_id,ability_id))")
        db.execSQL("create table if not exists review_chapter_results(account_id text not null, review_chapter_id text not null, command_id text not null, local_date text not null, completed_focus_count integer not null, request_digest text, primary key(account_id,review_chapter_id), unique(account_id,command_id))")
        createReviewSettlement(db)
    }

    private fun createReviewSettlement(db: SQLiteDatabase) {
        db.execSQL("create table if not exists review_session_settlements(account_id text not null, review_session_id text not null, command_id text not null, summary text not null, primary key(account_id,review_session_id), unique(account_id,command_id))")
    }

    private fun migratePatternSpeakingStatus(db: SQLiteDatabase) {
        db.execSQL("alter table pattern_items rename to pattern_items_v8")
        db.execSQL("create table pattern_items(account_id text not null, item_id text not null, semantic_pattern_key text not null, structure_en text not null, meaning_zh text not null, writing_status text not null check(writing_status in ('LEARNING','VERIFIED')), speaking_status text not null check(speaking_status in ('LEARNING','VERIFIED')), writing_streak integer not null check(writing_streak between 0 and 3), speaking_streak integer not null check(speaking_streak between 0 and 3), active_validation integer not null check(active_validation in (0,1)), primary key(account_id,item_id), unique(account_id,semantic_pattern_key))")
        db.execSQL("insert into pattern_items select * from pattern_items_v8")
        db.execSQL("drop table pattern_items_v8")
    }
}
