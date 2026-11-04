package com.reallanguage.app.feature.error.domain

enum class ServiceFailureCategory {
    AI_OR_SERVICE,
    RECORDING_TRANSCRIPTION,
}

data class ServiceFailureContext(
    val stepId: String,
    val stepLabelZh: String,
    val preservedDataLabelZh: String,
    val category: ServiceFailureCategory,
)

sealed interface FailedStepRetryResult {
    data object Started : FailedStepRetryResult
    data object Failed : FailedStepRetryResult
}

interface FailedStepRetryRepository {
    suspend fun retryStep(stepId: String, commandId: String): FailedStepRetryResult
}
