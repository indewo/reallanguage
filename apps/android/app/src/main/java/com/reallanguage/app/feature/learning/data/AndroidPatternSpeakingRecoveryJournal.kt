package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.*
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeSet

class AndroidPatternSpeakingPayloadKeys internal constructor(
    private val namespace: String = "reallanguage.pattern-speaking.payload",
    private val canUseAccount: (String) -> Boolean = com.reallanguage.app.core.session.DeletedAccountLocalAccess::allowsAccountHash,
) : FormationPayloadKeys {
    init { require(namespace == "reallanguage.pattern-speaking.payload" ||
        namespace.matches(Regex("reallanguage\\.pattern-speaking\\.payload\\.test\\.[a-f0-9]{32}"))) }
    override fun existing(accountSubjectHash: String, keyVersion: Int): SecretKey? = synchronized(keyLock) {
        val alias = alias(accountSubjectHash, keyVersion)
        check(canUseAccount(accountSubjectHash))
        val store = store()
        if (!store.containsAlias(alias)) null else requireNotNull(store.getKey(alias, null) as? SecretKey)
    }
    override fun createIfAbsent(accountSubjectHash: String, keyVersion: Int): SecretKey = synchronized(keyLock) {
        val alias = alias(accountSubjectHash, keyVersion)
        check(canUseAccount(accountSubjectHash))
        val store = store()
        if (store.containsAlias(alias)) return@synchronized requireNotNull(store.getKey(alias, null) as? SecretKey)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    override fun deleteAccount(accountSubjectHash: String) = synchronized(keyLock) { store().deleteEntry(alias(accountSubjectHash, 1)) }
    private fun alias(hash: String, version: Int): String {
        require(hash.matches(Regex("[a-f0-9]{64}")) && version == 1)
        return "$namespace.$hash.v$version"
    }
    private fun store(): KeyStore {
        check(Looper.myLooper() != Looper.getMainLooper())
        return KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }
    private companion object { val keyLock = Any() }
}


class AndroidPatternSpeakingRecoveryJournal(
    context: Context,
    private val runtime: SessionRuntime,
    private val chapterId: String,
    private val sentenceId: String,
    private val targetTextEn: String,
    private val pattern: LockedSpeakingPattern,
    practiceSet: PatternPracticeSet,
    keys: FormationPayloadKeys = AndroidPatternSpeakingPayloadKeys(),
) : PatternSpeakingRecoveryJournal {
    private val context = context.applicationContext
    private val accountId = runtime.accountId
    private val accountEpoch = runtime.accountEpoch
    private val practice = practiceSet.copy(examples = practiceSet.examples.toList())
    private val hash = fixedSourceAccountHash(accountId.orEmpty())
    private val owner = FormationPayloadOwner(hash, "LR07:" + digest((listOf(chapterId, sentenceId, targetTextEn,
        pattern.patternId, pattern.sourceSentenceId, pattern.structure, pattern.explanationZh, practice.patternId) +
        practice.examples.flatMap { listOf(it.exampleId, it.sceneZh, it.aiPromptEn, it.targetResponseEn, it.targetResponseZh) })
        .joinToString("") { "${it.length}:$it" }))
    private val reader = AndroidLockedPatternReader(this.context, runtime)
    private val delegate = EncryptedPatternSpeakingRecoveryJournal(FormationRecoveryFileStore(
        File(accountLearningAttemptsDirectory(this.context.filesDir, accountId.orEmpty()), "pattern-speaking-recovery"),
        FormationPayloadCipher(keys, maxPlaintextBytes = 16 * 1024 * 1024),
        canAccess = { it == hash && currentlyAllowed() },
    ), owner)

    override suspend fun load(): PatternSpeakingRecoveryLoad = delegate.load().let { loaded ->
        if (!ownerActive() || (loaded is PatternSpeakingRecoveryLoad.Loaded && !validSnapshot(loaded.snapshot)))
            PatternSpeakingRecoveryLoad.Unavailable else loaded
    }
    override suspend fun save(expectedRevision: Long?, snapshot: PatternSpeakingRecoverySnapshot): Long? {
        if (!validSnapshot(snapshot) || load() == PatternSpeakingRecoveryLoad.Unavailable) return null
        return delegate.save(expectedRevision, snapshot).takeIf { ownerActive() }
    }

    private fun validSnapshot(snapshot: PatternSpeakingRecoverySnapshot) = practice.examples.any { it.exampleId == snapshot.exampleId }
    private fun ownerActive() = accountId != null && runtime.accountId == accountId && runtime.accountEpoch == accountEpoch && runtime.accessToken != null

    private fun currentlyAllowed(): Boolean = try {
        if (!ownerActive() || pattern.sourceSentenceId != sentenceId || practice.patternId != pattern.patternId ||
            practice.examples.size !in 1..5 || practice.examples.map { it.exampleId }.distinct().size != practice.examples.size) false
        else {
            val progressMatches = FormationDatabase(context).use { helper ->
                helper.readableDatabase.rawQuery(
                    "select m.payload from chapters c join fixed_sentences s on s.account_id=c.account_id and s.chapter_id=c.chapter_id " +
                        "join learning_checkpoints p on p.account_id=c.account_id and p.chapter_id=c.chapter_id " +
                        "join learning_module_progress m on m.account_id=c.account_id and m.chapter_id=c.chapter_id and m.sentence_id=s.unit_id and m.module_id='LR-07' " +
                        "where c.account_id=? and c.chapter_id=? and c.status!='COMPLETED' and s.unit_id=? and s.role='FOCUS' " +
                        "and s.text_en=? and p.module_id='LR-07' and p.step_id=?",
                    arrayOf(accountId, chapterId, sentenceId, targetTextEn, sentenceId),
                ).use { cursor -> cursor.moveToFirst() && matchesProgress(JSONObject(cursor.getString(0))) && !cursor.moveToNext() }
            }
            progressMatches && reader.read(chapterId, sentenceId)?.singleOrNull { it.patternId == pattern.patternId } == pattern && ownerActive()
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    private fun matchesProgress(json: JSONObject): Boolean {
        val saved = json.keys().asSequence().associateWith { key -> (json.get(key) as? String) ?: error("Invalid progress value") }
        val version = saved.getValue("version")
        if (version !in setOf("1", "2") || saved.getValue("completed") != "false" ||
            saved.getValue("stage") !in setOf("READING", "RESPONDING")) return false
        val count = saved.getValue("patternCount").toInt()
        val index = saved.getValue("patternIndex").toInt()
        if (index !in 0 until count || saved["pattern.$index.id"] != pattern.patternId ||
            saved["pattern.$index.source"] != pattern.sourceSentenceId || saved["pattern.$index.structure"] != pattern.structure ||
            saved["pattern.$index.explanation"] != pattern.explanationZh || saved["exampleCount"] != practice.examples.size.toString()) return false
        if (version == "2") {
            val processed = saved.getValue("processedPatternCount").toInt()
            if (processed !in 0..count || (0 until processed).any { saved["processedPattern.$it.id"] == pattern.patternId }) return false
        }
        val exactExamples = practice.examples.withIndex().all { (i, example) ->
            saved["example.$i.id"] == example.exampleId && saved["example.$i.scene"] == example.sceneZh &&
                saved["example.$i.prompt"] == example.aiPromptEn && saved["example.$i.en"] == example.targetResponseEn &&
                saved["example.$i.zh"] == example.targetResponseZh && saved["example.$i.read"] in setOf("true", "false")
        }
        val responseIndex = saved.getValue("responseIndex").toInt()
        return exactExamples && responseIndex in practice.examples.indices &&
            (saved["stage"] != "RESPONDING" || practice.examples.indices.all { saved["example.$it.read"] == "true" })
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
