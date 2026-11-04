package com.reallanguage.app.feature.diary.domain

sealed interface DiaryDictationResult {
    data class Recognized(val transcript: String) : DiaryDictationResult
    data object Failed : DiaryDictationResult
}

interface DiaryDictationRepository {
    fun start(): Boolean
    suspend fun finish(commandId: String): DiaryDictationResult
    fun cancel()
}

object UnavailableDiaryDictationRepository : DiaryDictationRepository {
    override fun start() = false
    override suspend fun finish(commandId: String) = DiaryDictationResult.Failed
    override fun cancel() = Unit
}
