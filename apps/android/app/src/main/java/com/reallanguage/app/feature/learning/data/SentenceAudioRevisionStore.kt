package com.reallanguage.app.feature.learning.data

import android.content.Context

/** Only account/text-hashed request metadata. No audio, text or session secrets. */
class SentenceAudioRevisionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("sentence_audio_user_retries", Context.MODE_PRIVATE)

    private fun current(scope: String): Pair<Int, Boolean> {
        val generation = preferences.getInt("generation:$scope", if (preferences.getBoolean(scope, false)) 1 else 0)
        require(generation in 0..10000)
        // Legacy generations did not record whether their POST completed. Replay
        // the existing identity before authorizing any newer paid generation.
        val pending = preferences.getBoolean("pending:$scope", generation > 0)
        require(!pending || generation > 0)
        return generation to pending
    }

    fun request(scope: String, regenerate: Boolean): Int = synchronized(lock) {
        val (generation, pending) = current(scope)
        if (!regenerate || pending) generation else {
            val next = generation + 1
            require(next <= 10000)
            check(preferences.edit().putInt("generation:$scope", next).putBoolean("pending:$scope", true).commit())
            next
        }
    }

    /** A validated successful POST proves delivery, even if downloading its bytes fails. */
    fun delivered(scope: String, generation: Int) = synchronized(lock) {
        val (latest, pending) = current(scope)
        check(latest == generation)
        if (pending) check(preferences.edit().putBoolean("pending:$scope", false).commit())
    }

    private companion object { val lock = Any() }
}
