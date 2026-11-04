package com.reallanguage.app.development

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.feature.auth.presentation.AgeIneligibleScreen
import com.reallanguage.app.feature.auth.presentation.AuthAction
import com.reallanguage.app.feature.auth.presentation.AuthMode
import com.reallanguage.app.feature.auth.presentation.AuthScreen
import com.reallanguage.app.feature.auth.presentation.AuthUiState
import com.reallanguage.app.feature.auth.presentation.EmailVerificationScreen
import com.reallanguage.app.feature.auth.presentation.EmailVerificationStatus
import com.reallanguage.app.feature.auth.presentation.EmailVerificationUiState
import com.reallanguage.app.feature.home.presentation.HomeScreen
import com.reallanguage.app.feature.home.presentation.HomeAction
import com.reallanguage.app.feature.home.presentation.HomeUiState
import com.reallanguage.app.feature.home.presentation.DailyReminderIntroDialog
import com.reallanguage.app.feature.home.presentation.DailyReminderIntroUiState
import com.reallanguage.app.feature.home.domain.IncompleteLearningEntry
import com.reallanguage.app.feature.home.domain.OrdinarySourceType
import com.reallanguage.app.feature.home.domain.ResumeLearningKind
import com.reallanguage.app.feature.home.presentation.IncompleteLearningScreen
import com.reallanguage.app.feature.home.presentation.IncompleteLearningUiState
import com.reallanguage.app.feature.home.domain.AppMessage
import com.reallanguage.app.feature.home.domain.AppMessageTarget
import com.reallanguage.app.feature.home.domain.MessageKind
import com.reallanguage.app.feature.home.domain.MessageTargetState
import com.reallanguage.app.feature.home.presentation.MessagesScreen
import com.reallanguage.app.feature.home.presentation.MessagesUiState
import com.reallanguage.app.feature.home.domain.AccountSnapshot
import com.reallanguage.app.feature.home.presentation.AccountScreen
import com.reallanguage.app.feature.home.presentation.AccountUiState
import com.reallanguage.app.feature.recording.presentation.RecordingIntroScreen
import com.reallanguage.app.feature.recording.presentation.RecordingIntroUiState
import com.reallanguage.app.feature.recording.presentation.ActiveRecordingScreen
import com.reallanguage.app.feature.recording.presentation.ActiveRecordingUiState
import com.reallanguage.app.feature.recording.domain.LocalRecordingSummary
import com.reallanguage.app.feature.recording.presentation.RecordingConfirmationScreen
import com.reallanguage.app.feature.recording.presentation.RecordingConfirmationUiState
import com.reallanguage.app.feature.recording.presentation.RecordingDeletionDialog
import com.reallanguage.app.feature.recording.presentation.RecordingDeletionUiState
import com.reallanguage.app.feature.recording.domain.RecordingEndReason
import com.reallanguage.app.feature.recording.domain.TranscriptionRequest
import com.reallanguage.app.feature.recording.presentation.RecordingTranscriptionScreen
import com.reallanguage.app.feature.recording.presentation.RecordingTranscriptionUiState
import com.reallanguage.app.feature.recording.domain.RecordingTranscript
import com.reallanguage.app.feature.recording.domain.RepairChange
import com.reallanguage.app.feature.recording.domain.RepairReason
import com.reallanguage.app.feature.recording.domain.TranscriptRepairResult
import com.reallanguage.app.feature.recording.presentation.TranscriptResultScreen
import com.reallanguage.app.feature.recording.presentation.TranscriptResultUiState
import com.reallanguage.app.feature.recording.presentation.TranscriptionFailureScreen
import com.reallanguage.app.feature.recording.presentation.TranscriptionFailureUiState
import com.reallanguage.app.feature.recording.presentation.MicrophonePermissionScreen
import com.reallanguage.app.feature.recording.presentation.MicrophonePermissionUiState
import com.reallanguage.app.feature.scene.domain.RecentSceneRecommendation
import com.reallanguage.app.feature.scene.presentation.SceneHomeScreen
import com.reallanguage.app.feature.scene.presentation.SceneHomeUiState
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneStart
import com.reallanguage.app.feature.scene.presentation.SceneTypeScreen
import com.reallanguage.app.feature.scene.presentation.SceneTypeUiState
import com.reallanguage.app.feature.scene.domain.SceneAnswer
import com.reallanguage.app.feature.scene.domain.SceneQuestion
import com.reallanguage.app.feature.scene.domain.SceneQuestionSession
import com.reallanguage.app.feature.scene.presentation.AnsweredSceneQuestion
import com.reallanguage.app.feature.scene.presentation.SceneQuestionsScreen
import com.reallanguage.app.feature.scene.presentation.SceneQuestionsUiState
import com.reallanguage.app.feature.scene.presentation.SceneAbandonDialog
import com.reallanguage.app.feature.scene.presentation.SceneAbandonUiState
import com.reallanguage.app.feature.scene.presentation.SceneGenerationScreen
import com.reallanguage.app.feature.scene.presentation.SceneGenerationUiState
import com.reallanguage.app.feature.scene.domain.SceneDraftUnit
import com.reallanguage.app.feature.scene.domain.SceneSpeaker
import com.reallanguage.app.feature.scene.presentation.SceneChineseReviewScreen
import com.reallanguage.app.feature.scene.presentation.SceneChineseReviewUiState
import com.reallanguage.app.feature.diary.presentation.DiaryInputScreen
import com.reallanguage.app.feature.diary.presentation.DiaryInputUiState
import com.reallanguage.app.feature.diary.presentation.DiaryInputAction
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import com.reallanguage.app.feature.settings.domain.PurposeVocabulary
import com.reallanguage.app.feature.settings.presentation.InitialSettingsScreen
import com.reallanguage.app.feature.settings.presentation.InitialSettingsStep
import com.reallanguage.app.feature.settings.presentation.InitialSettingsUiState
import com.reallanguage.app.feature.settings.presentation.SubsequentSettingsScreen
import com.reallanguage.app.feature.settings.presentation.SubsequentSettingsUiState
import com.reallanguage.app.feature.settings.domain.SettingsSourceContext
import com.reallanguage.app.feature.settings.presentation.SettingsEffectScopeScreen
import com.reallanguage.app.feature.settings.presentation.SettingsEffectScopeUiState
import com.reallanguage.app.feature.formation.domain.SemanticUnit
import com.reallanguage.app.feature.formation.presentation.FocusSelectionScreen
import com.reallanguage.app.feature.formation.presentation.FocusSelectionUiState
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.CandidateExample
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.QueryableExpression
import com.reallanguage.app.feature.formation.domain.SourceEvidence
import com.reallanguage.app.feature.formation.presentation.EnglishConfirmationScreen
import com.reallanguage.app.feature.formation.presentation.EnglishConfirmationUiState
import com.reallanguage.app.feature.formation.presentation.LearningTargetScreen
import com.reallanguage.app.feature.formation.presentation.LearningTargetUiState
import com.reallanguage.app.feature.formation.presentation.FinalFixScreen
import com.reallanguage.app.feature.formation.presentation.FinalFixUiState
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.presentation.ChapterLearningFrame
import com.reallanguage.app.feature.learning.presentation.ChapterEntryScreen
import com.reallanguage.app.feature.learning.presentation.ChapterEntryUiState
import com.reallanguage.app.feature.learning.domain.SelectableExpression
import com.reallanguage.app.feature.learning.domain.ExpressionExplanation
import com.reallanguage.app.feature.learning.domain.SelfAssessmentAbility
import com.reallanguage.app.feature.learning.domain.SelfAssessmentTarget
import com.reallanguage.app.feature.learning.presentation.ExplanationReadingScreen
import com.reallanguage.app.feature.learning.presentation.ExplanationReadingUiState
import com.reallanguage.app.feature.learning.presentation.TargetSelfAssessmentControls
import com.reallanguage.app.feature.learning.presentation.TargetSelfAssessmentUiState
import com.reallanguage.app.feature.learning.presentation.ReadSpeakScreen
import com.reallanguage.app.feature.learning.presentation.ReadSpeakUiState
import com.reallanguage.app.feature.learning.presentation.HandwritingCopyScreen
import com.reallanguage.app.feature.learning.presentation.HandwritingCopyUiState
import com.reallanguage.app.feature.learning.presentation.AudioDictationScreen
import com.reallanguage.app.feature.learning.presentation.AudioDictationUiState
import com.reallanguage.app.feature.learning.presentation.FullSentenceSpeakingScreen
import com.reallanguage.app.feature.learning.presentation.FullSentenceSpeakingUiState
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeExample
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingScreen
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingStage
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingUiState
import com.reallanguage.app.feature.learning.domain.LockedWritingTarget
import com.reallanguage.app.feature.learning.domain.WritingTargetType
import com.reallanguage.app.feature.learning.presentation.CompleteWritingPhase
import com.reallanguage.app.feature.learning.presentation.CompleteWritingScreen
import com.reallanguage.app.feature.learning.presentation.CompleteWritingUiState
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.SceneDialogueLine
import com.reallanguage.app.feature.learning.presentation.SceneResponseScreen
import com.reallanguage.app.feature.learning.presentation.SceneResponseUiState
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import com.reallanguage.app.feature.learning.presentation.LearningExitDialog
import com.reallanguage.app.feature.learning.presentation.LearningExitUiState
import com.reallanguage.app.feature.learning.presentation.LearningExitAction
import com.reallanguage.app.feature.learning.domain.ChapterArchiveSummary
import com.reallanguage.app.feature.learning.domain.ChapterResultCategory
import com.reallanguage.app.feature.learning.domain.ChapterResultSummaryItem
import com.reallanguage.app.feature.learning.presentation.ChapterResultScreen
import com.reallanguage.app.feature.learning.presentation.ChapterResultUiState
import com.reallanguage.app.feature.learning.domain.RemainingChapterSummary
import com.reallanguage.app.feature.learning.presentation.ChapterSelectionScreen
import com.reallanguage.app.feature.learning.presentation.ChapterSelectionUiState
import com.reallanguage.app.feature.learning.presentation.ChapterAbandonScreen
import com.reallanguage.app.feature.learning.presentation.ChapterAbandonUiState
import com.reallanguage.app.feature.learning.domain.CompletedStepSnapshot
import com.reallanguage.app.feature.learning.presentation.CompletedStepReviewScreen
import com.reallanguage.app.feature.learning.presentation.CompletedStepReviewUiState
import com.reallanguage.app.feature.review.domain.ReviewChapterSummary
import com.reallanguage.app.feature.review.domain.ReviewSourceType
import com.reallanguage.app.feature.review.presentation.TodayReviewScreen
import com.reallanguage.app.feature.review.presentation.TodayReviewUiState
import com.reallanguage.app.feature.review.domain.ReviewAbility
import com.reallanguage.app.feature.review.domain.ReviewAbilityModule
import com.reallanguage.app.feature.review.domain.ReviewAbilityStatus
import com.reallanguage.app.feature.review.domain.ReviewChapterSnapshot
import com.reallanguage.app.feature.review.presentation.ReviewChapterScreen
import com.reallanguage.app.feature.review.presentation.ReviewChapterUiState
import com.reallanguage.app.feature.review.presentation.ReviewExitDialog
import com.reallanguage.app.feature.review.presentation.ReviewExitUiState
import com.reallanguage.app.feature.review.domain.ReviewResultSummary
import com.reallanguage.app.feature.review.presentation.ReviewResultScreen
import com.reallanguage.app.feature.review.presentation.ReviewResultUiState
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyEntry
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
import com.reallanguage.app.feature.knowledge.domain.VocabularyExample
import com.reallanguage.app.feature.knowledge.presentation.VocabularyBookScreen
import com.reallanguage.app.feature.knowledge.presentation.VocabularyBookUiState
import com.reallanguage.app.feature.knowledge.domain.PatternRecord
import com.reallanguage.app.feature.knowledge.domain.PatternExample
import com.reallanguage.app.feature.knowledge.presentation.PatternBookScreen
import com.reallanguage.app.feature.knowledge.presentation.PatternBookUiState
import com.reallanguage.app.feature.knowledge.presentation.PatternRemovalDialog
import com.reallanguage.app.feature.knowledge.presentation.PatternRemovalUiState
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshot
import com.reallanguage.app.feature.billing.domain.AiChargeRecord
import com.reallanguage.app.feature.billing.domain.AiChargeType
import com.reallanguage.app.feature.billing.presentation.AiBillingScreen
import com.reallanguage.app.feature.billing.presentation.AiBillingUiState
import com.reallanguage.app.feature.billing.domain.PaymentMethod
import com.reallanguage.app.feature.billing.domain.RechargeOption
import com.reallanguage.app.feature.billing.presentation.RechargeScreen
import com.reallanguage.app.feature.billing.presentation.RechargeUiState
import com.reallanguage.app.feature.billing.domain.BalanceInsufficientContext
import com.reallanguage.app.feature.billing.domain.BalanceOriginState
import com.reallanguage.app.feature.billing.presentation.InsufficientBalanceScreen
import com.reallanguage.app.feature.billing.presentation.InsufficientBalanceUiState
import com.reallanguage.app.feature.billing.domain.RefundRequestStatus
import com.reallanguage.app.feature.billing.presentation.RefundScreen
import com.reallanguage.app.feature.billing.presentation.RefundUiState
import com.reallanguage.app.feature.error.domain.ConnectionResumePoint
import com.reallanguage.app.feature.error.presentation.ConnectionErrorOverlay
import com.reallanguage.app.feature.error.presentation.ConnectionErrorUiState
import com.reallanguage.app.feature.error.domain.ServiceFailureCategory
import com.reallanguage.app.feature.error.domain.ServiceFailureContext
import com.reallanguage.app.feature.error.presentation.ServiceFailureScreen
import com.reallanguage.app.feature.error.presentation.ServiceFailureUiState
import com.reallanguage.app.feature.auth.domain.AgreementDocument
import com.reallanguage.app.feature.auth.domain.AgreementKind
import com.reallanguage.app.feature.auth.domain.RegistrationDraftSnapshot
import com.reallanguage.app.feature.auth.presentation.AgreementDetailScreen
import com.reallanguage.app.feature.auth.presentation.AgreementDetailUiState
import com.reallanguage.app.feature.auth.presentation.PasswordResetCompletionDestination
import com.reallanguage.app.feature.auth.presentation.PasswordResetScreen
import com.reallanguage.app.feature.auth.presentation.PasswordResetStep
import com.reallanguage.app.feature.auth.presentation.PasswordResetUiState
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SafetyCaseDetails
import com.reallanguage.app.feature.safety.domain.SafetyEdit
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import com.reallanguage.app.feature.safety.presentation.SafetyCheckScreen
import com.reallanguage.app.feature.safety.presentation.SafetyCheckUiState
import com.reallanguage.app.feature.safety.presentation.SafetyHandlingScreen
import com.reallanguage.app.feature.safety.presentation.SafetyHandlingUiState
import com.reallanguage.app.feature.safety.presentation.ManualReviewPendingScreen
import com.reallanguage.app.feature.safety.presentation.ManualReviewPendingUiState
import com.reallanguage.app.feature.safety.presentation.ManualReviewOutcome
import com.reallanguage.app.feature.safety.presentation.ManualReviewResultScreen
import com.reallanguage.app.feature.safety.presentation.ManualReviewResultUiState

