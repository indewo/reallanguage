package com.reallanguage.app.core.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidEncryptedSessionPreferences(context: Context) : EncryptedSessionPreferences {
    private val preferences = context.applicationContext.getSharedPreferences(
        SESSION_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    override fun read(): String? = preferences.getString(SESSION_VALUE, null)

    override fun write(value: String) {
        check(preferences.edit().putString(SESSION_VALUE, value).commit()) {
            "Unable to persist session credential"
        }
    }

    override fun remove() {
        check(preferences.edit().remove(SESSION_VALUE).commit()) {
            "Unable to clear session credential"
        }
    }

    private companion object {
        const val SESSION_PREFERENCES = "reallanguage_secure_session"
        const val SESSION_VALUE = "encrypted_session_v1"
    }
}

class AndroidKeystoreSessionCipher : SessionCipher {
    override fun encrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext)
        val envelope = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(ENVELOPE_VERSION)
                output.writeInt(cipher.iv.size)
                output.write(cipher.iv)
                output.writeInt(encrypted.size)
                output.write(encrypted)
            }
            bytes.toByteArray()
        }
        return Base64.getEncoder().encodeToString(envelope)
    }

    override fun decrypt(ciphertext: String): ByteArray {
        val envelope = Base64.getDecoder().decode(ciphertext)
        val (iv, encrypted) = DataInputStream(ByteArrayInputStream(envelope)).use { input ->
            check(input.readInt() == ENVELOPE_VERSION) { "Unsupported cipher envelope" }
            val iv = ByteArray(input.readInt().also { check(it in 12..32) }).also(input::readFully)
            val encrypted = ByteArray(
                input.readInt().also { check(it in 1..MAX_ENCRYPTED_SESSION_BYTES) },
            ).also(input::readFully)
            check(input.available() == 0) { "Unexpected cipher envelope data" }
            iv to encrypted
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "reallanguage.session.refresh.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val ENVELOPE_VERSION = 1
        const val MAX_ENCRYPTED_SESSION_BYTES = 16 * 1024
    }
}

class AndroidInstallationIdentity(context: Context) : InstallationIdentity {
    private val preferences = context.applicationContext.getSharedPreferences(
        INSTALLATION_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    override fun value(): String = synchronized(preferences) {
        preferences.getString(INSTALLATION_ID, null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString(INSTALLATION_ID, it).commit()) {
                "Unable to persist installation identity"
            }
        }
    }

    private companion object {
        const val INSTALLATION_PREFERENCES = "reallanguage_installation"
        const val INSTALLATION_ID = "installation_id_v1"
    }
}

fun androidSessionCredentialStore(context: Context): SessionCredentialStore =
    EncryptedSessionCredentialStore(
        preferences = AndroidEncryptedSessionPreferences(context),
        cipher = AndroidKeystoreSessionCipher(),
    )
