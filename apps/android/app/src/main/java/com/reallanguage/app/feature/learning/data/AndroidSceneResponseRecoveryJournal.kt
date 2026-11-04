package com.reallanguage.app.feature.learning.data

import android.content.Context
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.AndroidFormationPayloadKeys
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.FormationPayloadCipher
import com.reallanguage.app.feature.formation.data.FormationPayloadKeys
import com.reallanguage.app.feature.formation.data.FormationPayloadOwner
import com.reallanguage.app.feature.formation.data.FormationRecoveryFileStore
import com.reallanguage.app.feature.formation.data.fixedSourceAccountHash
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Only the current ordinary LR09 task/round may read or change its encrypted attempt. */
class AndroidSceneResponseRecoveryJournal(
    context: Context,
    private val runtime: SessionRuntime,
    chapter: FixedLearningChapter,
    task: FixedSceneResponseTask,
    private val roundIndex: Int,
    keys: FormationPayloadKeys = AndroidFormationPayloadKeys(),
) : SceneResponseRecoveryJournal {
    private val context = context.applicationContext
    private val accountId = runtime.accountId
    private val accountEpoch = runtime.accountEpoch
    private val chapter = chapter.copy(sentences = chapter.sentences.toList())
    private val task = task.copy(contextLines = task.contextLines.toList())
    private val accountHash = fixedSourceAccountHash(accountId.orEmpty())
    private val owner = FormationPayloadOwner(accountHash, "LR09:" + digest(buildList {
        add(this@AndroidSceneResponseRecoveryJournal.chapter.chapterId)
        add(this@AndroidSceneResponseRecoveryJournal.chapter.chapterNumber.toString())
        add(this@AndroidSceneResponseRecoveryJournal.chapter.sentences.size.toString())
        this@AndroidSceneResponseRecoveryJournal.chapter.sentences.forEach {
            add(it.sentenceId); add(it.textEn); add(it.textZh); add(it.isFocus.toString())
        }
        add(this@AndroidSceneResponseRecoveryJournal.task.taskId)
        add(this@AndroidSceneResponseRecoveryJournal.task.sentenceId)
        add((this@AndroidSceneResponseRecoveryJournal.task.backgroundEn != null).toString())
        add(this@AndroidSceneResponseRecoveryJournal.task.backgroundEn.orEmpty())
        add(this@AndroidSceneResponseRecoveryJournal.task.contextLines.size.toString())
        this@AndroidSceneResponseRecoveryJournal.task.contextLines.forEach { add(it.speaker); add(it.textEn) }
        add(this@AndroidSceneResponseRecoveryJournal.task.targetSentenceEn)
        add(roundIndex.toString())
    }.joinToString("") { "${it.length}:$it" }))
    private val reader = AndroidSavedSceneTaskReader(this.context, runtime)
    private val delegate = EncryptedSceneResponseRecoveryJournal(FormationRecoveryFileStore(
        File(accountLearningAttemptsDirectory(this.context.filesDir, accountId.orEmpty()), "scene-response-recovery"),
        FormationPayloadCipher(keys, maxPlaintextBytes = 16 * 1024 * 1024),
        canAccess = { it == accountHash && currentlyAllowed() },
    ), owner)

    override suspend fun load(): SceneResponseRecoveryLoad {
        currentCoroutineContext().ensureActive()
        val loaded = delegate.load()
        currentCoroutineContext().ensureActive()
        return if (!ownerActive() || (loaded is SceneResponseRecoveryLoad.Loaded && !validSnapshot(loaded.snapshot)))
            SceneResponseRecoveryLoad.Unavailable else loaded
    }

    override suspend fun save(expectedRevision: Long?, snapshot: SceneResponseRecoverySnapshot): Long? {
        currentCoroutineContext().ensureActive()
        if (!validSnapshot(snapshot) || load() == SceneResponseRecoveryLoad.Unavailable) return null
        val saved = delegate.save(expectedRevision, snapshot)
        currentCoroutineContext().ensureActive()
        return saved.takeIf { ownerActive() }
    }

    private fun validSnapshot(snapshot: SceneResponseRecoverySnapshot) =
        snapshot.taskId == task.taskId && snapshot.roundIndex == roundIndex

    private fun ownerActive() = accountId != null && runtime.accountId == accountId &&
        runtime.accountEpoch == accountEpoch && runtime.accessToken != null

    private fun currentlyAllowed(): Boolean = try {
        if (!ownerActive() || roundIndex !in 0..2 || chapter.chapterId.isBlank() || task.taskId.isBlank()) false
        else {
            val tasks = reader.read(chapter)
            if (tasks == null || tasks.singleOrNull { it.taskId == task.taskId } != task) false
            else {
                val progressMatches = FormationDatabase(context).use { helper ->
                    helper.readableDatabase.rawQuery(
                        "select m.payload from chapters c join learning_checkpoints p " +
                            "on p.account_id=c.account_id and p.chapter_id=c.chapter_id " +
                            "join learning_module_progress m on m.account_id=c.account_id and m.chapter_id=c.chapter_id " +
                            "and m.sentence_id=p.step_id and m.module_id='LR-09' " +
                            "join fixed_sentences s on s.account_id=c.account_id and s.chapter_id=c.chapter_id and s.unit_id=p.step_id " +
                            "where c.account_id=? and c.chapter_id=? and c.status!='COMPLETED' " +
                            "and p.module_id='LR-09' and s.role='FOCUS'",
                        arrayOf(accountId, chapter.chapterId),
                    ).use { cursor -> cursor.moveToFirst() && matchesProgress(JSONObject(cursor.getString(0)), tasks) && !cursor.moveToNext() }
                }
                progressMatches && ownerActive()
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    private fun matchesProgress(json: JSONObject, tasks: List<FixedSceneResponseTask>): Boolean {
        val saved = json.keys().asSequence().associateWith { key -> (json.get(key) as? String) ?: error("Invalid progress value") }
        if (saved.getValue("version") != "1" || saved.getValue("completed") != "false" ||
            saved.getValue("taskCount").toInt() != tasks.size || saved.getValue("roundIndex").toInt() != roundIndex) return false
        val index = saved.getValue("taskIndex").toInt()
        if (index !in tasks.indices || tasks[index] != task) return false
        val exactTasks = tasks.withIndex().all { (i, value) ->
            saved["task.$i.id"] == value.taskId && saved["task.$i.sentence"] == value.sentenceId &&
                saved["task.$i.target"] == value.targetSentenceEn && saved["task.$i.background"] == value.backgroundEn.orEmpty() &&
                saved.getValue("task.$i.lines").toInt() == value.contextLines.size && value.contextLines.withIndex().all { (j, line) ->
                    saved["task.$i.line.$j.speaker"] == line.speaker && saved["task.$i.line.$j.text"] == line.textEn
                }
        }
        if (!exactTasks) return false
        saved.getValue("transcript"); saved.getValue("feedback")
        saved.getValue("muted").toBooleanStrict()
        if (saved.getValue("trainingCompleted").toBooleanStrict() && roundIndex == 2) return false
        val finalDecision = saved.getValue("finalDecision")
        if (finalDecision.isNotEmpty() && (roundIndex != 2 || finalDecision !in DECISIONS || saved["candidateDecision"] == null)) return false
        saved["candidateDecision"]?.let {
            if (roundIndex != 2 || it !in DECISIONS) return false
            val turn = saved.getValue("candidateTurn").toBooleanStrict()
            val complete = saved.getValue("candidateComplete").toBooleanStrict()
            val pronunciation = saved.getValue("candidatePronunciation").toBooleanStrict()
            saved.getValue("candidateFeedback")
            if (finalDecision == "PASS" && (it != "PASS" || !turn || !complete || !pronunciation)) return false
        }
        return true
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object { val DECISIONS = setOf("PASS", "FAIL", "INCONCLUSIVE") }
}
