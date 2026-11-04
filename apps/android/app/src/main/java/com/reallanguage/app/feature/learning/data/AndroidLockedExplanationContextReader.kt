package com.reallanguage.app.feature.learning.data

import android.content.Context
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.decodeRelatedVocabularySnapshot
import com.reallanguage.app.feature.formation.data.decodeSourceEvidence
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.hasConsistentVocabularyExplanations
import com.reallanguage.app.feature.formation.domain.hasValidRelatedVocabularyItems
import com.reallanguage.app.feature.formation.domain.hasValidStructureParts
import com.reallanguage.app.feature.formation.domain.isValidForTarget
import com.reallanguage.app.feature.learning.domain.LockedExplanationContext
import com.reallanguage.app.feature.learning.domain.LockedExplanationTarget
import com.reallanguage.app.feature.learning.domain.LockedExplanationVocabulary
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Null means unavailable evidence, whereas an empty target list is a valid selection. */
class AndroidLockedExplanationContextReader(context: Context, private val runtime: SessionRuntime) {
    private val applicationContext = context.applicationContext
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId &&
        runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    fun read(chapterId: String, focusSentenceId: String): LockedExplanationContext? {
        val account = ownerAccountId ?: return null
        if (!ownerIsActive() || chapterId.isBlank() || focusSentenceId.isBlank()) return null
        return try {
            FormationDatabase(applicationContext).use { helper ->
                val db = helper.readableDatabase
                check(ownerIsActive())
                db.beginTransactionNonExclusive()
                try {
                    check(ownerIsActive())
                    val english = db.rawQuery(
                        """select s.text_en from fixed_sentences s
                            join chapters c on c.account_id=s.account_id and c.chapter_id=s.chapter_id
                            where s.account_id=? and s.chapter_id=? and s.unit_id=? and s.role='FOCUS'""".trimIndent(),
                        arrayOf(account, chapterId, focusSentenceId),
                    ).use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getString(0).also { check(it.isNotBlank() && !cursor.moveToNext()) }
                    }
                    val targets = db.rawQuery(
                        """select target_id,type,display,explanation_zh,structure_parts,related_vocabulary,
                            source_evidence,related_vocabulary_snapshot
                            from target_selections where account_id=? and chapter_id=? and unit_id=? order by target_id""".trimIndent(),
                        arrayOf(account, chapterId, focusSentenceId),
                    ).use { cursor -> buildList {
                        while (cursor.moveToNext()) {
                            val source = if (cursor.isNull(6)) null else requireNotNull(decodeSourceEvidence(JSONObject(cursor.getString(6))))
                            val snapshot = if (cursor.isNull(7)) null else decodeRelatedVocabularySnapshot(cursor.getString(7))
                            if (snapshot != null) check(snapshot.unitId == focusSentenceId && snapshot.sourceTextEn == english)
                            val target = LearningTarget(
                                targetId = cursor.getString(0), unitId = focusSentenceId,
                                type = LearningTargetType.valueOf(cursor.getString(1)),
                                display = cursor.getString(2), explanationZh = cursor.getString(3),
                                structureParts = savedList(cursor.getString(4)),
                                relatedVocabulary = savedList(cursor.getString(5)),
                                sourceEvidence = source,
                                relatedVocabularyItems = snapshot?.items,
                                relatedVocabularyItemRefs = snapshot?.refs,
                            )
                            check(target.targetId.isNotBlank() && target.display.isNotBlank() && target.explanationZh.isNotBlank())
                            check(target.hasValidStructureParts(requirePatternStructure = source != null))
                            check(source == null || source.isValidForTarget(english, target.display, target.type))
                            check(target.hasValidRelatedVocabularyItems(english))
                            add(target)
                        }
                    } }
                    // The same exact occurrence may be shared by patterns, but never receive
                    // contradictory explanations or become a second vocabulary learning target.
                    check(targets.hasConsistentVocabularyExplanations())
                    val result = LockedExplanationContext(chapterId, focusSentenceId, english, targets.map { target ->
                        val byRef = target.relatedVocabularyItems?.associateBy { it.itemRef }
                        LockedExplanationTarget(target.targetId, target.type, target.display, target.explanationZh,
                            target.structureParts, target.sourceEvidence,
                            target.relatedVocabularyItemRefs?.map { ref ->
                                val item = requireNotNull(byRef).getValue(ref)
                                LockedExplanationVocabulary(item.display, item.senseZh, item.unitType, item.sourceEvidence)
                            })
                    })
                    check(ownerIsActive())
                    db.setTransactionSuccessful()
                    result
                } finally { db.endTransaction() }
            }.takeIf { ownerIsActive() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    // Empty legacy collections are valid; blank elements in explicit stored lists are not trimmed away.
    private fun savedList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split('\u001f')
}
