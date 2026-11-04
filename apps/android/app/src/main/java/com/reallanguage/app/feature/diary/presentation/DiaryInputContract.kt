package com.reallanguage.app.feature.diary.presentation

enum class DiaryInputError {
    TOO_MANY_SENTENCES,
    TOO_LONG,
}

enum class DiaryDictationState { IDLE, RECORDING, RECOGNIZING, FAILED }

data class DiaryInputUiState(
    val text: String = "",
    val submitEnabled: Boolean = false,
    val submitting: Boolean = false,
    val error: DiaryInputError? = null,
    val showDiscardConfirmation: Boolean = false,
    val selectionStart: Int = text.length,
    val selectionEnd: Int = selectionStart,
    val dictationState: DiaryDictationState = DiaryDictationState.IDLE,
    val dictationElapsedSeconds: Int = 0,
)

sealed interface DiaryInputAction {
    data class TextChanged(
        val value: String,
        val selectionStart: Int = value.length,
        val selectionEnd: Int = selectionStart,
    ) : DiaryInputAction
    data object DictationHoldStarted : DiaryInputAction
    data object DictationReleased : DiaryInputAction
    data object DictationInterrupted : DiaryInputAction
    data object DictationPermissionDenied : DiaryInputAction
    data object BackRequested : DiaryInputAction
    data object SubmitRequested : DiaryInputAction
    data object ContinueEditingRequested : DiaryInputAction
    data object DiscardConfirmed : DiaryInputAction
}

sealed interface DiaryInputEffect {
    data object OpenHome : DiaryInputEffect
    data class OpenSafetyCheck(val confirmedText: String) : DiaryInputEffect
}

enum class DiaryInputValidity {
    EMPTY,
    VALID,
    TOO_MANY_SENTENCES,
    TOO_LONG,
}

fun interface DiaryInputValidator {
    fun validate(text: String): DiaryInputValidity
}

object ConfirmedDiaryInputValidator : DiaryInputValidator {
    private val sentenceBoundary = Regex("[。！？!?]+")

    override fun validate(text: String): DiaryInputValidity {
        if (text.isBlank()) return DiaryInputValidity.EMPTY
        if (text.length > MAX_CHARACTERS) return DiaryInputValidity.TOO_LONG
        val sentenceCount = text
            .split(sentenceBoundary)
            .count { it.isNotBlank() }
        return if (sentenceCount <= 10) {
            DiaryInputValidity.VALID
        } else {
            DiaryInputValidity.TOO_MANY_SENTENCES
        }
    }

    const val MAX_CHARACTERS = 300
}
