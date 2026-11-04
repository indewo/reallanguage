package com.reallanguage.app.core.session

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Synchronous gate used under the key lock; a confirmed deletion revokes further local writes. */
object DeletedAccountLocalAccess {
    private val revoked = ConcurrentHashMap.newKeySet<String>()
    fun allowsAccountHash(accountHash: String): Boolean = accountHash.matches(Regex("[a-f0-9]{64}")) && accountHash !in revoked
    fun allowsAccount(accountId: String): Boolean = accountId.isNotBlank() && allowsAccountHash(hash(accountId))
    fun revoke(accountId: String) {
        require(accountId.isNotBlank())
        revoked += hash(accountId)
    }

    private fun hash(accountId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(accountId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
