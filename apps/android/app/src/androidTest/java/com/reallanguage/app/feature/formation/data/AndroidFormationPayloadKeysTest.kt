package com.reallanguage.app.feature.formation.data

import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class AndroidFormationPayloadKeysTest {
    private fun isolated(block: (String) -> Unit) = runBlocking { withContext(Dispatchers.IO) {
        val namespace = "reallanguage.formation.payload.test.${UUID.randomUUID().toString().replace("-", "")}"
        try { block(namespace) }
        finally {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.aliases().toList().filter { it.startsWith("$namespace.") }.forEach(store::deleteEntry)
        }
    } }

    @Test fun realKeystoreRoundTripSurvivesAdapterRecreationAndKeyIsNonExportable() = isolated { namespace ->
        val owner = FormationPayloadOwner("a".repeat(64), "source:isolated-1")
        val identity = FormationPayloadIdentity(owner, 7, 1)
        val keys = AndroidFormationPayloadKeys(namespace)
        val plaintext = "隔离测试：确认中文与英文候选".toByteArray()
        val encrypted = FormationPayloadCipher(keys).encrypt(identity, plaintext)
        assertFalse(encrypted.contentEquals(plaintext))
        val restored = AndroidFormationPayloadKeys(namespace)
        assertArrayEquals(plaintext, FormationPayloadCipher(restored).decrypt(identity, encrypted))
        assertNotNull(restored.existing(owner.accountSubjectHash, 1))
        assertNull(restored.existing(owner.accountSubjectHash, 1)!!.encoded)
    }

    @Test fun accountKeyDeletionDoesNotDeleteOtherAccountAndCannotBeRecreatedByRead() = isolated { namespace ->
        val keys = AndroidFormationPayloadKeys(namespace)
        val a = FormationPayloadIdentity(FormationPayloadOwner("a".repeat(64), "source:one"), 0, 1)
        val b = a.copy(owner = a.owner.copy(accountSubjectHash = "b".repeat(64)))
        val cipher = FormationPayloadCipher(keys)
        val first = cipher.encrypt(a, "first".toByteArray())
        val other = cipher.encrypt(b, "other".toByteArray())
        keys.deleteAccount(a.owner.accountSubjectHash)
        assertNull(cipher.decrypt(a, first))
        assertNull(keys.existing(a.owner.accountSubjectHash, 1))
        assertArrayEquals("other".toByteArray(), cipher.decrypt(b, other))
    }

    @Test fun missingReadCreatesNoAliasAndInvalidIdentityCannotSelectSessionNamespace() = isolated { namespace ->
        val keys = AndroidFormationPayloadKeys(namespace)
        assertNull(keys.existing("a".repeat(64), 1))
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(store.aliases().toList().any { it.startsWith("$namespace.") })
        assertThrows(IllegalArgumentException::class.java) { keys.createIfAbsent("../escape", 1) }
        assertThrows(IllegalArgumentException::class.java) { AndroidFormationPayloadKeys("reallanguage.session.refresh") }
    }

    @Test fun revokedWriterCannotRecreateADeletedFormationKey() = isolated { namespace ->
        val hash = "a".repeat(64)
        var allowed = true
        val writer = AndroidFormationPayloadKeys(namespace, canUseAccount = { allowed })
        writer.createIfAbsent(hash, 1)
        allowed = false
        val cleanup = AndroidFormationPayloadKeys(namespace, canUseAccount = { true })
        cleanup.deleteAccount(hash)
        assertThrows(IllegalStateException::class.java) { writer.createIfAbsent(hash, 1) }
        assertNull(cleanup.existing(hash, 1))
    }
}
