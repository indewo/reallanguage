package com.reallanguage.app.feature.formation.data

import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Formation-specific namespace; never shares the refresh-session key. */
class AndroidFormationPayloadKeys internal constructor(
    private val namespace: String = "reallanguage.formation.payload",
    private val canUseAccount: (String) -> Boolean = com.reallanguage.app.core.session.DeletedAccountLocalAccess::allowsAccountHash,
) : FormationPayloadKeys {
    init {
        require(namespace == PRODUCTION_NAMESPACE ||
            namespace.matches(Regex("reallanguage\\.formation\\.payload\\.test\\.[a-f0-9]{32}")))
    }

    override fun existing(accountSubjectHash: String, keyVersion: Int): SecretKey? = synchronized(keyLock) {
        val alias = alias(accountSubjectHash, keyVersion)
        check(canUseAccount(accountSubjectHash)) { "Formation account access is revoked" }
        val store = openStore()
        if (!store.containsAlias(alias)) null
        else requireNotNull(store.getKey(alias, null) as? SecretKey) { "Formation payload key is unavailable" }
    }

    override fun createIfAbsent(accountSubjectHash: String, keyVersion: Int): SecretKey = synchronized(keyLock) {
        val alias = alias(accountSubjectHash, keyVersion)
        check(canUseAccount(accountSubjectHash)) { "Formation account access is revoked" }
        val store = openStore()
        if (store.containsAlias(alias)) {
            // An invalidated or unreadable existing key is not permission to replace it.
            return@synchronized requireNotNull(store.getKey(alias, null) as? SecretKey) { "Formation payload key is unavailable" }
        }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    override fun deleteAccount(accountSubjectHash: String) = synchronized(keyLock) {
        openStore().deleteEntry(alias(accountSubjectHash, 1))
    }

    private fun alias(accountSubjectHash: String, keyVersion: Int): String {
        require(validFormationAccountHash(accountSubjectHash) && keyVersion == 1)
        return "$namespace.$accountSubjectHash.v$keyVersion"
    }

    private fun openStore(): KeyStore {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Keystore work must run off the main thread" }
        return KeyStore.getInstance(KEYSTORE).apply { load(null) }
    }

    private companion object {
        const val PRODUCTION_NAMESPACE = "reallanguage.formation.payload"
        const val KEYSTORE = "AndroidKeyStore"
        val keyLock = Any()
    }
}
