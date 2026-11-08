package com.reallanguage.app.feature.home.presentation

data class HomeUiState(
    val hasDueReview: Boolean = false,
    val hasIncompleteLearning: Boolean = false,
)

sealed interface HomeAction {
    data object MessagesRequested : HomeAction
    data object SettingsRequested : HomeAction
    data object AccountRequested : HomeAction
    data object DiaryRequested : HomeAction
    data object AiSceneRequested : HomeAction
    data object QuickRecordingRequested : HomeAction
    data object ScreenshotImportRequested : HomeAction
    data object AudioImportRequested : HomeAction
    data object DueReviewRequested : HomeAction
    data object IncompleteLearningRequested : HomeAction
    data object VocabularyRequested : HomeAction
    data object SentencePatternsRequested : HomeAction
}
