package com.reallanguage.app.development

import androidx.compose.runtime.Composable
import com.reallanguage.app.feature.home.presentation.IncompleteLearningScreen
import com.reallanguage.app.feature.home.presentation.IncompleteLearningUiState
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.PatternRecord
import com.reallanguage.app.feature.knowledge.presentation.PatternBookScreen
import com.reallanguage.app.feature.knowledge.presentation.PatternBookUiState
import com.reallanguage.app.feature.knowledge.presentation.VocabularyBookScreen
import com.reallanguage.app.feature.knowledge.presentation.VocabularyBookUiState
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import com.reallanguage.app.feature.learning.presentation.LearningExitDialog
import com.reallanguage.app.feature.learning.presentation.LearningExitUiState
import com.reallanguage.app.feature.review.presentation.TodayReviewScreen
import com.reallanguage.app.feature.review.presentation.TodayReviewUiState
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import com.reallanguage.app.feature.settings.domain.SettingsSourceContext
import com.reallanguage.app.feature.settings.presentation.SettingsEffectScopeScreen
import com.reallanguage.app.feature.settings.presentation.SettingsEffectScopeUiState

/** Finite screenshot-only states. No repositories, ViewModels, production routes or writes. */
internal val remainingBoundaryDestinations: Set<String> = setOf(
    "UNFINISHED_LEARNING_FAILED",
    "UNFINISHED_LEARNING_EMPTY",
    "REVIEW_TODAY_FAILED",
    "REVIEW_TODAY_EMPTY",
    "VOCABULARY_BOOK_FAILED",
    "VOCABULARY_BOOK_EMPTY",
    "VOCABULARY_BOOK_NO_RESULTS",
    "PATTERN_BOOK_FAILED",
    "PATTERN_BOOK_EMPTY",
    "PATTERN_BOOK_NO_RESULTS",
    "LEARNING_EXIT_SAVE_FAILED",
    "SETTINGS_EFFECT_NO_CURRENT",
)

@Composable
internal fun RemainingBoundaryPreview(destinationName: String) {
    require(destinationName in remainingBoundaryDestinations) {
        "Unknown remaining-boundary preview: $destinationName"
    }
    when (destinationName) {
        "UNFINISHED_LEARNING_FAILED" -> IncompleteLearningScreen(
            IncompleteLearningUiState(loading = false, loadFailed = true), {},
        )
        "UNFINISHED_LEARNING_EMPTY" -> IncompleteLearningScreen(
            IncompleteLearningUiState(loading = false), {},
        )
        "REVIEW_TODAY_FAILED" -> TodayReviewScreen(
            TodayReviewUiState(loading = false, loadFailed = true), {},
        )
        "REVIEW_TODAY_EMPTY" -> TodayReviewScreen(
            TodayReviewUiState(loading = false), {},
        )
        "VOCABULARY_BOOK_FAILED" -> VocabularyBookScreen(
            VocabularyBookUiState(currentScope = "六级单词", loading = false, loadFailed = true), {},
        )
        "VOCABULARY_BOOK_EMPTY" -> VocabularyBookScreen(
            VocabularyBookUiState(currentScope = "六级单词", loading = false), {},
        )
        "VOCABULARY_BOOK_NO_RESULTS" -> VocabularyBookScreen(
            VocabularyBookUiState(
                currentScope = "六级单词", loading = false,
                query = "unmatched-preview-word", appliedQuery = "unmatched-preview-word",
            ), {},
        )
        "PATTERN_BOOK_FAILED" -> PatternBookScreen(
            PatternBookUiState(loading = false, loadFailed = true), {},
        )
        "PATTERN_BOOK_EMPTY" -> PatternBookScreen(
            PatternBookUiState(loading = false), {},
        )
        "PATTERN_BOOK_NO_RESULTS" -> PatternBookScreen(
            PatternBookUiState(
                loading = false,
                query = "没有匹配的查询",
                // Keep source data nonempty so this exercises the actual filtered-empty branch.
                patterns = listOf(
                    PatternRecord(
                        patternId = "boundary-preview-pattern",
                        structureEn = "work up the courage to + 动词",
                        functionZh = "鼓起勇气做某事",
                        writingStatus = KnowledgeStatus.NOT_STARTED,
                        speakingStatus = KnowledgeStatus.NOT_STARTED,
                        consecutiveSuccessCount = 0,
                        occurrenceCount = 1,
                    ),
                ),
            ), {},
        )
        "LEARNING_EXIT_SAVE_FAILED" -> LearningExitDialog(
            LearningExitUiState(
                checkpoint = OrdinaryLearningCheckpoint("preview-c1", "LR-09", "round-2"),
                saveFailed = true,
            ), {},
        )
        "SETTINGS_EFFECT_NO_CURRENT" -> {
            val original = InitialSettingsDraft(formalityLevel = 3, basicVocabulary = BasicVocabulary.CET_6)
            SettingsEffectScopeScreen(
                SettingsEffectScopeUiState(
                    originalSettings = original,
                    proposedSettings = original.copy(formalityLevel = 4),
                    sourceContext = SettingsSourceContext.NoCurrentContent,
                ), {},
            )
        }
    }
}
