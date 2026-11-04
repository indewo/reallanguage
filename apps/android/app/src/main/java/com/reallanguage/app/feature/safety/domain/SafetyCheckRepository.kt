package com.reallanguage.app.feature.safety.domain

enum class SourceType {
    DIARY,
    RECORDING,
    AI_SCENE,
}

enum class SourceContentShape {
    ARTICLE,
    DIALOGUE,
}

data class ConfirmedSource(
    val text: String,
    val sourceType: SourceType,
    val contentShape: SourceContentShape,
)

sealed interface SafetyCheckResult {
    data object Passed : SafetyCheckResult
    data class Blocked(val safetyCaseId: String) : SafetyCheckResult
    data object Failed : SafetyCheckResult
}

fun interface SafetyCheckRepository {
    suspend fun check(source: ConfirmedSource): SafetyCheckResult
}
