package com.reallanguage.app.testing

import com.reallanguage.app.feature.formation.data.FormationPayloadKeys
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** A per-test real AES key set. Graph re-creation is supported, process death is deliberately not. */
class IsolatedFormationPayloadKeys : FormationPayloadKeys {
    private val values = mutableMapOf<String, SecretKey>()
    @Synchronized override fun existing(accountSubjectHash: String, keyVersion: Int): SecretKey? {
        require(keyVersion == 1)
        return values[accountSubjectHash]
    }
    @Synchronized override fun createIfAbsent(accountSubjectHash: String, keyVersion: Int): SecretKey {
        require(keyVersion == 1)
        return values.getOrPut(accountSubjectHash) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    }
    @Synchronized override fun deleteAccount(accountSubjectHash: String) { values.remove(accountSubjectHash) }
}
