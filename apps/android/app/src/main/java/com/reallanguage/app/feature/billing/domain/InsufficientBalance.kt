package com.reallanguage.app.feature.billing.domain

enum class BalanceOriginState {
    NORMAL_LEARNING,
    UNIFIED_REVIEW,
    UNFIXED_DIARY_OR_AI_SCENE,
    RECORDING_AWAITING_TRANSCRIPTION,
    FIXED_UNSTARTED_CHAPTER,
}

enum class BalanceExitDisposition {
    SAVE_CURRENT_STEP,
    KEEP_COMPLETED_RESTART_CURRENT,
    DISCARD_DRAFT,
    KEEP_LOCAL_RECORDING,
    KEEP_FIXED_CHAPTER,
}

data class BalanceInsufficientContext(
    val originId: String,
    val originState: BalanceOriginState,
) {
    val exitDisposition: BalanceExitDisposition
        get() = when (originState) {
            BalanceOriginState.NORMAL_LEARNING -> BalanceExitDisposition.SAVE_CURRENT_STEP
            BalanceOriginState.UNIFIED_REVIEW -> BalanceExitDisposition.KEEP_COMPLETED_RESTART_CURRENT
            BalanceOriginState.UNFIXED_DIARY_OR_AI_SCENE -> BalanceExitDisposition.DISCARD_DRAFT
            BalanceOriginState.RECORDING_AWAITING_TRANSCRIPTION -> BalanceExitDisposition.KEEP_LOCAL_RECORDING
            BalanceOriginState.FIXED_UNSTARTED_CHAPTER -> BalanceExitDisposition.KEEP_FIXED_CHAPTER
        }
}

sealed interface BalanceExitResult {
    data object Applied : BalanceExitResult
    data object AlreadyApplied : BalanceExitResult
    data object Failed : BalanceExitResult
}

interface BalanceInsufficientRepository {
    suspend fun applyExitPolicy(
        originId: String,
        disposition: BalanceExitDisposition,
        commandId: String,
    ): BalanceExitResult
}
