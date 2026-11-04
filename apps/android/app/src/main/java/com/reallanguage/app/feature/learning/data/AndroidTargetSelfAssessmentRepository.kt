package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.SelfAssessmentAbility
import com.reallanguage.app.feature.learning.domain.SelfAssessmentTarget
import com.reallanguage.app.feature.learning.domain.TargetSelfAssessmentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidTargetSelfAssessmentRepository(
    context: Context,
    private val runtime: SessionRuntime,
    private val chapterId: String,
    private val sentenceId: String,
    private val moduleId: String,
) : TargetSelfAssessmentRepository {
    private val context = context.applicationContext
    private val accountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch

    /** Fixed expression lookup is also allowed in a completed chapter's read-only review. */
    suspend fun selectedExpressionDisplays(): List<String> = withContext(Dispatchers.IO) {
        requireOwner()
        FormationDatabase(context).use { helper ->
            val db = helper.readableDatabase
            requireOwner()
            db.requireChapter(allowCompleted = true)
            db.targets().filter { SelfAssessmentAbility.MEANING_WRITING in it.abilities }.map { it.display }
        }.also { requireOwner() }
    }

    override suspend fun load(): List<SelfAssessmentTarget> = withContext(Dispatchers.IO) {
        requireOwner()
        FormationDatabase(context).use { helper ->
            val db = helper.readableDatabase
            requireOwner()
            db.requireOpenChapter()
            db.targets()
        }.also { requireOwner() }
    }

    override suspend fun mark(targetId: String, ability: SelfAssessmentAbility): Boolean = withContext(Dispatchers.IO) {
        try {
            requireOwner()
            check(moduleId in setOf("LR-02", "LR-07"))
            FormationDatabase(context).use { helper ->
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    requireOwner()
                    db.requireOpenChapter()
                    check(db.rawQuery("select module_id,step_id from learning_checkpoints where account_id=? and chapter_id=?",
                        arrayOf(accountId, chapterId)).use { cursor ->
                        cursor.moveToFirst() && cursor.getString(0) == moduleId &&
                            (moduleId == "LR-07" || cursor.getString(1) in setOf(sentenceId, "explanation:$sentenceId"))
                    })
                    val target = db.targets().singleOrNull { it.targetId == targetId } ?: error("Target is not selected in this sentence")
                    check(ability in target.abilities)
                    db.insertWithOnConflict("learning_self_assessments", null, ContentValues().apply {
                        put("account_id", accountId); put("chapter_id", chapterId); put("sentence_id", sentenceId)
                        put("target_id", targetId); put("capability_type", ability.name)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                    // Read the persisted row before acknowledging success, including retry.
                    check(db.rawQuery("select 1 from learning_self_assessments where account_id=? and chapter_id=? and target_id=? and capability_type=?",
                        arrayOf(accountId, chapterId, targetId, ability.name)).use { it.moveToFirst() })
                    requireOwner()
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            requireOwner()
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    private fun requireOwner() {
        check(accountId != null && runtime.accountId == accountId &&
            runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null)
    }

    private fun SQLiteDatabase.requireOpenChapter() {
        requireChapter(allowCompleted = false)
    }

    private fun SQLiteDatabase.requireChapter(allowCompleted: Boolean) {
        check(rawQuery("select 1 from chapters c join fixed_sentences s on s.account_id=c.account_id and s.chapter_id=c.chapter_id " +
            "where c.account_id=? and c.chapter_id=? " + (if (allowCompleted) "" else "and c.status!='COMPLETED' ") +
            "and s.unit_id=? and s.role='FOCUS'",
            arrayOf(accountId, chapterId, sentenceId)).use { it.moveToFirst() })
    }

    private fun SQLiteDatabase.targets(): List<SelfAssessmentTarget> = rawQuery(
        "select t.target_id,t.type,t.display,t.explanation_zh from target_selections t where t.account_id=? and t.chapter_id=? and t.unit_id=? and ($ACTIVE_FIXED_TARGET_PREDICATE) order by t.rowid",
        arrayOf(accountId, chapterId, sentenceId),
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) {
            val id = cursor.getString(0)
            val abilities = when (cursor.getString(1)) {
                "WORD", "FIXED_EXPRESSION" -> setOf(SelfAssessmentAbility.MEANING_WRITING)
                "PATTERN" -> setOf(SelfAssessmentAbility.PATTERN_WRITING, SelfAssessmentAbility.PATTERN_SPEAKING)
                else -> error("Unknown selected target type")
            }
            val assessed = rawQuery("select capability_type from learning_self_assessments where account_id=? and chapter_id=? and sentence_id=? and target_id=?",
                arrayOf(accountId, chapterId, sentenceId, id)).use { rows -> buildSet {
                while (rows.moveToNext()) add(SelfAssessmentAbility.valueOf(rows.getString(0)))
            } }
            check(abilities.containsAll(assessed))
            add(SelfAssessmentTarget(id, sentenceId, cursor.getString(2), cursor.getString(3), abilities, assessed))
        }
    } }
}