private enum class PreviewDestination {
    STARTUP_CONNECTION_ERROR,
    SCENE_DIRECT_DISCARD,
    SCENE_GENERATION_FAILED,
    SAFETY_CHECK_FAILED,
    TRANSCRIPT_SEGMENT_REVIEW,
    LOCAL_RECORDINGS_EMPTY,
    LOCAL_RECORDINGS_FAILED,
    RECORDING_CONFIRMATION_FAILED,
    TRANSCRIPT_LOAD_FAILED,
    SCENE_QUESTIONS_INVALID,
    SCENE_QUESTIONS_INSUFFICIENT,
    SAFETY_HANDLING_LOADING,
    SAFETY_HANDLING_LOAD_FAILED,
    ENGLISH_GENERATION,
    ENGLISH_GENERATION_FAILED,
    LEARNING_TARGET_LOADING,
    LEARNING_TARGET_FAILED,
    SCENE_DIRECT_DESCRIPTION,
    LOCAL_RECORDINGS,
    EXPLANATION_PREPARATION,
    EXPLANATION_PREPARATION_FAILED,
    READ_WRITE_CORRECTION,
    PATTERN_INDEPENDENT,
    ACCOUNT_DELETION,
    CATALOG,
    LOGIN,
    REGISTRATION,
    AGE_INELIGIBLE,
    EMAIL_VERIFICATION,
    SETTINGS_ONE,
    SETTINGS_TWO,
    SETTINGS_THREE,
    HOME,
    DIARY_INPUT,
    SAFETY_CHECK,
    SAFETY_HANDLING,
    REVIEW_PENDING,
    REVIEW_APPROVED,
    REVIEW_NOT_APPROVED,
    FOCUS_SELECTION,
    ENGLISH_CONFIRMATION,
    LEARNING_TARGETS,
    FINAL_FIX,
    CHAPTER_LEARNING,
    EXPLANATION_READING,
    READ_SPEAK,
    HANDWRITING,
    AUDIO_DICTATION,
    FULL_SENTENCE_SPEAKING,
    PATTERN_SPEAKING,
    COMPLETE_WRITING,
    SCENE_RESPONSE,
    LEARNING_EXIT,
    CHAPTER_RESULT,
    CHAPTER_SELECTION,
    CHAPTER_ABANDON,
    COMPLETED_STEP_REVIEW,
    TODAY_REVIEW,
    REVIEW_CHAPTER,
    REVIEW_EXIT,
    REVIEW_RESULT,
    VOCABULARY_BOOK,
    VOCABULARY_DETAIL,
    PATTERN_BOOK,
    PATTERN_DETAIL,
    PATTERN_REMOVAL,
    AI_BILLING,
    RECHARGE,
    INSUFFICIENT_BALANCE,
    REFUND,
    CONNECTION_ERROR,
    SERVICE_FAILURE,
    AGREEMENT_DETAIL,
    PASSWORD_RESET,
    SUBSEQUENT_SETTINGS,
    SETTINGS_EFFECT_SCOPE,
    DAILY_REMINDER_INTRO,
    INCOMPLETE_LEARNING,
    MESSAGES,
    ACCOUNT,
    RECORDING_INTRO,
    ACTIVE_RECORDING,
    RECORDING_CONFIRMATION,
    RECORDING_DELETION,
    RECORDING_TRANSCRIPTION,
    TRANSCRIPT_RESULT,
    TRANSCRIPTION_FAILURE,
    MICROPHONE_PERMISSION,
    SCENE_HOME,
    SCENE_TYPE,
    SCENE_QUESTIONS,
    SCENE_ABANDON,
    SCENE_GENERATION,
    SCENE_CHINESE_REVIEW,
}

private val LocalShowPreviewReturn = compositionLocalOf { true }

