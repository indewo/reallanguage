package com.reallanguage.app.feature.safety.domain

data class SafetyEdit(
    val originalText: String,
    val replacementText: String,
    val explanationZh: String,
)

data class SafetyCaseDetails(
    val safetyCaseId: String,
    val originalSource: ConfirmedSource,
    val userReasonZh: String,
    val deidentifiedSource: ConfirmedSource,
    val edits: List<SafetyEdit>,
)

enum class SafetyHandlingCommandResult {
    Completed,
    Failed,
}

enum class ManualReviewSubmissionStatus { PENDING, APPROVED, NOT_APPROVED }

sealed interface ManualReviewSubmissionResult {
    data class Submitted(val reviewRequestId: String, val reviewStatus: ManualReviewSubmissionStatus = ManualReviewSubmissionStatus.PENDING) : ManualReviewSubmissionResult
    data object Failed : ManualReviewSubmissionResult
}

interface SafetyHandlingRepository {
    suspend fun acceptDeidentified(safetyCaseId: String): SafetyHandlingCommandResult
    suspend fun returnToEdit(safetyCaseId: String): SafetyHandlingCommandResult
    suspend fun submitManualReview(safetyCaseId: String): ManualReviewSubmissionResult
}

sealed interface SafetyHandlingDetailsResult {
    data class Ready(val details: SafetyCaseDetails) : SafetyHandlingDetailsResult
    data object Failed : SafetyHandlingDetailsResult
}

fun interface SafetyHandlingDetailsRepository {
    suspend fun load(safetyCaseId: String): SafetyHandlingDetailsResult
}
