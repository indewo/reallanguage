package com.reallanguage.app.core.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EncryptedSessionCredentialStoreTest {
    @Test fun roundTripStoresNoPlaintextCredentialFields() = runTest {
        val preferences = FakeEncryptedPreferences()
        val store = EncryptedSessionCredentialStore(preferences, ReversingCipher())
        val session = StoredSession("account-1", "private-refresh-token", true)

        store.save(session)

        assertFalse(preferences.value!!.contains("private-refresh-token"))
        assertEquals(session, store.load())
    }

    @Test fun clearingRemovesTheEncryptedSession() = runTest {
        val preferences = FakeEncryptedPreferences("ciphertext")
        val store = EncryptedSessionCredentialStore(preferences, ReversingCipher())
        store.clear()
        assertNull(preferences.value)
    }

    @Test fun unreadableCredentialDataIsRemovedAndTreatedAsSignedOut() = runTest {
        val preferences = FakeEncryptedPreferences("broken")
        val store = EncryptedSessionCredentialStore(
            preferences,
            object : SessionCipher {
                override fun encrypt(plaintext: ByteArray) = "unused"
                override fun decrypt(ciphertext: String): ByteArray = error("invalid key")
            },
        )

        assertNull(store.load())
        assertNull(preferences.value)
    }
}

private class FakeEncryptedPreferences(var value: String? = null) : EncryptedSessionPreferences {
    override fun read(): String? = value
    override fun write(value: String) { this.value = value }
    override fun remove() { value = null }
}

private class ReversingCipher : SessionCipher {
    override fun encrypt(plaintext: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(plaintext.reversedArray())

    override fun decrypt(ciphertext: String): ByteArray =
        java.util.Base64.getDecoder().decode(ciphertext).reversedArray()
}