@Composable
fun DevelopmentPreviewScreen(
    modifier: Modifier = Modifier,
    initialDestinationName: String? = null,
    diaryInputState: DiaryInputUiState? = null,
) {
    val initialDestination = initialDestinationName
        ?.let { name -> PreviewDestination.entries.firstOrNull { it.name == name } }
        ?: PreviewDestination.CATALOG
    var destinationName by rememberSaveable {
        mutableStateOf(initialDestination.name)
    }
    val destination = PreviewDestination.valueOf(destinationName)
    val open: (PreviewDestination) -> Unit = { destinationName = it.name }
    val returnToCatalog = { open(PreviewDestination.CATALOG) }

    BackHandler(enabled = destination != PreviewDestination.CATALOG) {
        returnToCatalog()
    }

    CompositionLocalProvider(
        LocalShowPreviewReturn provides (initialDestinationName == null),
    ) {
    when (destination) {
        PreviewDestination.SCENE_DIRECT_DISCARD,
        PreviewDestination.SCENE_GENERATION_FAILED,
        PreviewDestination.SAFETY_CHECK_FAILED,
        PreviewDestination.TRANSCRIPT_SEGMENT_REVIEW,
        PreviewDestination.LOCAL_RECORDINGS_EMPTY,
        PreviewDestination.LOCAL_RECORDINGS_FAILED,
        PreviewDestination.RECORDING_CONFIRMATION_FAILED,
        PreviewDestination.TRANSCRIPT_LOAD_FAILED,
        PreviewDestination.SCENE_QUESTIONS_INVALID,
        PreviewDestination.SCENE_QUESTIONS_INSUFFICIENT,
        PreviewDestination.SAFETY_HANDLING_LOADING, PreviewDestination.SAFETY_HANDLING_LOAD_FAILED -> PreviewFrame(returnToCatalog, modifier) {
            com.reallanguage.app.feature.safety.presentation.SafetyHandlingLoaderScreen(
                com.reallanguage.app.feature.safety.presentation.SafetyHandlingLoaderUiState(
                    loading = destination == PreviewDestination.SAFETY_HANDLING_LOADING,
                    failed = destination == PreviewDestination.SAFETY_HANDLING_LOAD_FAILED), {})
        }
        PreviewDestination.ENGLISH_GENERATION, PreviewDestination.ENGLISH_GENERATION_FAILED -> PreviewFrame(returnToCatalog, modifier) {
            com.reallanguage.app.feature.formation.presentation.EnglishGenerationScreen(
                com.reallanguage.app.feature.formation.presentation.EnglishGenerationUiState(
                    processing = destination == PreviewDestination.ENGLISH_GENERATION,
                    failed = destination == PreviewDestination.ENGLISH_GENERATION_FAILED), {})
        }
        PreviewDestination.LEARNING_TARGET_LOADING, PreviewDestination.LEARNING_TARGET_FAILED -> PreviewFrame(returnToCatalog, modifier) {
            com.reallanguage.app.feature.formation.presentation.LearningTargetLoadingScreen(
                com.reallanguage.app.feature.formation.presentation.LearningTargetLoadingUiState(
                    processing = destination == PreviewDestination.LEARNING_TARGET_LOADING,
                    failed = destination == PreviewDestination.LEARNING_TARGET_FAILED), {})
        }
        PreviewDestination.SCENE_DIRECT_DESCRIPTION -> PreviewFrame(returnToCatalog, modifier) {
            com.reallanguage.app.feature.scene.presentation.SceneDirectDescriptionScreen(
                com.reallanguage.app.feature.scene.presentation.SceneDirectDescriptionUiState(
                    text = "我在去见朋友的路上，手机没电了。我想找个地方充电，再给朋友发微信，告诉他我会晚一点到。"), {})
        }
        PreviewDestination.LOCAL_RECORDINGS -> PreviewFrame(returnToCatalog, modifier) {
            com.reallanguage.app.feature.recording.presentation.LocalRecordingsScreen(
                com.reallanguage.app.feature.recording.presentation.LocalRecordingsUiState(
                    loading = false, recordings = listOf(
                        com.reallanguage.app.feature.recording.domain.LocalRecordingEntry("preview-recording-1", 1789776000000L),
                        com.reallanguage.app.feature.recording.domain.LocalRecordingEntry("preview-recording-2", 1789689600000L))), {})
        }
        PreviewDestination.EXPLANATION_PREPARATION, PreviewDestination.EXPLANATION_PREPARATION_FAILED -> PreviewFrame(returnToCatalog, modifier) {
            com.reallanguage.app.feature.learning.presentation.ExplanationPreparationScreen(
                com.reallanguage.app.feature.learning.presentation.ExplanationPreparationUiState(
                    loading = destination == PreviewDestination.EXPLANATION_PREPARATION,
                    failed = destination == PreviewDestination.EXPLANATION_PREPARATION_FAILED), {})
        }
        PreviewDestination.READ_WRITE_CORRECTION -> PreviewFrame(returnToCatalog, modifier) {
            com.reallanguage.app.feature.learning.presentation.ReadWriteCorrectionScreen(
                com.reallanguage.app.feature.learning.presentation.ReadWriteCorrectionUiState(
                    chapter = FixedLearningChapter("preview-correction", 1, listOf(
                        FixedLearningSentence("s1", "I was a little nervous during today’s meeting.", "今天开会时，我本来有点紧张。", false),
                        FixedLearningSentence("s2", "But in the end, I worked up the courage to explain my idea clearly.", "但最后还是鼓起勇气把想法讲清楚了。", true))),
                    currentFocusSentenceId = "s2", lockedHintItems = listOf("worked up the courage", "explain my idea clearly")), {})
        }
        PreviewDestination.PATTERN_INDEPENDENT -> PreviewFrame(returnToCatalog, modifier) {
            com.reallanguage.app.feature.review.presentation.PatternIndependentScreen(
                com.reallanguage.app.feature.review.presentation.PatternIndependentUiState(
                    pattern = LockedSpeakingPattern("p1", "s2", "work up the courage to + 动词", "鼓起勇气做某事"),
                    loading = false,
                    example = PatternPracticeExample("e1", "你一直想报名参加一个活动，今天终于准备行动。",
                        "What did you finally decide to do?", "I worked up the courage to sign up.", "我鼓起勇气报了名。")), {})
        }
        PreviewDestination.ACCOUNT_DELETION -> Box(modifier.fillMaxSize()) {
            AccountScreen(
                AccountUiState(account = AccountSnapshot("learner@example.com"), loading = false),
                onAction = {},
            )
            com.reallanguage.app.feature.home.presentation.AccountDeletionDialog(
                com.reallanguage.app.feature.home.presentation.AccountDeletionUiState(), {})
        }
        PreviewDestination.STARTUP_CONNECTION_ERROR -> PreviewFrame(returnToCatalog, modifier) {
            com.reallanguage.app.feature.error.presentation.StartupConnectionScreen {}
        }
        PreviewDestination.CATALOG -> DevelopmentCatalog(open, modifier)
        PreviewDestination.LOGIN -> PreviewFrame(returnToCatalog, modifier) {
            AuthScreen(
                state = AuthUiState(mode = AuthMode.LOGIN),
                onAction = { action ->
                    if (action == AuthAction.RegistrationRequested) {
                        open(PreviewDestination.REGISTRATION)
                    }
                },
            )
        }
        PreviewDestination.REGISTRATION -> PreviewFrame(returnToCatalog, modifier) {
            AuthScreen(
                state = AuthUiState(
                    mode = AuthMode.REGISTRATION,
                    birthYearMonth = "1992-09",
                ),
                onAction = { action ->
                    if (action == AuthAction.LoginRequested) {
                        open(PreviewDestination.LOGIN)
                    }
                },
            )
        }
        PreviewDestination.AGE_INELIGIBLE -> PreviewFrame(returnToCatalog, modifier) {
            AgeIneligibleScreen(onReturnToLogin = { open(PreviewDestination.LOGIN) })
        }
        PreviewDestination.EMAIL_VERIFICATION -> PreviewFrame(returnToCatalog, modifier) {
            EmailVerificationScreen(
                state = EmailVerificationUiState(
                    email = "indewo@outlook.com",
                    status = EmailVerificationStatus.WAITING,
                ),
                onAction = {},
            )
        }
        PreviewDestination.SETTINGS_ONE -> PreviewFrame(returnToCatalog, modifier) {
            InitialSettingsScreen(
                state = InitialSettingsUiState(
                    draft = InitialSettingsDraft(
                        formalityLevel = 3,
                        slangEnabled = true,
                        basicVocabulary = BasicVocabulary.CET_6,
                    ),
                    continueEnabled = true,
                ),
                onAction = {},
            )
        }
        PreviewDestination.SETTINGS_TWO -> PreviewFrame(returnToCatalog, modifier) {
            InitialSettingsScreen(
                state = InitialSettingsUiState(
                    step = InitialSettingsStep.USAGE_PREFERENCES,
                    draft = InitialSettingsDraft(
                        basicVocabulary = BasicVocabulary.CET_6,
                        purposeVocabularies = setOf(PurposeVocabulary.BUSINESS_ENGLISH),
                        styleReference = "清晰、自然、有分寸，像一位耐心的同事。",
                    ),
                    continueEnabled = true,
                ),
                onAction = {},
            )
        }
        PreviewDestination.SETTINGS_THREE -> PreviewFrame(returnToCatalog, modifier) {
            InitialSettingsScreen(
                state = InitialSettingsUiState(
                    step = InitialSettingsStep.VOICE_SETTINGS,
                    draft = InitialSettingsDraft(
                        voiceEnabled = true,
                        autoPlayEnabled = true,
                        speechSpeedLevel = 3,
                    ),
                    continueEnabled = true,
                ),
                onAction = {},
            )
        }
        PreviewDestination.HOME -> PreviewFrame(returnToCatalog, modifier) {
            HomeScreen(
                // The frozen reference has no system cutout. Production HomeScreen
                // continues to protect actual device insets; only this audit canvas
                // consumes them so design geometry can be compared at the same origin.
                modifier = Modifier.consumeWindowInsets(WindowInsets.safeDrawing),
                state = HomeUiState(
                    hasDueReview = true,
                    hasIncompleteLearning = true,
                ),
                onAction = { action ->
                    if (action == HomeAction.DiaryRequested) {
                        open(PreviewDestination.DIARY_INPUT)
                    }
                },
            )
        }
        PreviewDestination.DIARY_INPUT -> PreviewFrame(returnToCatalog, modifier) {
            DiaryInputScreen(
                state = diaryInputState ?: DiaryInputUiState(),
                onAction = { action ->
                    if (action == DiaryInputAction.SubmitRequested) {
                        open(PreviewDestination.SAFETY_CHECK)
                    }
                },
            )
        }
        PreviewDestination.SAFETY_CHECK -> PreviewFrame(returnToCatalog, modifier) {
            SafetyCheckScreen(
                state = SafetyCheckUiState(
                    source = ConfirmedSource(
                        text = "今天我第一次在开发中的 RealLanguage 里写日记。",
                        sourceType = SourceType.DIARY,
                        contentShape = SourceContentShape.ARTICLE,
                    ),
                    processing = true,
                ),
                onAction = {},
            )
        }
        PreviewDestination.SAFETY_HANDLING -> PreviewFrame(returnToCatalog, modifier) {
            val original = ConfirmedSource(
                text = "今天我和王经理在上海办公室讨论了项目。",
                sourceType = SourceType.DIARY,
                contentShape = SourceContentShape.ARTICLE,
            )
            SafetyHandlingScreen(
                state = SafetyHandlingUiState(
                    details = SafetyCaseDetails(
                        safetyCaseId = "preview-case",
                        originalSource = original,
                        userReasonZh = "原文包含可能识别个人身份的信息，暂时不能直接进入学习。",
                        deidentifiedSource = original.copy(
                            text = "今天我和一位同事在办公室讨论了项目。",
                        ),
                        edits = listOf(
                            SafetyEdit(
                                originalText = "王经理",
                                replacementText = "一位同事",
                                explanationZh = "已隐藏",
                            ),
                            SafetyEdit(
                                originalText = "上海办公室",
                                replacementText = "办公室",
                                explanationZh = "已隐藏",
                            ),
                        ),
                    ),
                ),
                onAction = {},
            )
        }
        PreviewDestination.REVIEW_PENDING -> PreviewFrame(returnToCatalog, modifier) {
            ManualReviewPendingScreen(
                state = ManualReviewPendingUiState(SourceType.DIARY),
                onAction = {},
            )
        }
        PreviewDestination.REVIEW_APPROVED -> PreviewFrame(returnToCatalog, modifier) {
            ManualReviewResultScreen(
                state = ManualReviewResultUiState(
                    outcome = ManualReviewOutcome.APPROVED,
                    summaryZh = "本次内容已通过人工复核。",
                ),
                onAction = {},
            )
        }
        PreviewDestination.REVIEW_NOT_APPROVED -> PreviewFrame(returnToCatalog, modifier) {
            ManualReviewResultScreen(
                state = ManualReviewResultUiState(
                    outcome = ManualReviewOutcome.NOT_APPROVED,
                    summaryZh = "本次内容未通过人工复核。",
                    reasonZh = "内容仍包含需要处理的具体信息。",
                ),
                onAction = {},
            )
        }
        PreviewDestination.FOCUS_SELECTION -> PreviewFrame(returnToCatalog, modifier) {
            FocusSelectionScreen(
                state = FocusSelectionUiState(
                    units = listOf(
                        SemanticUnit(
                            "u1",
                            0,
                            "今天开会时，我本来有点紧张。",
                            "适合作为完整事件的起点",
                        ),
                        SemanticUnit("u2", 1, "但最后还是鼓起勇气把想法讲清楚了。", "适合练习真实交流目标"),
                        SemanticUnit(
                            "u3",
                            2,
                            "同事听完后点了点头。",
                        ),
                        SemanticUnit("u4", 3, "我也轻松了很多。"),
                    ),
                    selectedUnitIds = setOf("u1", "u2"),
                    confirmEnabled = true,
                ),
                onAction = {},
            )
        }
        PreviewDestination.ENGLISH_CONFIRMATION -> PreviewFrame(returnToCatalog, modifier) {
            EnglishConfirmationScreen(
                state = EnglishConfirmationUiState(
                    chapters = listOf(
                        EnglishChapterDraft(
                            "c1",
                            listOf(
                                EnglishSentenceDraft(
                                    "u1",
                                    "自从2017年以来，",
                                    false,
                                    listOf(
                                        EnglishCandidate(
                                            "e1",
                                            "Since 2017,",
                                        ),
                                    ),
                                ),
                                EnglishSentenceDraft(
                                    "u2",
                                    "这个套件已经收获了被几乎所有软件更多的用户，",
                                    true,
                                    listOf(
                                        EnglishCandidate(
                                            "e2",
                                            "this app has gotten more users than almost any other app,",
                                        ),
                                        EnglishCandidate(
                                            "e2b",
                                            "this suite has had more users than almost every other app,",
                                        ),
                                    ),
                                ),
                                EnglishSentenceDraft(
                                    "u3",
                                    "所以今天上班我就需要通过其他的方式去公司。",
                                    false,
                                    listOf(EnglishCandidate("e3", "So I need to find another way to get to work today.")),
                                ),
                                EnglishSentenceDraft(
                                    "u4",
                                    "有时候住得远也是一个大问题。",
                                    false,
                                    listOf(EnglishCandidate("e4", "Sometimes living far away can be a real problem.")),
                                ),
                            ),
                        ),
                    ),
                    selectedCandidateIds = mapOf("u1" to "e1", "u2" to "e2", "u3" to "e3", "u4" to "e4"),
                    continueEnabled = true,
                ),
                onAction = {},
            )
        }
        PreviewDestination.LEARNING_TARGETS -> PreviewFrame(returnToCatalog, modifier) {
            LearningTargetScreen(
                state = LearningTargetUiState(
                    chapters = listOf(
                        EnglishChapterDraft(
                            "c1",
                            listOf(
                                EnglishSentenceDraft(
                                    "u0",
                                    "今天开会时，我本来有点紧张。",
                                    false,
                                    listOf(EnglishCandidate("e0", "I was a little nervous during today's meeting.")),
                                ),
                                EnglishSentenceDraft(
                                    "u1",
                                    "但最后还是鼓起勇气把想法讲清楚了。",
                                    true,
                                    listOf(
                                        EnglishCandidate(
                                            "e1",
                                            "But in the end, I worked up the courage to explain my idea clearly.",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    selectedCandidateIds = mapOf("u1" to "e1"),
                    targets = listOf(
                        LearningTarget(
                            targetId = "t1",
                            unitId = "u1",
                            display = "work up the courage",
                            explanationZh = "鼓起勇气",
                            type = LearningTargetType.WORD,
                            learningScopes = setOf("CET_4", "CET_6"),
                            sourceEvidence = SourceEvidence(
                                version = 1,
                                startUtf16 = 18,
                                endUtf16 = 39,
                                surfaceForm = "worked up the courage",
                                normalizedForm = "work up the courage",
                                allowedForms = listOf("worked up the courage"),
                            ),
                            candidateExample = CandidateExample(
                                "It took him a while to work up the courage to apologize.",
                                "他花了一会儿才鼓起勇气道歉。",
                            ),
                        ),
                        LearningTarget(
                            targetId = "t2",
                            unitId = "u1",
                            display = "explain",
                            explanationZh = "解释；讲清楚",
                            type = LearningTargetType.FIXED_EXPRESSION,
                            learningScopes = setOf("CET_4", "CET_6"),
                            sourceEvidence = SourceEvidence(
                                version = 1,
                                startUtf16 = 43,
                                endUtf16 = 50,
                                surfaceForm = "explain",
                                normalizedForm = "explain",
                                allowedForms = listOf("explain"),
                            ),
                            candidateExample = CandidateExample(
                                "She explained the rule in a simple way.",
                                "她用简单的方式解释了规则。",
                            ),
                        ),
                        LearningTarget(
                            targetId = "t3",
                            unitId = "u1",
                            display = "work up the courage to + 动词",
                            explanationZh = "鼓起勇气做某事",
                            type = LearningTargetType.PATTERN,
                            structureParts = listOf("work up the courage to", "动词"),
                            relatedVocabulary = listOf("courage"),
                            learningScopes = setOf("CET_4", "CET_6"),
                            candidateExample = CandidateExample(
                                "He worked up the courage to apply for the scholarship.",
                                "他鼓起勇气申请了奖学金。",
                            ),
                        ),
                    ),
                    selectedTargetIds = setOf("t1", "t2", "t3"),
                    queryableExpressions = listOf(
                        QueryableExpression("word:0", "u1", "But", "但是；表示转折"),
                        QueryableExpression("word:58", "u1", "clearly", "清楚地；明确地"),
                    ),
                ),
                onAction = {},
            )
        }
        PreviewDestination.FINAL_FIX -> PreviewFrame(returnToCatalog, modifier) {
            FinalFixScreen(
                state = FinalFixUiState(
                    chapters = listOf(
                        EnglishChapterDraft(
                            "c1",
                            listOf(
                                EnglishSentenceDraft(
                                    "u1",
                                    "今天是美好的一天。",
                                    true,
                                    listOf(
                                        EnglishCandidate(
                                            "e1",
                                            "It's a beautiful day today.",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    selectedCandidateIds = mapOf("u1" to "e1"),
                    selectedTargets = listOf(
                        LearningTarget(
                            targetId = "t1",
                            unitId = "u1",
                            display = "beautiful",
                            explanationZh = "美好的；漂亮的",
                            type = LearningTargetType.WORD,
                        ),
                        LearningTarget(
                            targetId = "t2",
                            unitId = "u1",
                            display = "It's a ... day",
                            explanationZh = "这是……的一天",
                            type = LearningTargetType.PATTERN,
                        ),
                    ),
                ),
                onAction = {},
            )
        }
        PreviewDestination.CHAPTER_LEARNING -> PreviewFrame(returnToCatalog, modifier) {
            CompositionLocalProvider(
                com.reallanguage.app.feature.learning.presentation.LocalLearningExitAction provides {},
            ) {
                ChapterEntryScreen(
                    state = ChapterEntryUiState(
                        chapter = FixedLearningChapter(
                            chapterId = "c1",
                            chapterNumber = 1,
                            sentences = listOf(
                                FixedLearningSentence(
                                    "s1",
                                    "Yeah, I get really happy when I’ m eating.",
                                    "用户：嗯，吃饭时我会很开心。",
                                    true,
                                ),
                                FixedLearningSentence(
                                    "s2",
                                    "That’ s nice.",
                                    "对方：那挺好的，",
                                    false,
                                ),
                                FixedLearningSentence(
                                    "s3",
                                    "Just enjoy it for now.",
                                    "你就先享受吧。",
                                    false,
                                ),
                            ),
                        ),
                    ),
                    onAction = {},
                )
            }
        }
        PreviewDestination.EXPLANATION_READING -> PreviewFrame(returnToCatalog, modifier) {
            val target = SelfAssessmentTarget(
                targetId = "beautiful",
                sentenceId = "s1",
                display = "beautiful",
                explanationZh = "美好的；漂亮的",
                abilities = setOf(SelfAssessmentAbility.MEANING_WRITING),
            )
            ExplanationReadingScreen(
                state = ExplanationReadingUiState(
                    chapter = FixedLearningChapter(
                        chapterId = "c1",
                        chapterNumber = 1,
                        sentences = listOf(
                            FixedLearningSentence(
                                "s1",
                                "It's a beautiful day today.",
                                "今天是美好的一天。",
                                true,
                            ),
                        ),
                    ),
                    currentFocusSentenceId = "s1",
                    baseExplanationZh = "这句是很自然的日常说法，意思是“今天真不错／今天是美好的一天”。",
                    currentIncrementalHint = null,
                    expressions = listOf(
                        SelectableExpression("beautiful", "beautiful", 7, 16),
                    ),
                    selectedExpression = ExpressionExplanation(
                        "beautiful",
                        "美好的；漂亮的",
                        "/ˈbjuːtɪfl/",
                        naturalExample = com.reallanguage.app.feature.learning.domain.NaturalExpressionExample(
                            "It's a beautiful morning.", "这是一个美好的早晨。",
                        ),
                        contextualMeaningZh = "美好的；漂亮的",
                        usageZh = "这里用来形容 day，表达“天气／一天很好”。",
                        expressionHintZh = "常用于描述景色、天气、事物，也可形容人。",
                    ),
                    completedReadCount = 1,
                    explaining = false,
                    audioPlaying = false,
                ),
                onAction = {},
                targetAssessment = { display ->
                    TargetSelfAssessmentControls(
                        state = TargetSelfAssessmentUiState(listOf(target)),
                        onAction = {},
                        display = display,
                        cardAction = true,
                    )
                },
            )
        }
        PreviewDestination.READ_SPEAK -> PreviewFrame(returnToCatalog, modifier) {
            ReadSpeakScreen(
                state = ReadSpeakUiState(
                    chapter = FixedLearningChapter(
                        chapterId = "c1",
                        chapterNumber = 1,
                        sentences = listOf(
                            FixedLearningSentence(
                                "s1",
                                "I studied at the library today.",
                                "我今天在图书馆学习。",
                                true,
                            ),
                        ),
                    ),
                    currentFocusSentenceId = "s1",
                    completed = true,
                    feedbackZh = "内容不完整。请补全句子，必须包含“at the library today”。",
                ),
                onAction = {},
            )
        }
        PreviewDestination.HANDWRITING -> PreviewFrame(returnToCatalog, modifier) {
            HandwritingCopyScreen(
                state = HandwritingCopyUiState(
                    chapter = FixedLearningChapter(
                        chapterId = "c1",
                        chapterNumber = 1,
                        sentences = listOf(
                            FixedLearningSentence(
                                "s1",
                                "I was a little nervous during today’s meeting.",
                                "今天开会时，我本来有点紧张。",
                                false,
                            ),
                            FixedLearningSentence(
                                "s2",
                                "But in the end, I worked up the courage to explain my idea clearly.",
                                "但最后还是鼓起勇气把想法讲清楚了。",
                                true,
                            ),
                            FixedLearningSentence(
                                "s3",
                                "My colleague nodded after listening.",
                                "同事听完后点了点头。",
                                false,
                            ),
                        ),
                    ),
                    currentFocusSentenceId = "s2",
                ),
                onAction = {},
            )
        }
        PreviewDestination.AUDIO_DICTATION -> PreviewFrame(returnToCatalog, modifier) {
            AudioDictationScreen(
                state = AudioDictationUiState(
                    chapter = FixedLearningChapter(
                        chapterId = "c1",
                        chapterNumber = 1,
                        sentences = listOf(
                            FixedLearningSentence(
                                "s1",
                                "I was a little nervous during today’s meeting.",
                                "今天开会时，我本来有点紧张。",
                                false,
                            ),
                            FixedLearningSentence(
                                "s2",
                                "But in the end, I worked up the courage to explain my idea clearly.",
                                "但最后还是鼓起勇气把想法讲清楚了。",
                                true,
                            ),
                            FixedLearningSentence(
                                "s3",
                                "My colleague nodded after listening.",
                                "同事听完后点了点头。",
                                false,
                            ),
                        ),
                    ),
                    currentFocusSentenceId = "s2",
                    audioLooping = true,
                    input = "But in the end, I worked up the courage to explain...",
                    lockedHintItems = listOf("work up the courage：鼓起勇气"),
                ),
                onAction = {},
            )
        }
        PreviewDestination.FULL_SENTENCE_SPEAKING -> PreviewFrame(returnToCatalog, modifier) {
            FullSentenceSpeakingScreen(
                state = FullSentenceSpeakingUiState(
                    chapter = FixedLearningChapter(
                        chapterId = "c1",
                        chapterNumber = 1,
                        sentences = listOf(
                            FixedLearningSentence(
                                "s1",
                                "I was a little nervous during today’s meeting.",
                                "今天开会时，我本来有点紧张。",
                                false,
                            ),
                            FixedLearningSentence(
                                "s2",
                                "But in the end, I worked up the courage to explain my idea clearly.",
                                "但最后还是鼓起勇气把想法讲清楚了。",
                                true,
                            ),
                            FixedLearningSentence(
                                "s3",
                                "My colleague nodded after listening.",
                                "同事听完后点了点头。",
                                false,
                            ),
                        ),
                    ),
                    currentFocusSentenceId = "s2",
                    attemptIndex = 4,
                    recording = true,
                    transcript = "But in the end, I worked up the courage to explain my idea clearly.",
                ),
                onAction = {},
            )
        }
        PreviewDestination.PATTERN_SPEAKING -> PreviewFrame(returnToCatalog, modifier) {
            val previewExamples = listOf(
                PatternPracticeExample(
                    "e1",
                    "你一直想报名参加一个活动，今天终于准备行动。",
                    "What did you finally decide to do?",
                    "I worked up the courage to sign up.",
                    "我鼓起勇气报了名。",
                ),
                PatternPracticeExample(
                    "e2",
                    "你一直想向主管提出一个建议，今天终于准备开口。",
                    "What did you finally decide to do?",
                    "I worked up the courage to share my suggestion.",
                    "我鼓起勇气分享了自己的建议。",
                ),
                PatternPracticeExample(
                    "e3",
                    "你一直想在会议上发言，今天终于表达了想法。",
                    "What did you finally decide to do?",
                    "I worked up the courage to speak at the meeting.",
                    "我鼓起勇气在会议上发了言。",
                ),
            )
            PatternSpeakingScreen(
                state = PatternSpeakingUiState(
                    chapter = FixedLearningChapter(
                        chapterId = "c1",
                        chapterNumber = 1,
                        sentences = listOf(
                            FixedLearningSentence(
                                "s1",
                                "I was a little nervous during today’s meeting.",
                                "今天开会时，我本来有点紧张。",
                                false,
                            ),
                            FixedLearningSentence(
                                "s2",
                                "But in the end, I worked up the courage to explain my idea clearly.",
                                "但最后还是鼓起勇气把想法讲清楚了。",
                                true,
                            ),
                            FixedLearningSentence(
                                "s3",
                                "My colleague nodded after listening.",
                                "同事听完后点了点头。",
                                false,
                            ),
                        ),
                    ),
                    currentFocusSentenceId = "s2",
                    lockedPatterns = listOf(
                        LockedSpeakingPattern(
                            "p1",
                            "s2",
                            "work up the courage to + 动词",
                            "鼓起勇气做某事",
                        ),
                    ),
                    stage = PatternSpeakingStage.RESPONDING,
                    examples = previewExamples,
                    responseIndex = 1,
                    recording = true,
                    transcript = "I worked up the courage to share my suggestion.",
                ),
                onAction = {},
            )
        }
        PreviewDestination.COMPLETE_WRITING -> PreviewFrame(returnToCatalog, modifier) {
            CompleteWritingScreen(
                state = CompleteWritingUiState(
                    chapter = FixedLearningChapter(
                        chapterId = "c1",
                        chapterNumber = 1,
                        sentences = listOf(
                            FixedLearningSentence(
                                "s1",
                                "I was a little nervous during today’s meeting.",
                                "面试前我很紧张。",
                                true,
                            ),
                            FixedLearningSentence(
                                "s2",
                                "But in the end, I worked up the courage to explain my idea clearly.",
                                "但我最终鼓起勇气把想法讲清楚了。",
                                true,
                            ),
                        ),
                    ),
                    lockedTargets = listOf(
                        LockedWritingTarget(
                            "p1",
                            "s1",
                            WritingTargetType.PATTERN,
                            listOf("for the first time"),
                        ),
                    ),
                    phase = CompleteWritingPhase.FINAL_BATCH,
                    sentenceIndex = 1,
                    audioPlaying = false,
                    finalInputs = mapOf(
                        "s1" to "I was a little nervous during today’s meeting.",
                        "s2" to "But in the end, I worked up the courage...",
                    ),
                ),
                onAction = {},
            )
        }
        PreviewDestination.SCENE_RESPONSE -> PreviewFrame(returnToCatalog, modifier) {
            SceneResponseScreen(
                state = SceneResponseUiState(
                    chapter = FixedLearningChapter(
                        chapterId = "c1",
                        chapterNumber = 1,
                        sentences = listOf(
                            FixedLearningSentence(
                                "s1",
                                "I wrote a diary entry.",
                                "我写了一篇日记。",
                                true,
                            ),
                        ),
                    ),
                    tasks = listOf(
                        FixedSceneResponseTask(
                            "t1",
                            "s1",
                            "会议结束后，你和同事走出会议室。",
                            listOf(
                                SceneDialogueLine(
                                    "AI",
                                    "You explained your idea really clearly in there.",
                                ),
                            ),
                            "Thanks. I was nervous, but in the end, I worked up the courage to explain my idea clearly.",
                        ),
                    ),
                    roundIndex = 2,
                    recording = true,
                    transcript = "Thanks. I was nervous, but in the end, I worked up the courage to explain my idea clearly.",
                    feedbackZh = "回应同事，并在正确位置说出本章目标句。",
                ),
                onAction = {},
            )
        }
        PreviewDestination.LEARNING_EXIT -> Box(
            modifier = modifier.fillMaxSize().background(RlColor.Background),
        ) {
            LearningExitDialog(
                state = LearningExitUiState(
                    OrdinaryLearningCheckpoint("c1", "LR-09", "round-2"),
                ),
                onAction = { action ->
                    if (action == LearningExitAction.ContinueLearningRequested) {
                        returnToCatalog()
                    }
                },
            )
        }
        PreviewDestination.CHAPTER_RESULT -> PreviewFrame(returnToCatalog, modifier) {
            ChapterResultScreen(
                state = ChapterResultUiState(
                    chapterId = "c1",
                    commandId = "preview-command",
                    chapterNumber = 1,
                    archiving = false,
                    summary = ChapterArchiveSummary(
                        chapterId = "c1",
                        items = listOf(
                            ChapterResultSummaryItem(
                                ChapterResultCategory.SENTENCE,
                                "整句能力",
                                3,
                                3,
                            ),
                            ChapterResultSummaryItem(ChapterResultCategory.MEANING, "词义与固定搭配", 1, 1),
                            ChapterResultSummaryItem(
                                ChapterResultCategory.CORRECTION,
                                "订正",
                                1,
                                1,
                            ),
                        ),
                        hasNextChapter = false,
                        chapterNumber = 1,
                        reinforcementItems = emptyList(),
                    ),
                ),
                onAction = {},
            )
        }
        PreviewDestination.CHAPTER_SELECTION -> PreviewFrame(returnToCatalog, modifier) {
            ChapterSelectionScreen(
                state = ChapterSelectionUiState(
                    sourceId = "source-1",
                    chapters = listOf(
                        RemainingChapterSummary("c2", 2, "继续表达这次经历"),
                        RemainingChapterSummary("c3", 3, "总结自己的感受"),
                    ),
                ),
                onAction = {},
            )
        }
        PreviewDestination.CHAPTER_ABANDON -> PreviewFrame(returnToCatalog, modifier) {
            ChapterAbandonScreen(
                state = ChapterAbandonUiState("c2", "preview-delete-c2"),
                onAction = {},
            )
        }
        PreviewDestination.COMPLETED_STEP_REVIEW -> PreviewFrame(returnToCatalog, modifier) {
            CompletedStepReviewScreen(
                state = CompletedStepReviewUiState(
                    CompletedStepSnapshot(
                        "LR-02",
                        "第 1 章·基础讲解",
                        "But in the end, I worked up the courage to explain my idea clearly.",
                        "但最后还是鼓起勇气把想法讲清楚了。",
                        "常用于描述终于敢去做一件原本犹豫的事。",
                        contextSentences = listOf(
                            com.reallanguage.app.feature.learning.domain.FixedLearningSentence(
                                "preview-completed-1", "I was a little nervous during today’s meeting.", "今天开会时，我本来有点紧张。", false,
                            ),
                            com.reallanguage.app.feature.learning.domain.FixedLearningSentence(
                                "preview-completed-2", "But in the end, I worked up the courage to explain my idea clearly.", "但最后还是鼓起勇气把想法讲清楚了。", true,
                            ),
                            com.reallanguage.app.feature.learning.domain.FixedLearningSentence(
                                "preview-completed-3", "My colleague nodded after listening.", "同事听完后点了点头。", false,
                            ),
                        ),
                        activeSentenceId = "preview-completed-2",
                        reviewedExpression = com.reallanguage.app.feature.learning.domain.CompletedStepExpression(
                            com.reallanguage.app.feature.learning.domain.SelectableExpression(
                                "preview-phrase", "worked up the courage", 18, 39,
                            ),
                            com.reallanguage.app.feature.learning.domain.ExpressionExplanation(
                                "preview-phrase", "常用于描述终于敢去做一件原本犹豫的事。", null,
                                com.reallanguage.app.feature.learning.domain.NaturalExpressionExample(
                                    "She worked up the courage to ask.", "她鼓起勇气问了。",
                                ),
                                contextualMeaningZh = "鼓起勇气",
                                usageZh = "常用于描述终于敢去做一件原本犹豫的事。",
                                expressionHintZh = "work up the courage to + 动词",
                            ),
                        ),
                    ),
                    explanationVisible = false,
                ),
                onAction = {},
            )
        }
        PreviewDestination.TODAY_REVIEW -> PreviewFrame(returnToCatalog, modifier) {
            TodayReviewScreen(
                state = TodayReviewUiState(
                    chapters = listOf(
                        ReviewChapterSummary(
                            "review-c1",
                            "chapter-c1",
                            ReviewSourceType.DIARY,
                            "昨晚的日记",
                            "I finally spoke up.",
                            1,
                            2,
                        ),
                        ReviewChapterSummary(
                            "review-c2",
                            "chapter-c4",
                            ReviewSourceType.QUICK_RECORDING,
                            "通勤录音",
                            "The train was delayed again.",
                            2,
                            3,
                        ),
                    ),
                    loading = false,
                ),
                onAction = {},
            )
        }
        PreviewDestination.REVIEW_CHAPTER -> PreviewFrame(returnToCatalog, modifier) {
            ReviewChapterScreen(
                state = ReviewChapterUiState(
                    reviewChapterId = "review-c1",
                    commandId = "preview-review-c1",
                    chapter = ReviewChapterSnapshot(
                        "review-c1",
                        "chapter-c2",
                        ReviewSourceType.DIARY,
                        "7 月 12 日日记 · 面试前",
                        listOf(
                            "I was nervous before the interview.",
                            "I finally worked up the courage to speak to her.",
                            "She smiled and said hello.",
                        ),
                        "I finally worked up the courage to speak to her.",
                        listOf(
                            ReviewAbility(
                                "a1",
                                ReviewAbilityModule.FULL_SENTENCE_SPEAKING,
                                ReviewAbilityStatus.LEARNING,
                            ),
                            ReviewAbility(
                                "a2",
                                ReviewAbilityModule.WORD_MEANING,
                                ReviewAbilityStatus.TENTATIVE,
                            ),
                        ),
                    ),
                    loading = false,
                ),
                onAction = {},
            )
        }
        PreviewDestination.REVIEW_EXIT -> Box(
            modifier = modifier.fillMaxSize().background(RlColor.Background),
        ) {
            ReviewExitDialog(
                state = ReviewExitUiState("review-c2", completedChapterCount = 1),
                onAction = { returnToCatalog() },
            )
        }
        PreviewDestination.REVIEW_RESULT -> PreviewFrame(returnToCatalog, modifier) {
            ReviewResultScreen(
                state = ReviewResultUiState(
                    "review-session-1",
                    "preview-settle",
                    summary = ReviewResultSummary(3, 0, true, true),
                    settling = false,
                ),
                onAction = {},
            )
        }
        PreviewDestination.VOCABULARY_BOOK -> PreviewFrame(returnToCatalog, modifier) {
            // Match the selected R11 visual fixture; never written to the user's book.
            VocabularyBookScreen(
                state = VocabularyBookUiState(
                    currentScope = "六级单词",
                    entries = listOf(
                        VocabularyEntry("abstract", listOf(
                            VocabularySenseRecord("abstract-adj", "abstract", "抽象的", KnowledgeStatus.NOT_STARTED, 0, setOf("CET_6"), 1,
                                phonetic = "/ˈæbstrækt/", pronunciationText = "abstract", partOfSpeech = "adj."),
                            VocabularySenseRecord("abstract-n", "abstract", "摘要；抽象的东西", KnowledgeStatus.LEARNING, 0, setOf("CET_6"), 2,
                                phonetic = "/ˈæbstrækt/", pronunciationText = "abstract", partOfSpeech = "n."),
                            VocabularySenseRecord("abstract-vi", "abstract", "做摘要", KnowledgeStatus.TENTATIVE, 0, setOf("CET_6"), 3,
                                phonetic = "/ˈæbstrækt/", pronunciationText = "abstract", partOfSpeech = "vi."),
                            VocabularySenseRecord("abstract-vt", "abstract", "摘取；提取", KnowledgeStatus.MASTERED, 2, setOf("CET_6"), 4,
                                phonetic = "/ˈæbstrækt/", pronunciationText = "abstract", partOfSpeech = "vt."),
                        )),
                        VocabularyEntry("academic", listOf(
                            VocabularySenseRecord("academic-adj", "academic", "学术的；学院的", KnowledgeStatus.NOT_STARTED, 0, setOf("CET_6"), 5,
                                phonetic = "/ˌækəˈdemɪk/", pronunciationText = "academic", partOfSpeech = "adj."),
                        )),
                        VocabularyEntry("access", listOf(
                            VocabularySenseRecord("access-n", "access", "入口；接近；使用权", KnowledgeStatus.NOT_STARTED, 0, setOf("CET_6"), 6,
                                phonetic = "/ˈækses/", pronunciationText = "access", partOfSpeech = "n."),
                        )),
                    ),
                    loading = false,
                ),
                onAction = {},
            )
        }
        PreviewDestination.VOCABULARY_DETAIL -> PreviewFrame(returnToCatalog, modifier) {
            val detail = VocabularySenseRecord(
                "run-1", "run", "经营业务", KnowledgeStatus.MASTERED, 2,
                setOf("CET_6"), 2, "/rʌn/", "audio-run", "经营一家社区咖啡店",
                listOf(
                    VocabularyExample("She runs a small café.", "她经营一家小咖啡店。", "e1"),
                    VocabularyExample("He runs the business with his sister.", "他和姐姐一起经营这家公司。", "e2"),
                    VocabularyExample("They run the shop together.", "他们一起经营这家店。", "e3"),
                ), 2, 3,
            )
            VocabularyBookScreen(
                state = VocabularyBookUiState(
                    "六级单词",
                    listOf(
                        VocabularyEntry(
                            "run",
                            listOf(
                                VocabularySenseRecord(
                                    "run-2", "run", "跑步", KnowledgeStatus.MASTERED, 3,
                                    setOf("六级"), 1,
                                ),
                                detail,
                            ),
                        ),
                    ),
                    "run-1",
                    loading = false,
                ),
                onAction = {},
            )
        }
        PreviewDestination.PATTERN_BOOK -> PreviewFrame(returnToCatalog, modifier) {
            PatternBookScreen(
                PatternBookUiState(
                    listOf(
                        PatternRecord("p1", "work up the courage to + 动词", "鼓起勇气做某事", KnowledgeStatus.MASTERED, KnowledgeStatus.MASTERED, 3, 6,
                            writingConsecutiveSuccessCount = 2, speakingConsecutiveSuccessCount = 1),
                        PatternRecord("p2", "I'd like to…", "礼貌表达意愿", KnowledgeStatus.MASTERED, KnowledgeStatus.MASTERED, 3, 5,
                            writingConsecutiveSuccessCount = 3, speakingConsecutiveSuccessCount = 3),
                        PatternRecord("p3", "It took me… to…", "表达完成某事所花时间", KnowledgeStatus.LEARNING, KnowledgeStatus.NOT_STARTED, 1, 3),
                        PatternRecord("p4", "The reason is that…", "说明原因", KnowledgeStatus.NOT_STARTED, KnowledgeStatus.NOT_STARTED, 0, 2),
                    ),
                    loading = false,
                ),
                onAction = {},
            )
        }
        PreviewDestination.PATTERN_DETAIL -> PreviewFrame(returnToCatalog, modifier) {
            // Visual comparison fixture only; production entries are loaded from the account's book.
            val detail = PatternRecord(
                "p1", "work up the courage to + 动词", "鼓起勇气做某事", KnowledgeStatus.MASTERED,
                KnowledgeStatus.LEARNING, 2, 2, "终于决定去做一直犹豫的事",
                listOf(
                    PatternExample("I worked up the courage to apply for the scholarship.", "我鼓起勇气申请了那项奖学金。", "I worked up the courage to apply for the scholarship."),
                    PatternExample("It took me weeks to work up the courage to talk to him.", "我花了好几周才鼓起勇气和他说话。", "It took me weeks to work up the courage to talk to him."),
                    PatternExample("She finally worked up the courage to leave her old job.", "她终于鼓起勇气离开了那份旧工作。", "She finally worked up the courage to leave her old job."),
                ),
                writingConsecutiveSuccessCount = 2, speakingConsecutiveSuccessCount = 1,
                structureParts = listOf("work", "up", "the courage", "to", "+ 动词"),
            )
            PatternBookScreen(PatternBookUiState(listOf(detail,
                PatternRecord("p2", "I'd like to…", "礼貌表达意愿", KnowledgeStatus.MASTERED, KnowledgeStatus.MASTERED, 3, 3),
                PatternRecord("p3", "It took me…to…", "表达完成某事所花时间", KnowledgeStatus.LEARNING, KnowledgeStatus.TENTATIVE, 0, 1),
                PatternRecord("p4", "The reason is that…", "说明原因", KnowledgeStatus.TENTATIVE, KnowledgeStatus.TENTATIVE, 0, 1),
            ), "p1", loading = false), onAction = {})
        }
        PreviewDestination.PATTERN_REMOVAL -> Box(modifier.fillMaxSize()) {
            val detail = PatternRecord(
                "p1",
                "work up the courage to + 动词",
                "鼓起勇气做某事",
                KnowledgeStatus.MASTERED,
                KnowledgeStatus.MASTERED,
                3,
                6,
                "鼓起勇气完成重要的事情",
                listOf(
                    PatternExample("I worked up the courage to apply for the scholarship.", "我鼓起勇气申请奖学金。", "e1"),
                    PatternExample("It took me weeks to work up the courage to talk to him.", "我花了几周才鼓起勇气和他谈。", "e2"),
                    PatternExample("She finally worked up the courage to leave her old job.", "她终于鼓起勇气离开原来的工作。", "e3"),
                ),
            )
            PatternBookScreen(
                PatternBookUiState(listOf(detail), "p1", loading = false),
                onAction = {},
            )
            PatternRemovalDialog(
                state = PatternRemovalUiState(
                    patternId = "p1",
                    commandId = "preview-remove-p1",
                ),
                pattern = detail.structureEn,
                onAction = {},
            )
        }
        PreviewDestination.AI_BILLING -> PreviewFrame(returnToCatalog, modifier) {
            val preview = AiBillingSnapshot(
                balanceAmountFen = 3_680,
                charges = listOf(
                    AiChargeRecord(
                        chargeId = "preview-diary",
                        amountFen = 42,
                        occurredAtEpochMillis = 1_788_396_800_000,
                        modelSource = "OpenAI",
                        callType = AiChargeType.INITIAL_CALL,
                        operationLabelZh = "日记整理",
                    ),
                    AiChargeRecord(
                        chargeId = "preview-safety",
                        amountFen = 8,
                        occurredAtEpochMillis = 1_788_393_200_000,
                        modelSource = "OpenAI",
                        callType = AiChargeType.INITIAL_CALL,
                        operationLabelZh = "安全检查",
                    ),
                    AiChargeRecord(
                        chargeId = "preview-scene",
                        amountFen = 67,
                        occurredAtEpochMillis = 1_788_310_400_000,
                        modelSource = "OpenAI",
                        callType = AiChargeType.INITIAL_CALL,
                        operationLabelZh = "场景生成",
                    ),
                    AiChargeRecord(
                        chargeId = "preview-scene-retry",
                        amountFen = 31,
                        occurredAtEpochMillis = 1_788_306_800_000,
                        modelSource = "OpenAI",
                        callType = AiChargeType.USER_RETRY,
                        operationLabelZh = "场景生成重试",
                    ),
                ),
            )
            AiBillingScreen(
                state = AiBillingUiState(
                    balanceAmountFen = preview.balanceAmountFen,
                    charges = preview.charges,
                    loading = false,
                ),
                onAction = {},
            )
        }
        PreviewDestination.RECHARGE -> PreviewFrame(returnToCatalog, modifier) {
            RechargeScreen(
                state = RechargeUiState(
                    originId = "preview-ai-billing",
                    balanceAmountFen = 3_680,
                    recommendedOptions = listOf(
                        RechargeOption(1_000, "轻量体验"),
                        RechargeOption(3_000, "日常练习"),
                        RechargeOption(5_000, "稳定积累"),
                        RechargeOption(10_000, "长期使用"),
                    ),
                    selectedAmountFen = 3_000,
                    selectedMethod = PaymentMethod.WECHAT_PAY,
                    loading = false,
                ),
                onAction = {},
            )
        }
        PreviewDestination.INSUFFICIENT_BALANCE -> PreviewFrame(returnToCatalog, modifier) {
            InsufficientBalanceScreen(
                state = InsufficientBalanceUiState(
                    BalanceInsufficientContext(
                        originId = "preview-review-chapter",
                        originState = BalanceOriginState.UNFIXED_DIARY_OR_AI_SCENE,
                    ),
                    balanceAmountFen = 18,
                    recentCharges = listOf(
                        AiChargeRecord("insufficient-diary", 42, 1_788_396_800_000, "OpenAI", AiChargeType.INITIAL_CALL, "日记整理"),
                        AiChargeRecord("insufficient-safety", 8, 1_788_393_200_000, "OpenAI", AiChargeType.INITIAL_CALL, "安全检查"),
                        AiChargeRecord("insufficient-scene", 67, 1_788_310_400_000, "OpenAI", AiChargeType.INITIAL_CALL, "场景生成"),
                        AiChargeRecord("insufficient-retry", 31, 1_788_306_800_000, "OpenAI", AiChargeType.USER_RETRY, "场景生成重试"),
                    ),
                ),
                onAction = {},
            )
        }
        PreviewDestination.REFUND -> PreviewFrame(returnToCatalog, modifier) {
            RefundScreen(
                state = RefundUiState(
                    refundableAmountFen = 3_000,
                    refundMethodLabelZh = "微信支付",
                    loading = false,
                ),
                onAction = {},
            )
        }
        PreviewDestination.CONNECTION_ERROR -> PreviewFrame(returnToCatalog, modifier) {
            Box(Modifier.fillMaxSize()) {
                ExplanationReadingScreen(
                    state = ExplanationReadingUiState(
                        chapter = FixedLearningChapter(
                            chapterId = "c1",
                            chapterNumber = 1,
                            sentences = listOf(
                                FixedLearningSentence("s1", "I was a little nervous during the meeting.", "开会时我本来有点紧张。", false),
                                FixedLearningSentence("s2", "But in the end, I worked up the courage to explain it clearly.", "但最后我鼓起勇气把想法讲清楚了。", true),
                                FixedLearningSentence("s3", "My colleague nodded after listening.", "同事听完后点了点头。", false),
                            ),
                        ),
                        currentFocusSentenceId = "s2",
                        baseExplanationZh = "work up the courage to + 动词：鼓起勇气做某事。",
                        expressions = listOf(SelectableExpression("x1", "work up the courage to", 18, 40)),
                        explaining = false,
                        audioPlaying = false,
                    ),
                    onAction = {},
                )
                ConnectionErrorOverlay(
                    state = ConnectionErrorUiState(
                        ConnectionResumePoint(
                            pageId = "preview-explanation-reading",
                            positionToken = "sentence-s2",
                            temporaryTextInput = null,
                        ),
                    ),
                    onAction = {},
                )
            }
        }
        PreviewDestination.SERVICE_FAILURE -> PreviewFrame(returnToCatalog, modifier) {
            ServiceFailureScreen(
                state = ServiceFailureUiState(
                    ServiceFailureContext(
                        stepId = "preview-english-generation",
                        stepLabelZh = "英语生成",
                        preservedDataLabelZh = "已确认的中文内容",
                        category = ServiceFailureCategory.AI_OR_SERVICE,
                    ),
                    preservedItemsZh = listOf(
                        "今天开会时，我本来有点紧张。",
                        "但最后还是鼓起勇气把想法讲清楚了。",
                    ),
                ),
                onAction = {},
            )
        }
        PreviewDestination.AGREEMENT_DETAIL -> PreviewFrame(returnToCatalog, modifier) {
            AgreementDetailScreen(
                state = AgreementDetailUiState(
                    kind = AgreementKind.USER_AGREEMENT,
                    registrationDraft = RegistrationDraftSnapshot(
                        email = "learner@example.com",
                        password = "preview-password",
                        birthYearMonth = "2000-08",
                        acceptedTerms = true,
                        acceptedPrivacyPolicy = false,
                    ),
                    document = AgreementDocument(
                        kind = AgreementKind.USER_AGREEMENT,
                        titleZh = "RealLanguage 用户协议",
                        version = "v1.26",
                        sections = listOf(
                            "RealLanguage 是一款专注于英语学习的在线服务平台，致力于通过真实语境对话、AI 练习、学习记录与个性化反馈等功能，帮助用户提升英语听说读写能力。本服务的具体功能与形式可能会根据产品发展进行调整，我们会通过站内通知或公告的方式及时向用户说明。",
                            "2.1 你必须年满 14 周岁才能注册和使用本服务。若你未满 14 周岁，请在监护人同意并指导下使用本服务。\n2.2 你在注册时应提供真实、准确和完整的信息，并及时更新，以确保信息的时效性。\n2.3 你应妥善保管你的账号与密码，不得将其转让、借用或分享给他人使用。",
                            "你主动提交的日记、录音与练习内容仅用于完成对应学习任务，并按已经确认的产品规则处理。",
                            "AI 服务按实际发生的调用计费；未实际发生的后续调用不会扣费。",
                            "RealLanguage 软件、界面与服务内容的知识产权依法受到保护。",
                            "网络或服务失败不会创建错误的固定内容、学习结果或未实际发生的费用。",
                            "如需帮助，请通过产品内已经提供的支持入口联系我们。",
                        ),
                    ),
                    updatedDateZh = "2026 年 9 月 3 日",
                    loading = false,
                ),
                onAction = {},
            )
        }
        PreviewDestination.PASSWORD_RESET -> PreviewFrame(returnToCatalog, modifier) {
            PasswordResetScreen(
                state = PasswordResetUiState(
                    step = PasswordResetStep.WAITING_VERIFICATION,
                    email = "indewo@outlook.com",
                    completionDestination = PasswordResetCompletionDestination.LOGIN,
                ),
                onAction = {},
            )
        }
        PreviewDestination.SUBSEQUENT_SETTINGS -> PreviewFrame(returnToCatalog, modifier) {
            val settings = InitialSettingsDraft(
                formalityLevel = 3,
                slangEnabled = true,
                basicVocabulary = BasicVocabulary.CET_6,
                purposeVocabularies = setOf(PurposeVocabulary.BUSINESS_ENGLISH),
                styleReference = "清晰、自然、适合真实交流",
                voiceEnabled = true,
                autoPlayEnabled = false,
                speechSpeedLevel = 3,
            )
            SubsequentSettingsScreen(
                state = SubsequentSettingsUiState(
                    original = settings.copy(formalityLevel = 2),
                    draft = settings,
                    loading = false,
                    originalReminder = com.reallanguage.app.feature.settings.domain.ReminderSettingsDraft(false, java.time.LocalTime.of(20, 0)),
                    reminderDraft = com.reallanguage.app.feature.settings.domain.ReminderSettingsDraft(false, java.time.LocalTime.of(20, 0)),
                ),
                onAction = {},
            )
        }
        PreviewDestination.SETTINGS_EFFECT_SCOPE -> PreviewFrame(returnToCatalog, modifier) {
            val original = InitialSettingsDraft(formalityLevel = 3, basicVocabulary = BasicVocabulary.CET_6)
            SettingsEffectScopeScreen(
                state = SettingsEffectScopeUiState(
                    originalSettings = original,
                    proposedSettings = original.copy(formalityLevel = 4),
                    sourceContext = SettingsSourceContext.FixedContent("source-1", "昨晚的日记"),
                ),
                onAction = {},
            )
        }
        PreviewDestination.DAILY_REMINDER_INTRO -> Box(
            modifier = modifier.fillMaxSize().background(RlColor.Background),
        ) {
            HomeScreen(HomeUiState(hasDueReview = true), onAction = {})
            DailyReminderIntroDialog(DailyReminderIntroUiState(), onAction = {})
        }
        PreviewDestination.INCOMPLETE_LEARNING -> PreviewFrame(returnToCatalog, modifier) {
            IncompleteLearningScreen(
                IncompleteLearningUiState(
                    entries = listOf(
                        IncompleteLearningEntry(
                            "learning-1", OrdinarySourceType.DIARY, "9 月 2 日日记", 2,
                            "LR-06", "整句会说", ResumeLearningKind.ORDINARY,
                        ),
                        IncompleteLearningEntry(
                            "learning-2", OrdinarySourceType.QUICK_RECORDING, "本机录音 · 01:24", 1,
                            "LR-04", "手写抄写", ResumeLearningKind.ORDINARY,
                        ),
                        IncompleteLearningEntry(
                            "learning-3", OrdinarySourceType.AI_SCENE, "AI 场景 · 面试前", 3,
                            "LR-08", "整句会说", ResumeLearningKind.ORDINARY,
                        ),
                    ),
                    loading = false,
                ),
                onAction = {},
            )
        }
        PreviewDestination.MESSAGES -> PreviewFrame(returnToCatalog, modifier) {
            MessagesScreen(
                MessagesUiState(
                    messages = listOf(
                        AppMessage(
                            "m1", MessageKind.MANUAL_REVIEW_RESULT, "人工复核已完成", "昨晚的日记",
                            System.currentTimeMillis() - 60 * 60 * 1000, false, AppMessageTarget("manual-review", "review-1"), MessageTargetState.AVAILABLE,
                        ),
                        AppMessage(
                            "m2", MessageKind.SYSTEM, "学习内容已生成", "本机录音 · 共 4 句",
                            System.currentTimeMillis() - 4 * 60 * 60 * 1000, true, AppMessageTarget("learning", "learning-2"), MessageTargetState.AVAILABLE,
                        ),
                        AppMessage(
                            "m3", MessageKind.MANUAL_REVIEW_RESULT, "人工审核未通过", "请根据说明修改并重新提交",
                            System.currentTimeMillis() - 24 * 60 * 60 * 1000, false, AppMessageTarget("manual-review", "review-3"), MessageTargetState.AVAILABLE,
                        ),
                        AppMessage(
                            "m4", MessageKind.SYSTEM, "今日回顾已就绪", "3 个重点句等待回顾",
                            System.currentTimeMillis() - 35 * 60 * 60 * 1000, false, AppMessageTarget("review", "today"), MessageTargetState.AVAILABLE,
                        ),
                    ),
                    loading = false,
                ),
                onAction = {},
            )
        }
        PreviewDestination.ACCOUNT -> PreviewFrame(returnToCatalog, modifier) {
            AccountScreen(
                AccountUiState(account = AccountSnapshot("learner@example.com"), loading = false),
                onAction = {},
            )
        }
        PreviewDestination.RECORDING_INTRO -> PreviewFrame(returnToCatalog, modifier) {
            RecordingIntroScreen(RecordingIntroUiState(), onAction = {})
        }
        PreviewDestination.ACTIVE_RECORDING -> PreviewFrame(returnToCatalog, modifier) {
            ActiveRecordingScreen(
                ActiveRecordingUiState(recordingId = "r1", elapsedSeconds = 37, recording = true),
                onAction = {},
                homeState = HomeUiState(hasDueReview = true, hasIncompleteLearning = true),
            )
        }
        PreviewDestination.RECORDING_CONFIRMATION -> PreviewFrame(returnToCatalog, modifier) {
            RecordingConfirmationScreen(
                RecordingConfirmationUiState(
                    recording = LocalRecordingSummary("r1", 42), loading = false,
                ),
                onAction = {},
            )
        }
        PreviewDestination.RECORDING_DELETION -> Box(
            modifier = modifier.fillMaxSize().background(RlColor.Background),
        ) {
            RecordingConfirmationScreen(
                RecordingConfirmationUiState(
                    recording = LocalRecordingSummary("r1", 42), loading = false,
                ),
                onAction = {},
            )
            RecordingDeletionDialog(RecordingDeletionUiState("r1"), durationSeconds = 84, onAction = {})
        }
        PreviewDestination.RECORDING_TRANSCRIPTION -> PreviewFrame(returnToCatalog, modifier) {
            RecordingTranscriptionScreen(
                modifier = Modifier.consumeWindowInsets(WindowInsets.safeDrawing),
                state = RecordingTranscriptionUiState(
                    TranscriptionRequest(
                        "r1", 84, "audio/mp4", "zh-CN", RecordingEndReason.USER_STOPPED,
                    ),
                ),
            )
        }
        PreviewDestination.TRANSCRIPT_RESULT -> PreviewFrame(returnToCatalog, modifier) {
            TranscriptResultScreen(
                modifier = Modifier.consumeWindowInsets(WindowInsets.safeDrawing),
                state = TranscriptResultUiState(
                    transcript = RecordingTranscript("t1", "r1", "我昨天，去公园。"),
                    currentText = "我昨天，去公园。",
                    loading = false,
                    repairComparison = TranscriptRepairResult.Proposed(
                        repairedText = "我昨天去了公园。",
                        changes = listOf(
                            RepairChange(
                                "我昨天，去公园。",
                                "我昨天去了公园。",
                                RepairReason.CONFIRMED_DISFLUENCY,
                            ),
                        ),
                    ),
                ),
                onAction = {},
                onReturn = returnToCatalog,
            )
        }
        PreviewDestination.TRANSCRIPTION_FAILURE -> PreviewFrame(returnToCatalog, modifier) {
            TranscriptionFailureScreen(
                state = TranscriptionFailureUiState("r1", durationSeconds = 84),
                onAction = {},
            )
        }
        PreviewDestination.MICROPHONE_PERMISSION -> PreviewFrame(returnToCatalog, modifier) {
            MicrophonePermissionScreen(
                state = MicrophonePermissionUiState(canRequestDirectly = false),
                onAction = {},
            )
        }
        PreviewDestination.SCENE_HOME -> PreviewFrame(returnToCatalog, modifier) {
            SceneHomeScreen(
                state = SceneHomeUiState(
                    loadingRecommendations = false,
                    recommendations = listOf(
                        RecentSceneRecommendation("rec-1", "面试结束后的复盘", "与近期内容相关", listOf("source-1")),
                        RecentSceneRecommendation("rec-2", "和新同事第一次见面", "与近期内容相关", listOf("source-2")),
                        RecentSceneRecommendation("rec-3", "餐厅提出特殊需求", "与近期内容相关", listOf("source-3")),
                        RecentSceneRecommendation("rec-4", "机场行李出现问题", "与近期内容相关", listOf("source-4")),
                    ),
                    directDescription = "",
                ),
                onAction = {},
            )
        }
        PreviewDestination.SCENE_TYPE -> PreviewFrame(returnToCatalog, modifier) {
            SceneTypeScreen(
                state = SceneTypeUiState(
                    start = SceneStart.DirectDescription("向同事解释项目延期"),
                    selectedType = SceneContentType.DIALOGUE,
                ),
                onAction = {},
            )
        }
        PreviewDestination.SCENE_QUESTIONS -> PreviewFrame(returnToCatalog, modifier) {
            val answeredQuestion = SceneQuestion("q1", "你和对方是什么关系？", emptyList())
            val answer = SceneAnswer("q1", "我们是同事")
            SceneQuestionsScreen(
                state = SceneQuestionsUiState(
                    start = SceneStart.DirectDescription("向同事解释项目延期"),
                    contentType = SceneContentType.DIALOGUE,
                    currentQuestion = SceneQuestion(
                        "q2", "这次沟通发生在哪里？", listOf("办公室", "线上会议", "即时消息"),
                    ),
                    confirmedAnswers = listOf(answer),
                    history = listOf(AnsweredSceneQuestion(answeredQuestion, answer)),
                    loading = false,
                ),
                onAction = {},
            )
        }
        PreviewDestination.SCENE_ABANDON -> Box(modifier.fillMaxSize()) {
            val answeredQuestion = SceneQuestion("q1", "你和对方是什么关系？", emptyList())
            val answer = SceneAnswer("q1", "我们是同事")
            val secondQuestion = SceneQuestion("q2", "这次沟通发生在哪里？", emptyList())
            val secondAnswer = SceneAnswer("q2", "办公室会议结束后")
            val session = SceneQuestionSession(
                start = SceneStart.DirectDescription("面试结束后的复盘"),
                contentType = SceneContentType.DIALOGUE,
                answers = listOf(answer, secondAnswer),
                operationId = "preview-operation",
            )
            SceneQuestionsScreen(
                state = SceneQuestionsUiState(
                    start = session.start,
                    contentType = session.contentType,
                    currentQuestion = SceneQuestion(
                        "q2", "这次沟通发生在哪里？", listOf("办公室", "线上会议", "即时消息"),
                    ),
                    confirmedAnswers = session.answers,
                    history = listOf(
                        AnsweredSceneQuestion(answeredQuestion, answer),
                        AnsweredSceneQuestion(secondQuestion, secondAnswer),
                    ),
                    loading = false,
                ),
                onAction = {},
            )
            SceneAbandonDialog(state = SceneAbandonUiState(session), onAction = {})
        }
        PreviewDestination.SCENE_GENERATION -> PreviewFrame(returnToCatalog, modifier) {
            SceneGenerationScreen(
                state = SceneGenerationUiState(
                    session = SceneQuestionSession(
                        start = SceneStart.DirectDescription("向同事解释项目延期"),
                        contentType = SceneContentType.DIALOGUE,
                        answers = listOf(SceneAnswer("q1", "我们是同事")),
                        operationId = "preview-operation",
                    ),
                    generating = true,
                ),
                onAction = {},
            )
        }
        PreviewDestination.SCENE_CHINESE_REVIEW -> PreviewFrame(returnToCatalog, modifier) {
            val session = SceneQuestionSession(
                start = SceneStart.DirectDescription("日常心情表达"),
                contentType = SceneContentType.DIALOGUE,
                answers = listOf(SceneAnswer("q1", "开心")),
                operationId = "preview-operation",
            )
            SceneChineseReviewScreen(
                state = SceneChineseReviewUiState(
                    session = session,
                    units = listOf(
                        SceneDraftUnit("u1", SceneSpeaker.OTHER, "你看起来心情不错。", emptyList()),
                        SceneDraftUnit("u2", SceneSpeaker.USER, "对，吃饭的时候我挺开心。", emptyList()),
                        SceneDraftUnit("u3", SceneSpeaker.OTHER, "那挺好的，你就先享受吧。", emptyList()),
                    ),
                ),
                onAction = {},
            )
        }
    }
}

}

@Composable
private fun DevelopmentCatalog(
    open: (PreviewDestination) -> Unit,
    modifier: Modifier,
) {
    RlPaperBackground(modifier) {
        Box(
            modifier = Modifier.fillMaxSize().padding(RlSpace.Space24),
            contentAlignment = Alignment.Center,
        ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 920.dp)
                .fillMaxWidth(),
            shape = RlShape.Panel,
            color = RlColor.Surface,
        ) {
            Column(
                modifier = Modifier
                    .padding(RlSpace.Space24)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(RlSpace.Space16),
            ) {
                Text(
                    stringResource(R.string.dev_preview_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.dev_preview_explanation),
                    color = RlColor.TextSecondary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
                    verticalArrangement = Arrangement.spacedBy(RlSpace.Space12),
                ) {
                    PreviewDestination.entries
                        .filterNot { it == PreviewDestination.CATALOG }
                        .forEach { destination ->
                            OutlinedButton(onClick = { open(destination) }) {
                                Text(destination.label())
                            }
                        }
                }
            }
        }
    }
}

}

@Composable
private fun PreviewFrame(
    onReturnToCatalog: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    RlPaperBackground(modifier) {
        content()
        if (LocalShowPreviewReturn.current) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(RlSpace.Space8),
                shape = RlShape.Input,
                color = RlColor.Surface,
                shadowElevation = 2.dp,
            ) {
                TextButton(onClick = onReturnToCatalog) {
                    Text(stringResource(R.string.dev_preview_return))
                }
            }
        }
    }
}

@Composable
private fun PreviewDestination.label(): String = stringResource(
    when (this) {
        PreviewDestination.SCENE_DIRECT_DISCARD -> R.string.v73_preview_scene_direct_discard
        PreviewDestination.SCENE_GENERATION_FAILED -> R.string.v73_preview_scene_generation_failed
        PreviewDestination.SAFETY_CHECK_FAILED -> R.string.v73_preview_safety_check_failed
        PreviewDestination.TRANSCRIPT_SEGMENT_REVIEW -> R.string.v73_preview_transcript_segments
        PreviewDestination.LOCAL_RECORDINGS_EMPTY -> R.string.v73_preview_local_recordings_empty
        PreviewDestination.LOCAL_RECORDINGS_FAILED -> R.string.v73_preview_local_recordings_failed
        PreviewDestination.RECORDING_CONFIRMATION_FAILED -> R.string.v73_preview_recording_confirmation_failed
        PreviewDestination.TRANSCRIPT_LOAD_FAILED -> R.string.v73_preview_transcript_load_failed
        PreviewDestination.SCENE_QUESTIONS_INVALID -> R.string.v73_preview_scene_questions_invalid
        PreviewDestination.SCENE_QUESTIONS_INSUFFICIENT -> R.string.v73_preview_scene_questions_insufficient
        PreviewDestination.SAFETY_HANDLING_LOADING -> R.string.v73_preview_safety_loading
        PreviewDestination.SAFETY_HANDLING_LOAD_FAILED -> R.string.v73_preview_safety_load_failed
        PreviewDestination.ENGLISH_GENERATION -> R.string.v73_preview_english_generation
        PreviewDestination.ENGLISH_GENERATION_FAILED -> R.string.v73_preview_english_failed
        PreviewDestination.LEARNING_TARGET_LOADING -> R.string.v73_preview_target_loading
        PreviewDestination.LEARNING_TARGET_FAILED -> R.string.v73_preview_target_failed
        PreviewDestination.SCENE_DIRECT_DESCRIPTION -> R.string.v73_preview_scene_direct
        PreviewDestination.LOCAL_RECORDINGS -> R.string.v73_preview_local_recordings
        PreviewDestination.EXPLANATION_PREPARATION -> R.string.v73_preview_explanation_preparation
        PreviewDestination.EXPLANATION_PREPARATION_FAILED -> R.string.v73_preview_explanation_failed
        PreviewDestination.READ_WRITE_CORRECTION -> R.string.v73_preview_read_write_correction
        PreviewDestination.PATTERN_INDEPENDENT -> R.string.v73_preview_pattern_independent
        PreviewDestination.ACCOUNT_DELETION -> R.string.v73_preview_account_deletion
        PreviewDestination.CATALOG -> error("Catalog has no entry label")
        PreviewDestination.LOGIN -> R.string.auth_login
        PreviewDestination.REGISTRATION -> R.string.auth_register
        PreviewDestination.AGE_INELIGIBLE -> R.string.dev_preview_age_ineligible
        PreviewDestination.EMAIL_VERIFICATION -> R.string.auth_email_verification_title
        PreviewDestination.SETTINGS_ONE -> R.string.dev_preview_settings_one
        PreviewDestination.SETTINGS_TWO -> R.string.dev_preview_settings_two
        PreviewDestination.SETTINGS_THREE -> R.string.dev_preview_settings_three
        PreviewDestination.HOME -> R.string.dev_preview_home
        PreviewDestination.DIARY_INPUT -> R.string.dev_preview_diary
        PreviewDestination.SAFETY_CHECK -> R.string.dev_preview_safety_check
        PreviewDestination.SAFETY_HANDLING -> R.string.dev_preview_safety_handling
        PreviewDestination.REVIEW_PENDING -> R.string.dev_preview_review_pending
        PreviewDestination.REVIEW_APPROVED -> R.string.dev_preview_review_approved
        PreviewDestination.REVIEW_NOT_APPROVED -> {
            R.string.dev_preview_review_not_approved
        }
        PreviewDestination.FOCUS_SELECTION -> R.string.dev_preview_focus_selection
        PreviewDestination.ENGLISH_CONFIRMATION -> {
            R.string.dev_preview_english_confirmation
        }
        PreviewDestination.LEARNING_TARGETS -> R.string.dev_preview_learning_targets
        PreviewDestination.FINAL_FIX -> R.string.dev_preview_final_fix
        PreviewDestination.CHAPTER_LEARNING -> R.string.dev_preview_chapter_learning
        PreviewDestination.EXPLANATION_READING -> {
            R.string.dev_preview_explanation_reading
        }
        PreviewDestination.READ_SPEAK -> R.string.dev_preview_read_speak
        PreviewDestination.HANDWRITING -> R.string.dev_preview_handwriting
        PreviewDestination.AUDIO_DICTATION -> R.string.dev_preview_audio_dictation
        PreviewDestination.FULL_SENTENCE_SPEAKING -> {
            R.string.dev_preview_full_sentence_speaking
        }
        PreviewDestination.PATTERN_SPEAKING -> R.string.dev_preview_pattern_speaking
        PreviewDestination.COMPLETE_WRITING -> R.string.dev_preview_complete_writing
        PreviewDestination.SCENE_RESPONSE -> R.string.dev_preview_scene_response
        PreviewDestination.LEARNING_EXIT -> R.string.dev_preview_learning_exit
        PreviewDestination.CHAPTER_RESULT -> R.string.dev_preview_chapter_result
        PreviewDestination.CHAPTER_SELECTION -> R.string.dev_preview_chapter_selection
        PreviewDestination.CHAPTER_ABANDON -> R.string.dev_preview_chapter_abandon
        PreviewDestination.COMPLETED_STEP_REVIEW -> R.string.dev_preview_completed_step
        PreviewDestination.TODAY_REVIEW -> R.string.dev_preview_today_review
        PreviewDestination.REVIEW_CHAPTER -> R.string.dev_preview_review_chapter
        PreviewDestination.REVIEW_EXIT -> R.string.dev_preview_review_exit
        PreviewDestination.REVIEW_RESULT -> R.string.dev_preview_review_result
        PreviewDestination.VOCABULARY_BOOK -> R.string.dev_preview_vocabulary_book
        PreviewDestination.VOCABULARY_DETAIL -> R.string.dev_preview_vocabulary_detail
        PreviewDestination.PATTERN_BOOK -> R.string.dev_preview_pattern_book
        PreviewDestination.PATTERN_DETAIL -> R.string.dev_preview_pattern_detail
        PreviewDestination.PATTERN_REMOVAL -> R.string.dev_preview_pattern_removal
        PreviewDestination.AI_BILLING -> R.string.dev_preview_ai_billing
        PreviewDestination.RECHARGE -> R.string.dev_preview_recharge
        PreviewDestination.INSUFFICIENT_BALANCE -> R.string.dev_preview_insufficient_balance
        PreviewDestination.REFUND -> R.string.dev_preview_refund
        PreviewDestination.CONNECTION_ERROR, PreviewDestination.STARTUP_CONNECTION_ERROR -> R.string.dev_preview_connection_error
        PreviewDestination.SERVICE_FAILURE -> R.string.dev_preview_service_failure
        PreviewDestination.AGREEMENT_DETAIL -> R.string.dev_preview_agreement_detail
        PreviewDestination.PASSWORD_RESET -> R.string.dev_preview_password_reset
        PreviewDestination.SUBSEQUENT_SETTINGS -> R.string.dev_preview_subsequent_settings
        PreviewDestination.SETTINGS_EFFECT_SCOPE -> R.string.dev_preview_settings_effect
        PreviewDestination.DAILY_REMINDER_INTRO -> R.string.dev_preview_daily_reminder_intro
        PreviewDestination.INCOMPLETE_LEARNING -> R.string.dev_preview_incomplete_learning
        PreviewDestination.MESSAGES -> R.string.dev_preview_messages
        PreviewDestination.ACCOUNT -> R.string.dev_preview_account
        PreviewDestination.RECORDING_INTRO -> R.string.dev_preview_recording_intro
        PreviewDestination.ACTIVE_RECORDING -> R.string.dev_preview_active_recording
        PreviewDestination.RECORDING_CONFIRMATION -> R.string.dev_preview_recording_confirmation
        PreviewDestination.RECORDING_DELETION -> R.string.dev_preview_recording_deletion
        PreviewDestination.RECORDING_TRANSCRIPTION -> R.string.dev_preview_recording_transcription
        PreviewDestination.TRANSCRIPT_RESULT -> R.string.dev_preview_transcript_result
        PreviewDestination.TRANSCRIPTION_FAILURE -> R.string.dev_preview_transcription_failure
        PreviewDestination.MICROPHONE_PERMISSION -> R.string.dev_preview_microphone_permission
        PreviewDestination.SCENE_HOME -> R.string.dev_preview_scene_home
        PreviewDestination.SCENE_TYPE -> R.string.dev_preview_scene_type
        PreviewDestination.SCENE_QUESTIONS -> R.string.dev_preview_scene_questions
        PreviewDestination.SCENE_ABANDON -> R.string.dev_preview_scene_abandon
        PreviewDestination.SCENE_GENERATION -> R.string.dev_preview_scene_generation
        PreviewDestination.SCENE_CHINESE_REVIEW -> R.string.dev_preview_scene_chinese_review
    },
)
