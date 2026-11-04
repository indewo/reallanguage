package com.reallanguage.app.feature.learning.data

import android.content.Context
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.SceneDialogueLine

/** Reads fixed task facts only. It cannot capture audio, call AI, or advance learning. */
internal class AndroidSavedSceneTaskReader(context: Context, private val runtime: SessionRuntime) {
    private val applicationContext = context.applicationContext
    private val owner = runtime.accountId
    private val epoch = runtime.accountEpoch
    private fun ownerIsActive() = owner != null && runtime.accountId == owner &&
        runtime.accountEpoch == epoch && runtime.accessToken != null

    fun read(chapter: FixedLearningChapter, requireArchived: Boolean = false): List<FixedSceneResponseTask>? =
        read(chapter, requireArchived, allowPartial = false)

    /** Only generation uses partial delivery. A learning/review route still needs the complete set. */
    fun readAvailable(chapter: FixedLearningChapter): List<FixedSceneResponseTask> =
        read(chapter, requireArchived = false, allowPartial = true).orEmpty()

    private fun read(chapter: FixedLearningChapter, requireArchived: Boolean, allowPartial: Boolean): List<FixedSceneResponseTask>? {
        check(ownerIsActive())
        return FormationDatabase(applicationContext).use { helper ->
            val db = helper.readableDatabase
            // One consistent read of the archive boundary, fixed sentences, and cached tasks.
            db.beginTransactionNonExclusive()
            try {
                check(ownerIsActive())
                db.rawQuery("select c.chapter_order,c.status,exists(select 1 from chapter_archives a " +
                    "where a.account_id=c.account_id and a.chapter_id=c.chapter_id) from chapters c " +
                    "where c.account_id=? and c.chapter_id=?", arrayOf(owner, chapter.chapterId)).use {
                    check(it.moveToFirst())
                    check(it.getInt(0) + 1 == chapter.chapterNumber)
                    if (requireArchived) check(it.getString(1) == "COMPLETED" && it.getInt(2) == 1)
                }
                val sentences = db.rawQuery("select unit_id,text_en,text_zh,role from fixed_sentences " +
                    "where account_id=? and chapter_id=? order by sentence_order", arrayOf(owner, chapter.chapterId)).use {
                    buildList { while (it.moveToNext()) add(FixedLearningSentence(
                        it.getString(0), it.getString(1), it.getString(2), it.getString(3) == "FOCUS")) }
                }
                if (requireArchived) check(sentences == chapter.sentences) else {
                    // Review activates one original FOCUS at a time without changing any fixed facts.
                    check(sentences.size == chapter.sentences.size)
                    sentences.zip(chapter.sentences).forEach { (fixed, requested) ->
                        check(fixed.copy(isFocus = false) == requested.copy(isFocus = false))
                        check(!requested.isFocus || fixed.isFocus)
                    }
                }
                val focuses = chapter.sentences.filter { it.isFocus }
                check(focuses.isNotEmpty())
                val values = db.rawQuery("select task_id,sentence_id,background_en,context_lines,target_sentence_en " +
                    "from scene_response_tasks where account_id=? and chapter_id=? order by sentence_order",
                    arrayOf(owner, chapter.chapterId)).use {
                    buildList { while (it.moveToNext()) add(FixedSceneResponseTask(
                        it.getString(0), it.getString(1), it.getString(2).takeIf(String::isNotEmpty),
                        decodeSavedSceneLines(it.getString(3)), it.getString(4))) }
                }
                val result = if (values.isEmpty()) null else {
                    if (requireArchived) check(values.size == focuses.size)
                    check(values.map { it.taskId }.toSet().size == values.size)
                    check(values.map { it.sentenceId }.toSet().size == values.size)
                    values.forEach { task ->
                        val fixed = sentences.single { it.isFocus && it.sentenceId == task.sentenceId }
                        check(task.taskId.isNotBlank() && task.targetSentenceEn == fixed.textEn)
                    }
                    val expected = if (allowPartial) focuses.filter { sentence -> values.any { it.sentenceId == sentence.sentenceId } } else focuses
                    expected.map { sentence -> values.single { it.sentenceId == sentence.sentenceId }.also {
                        check(it.taskId.isNotBlank() && it.targetSentenceEn == sentence.textEn)
                    } }
                }
                check(ownerIsActive())
                result
            } finally { db.endTransaction() }
        }.also { check(ownerIsActive()) }
    }
}

private fun decodeSavedSceneLines(value: String): List<SceneDialogueLine> =
    if (value.isEmpty()) emptyList() else value.split("\u001e").map { encoded ->
        val parts = encoded.split("\u001f", limit = 2)
        require(parts.size == 2 && parts.all(String::isNotBlank))
        SceneDialogueLine(parts[0], parts[1])
    }
