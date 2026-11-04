package com.reallanguage.app.core.session

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

interface EncryptedSessionPreferences {
    fun read(): String?
    fun write(value: String)
    fun remove()
}

interface SessionCipher {
    fun encrypt(plaintext: ByteArray): String
    fun decrypt(ciphertext: String): ByteArray
}

class EncryptedSessionCredentialStore(
    private val preferences: EncryptedSessionPreferences,
    private val cipher: SessionCipher,
) : SessionCredentialStore {
    override suspend fun load(): StoredSession? {
        val ciphertext = preferences.read() ?: return null
        return try {
            decode(cipher.decrypt(ciphertext))
        } catch (_: Exception) {
            preferences.remove()
            null
        }
    }

    override suspend fun save(session: StoredSession) {
        preferences.write(cipher.encrypt(encode(session)))
    }

    override suspend fun clear() {
        preferences.remove()
    }

    private fun encode(session: StoredSession): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeUTF(session.accountId)
                output.writeUTF(session.refreshToken)
                output.writeBoolean(session.settingsCompleted)
            }
            bytes.toByteArray()
        }

    private fun decode(bytes: ByteArray): StoredSession =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readInt() == FORMAT_VERSION) { "Unsupported session format" }
            val session = StoredSession(
                accountId = input.readUTF(),
                refreshToken = input.readUTF(),
                settingsCompleted = input.readBoolean(),
            )
            check(session.accountId.isNotBlank() && session.refreshToken.isNotBlank()) {
                "Invalid session credential"
            }
            check(input.available() == 0) { "Unexpected session data" }
            session
        }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}
