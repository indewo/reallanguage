package com.reallanguage.app.feature.formation.data

import java.nio.ByteBuffer
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.*
import org.junit.Test

class FormationPayloadCipherTest {
    private val owner = FormationPayloadOwner("a".repeat(64), "source:isolated-1")
    private val identity = FormationPayloadIdentity(owner, 3, 1)
    private val text = "已确认的测试中文：café，I'm ready.\n第二句。".toByteArray(Charsets.UTF_8)

    @Test fun `real AES GCM roundtrip stores no plaintext and randomizes equal inputs`() {
        val keys = JceFormationTestKeys()
        val cipher = FormationPayloadCipher(keys)
        val first = cipher.encrypt(identity, text)
        val second = cipher.encrypt(identity, text)
        assertFalse(first.contentEquals(text))
        assertFalse(first.contentEquals(second))
        assertArrayEquals(text, FormationPayloadCipher(keys).decrypt(identity, first))
        assertEquals(FormationEnvelopeMetadata(identity), cipher.metadata(first))
    }

    @Test fun `account source revision and payload version are authenticated independently`() {
        val cipher = FormationPayloadCipher(JceFormationTestKeys())
        val encrypted = cipher.encrypt(identity, text)
        listOf(identity.copy(owner = owner.copy(accountSubjectHash = "b".repeat(64))),
            identity.copy(owner = owner.copy(sourceEntryId = "source:isolated-2")),
            identity.copy(revision = 4), identity.copy(payloadVersion = 2)).forEach {
            assertNull(cipher.decrypt(it, encrypted))
        }
    }

    @Test fun `ciphertext damage truncation trailing bytes and unknown envelope version fail closed`() {
        val cipher = FormationPayloadCipher(JceFormationTestKeys())
        val encrypted = cipher.encrypt(identity, text)
        val damaged = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val unknown = encrypted.copyOf().also { ByteBuffer.wrap(it).putInt(4, Int.MAX_VALUE) }
        listOf(damaged, encrypted.copyOf(encrypted.size - 1), encrypted + byteArrayOf(0), unknown,
            ByteArray(16) { 0x7f.toByte() }).forEach { assertNull(cipher.decrypt(identity, it)) }
    }

    @Test fun `missing or deleted key never creates a replacement during read`() {
        val firstKeys = JceFormationTestKeys()
        val encrypted = FormationPayloadCipher(firstKeys).encrypt(identity, text)
        val missingKeys = JceFormationTestKeys()
        assertNull(FormationPayloadCipher(missingKeys).decrypt(identity, encrypted))
        assertEquals(0, missingKeys.creations)
        firstKeys.deleteAccount(owner.accountSubjectHash)
        assertNull(FormationPayloadCipher(firstKeys).decrypt(identity, encrypted))
        assertEquals(1, firstKeys.creations)
    }

    @Test fun `wrong existing real key cannot authenticate the envelope`() {
        val encrypted = FormationPayloadCipher(JceFormationTestKeys()).encrypt(identity, text)
        val wrongKeys = JceFormationTestKeys().apply { createIfAbsent(owner.accountSubjectHash, 1) }
        assertNull(FormationPayloadCipher(wrongKeys).decrypt(identity, encrypted))
    }

    @Test fun `invalid identities and oversize payload are rejected before key creation`() {
        val keys = JceFormationTestKeys()
        val cipher = FormationPayloadCipher(keys, maxPlaintextBytes = text.size)
        listOf(identity.copy(owner = owner.copy(accountSubjectHash = "../owner")),
            identity.copy(owner = owner.copy(sourceEntryId = "")), identity.copy(revision = -1),
            identity.copy(payloadVersion = 0)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { cipher.encrypt(invalid, text) }
        }
        assertThrows(IllegalArgumentException::class.java) { cipher.encrypt(identity, text + byteArrayOf(1)) }
        assertEquals(0, keys.creations)
        assertArrayEquals(text, cipher.decrypt(identity, cipher.encrypt(identity, text)))
    }
}

/** Real JCE keys; only key custody is isolated, encryption is never replaced. */
internal class JceFormationTestKeys : FormationPayloadKeys {
    private val values = mutableMapOf<Pair<String, Int>, SecretKey>()
    var creations = 0
        private set
    override fun existing(accountSubjectHash: String, keyVersion: Int) = values[accountSubjectHash to keyVersion]
    override fun createIfAbsent(accountSubjectHash: String, keyVersion: Int): SecretKey =
        values.getOrPut(accountSubjectHash to keyVersion) {
            creations += 1
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    override fun deleteAccount(accountSubjectHash: String) { values.keys.removeAll { it.first == accountSubjectHash } }
}
