package com.reallanguage.app.feature.learning.domain

data class LearningVoicePreferences(
    val voiceEnabled: Boolean,
    val autoPlayEnabled: Boolean,
    val speedLevel: Int,
) {
    init { require(speedLevel in 1..5 && (!autoPlayEnabled || voiceEnabled)) }
    val speechRate: Float get() = listOf(.65f, .82f, 1f, 1.2f, 1.4f)[speedLevel - 1]
}

fun interface LearningVoicePreferencesProvider {
    /** Null is a loading failure, never permission to silently turn voice on. */
    suspend fun load(): LearningVoicePreferences?
}
