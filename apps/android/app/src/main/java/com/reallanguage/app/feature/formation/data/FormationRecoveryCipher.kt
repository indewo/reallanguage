package com.reallanguage.app.feature.formation.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class FormationPayloadOwner(val accountSubjectHash: String, val sourceEntryId: String)
data class FormationPayloadIdentity(val owner: FormationPayloadOwner, val revision: Long, val payloadVersion: Int)
data class FormationEnvelopeMetadata(val identity: FormationPayloadIdentity)

interface FormationPayloadKeys {
    fun existing(accountSubjectHash: String, keyVersion: Int): SecretKey?
    fun createIfAbsent(accountSubjectHash: String, keyVersion: Int): SecretKey
    fun deleteAccount(accountSubjectHash: String)
}

/** This boundary has no session token, HTTP, route or learning database dependency. */
class FormationPayloadCipher(
    private val keys: FormationPayloadKeys,
    private val maxPlaintextBytes: Int = 1024 * 1024,
) {
    init { require(maxPlaintextBytes in 1..16 * 1024 * 1024) }
    internal val maxEnvelopeBytes: Long get() = maxPlaintextBytes.toLong() + 1024

    /** Only the explicitly authorized account cleanup path may invoke this after removing its ciphertexts. */
    internal fun deleteAccountKey(accountSubjectHash: String) {
        require(validFormationAccountHash(accountSubjectHash))
        keys.deleteAccount(accountSubjectHash)
        check(keys.existing(accountSubjectHash, KEY_VERSION) == null)
    }

    fun encrypt(identity: FormationPayloadIdentity, plaintext: ByteArray): ByteArray {
        require(identity.isValid())
        require(plaintext.size <= maxPlaintextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.createIfAbsent(identity.owner.accountSubjectHash, KEY_VERSION))
        check(cipher.iv.size == IV_BYTES)
        cipher.updateAAD(authenticatedHeader(identity))
        val encrypted = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(authenticatedHeader(identity))
                output.writeInt(cipher.iv.size)
                output.write(cipher.iv)
                output.writeInt(encrypted.size)
                output.write(encrypted)
            }
            bytes.toByteArray()
        }
    }

    fun decrypt(identity: FormationPayloadIdentity, envelope: ByteArray): ByteArray? = safely {
        require(identity.isValid())
        val parsed = parse(envelope)
        require(parsed.identity == identity)
        // Reading never replaces a missing/invalidated key and never clears unreadable ciphertext.
        val key = keys.existing(identity.owner.accountSubjectHash, KEY_VERSION) ?: return@safely null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, parsed.iv))
        cipher.updateAAD(authenticatedHeader(identity))
        cipher.doFinal(parsed.encrypted).also { require(it.size <= maxPlaintextBytes) }
    }

    /** Header only, not authentication. Callers must decrypt before trusting an index entry. */
    fun metadata(envelope: ByteArray): FormationEnvelopeMetadata? = safely {
        FormationEnvelopeMetadata(parse(envelope).identity)
    }

    private fun parse(envelope: ByteArray): Parsed {
        require(envelope.size.toLong() <= maxEnvelopeBytes)
        return DataInputStream(ByteArrayInputStream(envelope)).use { input ->
            require(input.readInt() == MAGIC)
            require(input.readInt() == ENVELOPE_VERSION)
            require(input.readInt() == KEY_VERSION)
            val payloadVersion = input.readInt()
            val revision = input.readLong()
            val account = input.readBoundedAscii(64)
            val source = input.readBoundedAscii(256)
            val identity = FormationPayloadIdentity(FormationPayloadOwner(account, source), revision, payloadVersion)
            require(identity.isValid())
            require(input.readInt() == IV_BYTES)
            val iv = ByteArray(IV_BYTES).also(input::readFully)
            val encryptedSize = input.readInt()
            require(encryptedSize in TAG_BYTES..maxPlaintextBytes + TAG_BYTES)
            require(input.available() == encryptedSize)
            Parsed(identity, iv, ByteArray(encryptedSize).also(input::readFully))
        }
    }

    private fun authenticatedHeader(identity: FormationPayloadIdentity): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(ENVELOPE_VERSION)
                output.writeInt(KEY_VERSION)
                output.writeInt(identity.payloadVersion)
                output.writeLong(identity.revision)
                output.writeAscii(identity.owner.accountSubjectHash)
                output.writeAscii(identity.owner.sourceEntryId)
            }
            bytes.toByteArray()
        }

    private fun DataInputStream.readBoundedAscii(maxBytes: Int): String {
        val size = readInt()
        require(size in 1..maxBytes && size <= available())
        val bytes = ByteArray(size).also(::readFully)
        require(bytes.all { it.toInt() in 0..127 })
        return String(bytes, Charsets.US_ASCII)
    }

    private fun DataOutputStream.writeAscii(value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun <T> safely(block: () -> T?): T? = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private data class Parsed(val identity: FormationPayloadIdentity, val iv: ByteArray, val encrypted: ByteArray)
    private companion object {
        // RLFP is purpose-specific and is also authenticated; no session cipher compatibility.
        const val MAGIC = 0x524c4650
        const val ENVELOPE_VERSION = 1
        const val KEY_VERSION = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal fun validFormationAccountHash(value: String): Boolean = value.matches(Regex("[a-f0-9]{64}"))
internal fun FormationPayloadOwner.isValid(): Boolean = validFormationAccountHash(accountSubjectHash) &&
    sourceEntryId.matches(Regex("[A-Za-z0-9][A-Za-z0-9:._-]{0,255}"))
internal fun FormationPayloadIdentity.isValid(): Boolean = owner.isValid() && revision >= 0 && payloadVersion == 1
