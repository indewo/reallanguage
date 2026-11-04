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

class AndroidFullSpeakingPayloadKeys internal constructor(
    private val namespace: String = "reallanguage.full-speaking.payload",
    private val canUseAccount: (String) -> Boolean = com.reallanguage.app.core.session.DeletedAccountLocalAccess::allowsAccountHash,
) : FormationPayloadKeys {
    init { require(namespace == "reallanguage.full-speaking.payload" ||
        namespace.matches(Regex("reallanguage\\.full-speaking\\.payload\\.test\\.[a-f0-9]{32}"))) }
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

class AndroidFullSpeakingRecoveryJournal(
    context: Context,
    private val runtime: SessionRuntime,
    private val chapterId: String,
    private val sentenceId: String,
    private val targetTextEn: String,
    private val attemptIndex: Int,
    keys: FormationPayloadKeys = AndroidFullSpeakingPayloadKeys(),
) : FullSpeakingRecoveryJournal {
    init { require(attemptIndex in 0..4) }
    private val context = context.applicationContext
    private val accountId = runtime.accountId
    private val accountEpoch = runtime.accountEpoch
    private val hash = fixedSourceAccountHash(accountId.orEmpty())
    private val owner = FormationPayloadOwner(hash, "LR06:" + digest(listOf(chapterId, sentenceId, targetTextEn, attemptIndex.toString())
        .joinToString("") { "${it.length}:$it" }))
    private val delegate = EncryptedFullSpeakingRecoveryJournal(FormationRecoveryFileStore(
        File(accountLearningAttemptsDirectory(this.context.filesDir, accountId.orEmpty()), "full-speaking-recovery"),
        FormationPayloadCipher(keys, maxPlaintextBytes = 16 * 1024 * 1024),
        canAccess = { it == hash && currentlyAllowed() },
    ), owner)

    override suspend fun load(): FullSpeakingRecoveryLoad = delegate.load()
    override suspend fun save(expectedRevision: Long?, snapshot: FullSpeakingRecoverySnapshot): Long? = delegate.save(expectedRevision, snapshot)

    private fun currentlyAllowed(): Boolean = try {
        if (accountId == null || runtime.accountId != accountId || runtime.accountEpoch != accountEpoch || runtime.accessToken == null) false
        else FormationDatabase(context).use { helper ->
            helper.readableDatabase.rawQuery(
                "select m.payload from chapters c join fixed_sentences s on s.account_id=c.account_id and s.chapter_id=c.chapter_id " +
                    "left join learning_checkpoints p on p.account_id=c.account_id and p.chapter_id=c.chapter_id " +
                    "left join learning_module_progress m on m.account_id=c.account_id and m.chapter_id=c.chapter_id and m.sentence_id=s.unit_id and m.module_id='LR-06' " +
                    "where c.account_id=? and c.chapter_id=? and c.status!='COMPLETED' and s.unit_id=? and s.role='FOCUS' " +
                    "and s.text_en=? and (p.module_id is null or (p.module_id='LR-06' and p.step_id=?))",
                arrayOf(accountId, chapterId, sentenceId, targetTextEn, sentenceId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) false
                else if (cursor.isNull(0)) attemptIndex == 0
                else JSONObject(cursor.getString(0)).let { payload ->
                    payload.opt("version") == "1" && payload.opt("attemptIndex") == attemptIndex.toString()
                }
            }
        } && runtime.accountId == accountId && runtime.accountEpoch == accountEpoch && runtime.accessToken != null
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
