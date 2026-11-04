package com.reallanguage.app.core.vocabulary

import android.content.Context
import java.security.MessageDigest

class AndroidVocabularyPoolPreference(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun read(accountId: String): VocabularyPool? = preferences
        .getString(accountId.key(), null)
        ?.let { stored -> runCatching { VocabularyPool.valueOf(stored) }.getOrNull() }

    fun write(accountId: String, poolName: String): Boolean {
        val pool = runCatching { VocabularyPool.valueOf(poolName) }.getOrNull() ?: return false
        return preferences.edit().putString(accountId.key(), pool.name).commit()
    }

    fun clear(accountId: String): Boolean = preferences.edit().remove(accountId.key()).commit()

    private fun String.key(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val PREFERENCES_NAME = "vocabulary-pool-v1"
    }
}
