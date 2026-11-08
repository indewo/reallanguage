package com.reallanguage.app.core.app

import com.reallanguage.app.feature.formation.domain.toFixedSourceProjection
import com.reallanguage.app.feature.formation.domain.SemanticUnit

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Patterns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.reallanguage.app.BuildConfig
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.navigation.AppCoordinatorAction
import com.reallanguage.app.core.navigation.AppCoordinatorViewModel
import com.reallanguage.app.core.navigation.AppRoute
import com.reallanguage.app.core.session.AndroidInstallationIdentity
import com.reallanguage.app.core.session.HttpSessionApi
import com.reallanguage.app.core.session.JsonSessionWireCodec
import com.reallanguage.app.core.session.ProductionSessionRepository
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.UrlConnectionSessionTransport
import com.reallanguage.app.core.session.androidSessionCredentialStore
import com.reallanguage.app.core.vocabulary.AndroidEmbeddedVocabularyCatalog
import com.reallanguage.app.core.vocabulary.AndroidVocabularyPoolPreference
import com.reallanguage.app.core.network.BalanceAwareHttpTransport
import com.reallanguage.app.core.network.AddressFailoverHttpTransport
import com.reallanguage.app.core.network.AndroidNsdLocalApiDiscovery
import com.reallanguage.app.feature.auth.data.HttpRegistrationRepository
import com.reallanguage.app.feature.auth.data.HttpAgreementDocumentRepository
import com.reallanguage.app.feature.auth.data.JsonAgreementDocumentWireCodec
import com.reallanguage.app.feature.auth.data.JsonRegistrationWireCodec
import com.reallanguage.app.feature.auth.data.HttpVerificationEmailRepository
import com.reallanguage.app.feature.auth.data.JsonVerificationEmailWireCodec
import com.reallanguage.app.feature.auth.data.HttpPasswordResetRepository
import com.reallanguage.app.feature.auth.data.JsonPasswordResetWireCodec
import com.reallanguage.app.feature.auth.domain.CurrentYearMonth
import com.reallanguage.app.feature.auth.domain.EmailSyntaxValidator
import com.reallanguage.app.feature.auth.domain.LoginFormValidator
import com.reallanguage.app.feature.auth.domain.AgreementKind
import com.reallanguage.app.feature.auth.domain.RegistrationDraftSnapshot
import com.reallanguage.app.feature.auth.domain.RegistrationFormValidator
import com.reallanguage.app.feature.auth.presentation.AuthEffect
import com.reallanguage.app.feature.auth.presentation.AuthScreen
import com.reallanguage.app.feature.auth.presentation.AuthViewModel
import com.reallanguage.app.feature.auth.presentation.AgreementDetailEffect
import com.reallanguage.app.feature.auth.presentation.AgreementDetailScreen
import com.reallanguage.app.feature.auth.presentation.AgreementDetailViewModel
import com.reallanguage.app.feature.auth.presentation.AgeIneligibleScreen
import com.reallanguage.app.feature.auth.presentation.EmailVerificationEffect
import com.reallanguage.app.feature.auth.presentation.EmailVerificationScreen
import com.reallanguage.app.feature.auth.presentation.EmailVerificationViewModel
import com.reallanguage.app.feature.auth.presentation.RealVerificationCountdown
import com.reallanguage.app.feature.auth.presentation.RealVerificationStatusPolling
import com.reallanguage.app.feature.auth.presentation.PasswordResetEffect
import com.reallanguage.app.feature.auth.presentation.PasswordResetScreen
import com.reallanguage.app.feature.auth.presentation.PasswordResetViewModel
import com.reallanguage.app.feature.billing.data.HttpAiBillingRepository
import com.reallanguage.app.feature.billing.data.HttpRechargeRepository
import com.reallanguage.app.feature.billing.data.HttpRefundRepository
import com.reallanguage.app.feature.billing.presentation.AiBillingEffect
import com.reallanguage.app.feature.billing.presentation.AiBillingScreen
import com.reallanguage.app.feature.billing.presentation.AiBillingViewModel
import com.reallanguage.app.feature.billing.presentation.RechargeEffect
import com.reallanguage.app.feature.billing.presentation.RechargeScreen
import com.reallanguage.app.feature.billing.presentation.RechargeViewModel
import com.reallanguage.app.feature.billing.presentation.RefundEffect
import com.reallanguage.app.feature.billing.presentation.RefundScreen
import com.reallanguage.app.feature.billing.presentation.RefundViewModel
import com.reallanguage.app.feature.billing.domain.BalanceExitDisposition
import com.reallanguage.app.feature.billing.domain.BalanceExitResult
import com.reallanguage.app.feature.billing.domain.BalanceInsufficientContext
import com.reallanguage.app.feature.billing.domain.BalanceInsufficientRepository
import com.reallanguage.app.feature.billing.domain.BalanceOriginState
import com.reallanguage.app.feature.billing.presentation.InsufficientBalanceAction
import com.reallanguage.app.feature.billing.presentation.InsufficientBalanceEffect
import com.reallanguage.app.feature.billing.presentation.InsufficientBalanceScreen
import com.reallanguage.app.feature.billing.presentation.InsufficientBalanceViewModel
import com.reallanguage.app.feature.home.presentation.HomeScreen
import com.reallanguage.app.feature.home.presentation.DailyReminderIntroViewModel
import com.reallanguage.app.feature.home.presentation.DailyReminderIntroDialog
import com.reallanguage.app.feature.home.presentation.DailyReminderIntroEffect
import com.reallanguage.app.feature.home.presentation.DailyReminderIntroAction
import com.reallanguage.app.feature.home.domain.DailyReminderHomeEntry
import com.reallanguage.app.feature.home.domain.DailyReminderIntroRepository
import com.reallanguage.app.feature.home.domain.DailyReminderIntroResult
import com.reallanguage.app.feature.home.domain.ReminderPermission
import com.reallanguage.app.feature.home.data.AndroidDailyReminderRuntime
import com.reallanguage.app.feature.home.data.RuntimeReminderLocalIdentity
import com.reallanguage.app.feature.capture.presentation.AudioImportScreen
import com.reallanguage.app.feature.capture.presentation.ScreenshotImportScreen
import com.reallanguage.app.feature.capture.presentation.BilingualPassagesScreen
import com.reallanguage.app.feature.recording.data.captureRecordingUri
import com.reallanguage.app.feature.recording.data.transcribeCaptureAudio
import com.reallanguage.app.feature.recording.presentation.RecordingCaptureEntry
import com.reallanguage.app.feature.home.presentation.HomeAction
import com.reallanguage.app.feature.home.presentation.HomeUiState
import com.reallanguage.app.feature.home.data.AndroidIncompleteLearningRepository
import com.reallanguage.app.feature.home.presentation.IncompleteLearningEffect
import com.reallanguage.app.feature.home.presentation.IncompleteLearningScreen
import com.reallanguage.app.feature.home.presentation.IncompleteLearningViewModel
import com.reallanguage.app.feature.home.data.HttpMessagesRepository
import com.reallanguage.app.feature.home.presentation.MessagesEffect
import com.reallanguage.app.feature.home.presentation.MessagesScreen
import com.reallanguage.app.feature.home.presentation.MessagesViewModel
import com.reallanguage.app.feature.home.data.HttpAccountRepository
import com.reallanguage.app.feature.home.presentation.AccountEffect
import com.reallanguage.app.feature.home.presentation.AccountScreen
import com.reallanguage.app.feature.home.presentation.AccountViewModel
import com.reallanguage.app.feature.home.data.AndroidAccountDataCleaner
import com.reallanguage.app.feature.home.data.DeletedAccountSessionCleaner
import com.reallanguage.app.feature.home.data.HttpAccountDeletionRepository
import com.reallanguage.app.feature.home.presentation.AccountDeletionDialog
import com.reallanguage.app.feature.home.presentation.AccountDeletionEffect
import com.reallanguage.app.feature.home.presentation.AccountDeletionViewModel
import com.reallanguage.app.feature.recording.data.AndroidLocalRecordingsRepository
import com.reallanguage.app.feature.recording.presentation.LocalRecordingsEffect
import com.reallanguage.app.feature.recording.presentation.LocalRecordingsScreen
import com.reallanguage.app.feature.recording.presentation.LocalRecordingsViewModel
import com.reallanguage.app.feature.safety.data.HttpManualReviewResultRepository
import com.reallanguage.app.feature.safety.presentation.ManualReviewResultEffect
import com.reallanguage.app.feature.safety.presentation.ManualReviewResultViewModel
import com.reallanguage.app.feature.recording.data.AndroidRecordingConfirmationRepository
import com.reallanguage.app.feature.recording.data.AndroidRecordingDeletionRepository
import com.reallanguage.app.feature.recording.data.AndroidRecordingRuntimeRepository
import com.reallanguage.app.feature.recording.data.HttpRecordingRepositories
import com.reallanguage.app.feature.recording.domain.RecordingEndReason
import com.reallanguage.app.feature.recording.domain.TranscriptionRequest
import com.reallanguage.app.feature.recording.presentation.ActiveRecordingViewModel
import com.reallanguage.app.feature.recording.presentation.MicrophonePermissionAction
import com.reallanguage.app.feature.recording.presentation.MicrophonePermissionEffect
import com.reallanguage.app.feature.recording.presentation.MicrophonePermissionScreen
import com.reallanguage.app.feature.recording.presentation.MicrophonePermissionViewModel
import com.reallanguage.app.feature.recording.presentation.RecordingConfirmationEffect
import com.reallanguage.app.feature.recording.presentation.RecordingConfirmationScreen
import com.reallanguage.app.feature.recording.presentation.RecordingConfirmationViewModel
import com.reallanguage.app.feature.recording.presentation.RecordingDeletionDialog
import com.reallanguage.app.feature.recording.presentation.RecordingDeletionEffect
import com.reallanguage.app.feature.recording.presentation.RecordingDeletionViewModel
import com.reallanguage.app.feature.recording.presentation.RecordingIntroAction
import com.reallanguage.app.feature.recording.presentation.RecordingIntroEffect
import com.reallanguage.app.feature.recording.presentation.RecordingIntroScreen
import com.reallanguage.app.feature.recording.presentation.RecordingIntroViewModel
import com.reallanguage.app.feature.recording.presentation.RecordingTranscriptionEffect
import com.reallanguage.app.feature.recording.presentation.RecordingTranscriptionScreen
import com.reallanguage.app.feature.recording.presentation.RecordingTranscriptionViewModel
import com.reallanguage.app.feature.recording.presentation.TranscriptResultEffect
import com.reallanguage.app.feature.recording.presentation.TranscriptResultScreen
import com.reallanguage.app.feature.recording.presentation.TranscriptResultViewModel
import com.reallanguage.app.feature.recording.presentation.TranscriptionFailureEffect
import com.reallanguage.app.feature.recording.presentation.TranscriptionFailureScreen
import com.reallanguage.app.feature.recording.presentation.TranscriptionFailureViewModel
import com.reallanguage.app.feature.diary.data.AndroidMediaRecorderDiaryDictationCapture
import com.reallanguage.app.feature.diary.data.HttpDiaryDictationRepository
import com.reallanguage.app.feature.scene.data.HttpSceneFormationRepositories
import com.reallanguage.app.feature.scene.data.HttpSceneHomeRepository
import com.reallanguage.app.feature.scene.data.LocalSceneAbandonRepository
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneGeneratedDraft
import com.reallanguage.app.feature.scene.domain.SceneQuestionSession
import com.reallanguage.app.feature.scene.domain.SceneStart
import com.reallanguage.app.feature.scene.presentation.SceneAbandonEffect
import com.reallanguage.app.feature.scene.presentation.SceneAbandonDialog
import com.reallanguage.app.feature.scene.presentation.SceneAbandonViewModel
import com.reallanguage.app.feature.scene.presentation.SceneChineseReviewEffect
import com.reallanguage.app.feature.scene.presentation.SceneChineseReviewScreen
import com.reallanguage.app.feature.scene.presentation.SceneChineseReviewViewModel
import com.reallanguage.app.feature.scene.presentation.SceneGenerationEffect
import com.reallanguage.app.feature.scene.presentation.SceneGenerationScreen
import com.reallanguage.app.feature.scene.presentation.SceneGenerationViewModel
import com.reallanguage.app.feature.scene.presentation.SceneHomeEffect
import com.reallanguage.app.feature.scene.presentation.SceneHomeScreen
import com.reallanguage.app.feature.scene.presentation.SceneHomeViewModel
import com.reallanguage.app.feature.scene.presentation.SceneDirectDescriptionEffect
import com.reallanguage.app.feature.scene.presentation.SceneDirectDescriptionScreen
import com.reallanguage.app.feature.scene.presentation.SceneDirectDescriptionViewModel
import com.reallanguage.app.feature.scene.presentation.SceneQuestionsEffect
import com.reallanguage.app.feature.scene.presentation.SceneQuestionsScreen
import com.reallanguage.app.feature.scene.presentation.SceneQuestionsViewModel
import com.reallanguage.app.feature.scene.presentation.SceneTypeEffect
import com.reallanguage.app.feature.scene.presentation.SceneTypeScreen
import com.reallanguage.app.feature.scene.presentation.SceneTypeViewModel
import com.reallanguage.app.feature.knowledge.data.AndroidVocabularyBookRepository
import com.reallanguage.app.feature.knowledge.data.HttpCurrentVocabularyScopeSource
import com.reallanguage.app.feature.knowledge.data.LocalFirstVocabularyScopeSource
import com.reallanguage.app.feature.knowledge.presentation.VocabularyBookEffect
import com.reallanguage.app.feature.knowledge.presentation.VocabularyBookAction
import com.reallanguage.app.feature.knowledge.presentation.VocabularyBookScreen
import com.reallanguage.app.feature.knowledge.presentation.VocabularyBookViewModel
import com.reallanguage.app.feature.knowledge.data.AndroidPatternBookRepository
import com.reallanguage.app.feature.knowledge.data.AndroidPatternRemovalRepository
import com.reallanguage.app.feature.knowledge.presentation.PatternBookAction
import com.reallanguage.app.feature.knowledge.presentation.PatternBookEffect
import com.reallanguage.app.feature.knowledge.presentation.PatternBookScreen
import com.reallanguage.app.feature.knowledge.presentation.PatternBookViewModel
import com.reallanguage.app.feature.knowledge.presentation.PatternRemovalDialog
import com.reallanguage.app.feature.knowledge.presentation.PatternRemovalEffect
import com.reallanguage.app.feature.knowledge.presentation.PatternRemovalViewModel
import com.reallanguage.app.feature.review.data.AndroidTodayReviewRepository
import com.reallanguage.app.feature.review.data.AndroidReviewChapterRepository
import com.reallanguage.app.feature.review.data.ReviewWritingAdapter
import com.reallanguage.app.feature.review.data.ReviewSpeakingAdapter
import com.reallanguage.app.feature.review.data.ReviewSceneResponseAdapter
import com.reallanguage.app.feature.review.data.AndroidPatternIndependentRepository
import com.reallanguage.app.feature.review.data.AndroidReviewExitRepository
import com.reallanguage.app.feature.review.data.AndroidReviewResultRepository
import com.reallanguage.app.feature.review.domain.ReviewAbilityModule
import com.reallanguage.app.feature.review.domain.ReviewAbility
import com.reallanguage.app.feature.review.domain.ReviewAbilityResultDraft
import com.reallanguage.app.feature.review.domain.ReviewChapterSnapshot
import com.reallanguage.app.feature.review.presentation.TodayReviewEffect
import com.reallanguage.app.feature.review.presentation.TodayReviewScreen
import com.reallanguage.app.feature.review.presentation.TodayReviewViewModel
import com.reallanguage.app.feature.review.presentation.ReviewChapterAction
import com.reallanguage.app.feature.review.presentation.ReviewChapterEffect
import com.reallanguage.app.feature.review.presentation.ReviewChapterScreen
import com.reallanguage.app.feature.review.presentation.ReviewChapterViewModel
import com.reallanguage.app.feature.review.presentation.PatternIndependentAction
import com.reallanguage.app.feature.review.presentation.PatternIndependentEffect
import com.reallanguage.app.feature.review.presentation.PatternIndependentScreen
import com.reallanguage.app.feature.review.presentation.PatternIndependentViewModel
import com.reallanguage.app.feature.review.presentation.ReviewExitDialog
import com.reallanguage.app.feature.review.presentation.ReviewExitEffect
import com.reallanguage.app.feature.review.presentation.ReviewExitViewModel
import com.reallanguage.app.feature.review.presentation.ReviewResultEffect
import com.reallanguage.app.feature.review.presentation.ReviewResultScreen
import com.reallanguage.app.feature.review.presentation.ReviewResultViewModel
import com.reallanguage.app.feature.diary.presentation.ConfirmedDiaryInputValidator
import com.reallanguage.app.feature.diary.presentation.DiaryInputEffect
import com.reallanguage.app.feature.diary.presentation.DiaryInputScreen
import com.reallanguage.app.feature.diary.presentation.DiaryInputViewModel
import com.reallanguage.app.feature.safety.data.HttpSafetyCheckRepository
import com.reallanguage.app.feature.safety.data.JsonSafetyCheckWireCodec
import com.reallanguage.app.feature.safety.data.HttpSafetyHandlingRepository
import com.reallanguage.app.feature.safety.data.SafetyHandlingCommandIds
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import com.reallanguage.app.feature.safety.presentation.SafetyCheckScreen
import com.reallanguage.app.feature.safety.presentation.SafetyCheckViewModel
import com.reallanguage.app.feature.safety.presentation.SafetyCheckEffect
import com.reallanguage.app.feature.safety.presentation.SafetyHandlingEffect
import com.reallanguage.app.feature.safety.presentation.SafetyHandlingLoaderEffect
import com.reallanguage.app.feature.safety.presentation.SafetyHandlingLoaderScreen
import com.reallanguage.app.feature.safety.presentation.SafetyHandlingLoaderViewModel
import com.reallanguage.app.feature.safety.presentation.SafetyHandlingScreen
import com.reallanguage.app.feature.safety.presentation.SafetyHandlingViewModel
import com.reallanguage.app.feature.safety.presentation.ManualReviewPendingAction
import com.reallanguage.app.feature.safety.presentation.ManualReviewPendingScreen
import com.reallanguage.app.feature.safety.presentation.ManualReviewPendingUiState
import com.reallanguage.app.feature.formation.data.HttpSemanticSplitRepository
import com.reallanguage.app.feature.formation.data.JsonSemanticSplitWireCodec
import com.reallanguage.app.feature.formation.data.HttpChapterFormationRepository
import com.reallanguage.app.feature.formation.data.HttpEnglishGenerationRepository
import com.reallanguage.app.feature.formation.data.HttpLearningTargetRepository
import com.reallanguage.app.feature.formation.data.AndroidContentFixRepository
import com.reallanguage.app.feature.formation.data.HttpKnowledgeExampleRepository
import com.reallanguage.app.feature.formation.data.AndroidFixedChapterRepository
import com.reallanguage.app.feature.formation.presentation.ChapterFormationEffect
import com.reallanguage.app.feature.formation.presentation.ChapterFormationViewModel
import com.reallanguage.app.feature.formation.presentation.EnglishConfirmationEffect
import com.reallanguage.app.feature.formation.presentation.EnglishConfirmationScreen
import com.reallanguage.app.feature.formation.presentation.EnglishConfirmationViewModel
import com.reallanguage.app.feature.formation.presentation.EnglishGenerationEffect
import com.reallanguage.app.feature.formation.presentation.EnglishGenerationScreen
import com.reallanguage.app.feature.formation.presentation.EnglishGenerationViewModel
import com.reallanguage.app.feature.formation.presentation.LearningTargetEffect
import com.reallanguage.app.feature.formation.presentation.LearningTargetLoadingEffect
import com.reallanguage.app.feature.formation.presentation.LearningTargetLoadingScreen
import com.reallanguage.app.feature.formation.presentation.LearningTargetLoadingViewModel
import com.reallanguage.app.feature.formation.presentation.LearningTargetScreen
import com.reallanguage.app.feature.formation.presentation.LearningTargetViewModel
import com.reallanguage.app.feature.formation.presentation.FinalFixEffect
import com.reallanguage.app.feature.formation.presentation.FinalFixScreen
import com.reallanguage.app.feature.formation.presentation.FinalFixViewModel
import com.reallanguage.app.feature.learning.presentation.ChapterEntryAction
import com.reallanguage.app.feature.learning.presentation.ChapterEntryEffect
import com.reallanguage.app.feature.learning.presentation.ChapterEntryScreen
import com.reallanguage.app.feature.learning.presentation.ChapterEntryViewModel
import com.reallanguage.app.feature.learning.data.HttpExplanationReadingProvider
import com.reallanguage.app.feature.learning.data.AndroidMediaRecorderReadSpeakCapture
import com.reallanguage.app.feature.learning.data.AndroidReadSpeakRepository
import com.reallanguage.app.feature.learning.data.AndroidLearningStepStore
import com.reallanguage.app.feature.learning.data.AndroidSentenceSpeaker
import com.reallanguage.app.feature.learning.presentation.AudioDictationAction
import com.reallanguage.app.feature.learning.presentation.ExplanationAudioPlayback
import com.reallanguage.app.feature.learning.data.AndroidHandwritingCompletionRepository
import com.reallanguage.app.feature.learning.data.AndroidAudioDictationCompletionRepository
import com.reallanguage.app.feature.learning.data.AndroidFullSentenceSpeakingRepository
import com.reallanguage.app.feature.learning.data.AndroidLockedPatternReader
import com.reallanguage.app.feature.learning.data.AndroidPatternSpeakingRepository
import com.reallanguage.app.feature.learning.data.AndroidCompleteWritingRepository
import com.reallanguage.app.feature.learning.data.AndroidLockedWritingTargetReader
import com.reallanguage.app.feature.learning.data.AndroidSceneResponseRepository
import com.reallanguage.app.feature.learning.data.AndroidLearningCheckpointRepository
import com.reallanguage.app.feature.learning.data.AndroidChapterArchiveRepository
import com.reallanguage.app.feature.learning.data.AndroidChapterSelectionRepository
import com.reallanguage.app.feature.learning.data.AndroidChapterAbandonRepository
import com.reallanguage.app.feature.learning.data.AndroidLockedHintReader
import com.reallanguage.app.feature.learning.presentation.ExplanationPreparationEffect
import com.reallanguage.app.feature.learning.presentation.ExplanationPreparationScreen
import com.reallanguage.app.feature.learning.presentation.ExplanationPreparationViewModel
import com.reallanguage.app.feature.learning.presentation.ExplanationReadingEffect
import com.reallanguage.app.feature.learning.presentation.ExplanationReadingScreen
import com.reallanguage.app.feature.learning.presentation.ExplanationReadingViewModel
import com.reallanguage.app.feature.learning.presentation.ReadSpeakAction
import com.reallanguage.app.feature.learning.presentation.ReadSpeakEffect
import com.reallanguage.app.feature.learning.presentation.ReadSpeakScreen
import com.reallanguage.app.feature.learning.presentation.ReadSpeakViewModel
import com.reallanguage.app.feature.learning.presentation.HandwritingCopyEffect
import com.reallanguage.app.feature.learning.presentation.HandwritingCopyScreen
import com.reallanguage.app.feature.learning.presentation.HandwritingCopyViewModel
import com.reallanguage.app.feature.learning.presentation.AudioDictationEffect
import com.reallanguage.app.feature.learning.presentation.AudioDictationScreen
import com.reallanguage.app.feature.learning.presentation.AudioDictationViewModel
import com.reallanguage.app.feature.learning.presentation.ReadWriteCorrectionAction
import com.reallanguage.app.feature.learning.presentation.ReadWriteCorrectionEffect
import com.reallanguage.app.feature.learning.presentation.ReadWriteCorrectionScreen
import com.reallanguage.app.feature.learning.presentation.ReadWriteCorrectionViewModel
import com.reallanguage.app.feature.learning.presentation.FullSentenceSpeakingAction
import com.reallanguage.app.feature.learning.presentation.FullSentenceSpeakingEffect
import com.reallanguage.app.feature.learning.presentation.FullSentenceSpeakingScreen
import com.reallanguage.app.feature.learning.presentation.FullSentenceSpeakingViewModel
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingAction
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingEffect
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingScreen
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingViewModel
import com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore
import com.reallanguage.app.feature.learning.domain.withSavedExpression
import com.reallanguage.app.feature.learning.presentation.CompleteWritingEffect
import com.reallanguage.app.feature.learning.presentation.CompleteWritingScreen
import com.reallanguage.app.feature.learning.presentation.CompleteWritingViewModel
import com.reallanguage.app.feature.learning.domain.CompleteWritingEvaluator
import com.reallanguage.app.feature.learning.presentation.SceneResponseAction
import com.reallanguage.app.feature.learning.presentation.SceneResponseEffect
import com.reallanguage.app.feature.learning.presentation.SceneResponseScreen
import com.reallanguage.app.feature.learning.presentation.SceneResponseViewModel
import com.reallanguage.app.feature.learning.presentation.LearningExitViewModel
import com.reallanguage.app.feature.learning.presentation.LocalLearningExitAction
import com.reallanguage.app.feature.learning.presentation.ChapterResultEffect
import com.reallanguage.app.feature.learning.presentation.ChapterResultScreen
import com.reallanguage.app.feature.learning.presentation.ChapterResultViewModel
import com.reallanguage.app.feature.learning.presentation.ChapterSelectionEffect
import com.reallanguage.app.feature.learning.presentation.ChapterSelectionScreen
import com.reallanguage.app.feature.learning.presentation.ChapterSelectionViewModel
import com.reallanguage.app.feature.learning.presentation.ChapterAbandonAction
import com.reallanguage.app.feature.learning.presentation.ChapterAbandonEffect
import com.reallanguage.app.feature.learning.presentation.ChapterAbandonScreen
import com.reallanguage.app.feature.learning.presentation.ChapterAbandonViewModel
import com.reallanguage.app.feature.learning.presentation.CompletedStepReviewEffect
import com.reallanguage.app.feature.learning.presentation.CompletedStepReviewScreen
import com.reallanguage.app.feature.learning.presentation.CompletedStepReviewViewModel
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import com.reallanguage.app.feature.learning.domain.ChapterSelectionBoundary
import com.reallanguage.app.feature.learning.domain.CompletedStepSnapshot
import com.reallanguage.app.feature.learning.domain.FixedWritingComparator
import com.reallanguage.app.feature.learning.domain.FixedChapterLoadResult
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SceneEvaluationDecision
import com.reallanguage.app.feature.formation.presentation.FocusSelectionEffect
import com.reallanguage.app.feature.formation.presentation.FocusSelectionScreen
import com.reallanguage.app.feature.formation.presentation.FocusSelectionViewModel
import com.reallanguage.app.feature.formation.presentation.SemanticSplitEffect
import com.reallanguage.app.feature.formation.presentation.SemanticSplitViewModel
import com.reallanguage.app.feature.settings.data.HttpInitialSettingsRepository
import com.reallanguage.app.feature.settings.data.JsonInitialSettingsWireCodec
import com.reallanguage.app.feature.settings.data.HttpSettingsEffectRepository
import com.reallanguage.app.feature.settings.data.HttpSubsequentSettingsRepository
import com.reallanguage.app.feature.settings.data.JsonSubsequentSettingsWireCodec
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import com.reallanguage.app.feature.settings.domain.SettingsSourceContext
import com.reallanguage.app.feature.settings.presentation.ConfirmedInitialSettingsValidator
import com.reallanguage.app.feature.settings.presentation.InitialSettingsEffect
import com.reallanguage.app.feature.settings.presentation.InitialSettingsScreen
import com.reallanguage.app.feature.settings.presentation.InitialSettingsViewModel
import com.reallanguage.app.feature.settings.presentation.SettingsEffectScopeEffect
import com.reallanguage.app.feature.settings.presentation.SettingsEffectScopeScreen
import com.reallanguage.app.feature.settings.presentation.SettingsEffectScopeViewModel
import com.reallanguage.app.feature.settings.presentation.SubsequentSettingsEffect
import com.reallanguage.app.feature.settings.presentation.SubsequentSettingsScreen
import com.reallanguage.app.feature.settings.presentation.SubsequentSettingsViewModel
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.reallanguage.app.feature.review.domain.ReviewPatternEntryLease
import com.reallanguage.app.feature.review.domain.PatternIndependentReceiptIdentity
import com.reallanguage.app.feature.review.data.AndroidPatternIndependentReceiptStore

enum class ProductionSurface { LOGIN, INITIAL_SETTINGS, HOME, CONNECTION_ERROR }

fun productionSurface(route: AppRoute): ProductionSurface = when (route) {
    AppRoute.Login -> ProductionSurface.LOGIN
    AppRoute.InitialSettings -> ProductionSurface.INITIAL_SETTINGS
    AppRoute.Home -> ProductionSurface.HOME
    AppRoute.ConnectionError -> ProductionSurface.CONNECTION_ERROR
}

class ProductionAppGraph(
    context: Context,
    private val sessionRuntime: SessionRuntime = com.reallanguage.app.core.session.ProcessSessionRuntime.value,
    httpTransport: com.reallanguage.app.core.network.HttpTransport =
        productionHttpTransport(context.applicationContext),
    sessionCredentials: com.reallanguage.app.core.session.SessionCredentialStore = androidSessionCredentialStore(context.applicationContext),
    private val reviewPatternCaptureFactory: ((Context, SessionRuntime) -> com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture)? = null,
    private val fullSentenceSpeakingCaptureFactory: ((Context, SessionRuntime) -> com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture)? = null,
    private val patternSpeakingCaptureFactory: ((Context, SessionRuntime) -> com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture)? = null,
    private val sceneResponseCaptureFactory: ((Context, SessionRuntime) -> com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture)? = null,
    private val readSpeakCaptureFactory: ((Context, SessionRuntime) -> com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture)? = null,
    private val patternSpeakingRecoveryKeys: com.reallanguage.app.feature.formation.data.FormationPayloadKeys =
        com.reallanguage.app.feature.learning.data.AndroidPatternSpeakingPayloadKeys(),
    private val sceneResponseRecoveryKeys: com.reallanguage.app.feature.formation.data.FormationPayloadKeys =
        com.reallanguage.app.feature.formation.data.AndroidFormationPayloadKeys(),
) {
    private val applicationContext = context.applicationContext
    /** One process-wide, read-only catalog. Its SQLite asset is copied and verified locally on first use. */
    private val vocabularyCatalog by lazy { AndroidEmbeddedVocabularyCatalog(applicationContext) }
    private val vocabularyPoolPreference by lazy { AndroidVocabularyPoolPreference(applicationContext) }
    private var patternReviewEntry: ReviewPatternEntryLease? = null
    private val patternReviewSessions = mutableMapOf<Pair<ReviewPatternEntryLease, String>, AndroidPatternIndependentRepository>()
    @Synchronized internal fun currentPatternReviewEntry(): ReviewPatternEntryLease? {
        val current = patternReviewEntry ?: return null
        if (sessionRuntime.accessToken == null || !current.isActiveFor(sessionRuntime.accountId, sessionRuntime.accountEpoch)) {
            releasePatternReviewEntry(current)
            return null
        }
        return current
    }
    @Synchronized private fun acquirePatternReviewEntry(chapterId: String, entryId: String): ReviewPatternEntryLease? {
        val owner = sessionRuntime.accountId ?: return null
        if (sessionRuntime.accessToken == null || chapterId.isBlank() || entryId.isBlank()) return null
        currentPatternReviewEntry()?.let { current ->
            if (current.reviewChapterId == chapterId && current.entryId == entryId) return current
            releasePatternReviewEntry(current)
        }
        return ReviewPatternEntryLease(owner, sessionRuntime.accountEpoch, chapterId, entryId).also { patternReviewEntry = it }
    }
    @Synchronized internal fun releasePatternReviewEntry(lease: ReviewPatternEntryLease?) {
        lease?.revoke()
        patternReviewSessions.keys.filter { it.first === lease }.forEach { key ->
            patternReviewSessions.remove(key)?.cancelCapture()
        }
        if (patternReviewEntry === lease) patternReviewEntry = null
    }
    @Composable internal fun rememberPatternReviewEntry(chapterId: String?, entryId: String?): ReviewPatternEntryLease? {
        val revision by sessionRuntime.identityRevision.collectAsState()
        val epoch = remember(this, revision) { sessionRuntime.accountEpoch }
        val lease = remember(this, chapterId, entryId, epoch) {
            if (chapterId != null && entryId != null) acquirePatternReviewEntry(chapterId, entryId) else null
        }
        DisposableEffect(lease) { onDispose { releasePatternReviewEntry(lease) } }
        return lease
    }
    private val balanceRequiredSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val balanceRequiredEvents = balanceRequiredSignal.asSharedFlow()
    private val recoveryTransport = httpTransport
    internal val connectionGate = com.reallanguage.app.core.network.ConnectionGuardedHttpTransport(httpTransport) {
        com.reallanguage.app.core.network.ConnectionOwner(sessionRuntime.accountId, sessionRuntime.accountEpoch)
    }
    internal val connectionSessionRevision get() = sessionRuntime.identityRevision
    internal fun currentSessionOwner() = synchronized(sessionRuntime) {
        com.reallanguage.app.core.network.ConnectionOwner(sessionRuntime.accountId, sessionRuntime.accountEpoch)
    }
    internal fun isSessionOwnerCurrent(owner: com.reallanguage.app.core.network.ConnectionOwner) =
        synchronized(sessionRuntime) { owner.accountId != null && owner == currentSessionOwner() && sessionRuntime.accessToken != null }
    fun startNetworkObservation(
        source: com.reallanguage.app.core.network.NetworkAvailabilitySource = com.reallanguage.app.core.network.AndroidNetworkAvailabilitySource(applicationContext),
    ): AutoCloseable = com.reallanguage.app.core.network.NetworkAvailabilityWatch(source, connectionGate::networkLost).also { it.start() }
    internal fun playbackEligibility(): () -> Boolean {
        val owner = sessionRuntime.accountId
        val epoch = sessionRuntime.accountEpoch
        return {
            owner != null && sessionRuntime.accountId == owner && sessionRuntime.accountEpoch == epoch &&
                sessionRuntime.accessToken != null && connectionGate.incident.value == null
        }
    }
    private val transport = BalanceAwareHttpTransport(
        connectionGate,
    ) { balanceRequiredSignal.tryEmit(Unit) }
    internal suspend fun capturePassages(
        entryId: String, text: String, origin: CaptureOrigin, shape: SourceContentShape, back: HomeDiaryRoute,
    ) = generateCapturePassages(transport, sessionRuntime, entryId, text, origin, shape, back)

    internal fun capturedRecording(id: String) = captureRecordingUri(applicationContext, sessionRuntime, id)
    internal suspend fun captureTranscript(uri: Uri) =
        transcribeCaptureAudio(applicationContext, uri, transport, sessionRuntime)

    private val reminderRuntime = AndroidDailyReminderRuntime(applicationContext)
    private val reminderHomeEntry = DailyReminderHomeEntry()
    fun reminderOwner(): String? = sessionRuntime.accountId
    fun reminderRequestMatchesOwner(request: ReminderHomeRequest): Boolean = sessionRuntime.accountId?.let {
        com.reallanguage.app.feature.home.data.AndroidDailyReminderPlatform.accountKey(it) == request.ownerKey
    } == true
    suspend fun shouldShowReminder(): Boolean {
        val identity = RuntimeReminderLocalIdentity(sessionRuntime)
        val preferences = reminderRuntime.serialized { reminderRuntime.repository(identity).load() }
        return reminderHomeEntry.shouldShow(identity.accountId, preferences)
    }
    fun reminderPermission() = reminderRuntime.platform.permission()
    fun closeReminderForThisSession(accountId: String) = reminderHomeEntry.closed(accountId)
    suspend fun reconcileReminder() = reminderRuntime.reconcile(RuntimeReminderLocalIdentity(sessionRuntime))
    fun reminderIntroFactory() = viewModelFactory {
        val identity = RuntimeReminderLocalIdentity(sessionRuntime)
        val repository = reminderRuntime.repository(identity)
        DailyReminderIntroViewModel(DailyReminderIntroRepository { choice, commandId ->
            val result = reminderRuntime.serialized { repository.recordChoice(choice, commandId) }
            if (result == DailyReminderIntroResult.Saved) reminderRuntime.reconcile(identity)
            result
        }, UUID.randomUUID().toString())
    }
    val learningCheckpointRepository by lazy { AndroidLearningCheckpointRepository(applicationContext, sessionRuntime) }
    private val subsequentSettingsRepository by lazy {
        HttpSubsequentSettingsRepository(
            transport,
            JsonSubsequentSettingsWireCodec(),
            sessionRuntime,
            vocabularyPoolPreference,
        )
    }
    private val sessionRepository = ProductionSessionRepository(
        // Login/refresh are the verification path, not replay of the failed business operation.
        api = HttpSessionApi(com.reallanguage.app.core.network.HttpTransport { request ->
            if (request.method == "POST" && request.path in setOf("/api/v1/auth/sessions", "/api/v1/auth/sessions/refresh")) {
                recoveryTransport.execute(request)
            } else transport.execute(request)
        }, JsonSessionWireCodec()),
        credentials = sessionCredentials,
        runtime = sessionRuntime,
        installationIdentity = AndroidInstallationIdentity(applicationContext),
        localLifecycle = reminderRuntime,
    )

    private val deletionJournal = com.reallanguage.app.feature.home.data.AndroidAccountDeletionCleanupJournal(applicationContext)
    private val deletionAccess = com.reallanguage.app.feature.home.data.RuntimeDeletedAccountAccess(sessionRuntime)
    private val deletionData = AndroidConfirmedDeletionDataCleaner(applicationContext, deletionJournal)
    private val deletionSession = DeletedAccountSessionCleaner { account -> sessionRepository.clearAfterAccountDeletion(account) }
    private val deletionCleanup = com.reallanguage.app.feature.home.data.ConfirmedAccountDeletionCleanup(
        deletionJournal, deletionData, deletionAccess, deletionSession)
    private val verifiedStartupRepository = DeletionAwareStartupRepository(deletionJournal, deletionCleanup::complete, sessionRepository)
    private val startupRepository = com.reallanguage.app.core.navigation.AppStartupRepository {
        val incident = connectionGate.incident.value
        verifiedStartupRepository.resolve().also {
            if (it is com.reallanguage.app.core.navigation.AppStartupResult.SignedIn) connectionGate.acceptVerifiedNewSession(incident)
            else if (it == com.reallanguage.app.core.navigation.AppStartupResult.SignedOut) connectionGate.alignVerifiedAnonymousStartup()
        }
    }
    val coordinatorFactory = viewModelFactory { AppCoordinatorViewModel(startupRepository) }
    internal suspend fun resolveStartupForApp() = startupRepository.resolve()
    internal suspend fun loginForApp(command: com.reallanguage.app.feature.auth.domain.LoginCommand): com.reallanguage.app.feature.auth.domain.LoginResult {
        val incident = connectionGate.incident.value
        val result = sessionRepository.login(command)
        if (result is com.reallanguage.app.feature.auth.domain.LoginResult.Authenticated && result.accountId == sessionRuntime.accountId) {
            connectionGate.acceptVerifiedNewSession(incident)
        }
        return result
    }
    internal fun connectionRecoveryFactory(incident: com.reallanguage.app.core.network.ConnectionIncident, pageId: String) = viewModelFactory {
        var clearReceipt: com.reallanguage.app.core.session.SessionSignedOutReceipt? = null
        val recoveryStartup = DeletionAwareStartupRepository(deletionJournal, deletionCleanup::complete,
            com.reallanguage.app.core.navigation.AppStartupRepository {
                val account = incident.owner.accountId
                if (account == null) com.reallanguage.app.core.navigation.AppStartupResult.SignedOut else {
                    val checked = sessionRepository.resolveForConnection(account, incident.owner.epoch)
                    clearReceipt = checked.signedOutReceipt
                    checked.result
                }
            })
        com.reallanguage.app.feature.error.presentation.ConnectionErrorViewModel(
            com.reallanguage.app.feature.error.domain.ConnectionResumePoint(pageId, "in-memory:${incident.generation}"),
            com.reallanguage.app.feature.error.data.AccountConnectionRecoveryRepository(incident, connectionGate, recoveryStartup, serviceReachable = {
                val response = recoveryTransport.execute(com.reallanguage.app.core.network.HttpRequest("GET", "/api/v1/health", ""))
                if (response.statusCode != 200) false else {
                    val body = org.json.JSONObject(response.body)
                    body.opt("status") == "ok" && body.opt("service") == "api" &&
                        (body.opt("requestId") as? String)?.length in 1..128
                }
            }, completedSignOutIsCurrent = {
                clearReceipt?.let { it.accountId == incident.owner.accountId && it.sourceEpoch == incident.owner.epoch && sessionRuntime.isCurrent(it) } == true
            }),
        )
    }
    val authFactory = viewModelFactory {
        val emailValidator = EmailSyntaxValidator { Patterns.EMAIL_ADDRESS.matcher(it).matches() }
        AuthViewModel(
            validator = RegistrationFormValidator(
                currentYearMonth = CurrentYearMonth { YearMonth.now() },
                emailSyntaxValidator = emailValidator,
            ),
            registrationRepository = HttpRegistrationRepository(
                transport,
                JsonRegistrationWireCodec(),
            ),
            loginValidator = LoginFormValidator(emailValidator),
            loginRepository = object : com.reallanguage.app.feature.auth.domain.LoginRepository {
                override suspend fun login(command: com.reallanguage.app.feature.auth.domain.LoginCommand) = loginForApp(command)
            },
        )
    }
    val initialSettingsFactory = viewModelFactory {
        val owner = currentSessionOwner()
        InitialSettingsViewModel(
            validator = ConfirmedInitialSettingsValidator,
            repository = HttpInitialSettingsRepository(
                transport,
                JsonInitialSettingsWireCodec(),
                sessionRuntime,
                vocabularyPoolPreference,
            ),
            isOwnerActive = { isSessionOwnerCurrent(owner) },
        )
    }
    fun subsequentSettingsFactory(route: HomeDiaryRoute.Settings) = viewModelFactory {
        SubsequentSettingsViewModel(subsequentSettingsRepository, route.entryId, reminderSettingsRepository())
    }
    private fun reminderSettingsRepository(): com.reallanguage.app.feature.settings.domain.ReminderSettingsRepository {
        val identity = RuntimeReminderLocalIdentity(sessionRuntime)
        val local = com.reallanguage.app.feature.settings.data.LocalReminderSettingsRepository(reminderRuntime.repository(identity))
        return object : com.reallanguage.app.feature.settings.domain.ReminderSettingsRepository {
            override suspend fun load() = reminderRuntime.serialized { local.load() }
            override suspend fun save(expected: com.reallanguage.app.feature.settings.domain.ReminderSettingsDraft,
                proposed: com.reallanguage.app.feature.settings.domain.ReminderSettingsDraft): Boolean {
                val saved = reminderRuntime.serialized { local.save(expected, proposed) }
                if (saved) reminderRuntime.reconcile(identity)
                return saved
            }
        }
    }
    fun settingsEffectFactory(route: HomeDiaryRoute.SettingsEffect) = viewModelFactory {
        SettingsEffectScopeViewModel(
            route.original,
            route.proposed,
            SettingsSourceContext.NoCurrentContent,
            HttpSettingsEffectRepository(subsequentSettingsRepository),
            route.entryId,
            reminderRepository = reminderSettingsRepository(),
            originalReminder = route.originalReminder,
            proposedReminder = route.proposedReminder,
        )
    }
    fun emailVerificationFactory(
        registrationId: String,
        email: String,
    ) = viewModelFactory {
        EmailVerificationViewModel(
            registrationId = registrationId,
            email = email,
            repository = HttpVerificationEmailRepository(
                transport,
                JsonVerificationEmailWireCodec(),
            ),
            countdown = RealVerificationCountdown(),
            statusPolling = RealVerificationStatusPolling(),
            isConnectionAvailable = { connectionGate.incident.value == null },
        )
    }
    fun agreementFactory(
        kind: AgreementKind,
        draft: RegistrationDraftSnapshot,
    ) = viewModelFactory {
        AgreementDetailViewModel(
            kind = kind,
            registrationDraft = draft,
            repository = HttpAgreementDocumentRepository(
                transport,
                JsonAgreementDocumentWireCodec(),
            ),
        )
    }
    fun passwordResetFactory() = viewModelFactory {
        PasswordResetViewModel(
            repository = HttpPasswordResetRepository(
                transport,
                JsonPasswordResetWireCodec(),
            ),
            emailValidator = EmailSyntaxValidator {
                Patterns.EMAIL_ADDRESS.matcher(it).matches()
            },
            requestCommandId = UUID.randomUUID().toString(),
            resetCommandId = UUID.randomUUID().toString(),
        )
    }
    fun diaryInputFactory(initialText: String) = viewModelFactory {
        DiaryInputViewModel(
            ConfirmedDiaryInputValidator,
            initialText,
            HttpDiaryDictationRepository(
                transport,
                sessionRuntime,
                AndroidMediaRecorderDiaryDictationCapture(applicationContext),
            ),
        )
    }
    private val recordingPreferences = applicationContext.getSharedPreferences(
        "recording-entry", Context.MODE_PRIVATE,
    )
    fun hasSeenRecordingIntroduction(): Boolean =
        recordingPreferences.getBoolean("introduction-seen", false)
    fun markRecordingIntroductionSeen() {
        recordingPreferences.edit().putBoolean("introduction-seen", true).apply()
    }
    fun recordingIntroFactory() = viewModelFactory { RecordingIntroViewModel() }
    fun activeRecordingFactory() = viewModelFactory {
        ActiveRecordingViewModel(AndroidRecordingRuntimeRepository(applicationContext, sessionRuntime))
    }
    fun recordingConfirmationFactory(recordingId: String) = viewModelFactory {
        RecordingConfirmationViewModel(
            recordingId,
            AndroidRecordingConfirmationRepository(applicationContext, sessionRuntime, playbackEligibility()),
        )
    }
    fun recordingDeletionFactory(recordingId: String) = viewModelFactory {
        RecordingDeletionViewModel(
            recordingId,
            UUID.nameUUIDFromBytes("delete-recording:$recordingId".toByteArray()).toString(),
            AndroidRecordingDeletionRepository(applicationContext, sessionRuntime),
        )
    }
    fun recordingTranscriptionFactory(route: HomeDiaryRoute.RecordingTranscription) = viewModelFactory {
        RecordingTranscriptionViewModel(
            TranscriptionRequest(
                recordingId = route.recordingId,
                durationSeconds = route.durationSeconds,
                audioFormat = "audio/mp4",
                languageHint = "zh-CN",
                endReason = route.endReason,
            ),
            UUID.nameUUIDFromBytes("transcribe:${route.recordingId}".toByteArray()).toString(),
            HttpRecordingRepositories(applicationContext, transport, sessionRuntime, playbackEligibility()),
        )
    }
    fun transcriptResultFactory(route: HomeDiaryRoute.TranscriptResult) = viewModelFactory {
        TranscriptResultViewModel(
            recordingId = route.recordingId,
            transcriptId = route.transcriptId,
            repairCommandId = UUID.nameUUIDFromBytes("repair-transcript:${route.transcriptId}".toByteArray(Charsets.UTF_8)).toString(),
            confirmCommandId = UUID.nameUUIDFromBytes("confirm-transcript:${route.transcriptId}".toByteArray()).toString(),
            repository = HttpRecordingRepositories(applicationContext, transport, sessionRuntime, playbackEligibility()),
        )
    }
    fun transcriptionFailureFactory(recordingId: String, durationSeconds: Int? = null) = viewModelFactory {
        TranscriptionFailureViewModel(recordingId, durationSeconds)
    }
    fun microphonePermissionFactory(canRequestDirectly: Boolean) = viewModelFactory {
        MicrophonePermissionViewModel(canRequestDirectly)
    }
    fun sceneHomeFactory(initialStart: SceneStart?) = viewModelFactory {
        SceneHomeViewModel(HttpSceneHomeRepository(applicationContext, transport, sessionRuntime), initialStart)
    }
    fun sceneDirectDescriptionFactory(initialText: String) = viewModelFactory {
        SceneDirectDescriptionViewModel(initialText)
    }
    fun sceneTypeFactory(start: SceneStart) = viewModelFactory { SceneTypeViewModel(start) }
    fun sceneQuestionsFactory(route: HomeDiaryRoute.SceneQuestions) = viewModelFactory {
        SceneQuestionsViewModel(
            route.start, route.contentType, route.operationId,
            HttpSceneFormationRepositories(transport, sessionRuntime), route.initialAnswers,
        )
    }
    fun sceneGenerationFactory(route: HomeDiaryRoute.SceneGeneration) = viewModelFactory {
        SceneGenerationViewModel(
            route.session,
            UUID.nameUUIDFromBytes("scene-generate:${route.session.operationId}:${route.entryId}".toByteArray()).toString(),
            HttpSceneFormationRepositories(transport, sessionRuntime),
            previousUnits = route.previousUnits,
        )
    }
    fun sceneReviewFactory(route: HomeDiaryRoute.SceneChineseReview) = viewModelFactory {
        SceneChineseReviewViewModel(
            route.session, route.draft,
            UUID.nameUUIDFromBytes("scene-review:${route.draft.draftId}".toByteArray()).toString(),
            HttpSceneFormationRepositories(transport, sessionRuntime),
        )
    }
    fun sceneAbandonFactory(session: SceneQuestionSession) = viewModelFactory {
        SceneAbandonViewModel(
            session,
            UUID.nameUUIDFromBytes("scene-abandon:${session.operationId}".toByteArray()).toString(),
            LocalSceneAbandonRepository(),
        )
    }
    fun vocabularyBookFactory() = viewModelFactory {
        VocabularyBookViewModel(
            AndroidVocabularyBookRepository(
                context = applicationContext,
                runtime = sessionRuntime,
                vocabularyCatalog = vocabularyCatalog,
                scopeSource = LocalFirstVocabularyScopeSource(
                    sessionRuntime,
                    vocabularyPoolPreference,
                    HttpCurrentVocabularyScopeSource(transport, sessionRuntime),
                ),
            ),
        )
    }
    fun patternBookFactory() = viewModelFactory {
        PatternBookViewModel(AndroidPatternBookRepository(applicationContext, sessionRuntime))
    }
    fun patternRemovalFactory(patternId: String, commandId: String) = viewModelFactory {
        PatternRemovalViewModel(
            patternId,
            commandId,
            AndroidPatternRemovalRepository(applicationContext, sessionRuntime),
        )
    }
    fun incompleteLearningFactory() = viewModelFactory {
        IncompleteLearningViewModel(incompleteLearningRepository())
    }
    private var formationRuntime: AndroidFormationRecoveryRuntime? = null
    private fun formationRecovery(): AndroidFormationRecoveryRuntime = synchronized(this) {
        formationRuntime?.takeIf { it.isCurrent() } ?: AndroidFormationRecoveryRuntime(applicationContext, sessionRuntime)
            .also { formationRuntime = it }
    }
    suspend fun prepareFormation(route: HomeDiaryRoute, targetSentenceIndex: Int? = null, canApply: () -> Boolean = { true }): com.reallanguage.app.feature.formation.domain.FormationRecoverySnapshot? =
        formationRecovery().journal.prepare(route, targetSentenceIndex, canApply)
    suspend fun readFormation(sourceEntryId: String): com.reallanguage.app.feature.formation.domain.FormationRecoverySnapshot? =
        formationRecovery().journal.read(sourceEntryId)
    suspend fun completeFormation(source: com.reallanguage.app.feature.formation.domain.FixedSourceProjection): FixedFormationCleanup =
        formationRecovery().completion.complete(source)
    private fun incompleteLearningRepository(): FormationIncompleteLearningRepository {
        val recovery = formationRecovery()
        return FormationIncompleteLearningRepository(AndroidIncompleteLearningRepository(applicationContext, sessionRuntime),
            recovery.repository, recovery.completion::status)
    }
    fun messagesFactory() = viewModelFactory { MessagesViewModel(HttpMessagesRepository(transport, sessionRuntime)) }
    fun manualReviewResultFactory(reviewRequestId: String) = viewModelFactory {
        ManualReviewResultViewModel(reviewRequestId, HttpManualReviewResultRepository(transport, sessionRuntime))
    }
    fun accountFactory(entryId: String) = viewModelFactory {
        AccountViewModel(HttpAccountRepository(transport, sessionRuntime, sessionRepository), "logout-$entryId")
    }
    internal fun accountDeletionRepository() = HttpAccountDeletionRepository(
                transport,
                sessionRuntime,
                deletionData,
                deletionSession,
                journal = deletionJournal,
                access = deletionAccess,
            )
    fun accountDeletionFactory(commandId: String) = viewModelFactory {
        AccountDeletionViewModel(accountDeletionRepository(), commandId)
    }
    fun billingFactory() = viewModelFactory {
        AiBillingViewModel(HttpAiBillingRepository(transport, sessionRuntime))
    }
    fun rechargeFactory(route: HomeDiaryRoute.Recharge) = viewModelFactory {
        RechargeViewModel(
            originId = route.origin.toString(),
            commandId = UUID.nameUUIDFromBytes("recharge:${route.entryId}".toByteArray()).toString(),
            repository = HttpRechargeRepository(transport, sessionRuntime),
        )
    }
    fun refundFactory(route: HomeDiaryRoute.Refund) = viewModelFactory {
        RefundViewModel(
            UUID.nameUUIDFromBytes("refund:${route.entryId}".toByteArray()).toString(),
            HttpRefundRepository(transport, sessionRuntime),
        )
    }
    fun insufficientBalanceFactory(route: HomeDiaryRoute.InsufficientBalance) = viewModelFactory {
        val checkpointSession = learningCheckpointRepository.forCurrentSession()
        InsufficientBalanceViewModel(
            context = route.context,
            commandId = UUID.nameUUIDFromBytes("balance-exit:${route.entryId}".toByteArray()).toString(),
            repository = object : BalanceInsufficientRepository {
                override suspend fun applyExitPolicy(
                    originId: String,
                    disposition: BalanceExitDisposition,
                    commandId: String,
                ): BalanceExitResult = when (disposition) {
                    BalanceExitDisposition.SAVE_CURRENT_STEP -> {
                        val checkpoint = learningCheckpoint(route.origin)
                            ?: return BalanceExitResult.Failed
                        when (
                            checkpointSession.save(checkpoint)
                        ) {
                            com.reallanguage.app.feature.learning.domain.CheckpointSaveResult.Saved ->
                                BalanceExitResult.Applied
                            com.reallanguage.app.feature.learning.domain.CheckpointSaveResult.Failed ->
                                BalanceExitResult.Failed
                        }
                    }
                    BalanceExitDisposition.KEEP_COMPLETED_RESTART_CURRENT -> {
                        val reviewChapterId = when (val origin = route.origin) {
                            is HomeDiaryRoute.ReviewChapter -> origin.reviewChapterId
                            is HomeDiaryRoute.ReviewAbilityExecution -> origin.reviewChapterId
                            else -> return BalanceExitResult.Failed
                        }
                        when (
                            AndroidReviewExitRepository(
                                applicationContext,
                                sessionRuntime,
                            ).discardWorkingState(reviewChapterId)
                        ) {
                            com.reallanguage.app.feature.review.domain.ReviewExitResult.Discarded ->
                                BalanceExitResult.Applied
                            com.reallanguage.app.feature.review.domain.ReviewExitResult.AlreadyDiscarded ->
                                BalanceExitResult.AlreadyApplied
                            com.reallanguage.app.feature.review.domain.ReviewExitResult.Failed ->
                                BalanceExitResult.Failed
                        }
                    }
                    BalanceExitDisposition.DISCARD_DRAFT,
                    BalanceExitDisposition.KEEP_LOCAL_RECORDING,
                    BalanceExitDisposition.KEEP_FIXED_CHAPTER,
                    -> BalanceExitResult.Applied
                }
            },
            billingRepository = HttpAiBillingRepository(transport, sessionRuntime),
        )
    }
    fun localRecordingsFactory() = viewModelFactory { LocalRecordingsViewModel(AndroidLocalRecordingsRepository(applicationContext, sessionRuntime)) }
    suspend fun hasIncompleteLearning(): Boolean = when (val loaded = incompleteLearningRepository().loadOrdinaryLearning()) {
        is com.reallanguage.app.feature.home.domain.IncompleteLearningLoadResult.Ready -> loaded.entries.isNotEmpty()
        // Keep the existing entry reachable so a failed read has a retry, not a false empty home.
        com.reallanguage.app.feature.home.domain.IncompleteLearningLoadResult.Failed -> true
    }
    suspend fun resolveResumeRoute(chapterId: String, moduleId: String, stepId: String?): HomeDiaryRoute? {
        if (moduleId.startsWith("FORMATION:")) {
            return readFormation(chapterId)?.toFormationRecoveryRoute()?.let { restored ->
                (restored.route as? HomeDiaryRoute.LearningTargetSelection)?.copy(initialSentenceIndex = restored.targetSentenceIndex)
                    ?: restored.route
            }
        }
        val loaded = AndroidFixedChapterRepository(applicationContext, sessionRuntime).load(chapterId)
        val chapter = (loaded as? FixedChapterLoadResult.Loaded)?.chapter ?: return null
        if (moduleId == "LR-01" && stepId == "start") {
            return chapterEntryStartRoute(chapter, learningCheckpointRepository.read(chapterId))
        }
        if (moduleId == "LR-01" || moduleId == "LR-02") return learningResumeRoute(chapter, moduleId, stepId, null)
        if (moduleId !in setOf("LR-03", "LR-04", "LR-05", "LR-06", "LR-07", "LR-08", "LR-09")) return null
        val focus = learningResumeFocus(chapter, moduleId, stepId) ?: return null
        val recoveredInput = if (moduleId == "LR-05" && stepId?.startsWith("correction:") == true) {
            val saved = com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore(
                applicationContext, sessionRuntime, chapterId, focus, "LR-05",
            ).read()
            if (saved != null && (saved["version"] != "1" || !saved.containsKey("input"))) return null
            saved?.get("input").orEmpty()
        } else ""
        val explanation = explanationProvider(chapter, focus).prepare() as?
            com.reallanguage.app.feature.learning.domain.ExplanationPreparationResult.Ready ?: return null
        return learningResumeRoute(chapter, moduleId, stepId, explanation, recoveredInput)
    }
    fun safetyCheckFactory(source: ConfirmedSource, commandId: String) = viewModelFactory {
        SafetyCheckViewModel(
            source,
            HttpSafetyCheckRepository(
                transport = transport,
                codec = JsonSafetyCheckWireCodec(),
                sessionRuntime = sessionRuntime,
                commandId = commandId,
            ),
        )
    }
    fun semanticSplitFactory(source: ConfirmedSource, commandId: String) = viewModelFactory {
        SemanticSplitViewModel(
            source,
            HttpSemanticSplitRepository(
                transport,
                JsonSemanticSplitWireCodec(),
                sessionRuntime,
                commandId,
            ),
        )
    }
    fun focusSelectionFactory(units: List<com.reallanguage.app.feature.formation.domain.SemanticUnit>, initialSelectedUnitIds: Set<String> = emptySet()) =
        viewModelFactory { FocusSelectionViewModel(units, initialSelectedUnitIds) }
    fun chapterFormationFactory(
        units: List<com.reallanguage.app.feature.formation.domain.SemanticUnit>,
        selectedUnitIds: List<String>,
        commandId: String,
    ) = viewModelFactory {
        ChapterFormationViewModel(
            units,
            selectedUnitIds,
            HttpChapterFormationRepository(
                transport,
                sessionRuntime,
                commandId,
            ),
        )
    }
    fun englishGenerationFactory(route: HomeDiaryRoute.EnglishGeneration, commandId: String) = viewModelFactory {
        EnglishGenerationViewModel(
            route.units,
            route.selectedUnitIds,
            route.chapters,
            HttpEnglishGenerationRepository(
                transport,
                sessionRuntime,
                commandId,
                knowledgeSource = com.reallanguage.app.feature.formation.data.AndroidEnglishKnowledgeSnapshotSource(
                    applicationContext, sessionRuntime, HttpCurrentVocabularyScopeSource(transport, sessionRuntime),
                ),
                sourceType = route.sourceType.name,
            ),
        )
    }
    fun englishConfirmationFactory(chapters: List<com.reallanguage.app.feature.formation.domain.EnglishChapterDraft>, initialSelections: Map<String, String> = emptyMap()) =
        viewModelFactory { EnglishConfirmationViewModel(chapters, initialSelections) }
    private fun learningTargetRepository(route: HomeDiaryRoute.LearningTargets, commandId: String) = HttpLearningTargetRepository(
        transport, sessionRuntime, route.chapters, route.selections, commandId,
        knowledgeSource = com.reallanguage.app.feature.formation.data.AndroidKnownKnowledgeReferenceSource(applicationContext, sessionRuntime),
        vocabularyCatalog = vocabularyCatalog,
    )
    fun learningTargetLoadingFactory(route: HomeDiaryRoute.LearningTargets, commandId: String) = viewModelFactory {
        LearningTargetLoadingViewModel(learningTargetRepository(route, commandId))
    }
    fun learningTargetSelectionFactory(route: HomeDiaryRoute.LearningTargetSelection) = viewModelFactory {
        LearningTargetViewModel(
            route.chapters, route.selections, route.targets, route.queryableExpressions,
            HttpLearningTargetRepository(
                transport, sessionRuntime, route.chapters, route.selections, "target-selection:${route.entryId}",
                initialQueryableExpressions = route.queryableExpressions,
                initialTargets = route.targets,
                knowledgeSource = com.reallanguage.app.feature.formation.data.AndroidKnownKnowledgeReferenceSource(applicationContext, sessionRuntime),
                vocabularyCatalog = vocabularyCatalog,
            ),
            route.initialSelectedTargetIds,
            initialSentenceIndex = route.initialSentenceIndex,
        )
    }
    fun finalFixFactory(route: HomeDiaryRoute.FinalFix,
        sourceProjection: com.reallanguage.app.feature.formation.domain.FixedSourceProjection? = null) = viewModelFactory {
        FinalFixViewModel(
            formationFlowId = route.entryId,
            commandId = route.entryId,
            chapters = route.chapters,
            selectedCandidateIds = route.selections,
            targets = route.targets,
            selectedTargetIds = route.selectedTargetIds,
            repository = AndroidContentFixRepository(
                applicationContext,
                sessionRuntime,
                knowledgeExamples = HttpKnowledgeExampleRepository(transport, sessionRuntime),
            ),
            sourceType = when (route.sourceType) {
                SourceType.DIARY -> "DIARY"
                SourceType.RECORDING -> "QUICK_RECORDING"
                SourceType.AI_SCENE -> "AI_SCENE"
            },
            sourceTitle = route.text.lineSequence().firstOrNull()?.take(32).orEmpty().ifBlank {
                when (route.sourceType) {
                    SourceType.DIARY -> "日记"
                    SourceType.RECORDING -> "快速录音"
                    SourceType.AI_SCENE -> "AI 场景"
                }
            },
            sourceProjection = sourceProjection,
        )
    }
    fun chapterEntryFactory(chapterId: String) = viewModelFactory {
        ChapterEntryViewModel(chapterId, AndroidFixedChapterRepository(applicationContext, sessionRuntime))
    }
    fun completedStepSnapshotLoader(snapshot: CompletedStepSnapshot): (suspend () -> CompletedStepSnapshot)? {
        if (snapshot.stepId == "LR-09") {
            val owner = sessionRuntime.accountId
            val epoch = sessionRuntime.accountEpoch
            val reader = com.reallanguage.app.feature.learning.data.AndroidSavedSceneTaskReader(applicationContext, sessionRuntime)
            fun sameOwner() = owner != null && sessionRuntime.accountId == owner &&
                sessionRuntime.accountEpoch == epoch && sessionRuntime.accessToken != null
            return {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    check(sameOwner())
                    val chapterId = requireNotNull(snapshot.chapterId)
                    val sentenceId = requireNotNull(snapshot.activeSentenceId)
                    val loaded = AndroidFixedChapterRepository(applicationContext, sessionRuntime).load(chapterId)
                        as? com.reallanguage.app.feature.learning.domain.FixedChapterLoadResult.Loaded
                        ?: error("Fixed chapter unavailable for completed scene review")
                    val focus = loaded.chapter.sentences.single { it.isFocus && it.sentenceId == sentenceId }
                    require(snapshot.contentEn == focus.textEn && snapshot.contentZh == focus.textZh)
                    require(snapshot.contextSentences == loaded.chapter.sentences)
                    val tasks = requireNotNull(reader.read(loaded.chapter, requireArchived = true))
                    check(sameOwner())
                    snapshot.copy(completedSceneTasks = tasks)
                }
            }
        }
        if (snapshot.stepId != "LR-02") return null
        val chapterId = snapshot.chapterId ?: return null
        val sentenceId = snapshot.activeSentenceId ?: return null
        val store = AndroidLearningProgressStore(applicationContext, sessionRuntime, chapterId, sentenceId, "LR-02")
        val owner = sessionRuntime.accountId
        val epoch = sessionRuntime.accountEpoch
        fun sameOwner() = owner != null && sessionRuntime.accountId == owner &&
            sessionRuntime.accountEpoch == epoch && sessionRuntime.accessToken != null
        val chapters = AndroidFixedChapterRepository(applicationContext, sessionRuntime)
        return {
            check(sameOwner())
            val loaded = chapters.load(chapterId) as? com.reallanguage.app.feature.learning.domain.FixedChapterLoadResult.Loaded
                ?: error("Fixed chapter unavailable for read-only review")
            check(sameOwner())
            val focus = loaded.chapter.sentences.single { it.sentenceId == sentenceId && it.isFocus }
            require(snapshot.contentEn == focus.textEn && snapshot.contentZh == focus.textZh)
            require(snapshot.contextSentences == loaded.chapter.sentences)
            val restored = snapshot.withSavedExpression(store.read())
            restored.reviewedExpression?.let {
                require(explanationProvider(loaded.chapter, sentenceId)
                    .validateSavedExpressionOccurrence(sentenceId, it.expression, it.explanation))
            }
            check(sameOwner())
            restored
        }
    }
    fun targetSelfAssessmentFactory(chapterId: String, sentenceId: String, moduleId: String) = viewModelFactory {
        com.reallanguage.app.feature.learning.presentation.TargetSelfAssessmentViewModel(
            com.reallanguage.app.feature.learning.data.AndroidTargetSelfAssessmentRepository(
                applicationContext, sessionRuntime, chapterId, sentenceId, moduleId,
            ),
        )
    }
    private fun explanationProvider(chapter: com.reallanguage.app.feature.learning.domain.FixedLearningChapter, focusSentenceId: String): HttpExplanationReadingProvider {
        val targets = com.reallanguage.app.feature.learning.data.AndroidTargetSelfAssessmentRepository(
            applicationContext, sessionRuntime, chapter.chapterId, focusSentenceId, "LR-02",
        )
        val locked = com.reallanguage.app.feature.learning.data.AndroidLockedExplanationContextReader(applicationContext, sessionRuntime)
        return HttpExplanationReadingProvider(transport, sessionRuntime, chapter, focusSentenceId,
            UUID.randomUUID().toString(), cache = com.reallanguage.app.feature.learning.data.AndroidExplanationCache(applicationContext),
            selectedExpressions = targets::selectedExpressionDisplays,
            lockedContext = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { locked.read(chapter.chapterId, focusSentenceId) } })
    }
    fun explanationPreparationFactory(route: HomeDiaryRoute.ChapterExplanation) = viewModelFactory { ExplanationPreparationViewModel(explanationProvider(route.chapter, route.focusSentenceId)) }
    fun explanationReadingFactory(route: HomeDiaryRoute.ExplanationReading) = viewModelFactory {
        ExplanationReadingViewModel(route.chapter, route.focusSentenceId, route.baseExplanationZh, route.expressions,
            explanationProvider(route.chapter, route.focusSentenceId),
            com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore(applicationContext, sessionRuntime,
                route.chapter.chapterId, route.focusSentenceId, "LR-02", "explanation:${route.focusSentenceId}"),
            com.reallanguage.app.feature.learning.domain.LearningVoicePreferencesProvider {
                val account = sessionRuntime.accountId
                val result = subsequentSettingsRepository.load()
                if (account == null || sessionRuntime.accountId != account ||
                    result !is com.reallanguage.app.feature.settings.domain.SubsequentSettingsLoadResult.Ready) null
                else result.settings.let { preferences ->
                    com.reallanguage.app.feature.learning.domain.LearningVoicePreferences(
                        requireNotNull(preferences.voiceEnabled), requireNotNull(preferences.autoPlayEnabled),
                        requireNotNull(preferences.speechSpeedLevel))
                }
            })
    }
    fun readSpeakSession(route: HomeDiaryRoute.ReadSpeak) = AndroidReadSpeakRepository(
        transport = transport,
        runtime = sessionRuntime,
        chapterId = route.chapter.chapterId,
        targetTextEn = route.chapter.sentences.first { it.sentenceId == route.focusSentenceId }.textEn,
        commandId = UUID.randomUUID().toString(),
        capture = readSpeakCaptureFactory?.invoke(applicationContext, sessionRuntime)
            ?: AndroidMediaRecorderReadSpeakCapture(applicationContext, sessionRuntime),
        stepStore = AndroidLearningStepStore(applicationContext),
        isCaptureAllowed = playbackEligibility(),
        recoveryJournal = com.reallanguage.app.feature.learning.data.AndroidReadSpeakRecoveryJournal(
            applicationContext, sessionRuntime, route.chapter.chapterId, route.focusSentenceId,
            route.chapter.sentences.first { it.sentenceId == route.focusSentenceId }.textEn),
        recoverySentenceId = route.focusSentenceId,
    )
    fun readSpeakFactory(route: HomeDiaryRoute.ReadSpeak, repository: AndroidReadSpeakRepository) = viewModelFactory {
        ReadSpeakViewModel(route.chapter, route.focusSentenceId, repository,
            com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore(
                applicationContext, sessionRuntime, route.chapter.chapterId, route.focusSentenceId, "LR-03",
            ),
        )
    }
    fun handwritingFactory(route: HomeDiaryRoute.Handwriting) = viewModelFactory {
        HandwritingCopyViewModel(
            route.chapter,
            route.focusSentenceId,
            AndroidHandwritingCompletionRepository(
                sessionRuntime,
                route.chapter.chapterId,
                UUID.randomUUID().toString(),
                AndroidLearningStepStore(applicationContext),
            ),
            com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore(
                applicationContext, sessionRuntime, route.chapter.chapterId, route.focusSentenceId, "LR-04",
            ),
        )
    }
    private fun audioDictationCompletion(chapterId: String, isCorrection: Boolean = false) =
        AndroidAudioDictationCompletionRepository(
            sessionRuntime,
            chapterId,
            UUID.randomUUID().toString(),
            AndroidLearningStepStore(applicationContext),
            isCorrection = isCorrection,
        )
    suspend fun loadLockedHints(chapterId: String, sentenceId: String): List<String>? {
        val reader = AndroidLockedHintReader(applicationContext, sessionRuntime)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { reader.read(chapterId, sentenceId) }
    }
    fun audioDictationFactory(route: HomeDiaryRoute.AudioDictation, hints: List<String>) = viewModelFactory {
        AudioDictationViewModel(
            route.chapter,
            route.focusSentenceId,
            hints,
            route.input,
            FixedWritingComparator(),
            audioDictationCompletion(route.chapter.chapterId),
            com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore(
                applicationContext, sessionRuntime, route.chapter.chapterId, route.focusSentenceId, "LR-05",
            ),
        )
    }
    fun correctionFactory(route: HomeDiaryRoute.ReadWriteCorrection, hints: List<String>) = viewModelFactory {
        ReadWriteCorrectionViewModel(
            route.chapter,
            route.focusSentenceId,
            hints,
            audioDictationCompletion(route.chapter.chapterId, isCorrection = true),
        )
    }
    fun fullSentenceSpeakingSession(route: HomeDiaryRoute.FullSentenceSpeaking) =
        AndroidFullSentenceSpeakingRepository(
            context = applicationContext,
            transport = transport,
            runtime = sessionRuntime,
            chapterId = route.chapter.chapterId,
            targetTextEn = route.chapter.sentences.first {
                it.sentenceId == route.focusSentenceId
            }.textEn,
            capture = fullSentenceSpeakingCaptureFactory?.invoke(applicationContext, sessionRuntime)
                ?: AndroidMediaRecorderReadSpeakCapture(applicationContext, sessionRuntime),
            isCaptureAllowed = playbackEligibility(),
            recoveryJournalFactory = { sentenceId, round ->
                require(sentenceId == route.focusSentenceId)
                com.reallanguage.app.feature.learning.data.AndroidFullSpeakingRecoveryJournal(
                    applicationContext, sessionRuntime, route.chapter.chapterId, sentenceId,
                    route.chapter.sentences.first { it.sentenceId == sentenceId }.textEn, round,
                )
            },
        )
    fun fullSentenceSpeakingFactory(
        route: HomeDiaryRoute.FullSentenceSpeaking,
        repository: AndroidFullSentenceSpeakingRepository,
    ) = viewModelFactory {
        FullSentenceSpeakingViewModel(route.chapter, route.focusSentenceId, repository,
            com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore(
                applicationContext, sessionRuntime, route.chapter.chapterId, route.focusSentenceId, "LR-06",
            ),
        )
    }
    fun patternSpeakingSession(route: HomeDiaryRoute.PatternSpeaking) =
        AndroidPatternSpeakingRepository(
            transport,
            sessionRuntime,
            route.chapter,
            patternSpeakingCaptureFactory?.invoke(applicationContext, sessionRuntime)
                ?: AndroidMediaRecorderReadSpeakCapture(applicationContext, sessionRuntime),
            isCaptureAllowed = playbackEligibility(),
            recoveryJournalFactory = { pattern, practice ->
                val source = route.chapter.sentences.single { it.sentenceId == route.focusSentenceId && it.isFocus }
                com.reallanguage.app.feature.learning.data.AndroidPatternSpeakingRecoveryJournal(
                    applicationContext, sessionRuntime, route.chapter.chapterId, route.focusSentenceId,
                    source.textEn, pattern, practice, patternSpeakingRecoveryKeys,
                )
            },
        )
    fun patternSpeakingFactory(
        route: HomeDiaryRoute.PatternSpeaking,
        repository: AndroidPatternSpeakingRepository,
        lockedPatterns: List<com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern>,
    ) = viewModelFactory {
        val removedReader = AndroidLockedPatternReader(applicationContext, sessionRuntime)
        PatternSpeakingViewModel(
            route.chapter,
            route.focusSentenceId,
            lockedPatterns,
            repository,
            AndroidLearningProgressStore(
                applicationContext, sessionRuntime, route.chapter.chapterId, route.focusSentenceId, "LR-07",
            ),
            loadRemovedPatterns = {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    checkNotNull(removedReader.readRemoved(route.chapter.chapterId, route.focusSentenceId))
                }
            },
        )
    }
    fun completeWritingSession() = AndroidCompleteWritingRepository(
        applicationContext,
        sessionRuntime,
    )
    fun completeWritingFactory(
        route: HomeDiaryRoute.CompleteWriting,
        repository: AndroidCompleteWritingRepository,
        lockedTargets: List<com.reallanguage.app.feature.learning.domain.LockedWritingTarget>,
    ) = viewModelFactory {
        val comparator = FixedWritingComparator()
        CompleteWritingViewModel(
            chapter = route.chapter,
            lockedTargets = lockedTargets,
            comparator = comparator,
            evaluator = CompleteWritingEvaluator(comparator),
            repository = repository,
            progressStore = com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore(
                applicationContext, sessionRuntime, route.chapter.chapterId, route.focusSentenceId, "LR-08",
            ),
        )
    }
    fun sceneResponseSession() = AndroidSceneResponseRepository(
        applicationContext,
        transport,
        sessionRuntime,
        sceneResponseCaptureFactory?.invoke(applicationContext, sessionRuntime)
            ?: AndroidMediaRecorderReadSpeakCapture(applicationContext, sessionRuntime),
        isCaptureAllowed = playbackEligibility(),
        recoveryJournalFactory = { chapter, task, round ->
            com.reallanguage.app.feature.learning.data.AndroidSceneResponseRecoveryJournal(
                applicationContext, sessionRuntime, chapter, task, round, sceneResponseRecoveryKeys,
            )
        },
    )
    fun sceneResponseFactory(
        route: HomeDiaryRoute.SceneResponse,
        repository: AndroidSceneResponseRepository,
    ) = viewModelFactory {
        SceneResponseViewModel(route.chapter, null, repository,
            AndroidLearningProgressStore(applicationContext, sessionRuntime, route.chapter.chapterId, route.focusSentenceId, "LR-09"),
        )
    }
    fun learningExitFactory(
        checkpoint: OrdinaryLearningCheckpoint,
        repository: com.reallanguage.app.feature.learning.domain.LearningCheckpointRepository = learningCheckpointRepository.forCurrentSession(),
    ) = viewModelFactory {
        LearningExitViewModel(
            checkpoint,
            repository,
        )
    }
    fun chapterResultFactory(chapterId: String, commandId: String) = viewModelFactory {
        ChapterResultViewModel(
            chapterId,
            commandId,
            AndroidChapterArchiveRepository(applicationContext, sessionRuntime),
        )
    }
    fun chapterSelectionFactory(boundary: ChapterSelectionBoundary) = viewModelFactory {
        ChapterSelectionViewModel(
            boundary,
            AndroidChapterSelectionRepository(applicationContext, sessionRuntime),
        )
    }
    @Composable internal fun rememberChapterSelectionEntryOwner(boundary: ChapterSelectionBoundary): androidx.lifecycle.ViewModelStoreOwner {
        val revision by sessionRuntime.identityRevision.collectAsState()
        // A token refresh keeps the directory entry; a new login or Graph does not.
        val identity = remember(this, revision) { sessionRuntime.accountId to sessionRuntime.accountEpoch }
        return rememberEntryViewModelStoreOwner(listOf(this, boundary, identity))
    }
    fun chapterAbandonFactory(chapterId: String, commandId: String) = viewModelFactory {
        ChapterAbandonViewModel(
            chapterId,
            commandId,
            AndroidChapterAbandonRepository(applicationContext, sessionRuntime),
        )
    }
    fun todayReviewFactory() = viewModelFactory {
        TodayReviewViewModel(AndroidTodayReviewRepository(applicationContext, sessionRuntime))
    }
    fun reviewChapterFactory(reviewChapterId: String, commandId: String, lease: ReviewPatternEntryLease?) = viewModelFactory {
        ReviewChapterViewModel(
            reviewChapterId,
            commandId,
            AndroidReviewChapterRepository(applicationContext, sessionRuntime, lease),
        )
    }
    suspend fun hasDueReview() =
        AndroidTodayReviewRepository(applicationContext, sessionRuntime).hasDueReview()
    suspend fun loadReviewFixedChapter(chapterId: String) =
        AndroidFixedChapterRepository(applicationContext, sessionRuntime).load(chapterId)
    suspend fun reviewWritingTargets(route: HomeDiaryRoute.ReviewAbilityExecution, chapterId: String): List<com.reallanguage.app.feature.learning.domain.LockedWritingTarget>? {
        // Both readers capture the same owner before any suspending queue read.
        val reviews = AndroidReviewChapterRepository(applicationContext, sessionRuntime)
        val targets = AndroidLockedWritingTargetReader(applicationContext, sessionRuntime)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            loadReviewWritingTargets(route.reviewChapterId, chapterId, route.ability,
                loadChapter = reviews::loadChapter, readTarget = { chapter, sentence, target ->
                    targets.readTarget(chapter, sentence, target)
                })
        }
    }
    suspend fun loadWritingTargets(chapterId: String): List<com.reallanguage.app.feature.learning.domain.LockedWritingTarget>? {
        val reader = AndroidLockedWritingTargetReader(applicationContext, sessionRuntime)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { reader.read(chapterId) }
    }
    suspend fun loadSpeakingPatterns(chapterId: String, sentenceId: String): List<com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern>? {
        val reader = AndroidLockedPatternReader(applicationContext, sessionRuntime)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { reader.read(chapterId, sentenceId) }
    }
    fun reviewSpeakingSession(chapter: com.reallanguage.app.feature.learning.domain.FixedLearningChapter, sentenceId: String) =
        ReviewSpeakingAdapter(
            AndroidFullSentenceSpeakingRepository(
                context = applicationContext,
                transport = transport,
                runtime = sessionRuntime,
                chapterId = chapter.chapterId,
                targetTextEn = chapter.sentences.first { it.sentenceId == sentenceId }.textEn,
                capture = fullSentenceSpeakingCaptureFactory?.invoke(applicationContext, sessionRuntime)
                    ?: AndroidMediaRecorderReadSpeakCapture(applicationContext, sessionRuntime),
            ),
        )
    fun reviewSceneSession() = ReviewSceneResponseAdapter(
        AndroidSceneResponseRepository(
            applicationContext,
            transport,
            sessionRuntime,
            sceneResponseCaptureFactory?.invoke(applicationContext, sessionRuntime)
                ?: AndroidMediaRecorderReadSpeakCapture(applicationContext, sessionRuntime),
        ),
    )
    fun reviewPattern(chapterId: String, sentenceId: String, targetId: String) =
        AndroidLockedPatternReader(applicationContext, sessionRuntime)
            .read(chapterId, sentenceId)
            .orEmpty()
            .firstOrNull { it.patternId == targetId }
    @Synchronized fun reviewPatternIndependentSession(chapter: FixedLearningChapter, route: HomeDiaryRoute.ReviewAbilityExecution, lease: ReviewPatternEntryLease?): AndroidPatternIndependentRepository {
        val acceptedLease = lease?.takeIf { currentPatternReviewEntry() === it && it.reviewChapterId == route.reviewChapterId && it.entryId == route.entryId }
        val key = acceptedLease?.let { it to route.ability.abilityId }
        key?.let { patternReviewSessions[it] }?.let { return it }
        return AndroidPatternIndependentRepository(
            transport,
            sessionRuntime,
            chapter,
            reviewPatternCaptureFactory?.invoke(applicationContext, sessionRuntime)
                ?: AndroidMediaRecorderReadSpeakCapture(applicationContext, sessionRuntime),
            acceptedLease?.let { AndroidPatternIndependentReceiptStore(applicationContext, sessionRuntime, it,
                    PatternIndependentReceiptIdentity(it.accountId, it.accountEpoch, it.reviewChapterId, it.entryId,
                        chapter.chapterId, route.ability.sentenceId, route.ability.abilityId, route.ability.targetId)) },
        ).also { if (key != null) patternReviewSessions[key] = it }
    }
    fun reviewExitFactory(reviewChapterId: String) = viewModelFactory {
        ReviewExitViewModel(
            reviewChapterId,
            -1,
            AndroidReviewExitRepository(applicationContext, sessionRuntime,
                currentPatternReviewEntry()?.takeIf { it.reviewChapterId == reviewChapterId }),
        )
    }
    fun reviewResultFactory(reviewSessionId: String, commandId: String) = viewModelFactory {
        ReviewResultViewModel(
            reviewSessionId,
            commandId,
            AndroidReviewResultRepository(applicationContext, sessionRuntime),
        )
    }
    private fun safetyHandlingRepository() = HttpSafetyHandlingRepository(
        transport,
        sessionRuntime,
        SafetyHandlingCommandIds(
            accept = UUID.randomUUID().toString(),
            returnToEdit = UUID.randomUUID().toString(),
            manualReview = UUID.randomUUID().toString(),
        ),
    )
    fun safetyHandlingLoaderFactory(safetyCaseId: String) = viewModelFactory {
        SafetyHandlingLoaderViewModel(safetyCaseId, safetyHandlingRepository())
    }
    fun safetyHandlingFactory(details: com.reallanguage.app.feature.safety.domain.SafetyCaseDetails) = viewModelFactory {
        SafetyHandlingViewModel(details, safetyHandlingRepository())
    }
}

private fun productionHttpTransport(context: Context): com.reallanguage.app.core.network.HttpTransport {
    val discovery = AndroidNsdLocalApiDiscovery(
        context = context,
        serviceType = BuildConfig.API_NSD_SERVICE_TYPE,
    )
    return AddressFailoverHttpTransport(
        transports = buildList {
            add(UrlConnectionSessionTransport(
                baseUrl = BuildConfig.API_BASE_URL,
                allowCleartextForLocalDevelopment = BuildConfig.DEBUG,
            ))
            BuildConfig.API_FALLBACK_BASE_URL.takeIf(String::isNotBlank)?.let { fallback ->
                add(UrlConnectionSessionTransport(
                    baseUrl = fallback,
                    allowCleartextForLocalDevelopment = BuildConfig.DEBUG,
                ))
            }
        },
        discoverTransports = {
            discovery.discoverBaseUrls().map { baseUrl ->
                UrlConnectionSessionTransport(
                    baseUrl = baseUrl,
                    allowCleartextForLocalDevelopment = BuildConfig.DEBUG,
                )
            }
        },
    )
}

@Composable
fun ProductionApp(
    graph: ProductionAppGraph,
    passwordResetDeepLink: PasswordResetDeepLink? = null,
    onPasswordResetDeepLinkConsumed: () -> Unit = {},
    reminderHomeRequest: ReminderHomeRequest? = null,
    onReminderHomeConsumed: () -> Unit = {},
) {
    val coordinator: AppCoordinatorViewModel = viewModel(factory = graph.coordinatorFactory)
    val auth: AuthViewModel = viewModel(factory = graph.authFactory)
    val appState by coordinator.uiState.collectAsState()
    val authState by auth.uiState.collectAsState()
    var pendingManualReviewId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(passwordResetDeepLink) {
        if (passwordResetDeepLink != null) {
            coordinator.onAction(AppCoordinatorAction.LoggedOut)
        }
    }

    BackHandler(enabled = appState.route == AppRoute.Home) {
        coordinator.onAction(AppCoordinatorAction.BackRequested)
    }

    RlPaperBackground {
        when (appState.route?.let(::productionSurface)) {
            null -> ProductionLoadingScreen()
            ProductionSurface.LOGIN -> AuthProductionFlow(
            graph = graph,
            auth = auth,
            authState = authState,
            passwordResetDeepLink = passwordResetDeepLink,
            onPasswordResetDeepLinkConsumed = onPasswordResetDeepLinkConsumed,
            onAuthenticated = { settingsCompleted ->
                coordinator.onAction(AppCoordinatorAction.Authenticated(settingsCompleted))
            },
        )
        ProductionSurface.INITIAL_SETTINGS -> InitialSettingsProductionEntry(
            graph = graph,
            onCompleted = { coordinator.onAction(AppCoordinatorAction.InitialSettingsCompleted) },
            onLoginRequired = { coordinator.onAction(AppCoordinatorAction.LoggedOut) },
        )
        ProductionSurface.HOME -> HomeProductionFlow(
            graph = graph,
            initialRoute = pendingManualReviewId?.let { HomeDiaryRoute.ManualReviewResult(it) } ?: HomeDiaryRoute.Home,
            onInitialRouteConsumed = { pendingManualReviewId = null },
            onLoginRequired = { reviewRequestId ->
                pendingManualReviewId = reviewRequestId
                coordinator.onAction(AppCoordinatorAction.LoggedOut)
            },
            onLoggedOut = { coordinator.onAction(AppCoordinatorAction.LoggedOut) },
            onInitialSettingsRequired = { coordinator.onAction(AppCoordinatorAction.Authenticated(false)) },
            reminderHomeRequest = reminderHomeRequest,
            onReminderHomeConsumed = onReminderHomeConsumed,
        )
            ProductionSurface.CONNECTION_ERROR -> StartupConnectionErrorScreen {
                coordinator.onAction(AppCoordinatorAction.RetryStartupRequested)
            }
        }
    }
}

@Composable
internal fun HomeProductionFlow(
    graph: ProductionAppGraph,
    initialRoute: HomeDiaryRoute = HomeDiaryRoute.Home,
    onInitialRouteConsumed: () -> Unit = {},
    onLoginRequired: (String) -> Unit = {},
    onLoggedOut: () -> Unit = {},
    onInitialSettingsRequired: () -> Unit = {},
    reminderHomeRequest: ReminderHomeRequest? = null,
    onReminderHomeConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val connectionIncident by graph.connectionGate.incident.collectAsState()
    val connectionSessionRevision by graph.connectionSessionRevision.collectAsState()
    val playbackEligible = remember(graph) { graph.playbackEligibility() }
    val playbackInterrupted = remember(connectionIncident, connectionSessionRevision) { !playbackEligible() }
    // The graph survives logout; this Home composition and its pages do not gain
    // a later session's permission merely because their old callback runs late.
    val pageLearningCheckpoints = remember(graph) { graph.learningCheckpointRepository.forCurrentSession() }
    var route by remember { mutableStateOf(initialRoute) }
    val captureStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var captureStateKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(route) {
        val current = route
        if (current is HomeDiaryRoute.CaptureImport) {
            if (captureStateKey != current.entryId) captureStateKey?.let(captureStateHolder::removeState)
            captureStateKey = current.entryId
        } else if (current == HomeDiaryRoute.Home) {
            captureStateKey?.let(captureStateHolder::removeState)
            captureStateKey = null
        }
    }
    val recordPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (route == HomeDiaryRoute.Home) route = if (granted) HomeDiaryRoute.ActiveRecording()
        else HomeDiaryRoute.MicrophonePermission((context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true)
    }
    // Deeper learning routes intentionally contain only immutable learning data.
    // Retain the visible node that opened the chapter separately so walking all
    // the way back through the chapter never collapses to Home by accident.
    var learningRootReturnRoute by remember {
        mutableStateOf((initialRoute as? HomeDiaryRoute.ChapterLearning)?.returnRoute ?: HomeDiaryRoute.Home)
    }
    var diaryEditorEpoch by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var diaryAttemptId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var pendingBilingual by remember { mutableStateOf<PendingBilingualRequest?>(null) }
    // One unfixed scene operation only. Returning through questions retains its paid
    // attempt and edited review VM; a different answer/explicit regenerate replaces it.
    // Nothing here survives Home disposal, account replacement or confirmed abandonment.
    val sceneOwner = remember(graph) { graph.currentSessionOwner() }
    val sceneOwnerActive = graph.isSessionOwnerCurrent(sceneOwner)
    var sceneOperationId by remember(graph, sceneOwnerActive) { mutableStateOf(if (!sceneOwnerActive) null else when (initialRoute) {
        is HomeDiaryRoute.SceneQuestions -> initialRoute.operationId
        is HomeDiaryRoute.SceneGeneration -> initialRoute.session.operationId
        is HomeDiaryRoute.SceneChineseReview -> initialRoute.session.operationId
        else -> null
    }) }
    var sceneAttempt by remember(graph, sceneOwnerActive) { mutableStateOf<SceneGenerationContinuation?>(if (!sceneOwnerActive) null else when (initialRoute) {
        is HomeDiaryRoute.SceneGeneration -> SceneGenerationContinuation(initialRoute)
        is HomeDiaryRoute.SceneChineseReview -> SceneGenerationContinuation(
            HomeDiaryRoute.SceneGeneration(initialRoute.session), initialRoute)
        else -> null
    }) }
    val sceneFlowStore = rememberEntryViewModelStoreOwner(listOf(graph, sceneOwner, sceneOwnerActive, sceneOperationId))
    val sceneAttemptStore = rememberEntryViewModelStoreOwner(listOf(graph, sceneOwner, sceneOwnerActive, sceneAttempt?.generation?.entryId))
    fun openSceneGeneration(session: SceneQuestionSession, previousUnits: List<com.reallanguage.app.feature.scene.domain.SceneDraftUnit>? = null) {
        if (!graph.isSessionOwnerCurrent(sceneOwner)) return
        val saved = sceneAttempt?.takeIf { it.generation.session.hasSameGenerationInput(session) }
        sceneOperationId = session.operationId
        if (previousUnits == null && saved != null) {
            route = saved.delivered ?: saved.generation.copy(retryOnEntry = true)
        } else {
            val next = HomeDiaryRoute.SceneGeneration(session, previousUnits = previousUnits.orEmpty())
            sceneAttempt = SceneGenerationContinuation(next)
            route = next
        }
    }
    val reviewIdentity = patternReviewRouteIdentity(route)
    val reviewEntry = graph.rememberPatternReviewEntry(reviewIdentity?.first, reviewIdentity?.second)
    // A chapter's draft survives its ability pages, but not a replaced/closed entry.
    val reviewEntryOwner = rememberEntryViewModelStoreOwner(reviewEntry ?: Unit)
    var homeState by remember { mutableStateOf(HomeUiState()) }
    var reviewModuleResult by remember(reviewEntry) { mutableStateOf<ReviewAbilityResultDraft?>(null) }
    var reviewExitTarget by remember(reviewEntry) { mutableStateOf<HomeDiaryRoute?>(null) }
    var showLearningExit by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    var dispatchedReminderHome by remember { mutableStateOf<String?>(null) }
    var waitingReminderHome by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { onInitialRouteConsumed() }
    LaunchedEffect(graph) {
        graph.balanceRequiredEvents.collect {
            val origin = route
            if (
                origin !is HomeDiaryRoute.InsufficientBalance &&
                origin !is HomeDiaryRoute.Recharge
            ) {
                route = HomeDiaryRoute.InsufficientBalance(
                    origin = origin,
                    context = BalanceInsufficientContext(
                        originId = balanceOriginId(origin),
                        originState = balanceOriginState(origin),
                    ),
                )
            }
        }
    }
    LaunchedEffect(route) {
        pendingBilingual?.let { request ->
            if (request.visibleRoute != route) pendingBilingual = null
        }
        if (route is HomeDiaryRoute.Home || route is HomeDiaryRoute.ChapterLearning) {
            sceneAttempt = null
            sceneOperationId = null
        }
        if (route is HomeDiaryRoute.Home) {
            showReminder = graph.shouldShowReminder()
            graph.reconcileReminder()
            homeState = homeState.copy(
                hasDueReview = graph.hasDueReview(),
                hasIncompleteLearning = graph.hasIncompleteLearning(),
            )
        }
    }
    val checkpoint = learningCheckpoint(route)
    // Child effects run independently of the parent effect. Filter before
    // composition, rather than consuming a previous account's intent later.
    val matchingReminderHomeRequest = reminderHomeRequestForOwner(reminderHomeRequest, graph::reminderRequestMatchesOwner)
    LaunchedEffect(reminderHomeRequest, route) {
        val request = reminderHomeRequest ?: return@LaunchedEffect
        if (!graph.reminderRequestMatchesOwner(request)) {
            onReminderHomeConsumed()
            return@LaunchedEffect
        }
        if (route is HomeDiaryRoute.Home) {
            onReminderHomeConsumed()
            return@LaunchedEffect
        }
        if (dispatchedReminderHome == request.requestId) return@LaunchedEffect
        when (reminderHomeDisposition(route)) {
            ReminderHomeDisposition.HOME -> { route = HomeDiaryRoute.Home; onReminderHomeConsumed() }
            ReminderHomeDisposition.ORDINARY_EXIT -> {
                dispatchedReminderHome = request.requestId
                showLearningExit = true
            }
            ReminderHomeDisposition.REVIEW_EXIT -> {
                dispatchedReminderHome = request.requestId
                reviewExitTarget = route
            }
            ReminderHomeDisposition.CURRENT_FLOW_BACK -> Unit // The live child VM owns unsaved work and busy state.
            ReminderHomeDisposition.WAIT_FOR_SAFE_BOUNDARY -> if (waitingReminderHome != request.requestId) {
                waitingReminderHome = request.requestId
                android.widget.Toast.makeText(context, "当前内容尚未安全退出，请先完成或返回当前操作。", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    BackHandler(enabled = route !is HomeDiaryRoute.Home) {
        route = when (val current = route) {
            HomeDiaryRoute.Home -> current
            HomeDiaryRoute.TodayReview -> HomeDiaryRoute.Home
            HomeDiaryRoute.VocabularyBook -> HomeDiaryRoute.Home
            HomeDiaryRoute.PatternBook -> HomeDiaryRoute.Home
            is HomeDiaryRoute.IncompleteLearning -> HomeDiaryRoute.Home
            is HomeDiaryRoute.ResumeLearning -> HomeDiaryRoute.IncompleteLearning()
            is HomeDiaryRoute.Messages -> HomeDiaryRoute.Home
            is HomeDiaryRoute.ManualReviewResult -> HomeDiaryRoute.Messages()
            is HomeDiaryRoute.Account -> HomeDiaryRoute.Home
            is HomeDiaryRoute.Settings -> HomeDiaryRoute.Home
            is HomeDiaryRoute.SettingsEffect -> requireNotNull(contextualPreviousRoute(current))
            is HomeDiaryRoute.Billing -> HomeDiaryRoute.Account()
            is HomeDiaryRoute.Recharge -> requireNotNull(contextualPreviousRoute(current))
            is HomeDiaryRoute.Refund -> HomeDiaryRoute.Billing()
            is HomeDiaryRoute.InsufficientBalance -> requireNotNull(contextualPreviousRoute(current))
            is HomeDiaryRoute.LocalRecordings -> HomeDiaryRoute.Account()
            is HomeDiaryRoute.CaptureImport -> HomeDiaryRoute.Home
            is HomeDiaryRoute.Bilingual -> current.back
            HomeDiaryRoute.RecordingIntro -> HomeDiaryRoute.Home
            is HomeDiaryRoute.ActiveRecording -> current
            is HomeDiaryRoute.RecordingConfirmation -> requireNotNull(contextualPreviousRoute(current))
            is HomeDiaryRoute.RecordingTranscription -> HomeDiaryRoute.RecordingConfirmation(current.recordingId, current.returnRoute)
            is HomeDiaryRoute.TranscriptResult -> HomeDiaryRoute.RecordingConfirmation(current.recordingId, current.returnRoute)
            is HomeDiaryRoute.TranscriptionFailure -> HomeDiaryRoute.RecordingConfirmation(current.recordingId, current.returnRoute)
            is HomeDiaryRoute.MicrophonePermission -> requireNotNull(contextualPreviousRoute(current))
            is HomeDiaryRoute.SceneHome -> HomeDiaryRoute.Home
            is HomeDiaryRoute.SceneDirectDescription -> HomeDiaryRoute.SceneHome()
            is HomeDiaryRoute.SceneTypeSelection -> when (val start = current.start) {
                is SceneStart.DirectDescription -> HomeDiaryRoute.SceneDirectDescription(start.description)
                else -> HomeDiaryRoute.SceneHome(start)
            }
            is HomeDiaryRoute.SceneQuestions -> current
            is HomeDiaryRoute.SceneGeneration -> HomeDiaryRoute.SceneQuestions(
                current.session.start, current.session.contentType, current.session.operationId, current.session.answers,
            )
            is HomeDiaryRoute.SceneChineseReview -> HomeDiaryRoute.SceneQuestions(
                current.session.start, current.session.contentType, current.session.operationId, current.session.answers,
            )
            is HomeDiaryRoute.ReviewChapter -> HomeDiaryRoute.TodayReview
            is HomeDiaryRoute.ReviewAbilityExecution -> HomeDiaryRoute.ReviewChapter(current.reviewChapterId, current.entryId)
            is HomeDiaryRoute.ReviewResult -> HomeDiaryRoute.Home
            is HomeDiaryRoute.Input -> homeDiaryRoute(current, HomeDiaryEvent.ReturnHome)
            is HomeDiaryRoute.SafetyCheck -> current.returnRoute ?: if (current.sourceType == SourceType.DIARY) HomeDiaryRoute.Input(current.text) else HomeDiaryRoute.Home
            is HomeDiaryRoute.SemanticSplit -> formationReturnDestination(context, current)
            is HomeDiaryRoute.FocusSelection -> formationReturnDestination(context, current)
            is HomeDiaryRoute.SafetyHandlingLoading -> current.returnRoute ?: HomeDiaryRoute.Input(current.text)
            is HomeDiaryRoute.SafetyHandling -> current.returnRoute ?: HomeDiaryRoute.Input(current.details.originalSource.text)
            is HomeDiaryRoute.ReviewPending -> HomeDiaryRoute.Home
            is HomeDiaryRoute.ChapterFormation -> requireNotNull(formationPreviousRoute(current))
            is HomeDiaryRoute.EnglishGeneration -> requireNotNull(formationPreviousRoute(current))
            is HomeDiaryRoute.EnglishConfirmation -> requireNotNull(formationPreviousRoute(current))
            is HomeDiaryRoute.LearningTargets -> requireNotNull(formationPreviousRoute(current))
            is HomeDiaryRoute.LearningTargetSelection -> requireNotNull(formationPreviousRoute(current))
            is HomeDiaryRoute.FinalFix -> requireNotNull(formationPreviousRoute(current))
            is HomeDiaryRoute.ChapterLearning -> requireNotNull(contextualPreviousRoute(current))
            is HomeDiaryRoute.ChapterExplanation,
            is HomeDiaryRoute.ExplanationReading,
            is HomeDiaryRoute.ReadSpeak,
            is HomeDiaryRoute.Handwriting,
            is HomeDiaryRoute.AudioDictation,
            is HomeDiaryRoute.ReadWriteCorrection,
            is HomeDiaryRoute.FullSentenceSpeaking,
            is HomeDiaryRoute.PatternSpeaking,
            is HomeDiaryRoute.CompleteWriting,
            is HomeDiaryRoute.SceneResponse,
            -> requireNotNull(learningPreviousRoute(current, learningRootReturnRoute))
            is HomeDiaryRoute.ChapterResult,
            is HomeDiaryRoute.ChapterSelection,
            is HomeDiaryRoute.CompletedStepReview,
            is HomeDiaryRoute.ChapterAbandonConfirmation,
            -> requireNotNull(contextualPreviousRoute(current))
        }
    }

    Box(Modifier.fillMaxSize().then(if (connectionIncident != null) {
        Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics { }
    } else Modifier)) {
    CompositionLocalProvider(
        LocalConnectionPlaybackPolicy provides ConnectionPlaybackPolicy(playbackEligible, playbackInterrupted),
        LocalLearningExitAction provides checkpoint?.let { { showLearningExit = true } },
    ) {
    com.reallanguage.app.feature.learning.presentation.LearningCheckpointGate(
        checkpoint,
        pageLearningCheckpoints,
        onReturn = {
            route = learningPreviousRoute(route, learningRootReturnRoute) ?: HomeDiaryRoute.Home
        },
    ) {
    FormationContentGate(route, load = { requested -> graph.prepareFormation(requested) },
        onCached = { route = it }, onRestore = { route = it }) { gatedRoute, formationSnapshot ->
    FormationActionGate(gatedRoute,
        save = { input, index, canApply -> graph.prepareFormation(input, index, canApply) != null },
        onNavigate = { route = it }, initialSentenceIndex = formationSnapshot?.targetSentenceIndex ?: 0) { displayedRoute, progress ->
    Box(modifier = Modifier.fillMaxSize()) {
    when (val current = displayedRoute) {
        HomeDiaryRoute.Home -> HomeScreen(
            homeState,
            onAction = { action ->
                when (action) {
                    HomeAction.DiaryRequested -> {
                        diaryAttemptId = UUID.randomUUID().toString()
                        route = homeDiaryRoute(current, HomeDiaryEvent.OpenDiary)
                    }
                    HomeAction.DueReviewRequested -> route = HomeDiaryRoute.TodayReview
                    HomeAction.VocabularyRequested -> route = HomeDiaryRoute.VocabularyBook
                    HomeAction.SentencePatternsRequested -> route = HomeDiaryRoute.PatternBook
                    HomeAction.IncompleteLearningRequested -> route = HomeDiaryRoute.IncompleteLearning()
                    HomeAction.MessagesRequested -> route = HomeDiaryRoute.Messages()
                    HomeAction.SettingsRequested -> route = HomeDiaryRoute.Settings()
                    HomeAction.AccountRequested -> route = HomeDiaryRoute.Account()
                    HomeAction.ScreenshotImportRequested -> route = HomeDiaryRoute.CaptureImport(CaptureOrigin.SCREENSHOT)
                    HomeAction.AudioImportRequested -> route = HomeDiaryRoute.CaptureImport(CaptureOrigin.AUDIO_IMPORT)
                    HomeAction.QuickRecordingRequested -> {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            route = HomeDiaryRoute.ActiveRecording()
                        } else recordPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    HomeAction.AiSceneRequested -> route = HomeDiaryRoute.SceneHome()
                }
            },
        )
        HomeDiaryRoute.RecordingIntro -> RecordingIntroRoute(
            graph = graph,
            onRecording = { route = HomeDiaryRoute.ActiveRecording(returnRoute = HomeDiaryRoute.RecordingIntro) },
            onPermission = { canRequest ->
                route = HomeDiaryRoute.MicrophonePermission(canRequest, returnRoute = HomeDiaryRoute.RecordingIntro)
            },
            onHome = { route = HomeDiaryRoute.Home },
        )
        is HomeDiaryRoute.CaptureImport -> captureStateHolder.SaveableStateProvider(current.entryId) {
            val owner = remember { graph.currentSessionOwner() }
            suspend fun finish(text: String) {
                val review = graph.capturePassages(current.entryId, text, current.origin, SourceContentShape.DIALOGUE, current)
                if (route == current && graph.isSessionOwnerCurrent(owner)) route = review
            }
            if (current.origin == CaptureOrigin.SCREENSHOT) {
                ScreenshotImportScreen(onBack = { route = HomeDiaryRoute.Home }, onRecognized = { finish(it) })
            } else {
                AudioImportScreen(onBack = { route = HomeDiaryRoute.Home }, onTranscribe = { finish(graph.captureTranscript(it)) })
            }
        }
        is HomeDiaryRoute.Bilingual -> {
            var selection by remember(current.commandId) { mutableStateOf(current.selected) }
            fun accepted() = current.copy(selected = selection)
            BilingualPassagesScreen(current.paragraphs, selection,
                onSelect = { id ->
                    selection = when {
                        id in selection -> selection - id
                        selection.size < 3 -> selection + id
                        else -> selection
                    }
                    progress.stateChanged(accepted(), 0)
                },
                onBack = { progress.navigate(current.back) },
                onContinue = { if (selection.size in 1..3) progress.navigate(accepted().learningTargets()) },
            )
        }
        is HomeDiaryRoute.ActiveRecording -> {
            val recording: ActiveRecordingViewModel = viewModel(key = "active-recording:${current.entryId}", factory = graph.activeRecordingFactory())
            val owner = remember(current.entryId) { graph.currentSessionOwner() }
            RecordingCaptureEntry(recording, onBack = { route = HomeDiaryRoute.Home }) { id ->
                val text = graph.captureTranscript(graph.capturedRecording(id))
                val result = graph.capturePassages(current.entryId, text, CaptureOrigin.RECORDING, SourceContentShape.DIALOGUE, HomeDiaryRoute.Home)
                if (route == current && graph.isSessionOwnerCurrent(owner)) route = result
            }
        }
        is HomeDiaryRoute.RecordingConfirmation -> RecordingConfirmationRoute(
            graph = graph,
            route = current,
            onTranscribe = { recording -> recording.endReason?.let { reason ->
                route = HomeDiaryRoute.RecordingTranscription(
                    current.recordingId,
                    recording.durationSeconds,
                    reason,
                    returnRoute = current.returnRoute,
                )
            } },
            onReturn = { route = current.returnRoute ?: HomeDiaryRoute.Home },
            onHome = { route = HomeDiaryRoute.Home },
        )
        is HomeDiaryRoute.RecordingTranscription -> RecordingTranscriptionRoute(
            graph = graph,
            route = current,
            onResult = { transcriptId -> route = HomeDiaryRoute.TranscriptResult(current.recordingId, transcriptId, current.returnRoute) },
            onFailure = {
                route = HomeDiaryRoute.TranscriptionFailure(
                    current.recordingId,
                    current.durationSeconds,
                    current.endReason,
                    current.returnRoute,
                )
            },
            onHome = { route = HomeDiaryRoute.Home },
        )
        is HomeDiaryRoute.TranscriptResult -> TranscriptResultRoute(
            graph = graph,
            route = current,
            onReturn = { route = HomeDiaryRoute.RecordingConfirmation(current.recordingId, current.returnRoute) },
            onConfirmed = { text -> route = HomeDiaryRoute.SafetyCheck(text, SourceType.RECORDING, SourceContentShape.ARTICLE, current) },
        )
        is HomeDiaryRoute.TranscriptionFailure -> TranscriptionFailureRoute(
            graph = graph,
            route = current,
            onRetry = {
                route = HomeDiaryRoute.RecordingTranscription(
                    current.recordingId,
                    current.durationSeconds,
                    current.endReason,
                    returnRoute = current.returnRoute,
                )
            },
            onReturn = { route = HomeDiaryRoute.RecordingConfirmation(current.recordingId, current.returnRoute) },
        )
        is HomeDiaryRoute.MicrophonePermission -> MicrophonePermissionRoute(
            graph = graph,
            route = current,
            onRecording = { route = HomeDiaryRoute.ActiveRecording(returnRoute = current.returnRoute) },
            onReturn = { route = current.returnRoute },
            onHome = { route = HomeDiaryRoute.Home },
        )
        is HomeDiaryRoute.SceneHome -> if (sceneOwnerActive) CompositionLocalProvider(LocalViewModelStoreOwner provides sceneFlowStore) { SceneHomeRoute(
            graph = graph,
            route = current,
            onType = { route = HomeDiaryRoute.SceneTypeSelection(it) },
            onDirectDescription = { route = HomeDiaryRoute.SceneDirectDescription(it) },
            onBack = { route = HomeDiaryRoute.Home },
        ) }
        is HomeDiaryRoute.SceneDirectDescription -> if (sceneOwnerActive) CompositionLocalProvider(LocalViewModelStoreOwner provides sceneFlowStore) { SceneDirectDescriptionRoute(
            graph = graph,
            route = current,
            onType = { route = HomeDiaryRoute.SceneTypeSelection(SceneStart.DirectDescription(it)) },
            onHome = { route = HomeDiaryRoute.SceneHome() },
        ) }
        is HomeDiaryRoute.SceneTypeSelection -> if (sceneOwnerActive) CompositionLocalProvider(LocalViewModelStoreOwner provides sceneFlowStore) { SceneTypeRoute(
            graph = graph,
            route = current,
            onQuestions = { start, type ->
                val next = HomeDiaryRoute.SceneQuestions(start, type)
                sceneAttempt = null
                sceneOperationId = next.operationId
                route = next
            },
            onHome = { start -> route = when (start) {
                is SceneStart.DirectDescription -> HomeDiaryRoute.SceneDirectDescription(start.description)
                else -> HomeDiaryRoute.SceneHome(start)
            } },
        ) }
        is HomeDiaryRoute.SceneQuestions -> if (sceneOwnerActive) CompositionLocalProvider(LocalViewModelStoreOwner provides sceneFlowStore) { SceneQuestionsRoute(
            graph = graph,
            route = current,
            homeRequestId = matchingReminderHomeRequest?.requestId,
            onHomeRequestHandled = onReminderHomeConsumed,
            onGeneration = { openSceneGeneration(it) },
            onBack = { route = HomeDiaryRoute.SceneTypeSelection(current.start) },
            onHome = {
                if (graph.isSessionOwnerCurrent(sceneOwner)) {
                    sceneAttempt = null; sceneOperationId = null; route = HomeDiaryRoute.Home
                }
            },
        ) }
        is HomeDiaryRoute.SceneGeneration -> if (sceneOwnerActive) CompositionLocalProvider(LocalViewModelStoreOwner provides sceneAttemptStore) { SceneGenerationRoute(
            graph = graph,
            route = current,
            onReview = { session, draft ->
                if (graph.isSessionOwnerCurrent(sceneOwner) && sceneAttempt?.generation?.entryId == current.entryId) {
                    val next = HomeDiaryRoute.SceneChineseReview(session, draft)
                    sceneAttempt = sceneAttempt?.copy(delivered = next)
                    route = next
                }
            },
            onQuestions = { session ->
                if (graph.isSessionOwnerCurrent(sceneOwner)) route = HomeDiaryRoute.SceneQuestions(
                    session.start, session.contentType, session.operationId, session.answers)
            },
        ) }
        is HomeDiaryRoute.SceneChineseReview -> if (sceneOwnerActive) SceneChineseReviewRoute(
            graph = graph,
            route = current,
            onSafety = { source, confirmedDraft ->
                if (graph.isSessionOwnerCurrent(sceneOwner) && pendingBilingual == null) {
                    pendingBilingual = PendingBilingualRequest(current, HomeDiaryRoute.SemanticSplit(
                        source.text, source.sourceType, source.contentShape, current.copy(draft = confirmedDraft),
                        entryId = current.session.operationId + ":" + confirmedDraft.draftId))
                }
            },
            onQuestions = { session ->
                if (graph.isSessionOwnerCurrent(sceneOwner)) route = HomeDiaryRoute.SceneQuestions(
                    session.start, session.contentType, session.operationId, session.answers)
            },
            onGeneration = { session, previousUnits -> openSceneGeneration(session, previousUnits) },
            retainedOwner = sceneAttemptStore,
        )
        HomeDiaryRoute.TodayReview -> TodayReviewRoute(
            graph = graph,
            onChapter = { route = HomeDiaryRoute.ReviewChapter(it) },
            onHome = { route = HomeDiaryRoute.Home },
        )
        HomeDiaryRoute.VocabularyBook -> VocabularyBookRoute(
            graph = graph,
            onHome = { route = HomeDiaryRoute.Home },
        )
        HomeDiaryRoute.PatternBook -> PatternBookRoute(
            graph = graph,
            onHome = { route = HomeDiaryRoute.Home },
        )
        is HomeDiaryRoute.IncompleteLearning -> IncompleteLearningRoute(
            graph = graph,
            entryId = current.entryId,
            onResume = { chapterId, moduleId, stepId ->
                learningRootReturnRoute = current
                route = HomeDiaryRoute.ResumeLearning(chapterId, moduleId, stepId)
            },
            onHome = { route = HomeDiaryRoute.Home },
        )
        is HomeDiaryRoute.ResumeLearning -> {
            LearningRouteLoader(
                request = current,
                load = { graph.resolveResumeRoute(current.chapterId, current.moduleId, current.stepId) },
                onReady = { resolved ->
                    if (route == current) {
                        route = if (resolved is HomeDiaryRoute.ChapterLearning) {
                            resolved.copy(returnRoute = learningRootReturnRoute)
                        } else resolved
                    }
                },
                onReturn = {
                    route = if (current.moduleId.startsWith("FORMATION:")) {
                        HomeDiaryRoute.IncompleteLearning()
                    } else {
                        HomeDiaryRoute.ChapterLearning(current.chapterId, learningRootReturnRoute)
                    }
                },
            )
        }
        is HomeDiaryRoute.Messages -> MessagesRoute(
            graph = graph,
            route = current,
            onTarget = { target ->
                route = if (target.route == "manual-review") HomeDiaryRoute.ManualReviewResult(target.resourceId) else current
            },
            onHome = { route = HomeDiaryRoute.Home },
        )
        is HomeDiaryRoute.ManualReviewResult -> ManualReviewResultRoute(
            graph = graph,
            route = current,
            onApproved = { source ->
                pendingBilingual = PendingBilingualRequest(
                    visibleRoute = current,
                    operation = HomeDiaryRoute.SemanticSplit(
                        source.text,
                        source.sourceType,
                        source.contentShape,
                        sourceReturnRoute = current,
                        entryId = "review:${current.reviewRequestId}",
                    ),
                )
            },
            onNotApproved = { caseId, text -> route = HomeDiaryRoute.SafetyHandlingLoading(caseId, text, HomeDiaryRoute.Messages()) },
            onLoginRequired = onLoginRequired,
            onMessages = { route = HomeDiaryRoute.Messages() },
        )
        is HomeDiaryRoute.Account -> AccountRoute(
            graph = graph,
            route = current,
            onLocalRecordings = { route = HomeDiaryRoute.LocalRecordings() },
            onBilling = { route = HomeDiaryRoute.Billing() },
            onLoggedOut = onLoggedOut,
            onHome = { route = HomeDiaryRoute.Home },
        )
        is HomeDiaryRoute.Settings -> SubsequentSettingsRoute(
            graph = graph,
            route = current,
            homeRequestId = matchingReminderHomeRequest?.requestId,
            onHomeRequestHandled = onReminderHomeConsumed,
            onEffectScope = { original, proposed, originalReminder, proposedReminder ->
                route = HomeDiaryRoute.SettingsEffect(original, proposed,
                    originalReminder = originalReminder, proposedReminder = proposedReminder,
                    returnRoute = current)
            },
            onHome = { route = HomeDiaryRoute.Home },
        )
        is HomeDiaryRoute.SettingsEffect -> SettingsEffectRoute(
            graph = graph,
            route = current,
            homeRequestId = matchingReminderHomeRequest?.requestId,
            onHomeRequestHandled = onReminderHomeConsumed,
            onReturn = { route = current.returnRoute ?: HomeDiaryRoute.Settings() },
            onHome = { route = HomeDiaryRoute.Home },
        )
        is HomeDiaryRoute.Billing -> BillingRoute(
            graph = graph,
            route = current,
            onAccount = { route = HomeDiaryRoute.Account() },
            onRecharge = { route = HomeDiaryRoute.Recharge(current) },
            onRefund = { route = HomeDiaryRoute.Refund() },
        )
        is HomeDiaryRoute.Recharge -> RechargeRoute(
            graph = graph,
            route = current,
            onReturn = { route = current.origin },
        )
        is HomeDiaryRoute.Refund -> RefundRoute(
            graph = graph,
            route = current,
            onBilling = { route = HomeDiaryRoute.Billing() },
        )
        is HomeDiaryRoute.InsufficientBalance -> InsufficientBalanceRoute(
            graph = graph,
            route = current,
            onRecharge = { route = HomeDiaryRoute.Recharge(current.origin) },
            onExit = { route = current.origin },
        )
        is HomeDiaryRoute.LocalRecordings -> LocalRecordingsRoute(
            graph = graph,
            route = current,
            onRecording = { route = HomeDiaryRoute.RecordingConfirmation(it, current) },
            onAccount = { route = HomeDiaryRoute.Account() },
        )
        is HomeDiaryRoute.ReviewChapter -> CompositionLocalProvider(LocalViewModelStoreOwner provides reviewEntryOwner) { ReviewChapterRoute(
            graph = graph,
            route = current,
            onAbility = { chapter, ability ->
                if (route == current && reviewEntry != null && graph.currentPatternReviewEntry() === reviewEntry)
                    route = HomeDiaryRoute.ReviewAbilityExecution(current.reviewChapterId, current.entryId, chapter, ability)
            },
            onNext = { if (route == current && reviewEntry != null && graph.currentPatternReviewEntry() === reviewEntry) route = HomeDiaryRoute.ReviewChapter(it) },
            onResult = { sessionId ->
                if (route == current && reviewEntry != null && graph.currentPatternReviewEntry() === reviewEntry)
                    route = if (sessionId.isBlank()) HomeDiaryRoute.Home else HomeDiaryRoute.ReviewResult(sessionId)
            },
            completedModule = reviewModuleResult,
            onModuleConsumed = { reviewModuleResult = null },
            onExit = { reviewExitTarget = current },
        ) }
        is HomeDiaryRoute.ReviewAbilityExecution -> CompositionLocalProvider(LocalViewModelStoreOwner provides reviewEntryOwner) { ReviewAbilityExecutionRoute(
            graph = graph,
            route = current,
            onComplete = { result ->
                if (route == current && reviewEntry != null && graph.currentPatternReviewEntry() === reviewEntry) {
                    reviewModuleResult = result
                    route = HomeDiaryRoute.ReviewChapter(current.reviewChapterId, current.entryId)
                }
            },
            onBack = { reviewExitTarget = current },
        ) }
        is HomeDiaryRoute.ReviewResult -> ReviewResultRoute(
            graph = graph,
            route = current,
            onHome = { route = HomeDiaryRoute.Home },
        )
        is HomeDiaryRoute.Input -> androidx.compose.runtime.key(diaryEditorEpoch) {
            DiaryInputRoute(graph, current, homeRequestId = matchingReminderHomeRequest?.requestId,
                onHomeRequestHandled = onReminderHomeConsumed) { event ->
                if (event is HomeDiaryEvent.Submit) {
                    if (pendingBilingual == null) pendingBilingual = PendingBilingualRequest(current, HomeDiaryRoute.SemanticSplit(
                        event.text, sourceReturnRoute = HomeDiaryRoute.Input(event.text), entryId = diaryAttemptId))
                } else route = homeDiaryRoute(current, event)
            }
        }
        is HomeDiaryRoute.SafetyCheck -> SafetyCheckRoute(
            graph = graph,
            route = current,
            onPassed = { source ->
                pendingBilingual = PendingBilingualRequest(
                    visibleRoute = current,
                    operation = HomeDiaryRoute.SemanticSplit(
                        source.text,
                        source.sourceType,
                        source.contentShape,
                        sourceReturnRoute = safetySourceEditRoute(source, current.returnRoute) ?: current.returnRoute,
                        entryId = current.entryId,
                    ),
                )
            },
            onOpenSafetyHandling = { caseId, text ->
                route = HomeDiaryRoute.SafetyHandlingLoading(caseId, text, current.returnRoute)
            },
        )
        is HomeDiaryRoute.SemanticSplit -> SilentSemanticSplitRoute(graph, current,
            onReady = { units -> progress.navigate(current.toFocusSelection(units)) },
            onReturn = { progress.navigate(formationReturnDestination(context, current)) },
        )
        is HomeDiaryRoute.FocusSelection -> FocusSelectionRoute(
            graph = graph,
            route = current,
            onReturn = { progress.navigate(formationReturnDestination(context, current)) },
            onConfirm = { selected -> progress.navigate(preserveFormationOrigin(current, HomeDiaryRoute.ChapterFormation(current.text, current.units, selected, current.sourceType, current.contentShape))) },
            onStateChanged = { state -> progress.stateChanged(current.copy(units = state.units, initialSelectedUnitIds = state.selectedUnitIds), 0) },
        )
        is HomeDiaryRoute.SafetyHandlingLoading -> SafetyHandlingLoadingRoute(graph, current) { route = HomeDiaryRoute.SafetyHandling(it, current.returnRoute) }
        is HomeDiaryRoute.SafetyHandling -> SafetyHandlingRoute(
            graph = graph,
            route = current,
            onSplit = { source ->
                pendingBilingual = PendingBilingualRequest(
                    visibleRoute = current,
                    operation = HomeDiaryRoute.SemanticSplit(
                        source.text,
                        source.sourceType,
                        source.contentShape,
                        sourceReturnRoute = safetySourceEditRoute(source, current.returnRoute) ?: current.returnRoute,
                        entryId = "deidentified:${current.details.safetyCaseId}",
                    ),
                )
            },
            onEdit = { source -> safetySourceEditRoute(source, current.returnRoute)?.let { target -> route = target } },
            onReview = { route = HomeDiaryRoute.ReviewPending(current.details.originalSource.sourceType) },
            onExistingReview = { reviewRequestId -> route = HomeDiaryRoute.ManualReviewResult(reviewRequestId) },
        )
        is HomeDiaryRoute.ReviewPending -> ManualReviewPendingScreen(
            state = ManualReviewPendingUiState(current.sourceType),
            onAction = { action -> if (action == ManualReviewPendingAction.ReturnHomeRequested) route = HomeDiaryRoute.Home },
        )
        is HomeDiaryRoute.ChapterFormation -> ChapterFormationRoute(
            graph = graph,
            route = current,
            onFormed = { chapters -> progress.navigate(preserveFormationOrigin(current, HomeDiaryRoute.EnglishGeneration(current.text, current.units, current.selectedUnitIds, chapters, current.sourceType, current.contentShape))) },
            onReturn = { progress.navigate(requireNotNull(formationPreviousRoute(current, formationSnapshot))) },
        )
        is HomeDiaryRoute.EnglishGeneration -> EnglishGenerationRoute(
            graph,
            current,
            onGenerated = { progress.navigate(preserveFormationOrigin(current, HomeDiaryRoute.EnglishConfirmation(current.text, current.units, it, current.sourceType, current.contentShape))) },
            onReturn = { progress.navigate(requireNotNull(formationPreviousRoute(current, formationSnapshot))) },
        )
        is HomeDiaryRoute.EnglishConfirmation -> EnglishConfirmationRoute(
            graph,
            current,
            onTargets = { chapters, selections -> progress.navigate(preserveFormationOrigin(current, HomeDiaryRoute.LearningTargets(current.text, current.units, chapters, selections, current.sourceType, current.contentShape))) },
            onReturn = { progress.navigate(requireNotNull(formationPreviousRoute(current, formationSnapshot))) },
            onStateChanged = { state -> progress.stateChanged(current.copy(chapters = state.chapters, initialSelectedCandidateIds = state.selectedCandidateIds), 0) },
        )
        is HomeDiaryRoute.LearningTargets -> LearningTargetLoadingRoute(
            graph, current,
            onReady = { targets, expressions -> progress.navigate(preserveFormationOrigin(current, HomeDiaryRoute.LearningTargetSelection(current.text, current.units, current.chapters, current.selections, targets, expressions, sourceType = current.sourceType, contentShape = current.contentShape))) },
            onReturn = { progress.navigate(requireNotNull(formationPreviousRoute(current, formationSnapshot))) },
        )
        is HomeDiaryRoute.LearningTargetSelection -> LearningTargetSelectionRoute(
            graph, current,
            onContinue = { selected, targets -> progress.navigate(preserveFormationOrigin(current, HomeDiaryRoute.FinalFix(current.text, current.units, current.chapters, current.selections, targets, selected, current.sourceType, current.contentShape))) },
            onReturn = { progress.navigate(requireNotNull(formationPreviousRoute(current, formationSnapshot))) },
            onStateChanged = { state -> progress.stateChanged(current.copy(targets = state.targets, queryableExpressions = state.queryableExpressions,
                initialSelectedTargetIds = state.selectedTargetIds, initialSentenceIndex = state.focusSentenceIndex), state.focusSentenceIndex) },
        )
        is HomeDiaryRoute.FinalFix -> FinalFixRoute(
            graph, current,
            onFixed = { chapterId ->
                val owner = graph.reminderOwner()
                // Fixed is already committed. This only retires the matching draft;
                // a local cleanup retry must never invoke fix or bill AI again.
                formationSnapshot?.toFixedSourceProjection(current.entryId)?.let { graph.completeFormation(it) }
                if (owner != null && graph.reminderOwner() == owner) {
                    learningRootReturnRoute = HomeDiaryRoute.Home
                    route = HomeDiaryRoute.ChapterLearning(chapterId, learningRootReturnRoute)
                }
            },
            onReturn = { progress.navigate(requireNotNull(formationPreviousRoute(current, formationSnapshot))) },
            sourceProjection = formationSnapshot?.toFixedSourceProjection(current.entryId),
        )
        is HomeDiaryRoute.ChapterLearning -> ChapterEntryRoute(
            graph, current,
            onStart = { chapter, _ -> route = HomeDiaryRoute.ResumeLearning(chapter.chapterId, "LR-01", "start") },
            onReturn = { route = current.returnRoute },
        )
        is HomeDiaryRoute.ChapterExplanation -> ExplanationPreparationRoute(
            graph, current,
            onReady = { base, expressions -> route = HomeDiaryRoute.ExplanationReading(current.chapter, current.focusSentenceId, base, expressions) },
            onReturn = { route = requireNotNull(learningPreviousRoute(current, learningRootReturnRoute)) },
        )
        is HomeDiaryRoute.ExplanationReading -> ExplanationReadingRoute(
            graph, current,
            onReturn = { route = requireNotNull(learningPreviousRoute(current, learningRootReturnRoute)) },
            onNext = {
                nextLearningStep(current.chapter, current.focusSentenceId, "LR-02")?.let { route = it }
            },
        )
        is HomeDiaryRoute.ReadSpeak -> ReadSpeakRoute(
            graph,
            current,
            onReturn = { route = requireNotNull(learningPreviousRoute(current, learningRootReturnRoute)) },
            onHandwriting = {
                route = HomeDiaryRoute.Handwriting(
                    current.chapter,
                    current.focusSentenceId,
                    current.baseExplanationZh,
                    current.expressions,
                )
            },
        )
        is HomeDiaryRoute.Handwriting -> HandwritingRoute(
            graph,
            current,
            onReturn = { route = requireNotNull(learningPreviousRoute(current, learningRootReturnRoute)) },
            onDictation = {
                nextLearningStep(current.chapter, current.focusSentenceId, "LR-04")?.let { route = it }
            },
        )
        is HomeDiaryRoute.AudioDictation -> AudioDictationRoute(
            graph,
            current,
            onReturn = { route = requireNotNull(learningPreviousRoute(current, learningRootReturnRoute)) },
            onCorrection = { input ->
                route = HomeDiaryRoute.ReadWriteCorrection(
                    current.chapter,
                    current.focusSentenceId,
                    current.baseExplanationZh,
                    current.expressions,
                    input,
                )
            },
            onSpeaking = {
                nextLearningStep(current.chapter, current.focusSentenceId, "LR-05")?.let { route = it }
            },
        )
        is HomeDiaryRoute.ReadWriteCorrection -> ReadWriteCorrectionRoute(
            graph,
            current,
            onReturn = { route = requireNotNull(learningPreviousRoute(current, learningRootReturnRoute)) },
            onSpeaking = {
                nextLearningStep(current.chapter, current.focusSentenceId, "LR-05")?.let { route = it }
            },
        )
        is HomeDiaryRoute.FullSentenceSpeaking -> FullSentenceSpeakingRoute(
            graph,
            current,
            onReturn = { route = requireNotNull(learningPreviousRoute(current, learningRootReturnRoute)) },
            onPatternSpeaking = {
                nextLearningStep(current.chapter, current.focusSentenceId, "LR-06")?.let { route = it }
            },
        )
        is HomeDiaryRoute.PatternSpeaking -> PatternSpeakingRoute(
            graph,
            current,
            onReturn = { route = requireNotNull(learningPreviousRoute(current, learningRootReturnRoute)) },
            onCompleteWriting = {
                nextLearningStep(current.chapter, current.focusSentenceId, "LR-07")?.let { route = it }
            },
        )
        is HomeDiaryRoute.CompleteWriting -> CompleteWritingRoute(
            graph,
            current,
            onReturn = { route = requireNotNull(learningPreviousRoute(current, learningRootReturnRoute)) },
            onSceneResponse = {
                route = HomeDiaryRoute.SceneResponse(
                    current.chapter, current.focusSentenceId,
                    current.baseExplanationZh, current.expressions,
                )
            },
        )
        is HomeDiaryRoute.SceneResponse -> SceneResponseRoute(
            graph,
            current,
            onExit = { showLearningExit = true },
            onReturn = { route = requireNotNull(learningPreviousRoute(current, learningRootReturnRoute)) },
            onChapterResult = { route = HomeDiaryRoute.ChapterResult(current.chapter, returnRoute = current) },
        )
        is HomeDiaryRoute.ChapterResult -> ChapterResultRoute(
            graph,
            current,
            onChapterSelection = {
                route = HomeDiaryRoute.ChapterSelection(
                    ChapterSelectionBoundary.ArchivedChapter(current.chapter.chapterId),
                    returnRoute = current,
                )
            },
            onHome = { route = HomeDiaryRoute.Home },
        )
        is HomeDiaryRoute.ChapterSelection -> ChapterSelectionRoute(
            graph,
            current,
            onChapter = { chapterId ->
                learningRootReturnRoute = current
                route = HomeDiaryRoute.ChapterLearning(chapterId, current)
            },
            onReturn = { route = current.returnRoute ?: HomeDiaryRoute.Home },
            onHome = { route = HomeDiaryRoute.Home },
        )
        is HomeDiaryRoute.CompletedStepReview -> CompletedStepReviewRoute(
            current,
            onReturn = { route = current.currentTask },
            loadSnapshot = graph.completedStepSnapshotLoader(current.snapshot),
        )
        // Render this local confirmation outside the private learning-content cover.
        is HomeDiaryRoute.ChapterAbandonConfirmation -> Unit
    }
        pendingBilingual?.let { request ->
            CapturePreparationEntry(request, generate = {
                val op = request.operation
                graph.capturePassages(op.entryId, op.text, CaptureOrigin.valueOf(op.sourceType.name),
                    op.contentShape, op.sourceReturnRoute ?: HomeDiaryRoute.Home)
            }, onReady = { review ->
                if (pendingBilingual == request && route == request.visibleRoute) {
                    pendingBilingual = null
                    route = review
                }
            }, onBlocked = { id ->
                pendingBilingual = null
                route = HomeDiaryRoute.SafetyHandlingLoading(id, request.operation.text, request.operation.sourceReturnRoute)
            }, onReturn = {
                pendingBilingual = null
                if (request.visibleRoute is HomeDiaryRoute.Input) diaryEditorEpoch++
                route = request.operation.sourceReturnRoute ?: HomeDiaryRoute.Home
            })
        }
    }
    }
    }
    }
    }
    }
    connectionIncident?.let { incident ->
        ConnectionRecoveryEntry(graph, incident, route,
            onFlowReturn = {
                // A hidden child BackHandler may mean "previous learning step". ER-01 must
                // expose the existing exit decision, not navigate among concealed content.
                when {
                    checkpoint != null -> showLearningExit = true
                    route is HomeDiaryRoute.ReviewChapter || route is HomeDiaryRoute.ReviewAbilityExecution -> reviewExitTarget = route
                    else -> backDispatcher?.onBackPressed()
                }
            }, onLoginRequired = onLoggedOut,
            onInitialSettingsRequired = onInitialSettingsRequired)
    }
    (route as? HomeDiaryRoute.ChapterAbandonConfirmation)?.let { current ->
        ChapterAbandonRoute(
            graph,
            current,
            onContinue = { route = current.returnRoute },
            onChapterSelection = { sourceId ->
                route = HomeDiaryRoute.ChapterSelection(
                    ChapterSelectionBoundary.FixedSource(sourceId),
                    returnRoute = HomeDiaryRoute.Home,
                )
            },
            onHome = { route = HomeDiaryRoute.Home },
            aboveConnectionCover = connectionIncident != null,
        )
    }
    if (showLearningExit && checkpoint != null) {
        LearningExitRoute(
            graph,
            checkpoint,
            pageLearningCheckpoints,
            onClose = { showLearningExit = false; if (dispatchedReminderHome != null) onReminderHomeConsumed() },
            onHome = {
                showLearningExit = false
                route = HomeDiaryRoute.Home
            },
            onAbandon = {
                showLearningExit = false
                route = HomeDiaryRoute.ChapterAbandonConfirmation(
                    checkpoint.chapterId,
                    route,
                )
            },
        )
    }
    val reminderOwner = graph.reminderOwner()
    if (showReminder && route is HomeDiaryRoute.Home && reminderOwner != null) {
        DailyReminderProductionRoute(graph, reminderOwner) {
            graph.closeReminderForThisSession(reminderOwner)
            showReminder = false
        }
    }
    reviewExitTarget?.let { target ->
        val reviewChapterId = when (target) {
            is HomeDiaryRoute.ReviewChapter -> target.reviewChapterId
            is HomeDiaryRoute.ReviewAbilityExecution -> target.reviewChapterId
            else -> return@let
        }
        ReviewExitRoute(
            graph = graph,
            reviewChapterId = reviewChapterId,
            onContinue = { reviewExitTarget = null; if (dispatchedReminderHome != null) onReminderHomeConsumed() },
            onHome = {
                graph.releasePatternReviewEntry(reviewEntry)
                reviewExitTarget = null
                reviewModuleResult = null
                route = HomeDiaryRoute.Home
            },
        )
    }
}

private fun patternReviewRouteIdentity(route: HomeDiaryRoute): Pair<String, String>? = when (route) {
    is HomeDiaryRoute.ReviewChapter -> route.reviewChapterId to route.entryId
    is HomeDiaryRoute.ReviewAbilityExecution -> route.reviewChapterId to route.entryId
    is HomeDiaryRoute.InsufficientBalance -> patternReviewRouteIdentity(route.origin)
    is HomeDiaryRoute.Recharge -> patternReviewRouteIdentity(route.origin)
    else -> null
}

@Composable
private fun DailyReminderProductionRoute(graph: ProductionAppGraph, accountId: String, onClose: () -> Unit) {
    val reminder: DailyReminderIntroViewModel = viewModel(key = "daily-reminder:$accountId", factory = graph.reminderIntroFactory())
    val state by reminder.uiState.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        reminder.onAction(DailyReminderIntroAction.PermissionResult(graph.reminderPermission() == ReminderPermission.GRANTED))
    }
    LaunchedEffect(reminder) {
        reminder.effects.collect { effect ->
            when (effect) {
                DailyReminderIntroEffect.CloseNotice -> onClose()
                DailyReminderIntroEffect.RequestNotificationPermission -> {
                    if (graph.reminderPermission() == ReminderPermission.GRANTED) {
                        reminder.onAction(DailyReminderIntroAction.PermissionResult(true))
                    } else if (android.os.Build.VERSION.SDK_INT >= 33) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else reminder.onAction(DailyReminderIntroAction.PermissionResult(false))
                }
            }
        }
    }
    DailyReminderIntroDialog(state, reminder::onAction)
}

internal fun learningCheckpoint(route: HomeDiaryRoute): OrdinaryLearningCheckpoint? = when (route) {
    is HomeDiaryRoute.ChapterLearning -> OrdinaryLearningCheckpoint(route.chapterId, "LR-01", "entry")
    is HomeDiaryRoute.ChapterExplanation -> OrdinaryLearningCheckpoint(route.chapter.chapterId, "LR-02", "prepare:${route.focusSentenceId}")
    is HomeDiaryRoute.ExplanationReading -> OrdinaryLearningCheckpoint(route.chapter.chapterId, "LR-02", "explanation:${route.focusSentenceId}")
    is HomeDiaryRoute.ReadSpeak -> OrdinaryLearningCheckpoint(route.chapter.chapterId, "LR-03", route.focusSentenceId)
    is HomeDiaryRoute.Handwriting -> OrdinaryLearningCheckpoint(route.chapter.chapterId, "LR-04", route.focusSentenceId)
    is HomeDiaryRoute.AudioDictation,
    is HomeDiaryRoute.ReadWriteCorrection,
    -> (route as? HomeDiaryRoute.AudioDictation)?.let {
        OrdinaryLearningCheckpoint(it.chapter.chapterId, "LR-05", it.focusSentenceId)
    } ?: (route as HomeDiaryRoute.ReadWriteCorrection).let {
        OrdinaryLearningCheckpoint(it.chapter.chapterId, "LR-05", "correction:${it.focusSentenceId}")
    }
    is HomeDiaryRoute.FullSentenceSpeaking -> OrdinaryLearningCheckpoint(route.chapter.chapterId, "LR-06", route.focusSentenceId)
    is HomeDiaryRoute.PatternSpeaking -> OrdinaryLearningCheckpoint(route.chapter.chapterId, "LR-07", route.focusSentenceId)
    is HomeDiaryRoute.CompleteWriting -> OrdinaryLearningCheckpoint(route.chapter.chapterId, "LR-08", route.focusSentenceId)
    is HomeDiaryRoute.SceneResponse -> OrdinaryLearningCheckpoint(route.chapter.chapterId, "LR-09", route.focusSentenceId)
    else -> null
}

internal fun balanceOriginState(route: HomeDiaryRoute): BalanceOriginState = when {
    route is HomeDiaryRoute.ReviewChapter ||
        route is HomeDiaryRoute.ReviewAbilityExecution -> BalanceOriginState.UNIFIED_REVIEW
    route is HomeDiaryRoute.RecordingTranscription ->
        BalanceOriginState.RECORDING_AWAITING_TRANSCRIPTION
    route is HomeDiaryRoute.ChapterExplanation -> BalanceOriginState.FIXED_UNSTARTED_CHAPTER
    learningCheckpoint(route) != null -> BalanceOriginState.NORMAL_LEARNING
    else -> BalanceOriginState.UNFIXED_DIARY_OR_AI_SCENE
}

internal fun balanceOriginId(route: HomeDiaryRoute): String = when (route) {
    is HomeDiaryRoute.ReviewChapter -> "review:${route.reviewChapterId}"
    is HomeDiaryRoute.ReviewAbilityExecution -> "review:${route.reviewChapterId}"
    is HomeDiaryRoute.RecordingTranscription -> "recording:${route.recordingId}"
    is HomeDiaryRoute.ChapterExplanation -> "chapter:${route.chapter.chapterId}"
    else -> learningCheckpoint(route)?.let {
        "learning:${it.chapterId}:${it.moduleId}:${it.stepId}"
    } ?: "draft:${route::class.simpleName}"
}

@Composable
private fun RecordingIntroRoute(
    graph: ProductionAppGraph,
    onRecording: () -> Unit,
    onPermission: (Boolean) -> Unit,
    onHome: () -> Unit,
) {
    val context = LocalContext.current
    val intro: RecordingIntroViewModel = viewModel(
        key = "recording-intro",
        factory = graph.recordingIntroFactory(),
    )
    val state by intro.uiState.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        intro.onAction(RecordingIntroAction.PermissionResult(it))
    }
    LaunchedEffect(intro) {
        intro.effects.collect { effect ->
            when (effect) {
                RecordingIntroEffect.RequestMicrophonePermission -> {
                    graph.markRecordingIntroductionSeen()
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                }
                RecordingIntroEffect.OpenRecording -> onRecording()
                RecordingIntroEffect.OpenPermissionExplanation -> onPermission(
                    (context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) != false,
                )
                RecordingIntroEffect.OpenHome -> onHome()
            }
        }
    }
    RecordingIntroScreen(state, intro::onAction)
}

@Composable
private fun RecordingConfirmationRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.RecordingConfirmation,
    onTranscribe: (com.reallanguage.app.feature.recording.domain.LocalRecordingSummary) -> Unit,
    onReturn: () -> Unit,
    onHome: () -> Unit,
) {
    val confirmation: RecordingConfirmationViewModel = viewModel(
        key = "recording-confirmation:${route.recordingId}",
        factory = graph.recordingConfirmationFactory(route.recordingId),
    )
    val state by confirmation.uiState.collectAsState()
    RecordingPlaybackLifecycle(confirmation, confirmation::stopPlayback)
    var deletionRequested by remember(route.recordingId) { mutableStateOf(false) }
    LaunchedEffect(confirmation) {
        confirmation.effects.collect { effect ->
            when (effect) {
                is RecordingConfirmationEffect.OpenDeletionConfirmation -> deletionRequested = true
                is RecordingConfirmationEffect.OpenTranscription -> {
                    confirmation.uiState.value.recording?.let(onTranscribe)
                }
                RecordingConfirmationEffect.ReturnToPrevious -> onReturn()
                RecordingConfirmationEffect.OpenHome -> onHome()
            }
        }
    }
    RecordingConfirmationScreen(state, confirmation::onAction)
    if (deletionRequested) {
        val deletion: RecordingDeletionViewModel = viewModel(
            key = "recording-deletion:${route.recordingId}",
            factory = graph.recordingDeletionFactory(route.recordingId),
        )
        val deletionState by deletion.uiState.collectAsState()
        LaunchedEffect(deletion) {
            deletion.effects.collect { effect ->
                when (effect) {
                    is RecordingDeletionEffect.ReturnToConfirmation -> deletionRequested = false
                    RecordingDeletionEffect.OpenHome -> onHome()
                }
            }
        }
        RecordingDeletionDialog(
            deletionState,
            durationSeconds = state.recording?.durationSeconds,
            onAction = deletion::onAction,
        )
    }
}

@Composable
private fun RecordingTranscriptionRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.RecordingTranscription,
    onResult: (String) -> Unit,
    onFailure: () -> Unit,
    onHome: () -> Unit,
) {
    val transcription: RecordingTranscriptionViewModel = viewModel(
        key = "recording-transcription:${route.recordingId}:${route.entryId}",
        factory = graph.recordingTranscriptionFactory(route),
    )
    val state by transcription.uiState.collectAsState()
    LaunchedEffect(transcription) {
        transcription.effects.collect { effect ->
            when (effect) {
                is RecordingTranscriptionEffect.OpenResult -> onResult(effect.transcriptId)
                is RecordingTranscriptionEffect.OpenFailure -> onFailure()
                RecordingTranscriptionEffect.OpenHome -> onHome()
            }
        }
    }
    RecordingTranscriptionScreen(state, transcription::onAction)
}

@Composable
private fun TranscriptResultRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.TranscriptResult,
    onReturn: () -> Unit,
    onConfirmed: (String) -> Unit,
) {
    TranscriptResultEntry(route.transcriptId, graph.transcriptResultFactory(route), onReturn, onConfirmed)
}

@Composable
internal fun TranscriptResultEntry(
    transcriptId: String,
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    onReturn: (() -> Unit)? = null,
    onConfirmed: (String) -> Unit,
) {
    val transcript: TranscriptResultViewModel = viewModel(
        key = "transcript-result:$transcriptId",
        factory = factory,
    )
    val state by transcript.uiState.collectAsState()
    BackHandler(enabled = onReturn != null) { if (transcript.canLeave()) onReturn?.invoke() }
    RecordingPlaybackLifecycle(transcript, transcript::stopPlayback)
    LaunchedEffect(transcript) {
        transcript.onEntered()
        transcript.effects.collect { effect ->
            if (effect is TranscriptResultEffect.OpenSafetyCheck) {
                onConfirmed(effect.confirmedText)
            }
        }
    }
    TranscriptResultScreen(state, transcript::onAction, onReturn = onReturn?.let { leave ->
        { if (transcript.canLeave()) leave() }
    })
}

@Composable
internal fun RecordingPlaybackLifecycle(key: Any, stopPlayback: () -> Unit) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val policy = LocalConnectionPlaybackPolicy.current
    val stop by rememberUpdatedState(stopPlayback)
    LaunchedEffect(key, policy.interrupted) { if (policy.interrupted) stop() }
    DisposableEffect(key, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
}

@Composable
private fun TranscriptionFailureRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.TranscriptionFailure,
    onRetry: () -> Unit,
    onReturn: () -> Unit,
) {
    val failure: TranscriptionFailureViewModel = viewModel(
        key = "transcription-failure:${route.recordingId}",
        factory = graph.transcriptionFailureFactory(route.recordingId, route.durationSeconds),
    )
    val state by failure.uiState.collectAsState()
    LaunchedEffect(failure) {
        failure.effects.collect { effect ->
            when (effect) {
                is TranscriptionFailureEffect.OpenTranscription -> onRetry()
                is TranscriptionFailureEffect.OpenRecordingConfirmation -> onReturn()
            }
        }
    }
    TranscriptionFailureScreen(state, failure::onAction)
}

@Composable
private fun MicrophonePermissionRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.MicrophonePermission,
    onRecording: () -> Unit,
    onReturn: () -> Unit,
    onHome: () -> Unit,
) {
    val context = LocalContext.current
    val permissionExplanation: MicrophonePermissionViewModel = viewModel(
        key = "microphone-permission:${route.canRequestDirectly}",
        factory = graph.microphonePermissionFactory(route.canRequestDirectly),
    )
    val state by permissionExplanation.uiState.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionExplanation.onAction(MicrophonePermissionAction.PermissionResult(it))
    }
    LaunchedEffect(permissionExplanation) {
        permissionExplanation.effects.collect { effect ->
            when (effect) {
                MicrophonePermissionEffect.RequestMicrophonePermission -> permission.launch(Manifest.permission.RECORD_AUDIO)
                MicrophonePermissionEffect.OpenAppSettings -> context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
                MicrophonePermissionEffect.OpenRecording -> onRecording()
                MicrophonePermissionEffect.OpenHome -> onHome()
            }
        }
    }
    MicrophonePermissionScreen(state, permissionExplanation::onAction, onBack = onReturn)
}

@Composable
private fun SceneHomeRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.SceneHome,
    onType: (SceneStart) -> Unit,
    onDirectDescription: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scene: SceneHomeViewModel = viewModel(
        key = "scene-home:${route.preservedStart.hashCode()}",
        factory = graph.sceneHomeFactory(route.preservedStart),
    )
    val state by scene.uiState.collectAsState()
    LaunchedEffect(scene) {
        scene.effects.collect { effect ->
            when (effect) {
                is SceneHomeEffect.OpenTypeSelection -> onType(effect.start)
                is SceneHomeEffect.OpenDirectDescription -> onDirectDescription(effect.initialText)
            }
        }
    }
    SceneHomeScreen(state, scene::onAction, onBack)
}

@Composable
private fun SceneDirectDescriptionRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.SceneDirectDescription,
    onType: (String) -> Unit,
    onHome: () -> Unit,
) {
    val description: SceneDirectDescriptionViewModel = viewModel(
        key = "scene-description:${route.entryId}",
        factory = graph.sceneDirectDescriptionFactory(route.text),
    )
    val state by description.uiState.collectAsState()
    LaunchedEffect(description) {
        description.effects.collect { effect ->
            when (effect) {
                is SceneDirectDescriptionEffect.OpenTypeSelection -> onType(effect.description)
                SceneDirectDescriptionEffect.OpenSceneHome -> onHome()
            }
        }
    }
    SceneDirectDescriptionScreen(state, description::onAction)
}

@Composable
private fun SceneTypeRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.SceneTypeSelection,
    onQuestions: (SceneStart, SceneContentType) -> Unit,
    onHome: (SceneStart) -> Unit,
) {
    val type: SceneTypeViewModel = viewModel(
        key = "scene-type:${route.start.hashCode()}",
        factory = graph.sceneTypeFactory(route.start),
    )
    val state by type.uiState.collectAsState()
    LaunchedEffect(type) {
        type.effects.collect { effect ->
            when (effect) {
                is SceneTypeEffect.OpenQuestions -> onQuestions(effect.start, effect.type)
                is SceneTypeEffect.OpenSceneHome -> onHome(effect.preservedStart)
            }
        }
    }
    SceneTypeScreen(state, type::onAction)
}

@Composable
private fun SceneQuestionsRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.SceneQuestions,
    onGeneration: (SceneQuestionSession) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    homeRequestId: String? = null,
    onHomeRequestHandled: () -> Unit = {},
) {
    val questions: SceneQuestionsViewModel = viewModel(
        key = "scene-questions:${route.operationId}",
        factory = graph.sceneQuestionsFactory(route),
    )
    val state by questions.uiState.collectAsState()
    LaunchedEffect(homeRequestId) {
        if (homeRequestId != null) {
            questions.onAction(com.reallanguage.app.feature.scene.presentation.SceneQuestionsAction.ExitRequested)
            onHomeRequestHandled()
        }
    }
    var abandonSession by remember(route.operationId) { mutableStateOf<SceneQuestionSession?>(null) }
    LaunchedEffect(questions) {
        questions.effects.collect { effect ->
            when (effect) {
                is SceneQuestionsEffect.OpenGeneration -> onGeneration(effect.session)
                SceneQuestionsEffect.ReturnToPrevious -> onBack()
                is SceneQuestionsEffect.OpenAbandonConfirmation -> abandonSession = effect.session
            }
        }
    }
    SceneQuestionsScreen(state, questions::onAction)
    abandonSession?.let { session ->
        val abandon: SceneAbandonViewModel = viewModel(
            key = "scene-abandon:${session.operationId}",
            factory = graph.sceneAbandonFactory(session),
        )
        val abandonState by abandon.uiState.collectAsState()
        LaunchedEffect(abandon) {
            abandon.effects.collect { effect ->
                when (effect) {
                    is SceneAbandonEffect.ReturnToQuestions -> abandonSession = null
                    SceneAbandonEffect.OpenHome -> onHome()
                }
            }
        }
        SceneAbandonDialog(abandonState, abandon::onAction)
    }
}

@Composable
private fun SceneGenerationRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.SceneGeneration,
    onReview: (SceneQuestionSession, SceneGeneratedDraft) -> Unit,
    onQuestions: (SceneQuestionSession) -> Unit,
) {
    val generation: SceneGenerationViewModel = viewModel(
        key = "scene-generation:${route.entryId}",
        factory = graph.sceneGenerationFactory(route),
    )
    val state by generation.uiState.collectAsState()
    LaunchedEffect(generation) {
        if (route.retryOnEntry && generation.uiState.value.error != null) {
            generation.onAction(com.reallanguage.app.feature.scene.presentation.SceneGenerationAction.RetryRequested)
        }
        generation.effects.collect { effect ->
            when (effect) {
                is SceneGenerationEffect.OpenChineseReview -> onReview(effect.session, effect.draft)
                is SceneGenerationEffect.ReturnToQuestions -> onQuestions(effect.session)
            }
        }
    }
    SceneGenerationScreen(state, generation::onAction)
}

@Composable
private fun SceneChineseReviewRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.SceneChineseReview,
    onSafety: (ConfirmedSource, com.reallanguage.app.feature.scene.domain.SceneGeneratedDraft) -> Unit,
    onQuestions: (SceneQuestionSession) -> Unit,
    onGeneration: (SceneQuestionSession, List<com.reallanguage.app.feature.scene.domain.SceneDraftUnit>) -> Unit,
    retainedOwner: androidx.lifecycle.ViewModelStoreOwner? = null,
) {
    SceneChineseReviewEntry(graph.sceneReviewFactory(route), route, onSafety, onQuestions, onGeneration, retainedOwner)
}

private data class SceneGenerationContinuation(
    val generation: HomeDiaryRoute.SceneGeneration,
    val delivered: HomeDiaryRoute.SceneChineseReview? = null,
)

/** Compare user-confirmed upstream identity, not a newly generated interpretation of it.
 * The retained route still owns the full original session/facts/previousUnits sent to HTTP. */
private fun SceneQuestionSession.hasSameGenerationInput(other: SceneQuestionSession): Boolean =
    operationId == other.operationId && start == other.start && contentType == other.contentType &&
        answers.map { it.copy(answerZh = it.answerZh.trim()) } == other.answers.map { it.copy(answerZh = it.answerZh.trim()) }

@Composable
private fun IncompleteLearningRoute(
    graph: ProductionAppGraph,
    entryId: String,
    onResume: (String, String, String?) -> Unit,
    onHome: () -> Unit,
) {
    IncompleteLearningEntry(graph.incompleteLearningFactory(), entryId, onResume, onHome)
}

@Composable
internal fun IncompleteLearningEntry(
    factory: ViewModelProvider.Factory,
    entryId: String,
    onResume: (String, String, String?) -> Unit,
    onHome: () -> Unit,
) {
    val owner = rememberEntryViewModelStoreOwner("incomplete-learning-$entryId")
    val incomplete: IncompleteLearningViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "incomplete-learning-$entryId",
        factory = factory,
    )
    val state by incomplete.uiState.collectAsState()
    LaunchedEffect(incomplete) {
        incomplete.effects.collect { effect ->
            when (effect) {
                is IncompleteLearningEffect.Resume -> onResume(
                    effect.learningId, effect.savedStepId, effect.checkpointStepId,
                )
                IncompleteLearningEffect.OpenHome -> onHome()
            }
        }
    }
    IncompleteLearningScreen(state, incomplete::onAction)
}

@Composable
private fun MessagesRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.Messages,
    onTarget: (com.reallanguage.app.feature.home.domain.AppMessageTarget) -> Unit,
    onHome: () -> Unit,
) {
    MessagesEntry(graph.messagesFactory(), route.entryId, onTarget, onHome)
}

@Composable
internal fun MessagesEntry(
    factory: ViewModelProvider.Factory,
    entryId: String,
    onTarget: (com.reallanguage.app.feature.home.domain.AppMessageTarget) -> Unit,
    onHome: () -> Unit,
) {
    val owner = rememberEntryViewModelStoreOwner("messages-$entryId")
    val currentTarget by rememberUpdatedState(onTarget)
    val currentHome by rememberUpdatedState(onHome)
    val messages: MessagesViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "messages-$entryId",
        factory = factory,
    )
    val state by messages.uiState.collectAsState()
    LaunchedEffect(messages) {
        messages.effects.collect { effect ->
            when (effect) {
                is MessagesEffect.OpenTarget -> currentTarget(effect.target)
                is MessagesEffect.OpenLoginForTarget -> currentHome()
                MessagesEffect.OpenHome -> currentHome()
            }
        }
    }
    MessagesScreen(state, messages::onAction)
}

@Composable
private fun ManualReviewResultRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ManualReviewResult,
    onApproved: (com.reallanguage.app.feature.safety.domain.ReviewedSafetySource) -> Unit,
    onNotApproved: (String, String) -> Unit,
    onLoginRequired: (String) -> Unit,
    onMessages: () -> Unit,
) {
    ManualReviewResultEntry(graph.manualReviewResultFactory(route.reviewRequestId), route.entryId,
        onApproved, onNotApproved, onLoginRequired, onMessages)
}

@Composable
internal fun ManualReviewResultEntry(
    factory: ViewModelProvider.Factory,
    entryId: String,
    onApproved: (com.reallanguage.app.feature.safety.domain.ReviewedSafetySource) -> Unit,
    onNotApproved: (String, String) -> Unit,
    onLoginRequired: (String) -> Unit,
    onMessages: () -> Unit,
) {
    val owner = rememberEntryViewModelStoreOwner("manual-review-result:$entryId")
    val result: ManualReviewResultViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "manual-review-result-$entryId",
        factory = factory,
    )
    val state by result.uiState.collectAsState()
    LaunchedEffect(result) {
        result.effects.collect { effect ->
            when (effect) {
                is ManualReviewResultEffect.OpenSemanticSplit -> onApproved(effect.source)
                is ManualReviewResultEffect.OpenSafetyHandling -> onNotApproved(effect.safetyCaseId, effect.text)
                is ManualReviewResultEffect.OpenLoginPreservingTarget -> onLoginRequired(effect.reviewRequestId)
                ManualReviewResultEffect.OpenMessages -> onMessages()
            }
        }
    }
    BackHandler { result.onAction(com.reallanguage.app.feature.safety.presentation.ManualReviewResultAction.ReturnMessagesRequested) }
    com.reallanguage.app.feature.safety.presentation.ManualReviewResultScreen(state, result::onAction)
}

@Composable
private fun AccountRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.Account,
    onLocalRecordings: () -> Unit,
    onBilling: () -> Unit,
    onLoggedOut: () -> Unit,
    onHome: () -> Unit,
) {
    var deletionRequested by remember(route.entryId) { mutableStateOf(false) }
    val account: AccountViewModel = viewModel(key = "account-${route.entryId}", factory = graph.accountFactory(route.entryId))
    val state by account.uiState.collectAsState()
    LaunchedEffect(account) {
        account.effects.collect { effect ->
            when (effect) {
                AccountEffect.OpenLocalRecordings -> onLocalRecordings()
                AccountEffect.OpenLogin -> onLoggedOut()
                AccountEffect.OpenHome -> onHome()
                AccountEffect.OpenBilling -> onBilling()
                AccountEffect.OpenDeletionConfirmation -> deletionRequested = true
            }
        }
    }
    AccountScreen(state, account::onAction)
    if (deletionRequested) {
        val commandId = remember(route.entryId) { UUID.nameUUIDFromBytes("delete-account:${route.entryId}".toByteArray()).toString() }
        val deletion: AccountDeletionViewModel = viewModel(key = "account-deletion-$commandId", factory = graph.accountDeletionFactory(commandId))
        val deletionState by deletion.uiState.collectAsState()
        LaunchedEffect(deletion) {
            deletion.effects.collect { effect ->
                when (effect) {
                    AccountDeletionEffect.Close -> deletionRequested = false
                    AccountDeletionEffect.OpenLogin -> onLoggedOut()
                }
            }
        }
        AccountDeletionDialog(deletionState, deletion::onAction)
    }
}

@Composable
private fun SubsequentSettingsRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.Settings,
    onEffectScope: (InitialSettingsDraft, InitialSettingsDraft,
        com.reallanguage.app.feature.settings.domain.ReminderSettingsDraft?,
        com.reallanguage.app.feature.settings.domain.ReminderSettingsDraft?) -> Unit,
    onHome: () -> Unit,
    homeRequestId: String? = null,
    onHomeRequestHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings: SubsequentSettingsViewModel = viewModel(
        key = "settings-${route.entryId}",
        factory = graph.subsequentSettingsFactory(route),
    )
    val state by settings.uiState.collectAsState()
    val owner = remember(route.entryId) { graph.reminderOwner() }
    var awaitingReminderPermission by remember(route.entryId) { mutableStateOf(false) }
    var permissionEntryActive by remember(route.entryId) { mutableStateOf(true) }
    DisposableEffect(route.entryId) {
        onDispose { permissionEntryActive = false; awaitingReminderPermission = false }
    }
    val reminderPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (permissionEntryActive && awaitingReminderPermission && owner == graph.reminderOwner()) {
            awaitingReminderPermission = false
            if (graph.reminderPermission() == ReminderPermission.GRANTED) settings.onAction(
                com.reallanguage.app.feature.settings.presentation.SubsequentSettingsAction.ReminderEnabledChanged(true))
        }
    }
    LaunchedEffect(homeRequestId, state.saving, state.loading) {
        if (homeRequestId != null && !state.saving && !state.loading) {
            settings.onAction(com.reallanguage.app.feature.settings.presentation.SubsequentSettingsAction.ReturnRequested)
            onHomeRequestHandled()
        }
    }
    LaunchedEffect(settings) {
        settings.effects.collect { effect ->
            when (effect) {
                SubsequentSettingsEffect.OpenHome -> onHome()
                is SubsequentSettingsEffect.OpenEffectScope ->
                    onEffectScope(effect.original, effect.proposed, effect.originalReminder, effect.proposedReminder)
            }
        }
    }
    BackHandler { settings.onAction(com.reallanguage.app.feature.settings.presentation.SubsequentSettingsAction.ReturnRequested) }
    ReminderSchedulingEntry(owner, graph::reminderOwner, graph::reconcileReminder) { schedulingMessage ->
    SubsequentSettingsScreen(state, reminderSchedulingMessage = schedulingMessage, onAction = { action ->
        if (action is com.reallanguage.app.feature.settings.presentation.SubsequentSettingsAction.ReminderEnabledChanged && !action.enabled)
            awaitingReminderPermission = false
        settings.onAction(action)
    }, onReminderEnableRequested = {
        if (!state.saving && !state.loading && !awaitingReminderPermission) {
            if (graph.reminderPermission() == ReminderPermission.GRANTED) settings.onAction(
                com.reallanguage.app.feature.settings.presentation.SubsequentSettingsAction.ReminderEnabledChanged(true))
            else if (android.os.Build.VERSION.SDK_INT >= 33) {
                awaitingReminderPermission = true
                reminderPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else android.widget.Toast.makeText(context, "请在系统设置中允许 RealLanguage 发送通知。", android.widget.Toast.LENGTH_SHORT).show()
        }
    })
    }
}

@Composable
private fun SettingsEffectRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.SettingsEffect,
    onReturn: () -> Unit,
    onHome: () -> Unit,
    homeRequestId: String? = null,
    onHomeRequestHandled: () -> Unit = {},
) {
    val settings: SettingsEffectScopeViewModel = viewModel(
        key = "settings-effect-${route.entryId}",
        factory = graph.settingsEffectFactory(route),
    )
    val state by settings.uiState.collectAsState()
    BackHandler { settings.onAction(com.reallanguage.app.feature.settings.presentation.SettingsEffectScopeAction.CancelRequested) }
    LaunchedEffect(homeRequestId, state.applying) {
        if (homeRequestId != null && !state.applying) {
            settings.onAction(com.reallanguage.app.feature.settings.presentation.SettingsEffectScopeAction.CancelRequested)
            onHomeRequestHandled()
        }
    }
    LaunchedEffect(settings) {
        settings.effects.collect { effect ->
            when (effect) {
                SettingsEffectScopeEffect.ReturnToSettings -> onReturn()
                SettingsEffectScopeEffect.OpenHome -> onHome()
                is SettingsEffectScopeEffect.OpenReprocessing -> onHome()
            }
        }
    }
    SettingsEffectScopeScreen(state, settings::onAction)
}

@Composable
private fun BillingRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.Billing,
    onAccount: () -> Unit,
    onRecharge: () -> Unit,
    onRefund: () -> Unit,
) {
    BillingEntry(graph.billingFactory(), route.entryId, onAccount, onRecharge, onRefund)
}

@Composable
internal fun BillingEntry(
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    entryId: String,
    onAccount: () -> Unit,
    onRecharge: () -> Unit,
    onRefund: () -> Unit,
) {
    val owner = rememberEntryViewModelStoreOwner("billing-$entryId")
    val currentAccount by rememberUpdatedState(onAccount)
    val currentRecharge by rememberUpdatedState(onRecharge)
    val currentRefund by rememberUpdatedState(onRefund)
    val billing: AiBillingViewModel = viewModel(viewModelStoreOwner = owner, key = "billing-$entryId", factory = factory)
    val state by billing.uiState.collectAsState()
    BackHandler { billing.onAction(com.reallanguage.app.feature.billing.presentation.AiBillingAction.ReturnAccountRequested) }
    LaunchedEffect(billing) {
        billing.effects.collect { effect ->
            when (effect) {
                AiBillingEffect.ReturnAccount -> currentAccount()
                AiBillingEffect.OpenRecharge -> currentRecharge()
                AiBillingEffect.OpenRefund -> currentRefund()
            }
        }
    }
    AiBillingScreen(state, billing::onAction)
}

@Composable
private fun RechargeRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.Recharge,
    onReturn: () -> Unit,
) {
    val recharge: RechargeViewModel = viewModel(key = "recharge-${route.entryId}", factory = graph.rechargeFactory(route))
    val state by recharge.uiState.collectAsState()
    val context = LocalContext.current
    DisposableEffect(context, recharge) {
        val owner = context as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                recharge.onAction(com.reallanguage.app.feature.billing.presentation.RechargeAction.RefreshOrderRequested)
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(recharge) {
        recharge.effects.collect { effect ->
            when (effect) {
                is RechargeEffect.OpenPayment -> runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(effect.paymentLaunch)))
                }
                is RechargeEffect.ReturnToOrigin -> onReturn()
            }
        }
    }
    RechargeScreen(state, recharge::onAction)
}

@Composable
private fun RefundRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.Refund,
    onBilling: () -> Unit,
) {
    val refund: RefundViewModel = viewModel(key = "refund-${route.entryId}", factory = graph.refundFactory(route))
    val state by refund.uiState.collectAsState()
    LaunchedEffect(refund) {
        refund.effects.collect { effect ->
            when (effect) {
                RefundEffect.ReturnBilling -> onBilling()
            }
        }
    }
    RefundScreen(state, refund::onAction)
}

@Composable
private fun InsufficientBalanceRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.InsufficientBalance,
    onRecharge: () -> Unit,
    onExit: () -> Unit,
) {
    val balance: InsufficientBalanceViewModel = viewModel(
        key = "insufficient-balance-${route.entryId}",
        factory = graph.insufficientBalanceFactory(route),
    )
    val state by balance.uiState.collectAsState()
    LaunchedEffect(balance) {
        balance.effects.collect { effect ->
            when (effect) {
                is InsufficientBalanceEffect.OpenRecharge -> onRecharge()
                is InsufficientBalanceEffect.ReturnAfterExit -> onExit()
            }
        }
    }
    InsufficientBalanceScreen(state, balance::onAction)
}

@Composable
private fun LocalRecordingsRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.LocalRecordings,
    onRecording: (String) -> Unit,
    onAccount: () -> Unit,
) {
    val recordings: LocalRecordingsViewModel = viewModel(key = "local-recordings-${route.entryId}", factory = graph.localRecordingsFactory())
    val state by recordings.uiState.collectAsState()
    LaunchedEffect(recordings) {
        recordings.effects.collect { effect ->
            when (effect) {
                is LocalRecordingsEffect.OpenRecording -> onRecording(effect.recordingId)
                LocalRecordingsEffect.ReturnAccount -> onAccount()
            }
        }
    }
    LocalRecordingsScreen(state, recordings::onAction)
}

@Composable
private fun VocabularyBookRoute(
    graph: ProductionAppGraph,
    onHome: () -> Unit,
) {
    val context = LocalContext.current
    val speaker = rememberConnectionAwareSentenceSpeaker()
    DisposableEffect(speaker) { onDispose { speaker.close() } }
    VocabularyBookEntry(graph.vocabularyBookFactory(), onHome, { speaker.play(it) }, speaker::stop)
}

@Composable
internal fun VocabularyBookEntry(
    factory: ViewModelProvider.Factory,
    onHome: () -> Unit,
    onPronunciation: suspend (String) -> Boolean,
    onStopPronunciation: () -> Unit = {},
) {
    val owner = rememberEntryViewModelStoreOwner("vocabulary-book")
    val currentHome by rememberUpdatedState(onHome)
    val currentPronunciation by rememberUpdatedState(onPronunciation)
    val currentStopPronunciation by rememberUpdatedState(onStopPronunciation)
    val vocabulary: VocabularyBookViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "vocabulary-book",
        factory = factory,
    )
    val state by vocabulary.uiState.collectAsState()
    var playbackJob by remember(vocabulary) { mutableStateOf<Job?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(vocabulary, lifecycle) {
        fun stop() {
            playbackJob?.cancel()
            playbackJob = null
            currentStopPronunciation()
            vocabulary.onAction(VocabularyBookAction.PronunciationInterrupted)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    BackHandler { vocabulary.onAction(VocabularyBookAction.ReturnHomeRequested) }
    LaunchedEffect(vocabulary) {
        vocabulary.effects.collect { effect ->
            when (effect) {
                VocabularyBookEffect.ReturnHome -> currentHome()
                is VocabularyBookEffect.PlayPronunciation -> {
                    playbackJob?.cancel()
                    // Keep collecting Stop/Return while the real engine awaits readiness or onDone.
                    playbackJob = launch {
                        val succeeded = try { currentPronunciation(effect.text) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { false }
                        vocabulary.onAction(VocabularyBookAction.PronunciationFinished(effect.requestId, succeeded))
                    }
                }
                VocabularyBookEffect.StopPronunciation -> {
                    playbackJob?.cancel()
                    playbackJob = null
                    currentStopPronunciation()
                }
            }
        }
    }
    VocabularyBookScreen(state, vocabulary::onAction)
}

@Composable
private fun PatternBookRoute(
    graph: ProductionAppGraph,
    onHome: () -> Unit,
) {
    val context = LocalContext.current
    val speaker = rememberConnectionAwareSentenceSpeaker()
    DisposableEffect(speaker) { onDispose { speaker.close() } }
    PatternBookEntry(graph.patternBookFactory(), graph::patternRemovalFactory, onHome, { speaker.play(it) }, speaker::stop)
}

@Composable
internal fun PatternBookEntry(
    factory: ViewModelProvider.Factory,
    removalFactory: (String, String) -> ViewModelProvider.Factory,
    onHome: () -> Unit,
    onPronunciation: suspend (String) -> Boolean,
    onStopPronunciation: () -> Unit = {},
) {
    val owner = rememberEntryViewModelStoreOwner("pattern-book")
    val currentHome by rememberUpdatedState(onHome)
    val currentPronunciation by rememberUpdatedState(onPronunciation)
    val currentStopPronunciation by rememberUpdatedState(onStopPronunciation)
    val patterns: PatternBookViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "pattern-book",
        factory = factory,
    )
    val state by patterns.uiState.collectAsState()
    var removalTarget by remember { mutableStateOf<String?>(null) }
    var playbackJob by remember(patterns) { mutableStateOf<Job?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(patterns, lifecycle) {
        fun stop() {
            playbackJob?.cancel()
            playbackJob = null
            currentStopPronunciation()
            patterns.onAction(PatternBookAction.PronunciationInterrupted)
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    BackHandler(enabled = removalTarget == null) { patterns.onAction(PatternBookAction.ReturnHomeRequested) }
    LaunchedEffect(patterns) {
        patterns.effects.collect { effect ->
            when (effect) {
                PatternBookEffect.ReturnHome -> currentHome()
                is PatternBookEffect.OpenRemovalConfirmation -> removalTarget = effect.patternId
                is PatternBookEffect.PlayExample -> {
                    playbackJob?.cancel()
                    playbackJob = launch {
                        val succeeded = try { currentPronunciation(effect.text) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { false }
                        patterns.onAction(PatternBookAction.PronunciationFinished(effect.requestId, succeeded))
                    }
                }
                PatternBookEffect.StopExample -> {
                    playbackJob?.cancel()
                    playbackJob = null
                    currentStopPronunciation()
                }
            }
        }
    }
    PatternBookScreen(state, patterns::onAction)
    removalTarget?.let { patternId ->
        val commandId = remember(patternId) { UUID.randomUUID().toString() }
        val removalOwner = rememberEntryViewModelStoreOwner("pattern-removal:$patternId")
        val removal: PatternRemovalViewModel = viewModel(
            viewModelStoreOwner = removalOwner,
            key = "pattern-removal:$patternId",
            factory = removalFactory(patternId, commandId),
        )
        val removalState by removal.uiState.collectAsState()
        LaunchedEffect(removal) {
            removal.effects.collect { effect ->
                when (effect) {
                    PatternRemovalEffect.ReturnToDetail -> removalTarget = null
                    is PatternRemovalEffect.PatternRemoved -> {
                        removalTarget = null
                        patterns.onAction(PatternBookAction.CollapseRequested)
                        patterns.onAction(PatternBookAction.RetryRequested)
                    }
                }
            }
        }
        PatternRemovalDialog(
            state = removalState,
            pattern = state.patterns.firstOrNull { it.patternId == patternId }?.structureEn.orEmpty(),
            onAction = removal::onAction,
        )
    }
}

@Composable
private fun TodayReviewRoute(
    graph: ProductionAppGraph,
    onChapter: (String) -> Unit,
    onHome: () -> Unit,
) {
    TodayReviewEntry(graph.todayReviewFactory(), onChapter, onHome)
}

@Composable
internal fun TodayReviewEntry(
    factory: ViewModelProvider.Factory,
    onChapter: (String) -> Unit,
    onHome: () -> Unit,
) {
    val owner = rememberEntryViewModelStoreOwner("today-review")
    val currentChapter by rememberUpdatedState(onChapter)
    val currentHome by rememberUpdatedState(onHome)
    val review: TodayReviewViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "today-review",
        factory = factory,
    )
    val state by review.uiState.collectAsState()
    LaunchedEffect(review) {
        review.effects.collect { effect ->
            when (effect) {
                is TodayReviewEffect.OpenReviewChapter -> currentChapter(effect.reviewChapterId)
                TodayReviewEffect.ReturnHome -> currentHome()
            }
        }
    }
    TodayReviewScreen(state, review::onAction)
}

@Composable
private fun ReviewChapterRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ReviewChapter,
    onAbility: (ReviewChapterSnapshot, ReviewAbility) -> Unit,
    onNext: (String) -> Unit,
    onResult: (String) -> Unit,
    completedModule: ReviewAbilityResultDraft?,
    onModuleConsumed: () -> Unit,
    onExit: () -> Unit,
) {
    val lease = graph.currentPatternReviewEntry()
    val currentAbility by rememberUpdatedState(onAbility)
    val currentNext by rememberUpdatedState(onNext)
    val currentResult by rememberUpdatedState(onResult)
    val currentExit by rememberUpdatedState(onExit)
    val currentConsumed by rememberUpdatedState(onModuleConsumed)
    val commandId = remember(route.reviewChapterId) {
        UUID.nameUUIDFromBytes("review-chapter:${route.reviewChapterId}".toByteArray()).toString()
    }
    val review: ReviewChapterViewModel = viewModel(
        key = "review-chapter:${route.reviewChapterId}:${route.entryId}",
        factory = graph.reviewChapterFactory(route.reviewChapterId, commandId, lease),
    )
    val state by review.uiState.collectAsState()
    LaunchedEffect(completedModule) {
        completedModule?.let { result ->
            if (lease != null && graph.currentPatternReviewEntry() === lease) {
                review.onAction(ReviewChapterAction.ModuleCompleted(result.abilityId, result.evidenceId, result.passed, result.qualification, result.patternReceipt))
                currentConsumed()
            }
        }
    }
    LaunchedEffect(review) {
        review.effects.collect { effect ->
            if (lease == null || graph.currentPatternReviewEntry() !== lease) return@collect
            when (effect) {
                is ReviewChapterEffect.OpenAbilityModule -> {
                    val chapter = review.uiState.value.chapter ?: return@collect
                    val ability = review.uiState.value.currentAbility ?: return@collect
                    if (ability.abilityId == effect.abilityId && ability.module == effect.module) {
                        currentAbility(chapter, ability)
                    }
                }
                is ReviewChapterEffect.OpenNextReviewChapter -> currentNext(effect.reviewChapterId)
                ReviewChapterEffect.OpenReviewResult -> currentResult("")
                is ReviewChapterEffect.OpenReviewResultForSession -> currentResult(effect.reviewSessionId)
                ReviewChapterEffect.OpenExitConfirmation -> currentExit()
            }
        }
    }
    ReviewChapterScreen(state, review::onAction)
}

@Composable
private fun ReviewExitRoute(
    graph: ProductionAppGraph,
    reviewChapterId: String,
    onContinue: () -> Unit,
    onHome: () -> Unit,
) {
    ReviewExitEntry(reviewChapterId, graph.reviewExitFactory(reviewChapterId), onContinue, onHome)
}

@Composable
internal fun ReviewExitEntry(
    reviewChapterId: String,
    factory: ViewModelProvider.Factory,
    onContinue: () -> Unit,
    onHome: () -> Unit,
) {
    // An exit dialog is a new command session on every presentation, even for the same chapter.
    val owner = rememberEntryViewModelStoreOwner(reviewChapterId)
    val currentContinue by rememberUpdatedState(onContinue)
    val currentHome by rememberUpdatedState(onHome)
    val exit: ReviewExitViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "review-exit:$reviewChapterId",
        factory = factory,
    )
    val state by exit.uiState.collectAsState()
    LaunchedEffect(exit) {
        exit.effects.collect { effect ->
            when (effect) {
                ReviewExitEffect.ContinueReview -> currentContinue()
                ReviewExitEffect.ReturnHome -> currentHome()
            }
        }
    }
    ReviewExitDialog(state, exit::onAction)
}

@Composable
private fun ReviewResultRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ReviewResult,
    onHome: () -> Unit,
) {
    val commandId = remember(route.reviewSessionId) {
        UUID.nameUUIDFromBytes("review-settlement:${route.reviewSessionId}".toByteArray()).toString()
    }
    val result: ReviewResultViewModel = viewModel(
        key = "review-result:${route.reviewSessionId}",
        factory = graph.reviewResultFactory(route.reviewSessionId, commandId),
    )
    val state by result.uiState.collectAsState()
    LaunchedEffect(result) {
        result.effects.collect { effect ->
            if (effect == ReviewResultEffect.ReturnHome) onHome()
        }
    }
    ReviewResultScreen(state, result::onAction)
}

@Composable
private fun ReviewAbilityExecutionRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ReviewAbilityExecution,
    onComplete: (ReviewAbilityResultDraft) -> Unit,
    onBack: () -> Unit,
) {
    LearningContentLoader(
        requestKey = route,
        load = {
            when (val loaded = graph.loadReviewFixedChapter(route.chapter.originalChapterId)) {
                is FixedChapterLoadResult.Loaded -> loaded.chapter.takeIf { chapter ->
                    chapter.sentences.any { it.sentenceId == route.ability.sentenceId && it.isFocus }
                }
                FixedChapterLoadResult.Failed -> null
            }
        },
        onReturn = onBack,
    ) { chapter ->
        ReviewAbilityReadyRoute(graph, route, chapter, onComplete, onBack)
    }
}

@Composable
private fun ReviewAbilityReadyRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ReviewAbilityExecution,
    chapter: FixedLearningChapter,
    onComplete: (ReviewAbilityResultDraft) -> Unit,
    onBack: () -> Unit,
) {
    val focused = remember(chapter, route.ability.sentenceId) {
        chapter.copy(
            sentences = chapter.sentences.map {
                it.copy(isFocus = it.sentenceId == route.ability.sentenceId)
            },
        )
    }
    when (route.ability.module) {
        ReviewAbilityModule.COMPLETE_WRITING,
        ReviewAbilityModule.WORD_MEANING,
        ReviewAbilityModule.PATTERN_WRITING,
        -> ReviewWritingAbilityRoute(graph, route, focused, onComplete, onBack)
        ReviewAbilityModule.FULL_SENTENCE_SPEAKING ->
            ReviewSpeakingAbilityRoute(graph, route, focused, onComplete, onBack)
        ReviewAbilityModule.SCENE_RESPONSE ->
            ReviewSceneAbilityRoute(graph, route, focused, onComplete, onBack)
        ReviewAbilityModule.PATTERN_SPEAKING ->
            ReviewPatternIndependentAbilityRoute(graph, route, focused, onComplete, onBack)
    }
}

@Composable
private fun ReviewPatternIndependentAbilityRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ReviewAbilityExecution,
    chapter: FixedLearningChapter,
    onComplete: (ReviewAbilityResultDraft) -> Unit,
    onBack: () -> Unit,
) {
    LearningContentLoader(
        requestKey = route,
        load = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            graph.reviewPattern(chapter.chapterId, route.ability.sentenceId, route.ability.targetId)
        } },
        onReturn = onBack,
    ) { pattern ->
        ReviewPatternIndependentReadyRoute(graph, route, chapter, pattern, onComplete, onBack)
    }
}

@Composable
private fun ReviewPatternIndependentReadyRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ReviewAbilityExecution,
    chapter: FixedLearningChapter,
    pattern: com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern,
    onComplete: (ReviewAbilityResultDraft) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lease = graph.currentPatternReviewEntry()
    val currentComplete by rememberUpdatedState(onComplete)
    val currentBack by rememberUpdatedState(onBack)
    val repository = remember(lease, route.ability.abilityId) { graph.reviewPatternIndependentSession(chapter, route, lease) }
    val review: PatternIndependentViewModel = viewModel(
        key = "review-pattern-independent:${route.entryId}:${route.ability.abilityId}",
        factory = viewModelFactory { PatternIndependentViewModel(pattern, repository) },
    )
    val state by review.uiState.collectAsState()
    val capturePolicy = LocalConnectionPlaybackPolicy.current
    var pendingCapturePermit by remember(repository) { mutableStateOf<(() -> Boolean)?>(null) }
    LaunchedEffect(review, capturePolicy.interrupted) {
        if (capturePolicy.interrupted) {
            pendingCapturePermit = null
            review.interruptUnsubmittedCapture()
        }
    }
    DisposableEffect(repository) { onDispose { pendingCapturePermit = null; repository.cancelCapture() } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permit = pendingCapturePermit
        pendingCapturePermit = null
        if (granted && permit?.invoke() == true && capturePolicy.isAllowed() &&
            lease != null && graph.currentPatternReviewEntry() === lease) review.onAction(PatternIndependentAction.StartRequested)
    }
    LaunchedEffect(review) {
        review.effects.collect { effect ->
            if (lease == null || graph.currentPatternReviewEntry() !== lease) return@collect
            when (effect) {
                PatternIndependentEffect.ReturnToChapter -> currentBack()
                PatternIndependentEffect.NotNaturallyVerifiable -> currentComplete(ReviewAbilityResultDraft(
                    route.ability.abilityId,
                    evidenceId = "",
                    passed = false,
                    qualification = com.reallanguage.app.feature.review.domain.ReviewResultQualification.NOT_NATURALLY_VERIFIABLE,
                ))
                is PatternIndependentEffect.Completed -> effect.receipt?.let { receipt ->
                    currentComplete(ReviewAbilityResultDraft(route.ability.abilityId, receipt.evidenceId, effect.passed,
                        patternReceipt = receipt))
                }
            }
        }
    }
    PatternIndependentScreen(state) { action ->
        if (!capturePolicy.isAllowed() || lease == null || graph.currentPatternReviewEntry() !== lease) return@PatternIndependentScreen
        if (action == PatternIndependentAction.StartRequested) {
            val permit = graph.connectionGate.interactionPermit() ?: return@PatternIndependentScreen
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                if (permit()) review.onAction(action)
            } else { pendingCapturePermit = permit; permission.launch(Manifest.permission.RECORD_AUDIO) }
        } else review.onAction(action)
    }
}

@Composable
private fun ReviewWritingAbilityRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ReviewAbilityExecution,
    chapter: FixedLearningChapter,
    onComplete: (ReviewAbilityResultDraft) -> Unit,
    onBack: () -> Unit,
) {
    LearningContentLoader(
        requestKey = route,
        load = { graph.reviewWritingTargets(route, chapter.chapterId) },
        onReturn = onBack,
    ) { targets ->
        ReviewWritingReadyRoute(graph, route, chapter, targets, onComplete, onBack)
    }
}

@Composable
private fun ReviewWritingReadyRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ReviewAbilityExecution,
    chapter: FixedLearningChapter,
    targets: List<com.reallanguage.app.feature.learning.domain.LockedWritingTarget>,
    onComplete: (ReviewAbilityResultDraft) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(route.ability.abilityId) { ReviewWritingAdapter() }
    val writing: CompleteWritingViewModel = viewModel(
        key = "review-writing:${route.entryId}:${route.ability.abilityId}",
        factory = viewModelFactory {
            val comparator = FixedWritingComparator()
            CompleteWritingViewModel(chapter, targets, comparator, CompleteWritingEvaluator(comparator), repository)
        },
    )
    val state by writing.uiState.collectAsState()
    val speaker = rememberConnectionAwareSentenceSpeaker()
    DisposableEffect(speaker) { onDispose { speaker.close() } }
    LaunchedEffect(state.phase, state.sentenceIndex, state.audioPlaying, state.audioSentenceId) {
        if (!state.audioPlaying) speaker.stop() else {
            val sentenceId = state.audioSentenceId ?: state.currentFocusSentenceId
            state.focusSentences.firstOrNull { it.sentenceId == sentenceId }
                ?.let { speaker.start(it.textEn, speechRate = 0.82f) }
        }
    }
    LaunchedEffect(writing) {
        writing.effects.collect { effect ->
            when (effect) {
                CompleteWritingEffect.ReturnToPatternSpeaking -> onBack()
                CompleteWritingEffect.OpenSceneResponse -> {
                    repository.resultDraft(route.ability, UUID.randomUUID().toString())?.let(onComplete)
                }
            }
        }
    }
    CompleteWritingScreen(state, writing::onAction)
}

@Composable
private fun ReviewSpeakingAbilityRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ReviewAbilityExecution,
    chapter: FixedLearningChapter,
    onComplete: (ReviewAbilityResultDraft) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(route.ability.abilityId) {
        graph.reviewSpeakingSession(chapter, route.ability.sentenceId)
    }
    val speaking: FullSentenceSpeakingViewModel = viewModel(
        key = "review-speaking:${route.entryId}:${route.ability.abilityId}",
        factory = viewModelFactory { FullSentenceSpeakingViewModel(chapter, route.ability.sentenceId, repository) },
    )
    val state by speaking.uiState.collectAsState()
    val speaker = rememberConnectionAwareSentenceSpeaker()
    var answerPlaybackFailed by remember(speaker, state.attemptIndex) { mutableStateOf(false) }
    val capturePolicy = LocalConnectionPlaybackPolicy.current
    var pendingCapturePermit by remember(repository) { mutableStateOf<(() -> Boolean)?>(null) }
    LaunchedEffect(speaking, capturePolicy.interrupted) {
        if (capturePolicy.interrupted) {
            pendingCapturePermit = null
            speaking.interruptUnsubmittedCapture()
        }
    }
    DisposableEffect(speaker) { onDispose { pendingCapturePermit = null; speaker.close(); repository.cancelCapture() } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permit = pendingCapturePermit
        pendingCapturePermit = null
        if (granted && permit?.invoke() == true && capturePolicy.isAllowed()) speaking.onAction(FullSentenceSpeakingAction.StartRequested)
    }
    LaunchedEffect(speaking) {
        speaking.effects.collect { effect ->
            when (effect) {
                FullSentenceSpeakingEffect.ReturnToAudioDictation -> onBack()
                FullSentenceSpeakingEffect.OpenPatternSpeaking,
                FullSentenceSpeakingEffect.ArchiveWrongAndContinue,
                -> onComplete(ReviewAbilityResultDraft(
                    route.ability.abilityId,
                    UUID.randomUUID().toString(),
                    repository.decision == SpeakingEvaluationDecision.PASS,
                ))
            }
        }
    }
    FullSentenceSpeakingScreen(state.copy(answerPlaybackFailed = answerPlaybackFailed)) { action ->
        if (!capturePolicy.isAllowed()) return@FullSentenceSpeakingScreen
        when (action) {
            FullSentenceSpeakingAction.AnswerAudioRequested -> {
                if (state.submitting || state.evaluationReceived || state.technicalRetryPending || state.technicalRetryBlocked) return@FullSentenceSpeakingScreen
                val started = chapter.sentences.first { it.sentenceId == route.ability.sentenceId }
                    .let { speaker.start(it.textEn, speechRate = 0.82f) }
                answerPlaybackFailed = !started
                if (started) speaking.onAction(action)
            }
            FullSentenceSpeakingAction.StartRequested -> {
                val permit = graph.connectionGate.interactionPermit() ?: return@FullSentenceSpeakingScreen
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    if (permit()) speaking.onAction(action)
                } else { pendingCapturePermit = permit; permission.launch(Manifest.permission.RECORD_AUDIO) }
            }
            else -> speaking.onAction(action)
        }
    }
}

@Composable
private fun ReviewSceneAbilityRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ReviewAbilityExecution,
    chapter: FixedLearningChapter,
    onComplete: (ReviewAbilityResultDraft) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(route.ability.abilityId) { graph.reviewSceneSession() }
    val response: SceneResponseViewModel = viewModel(
        key = "review-scene:${route.entryId}:${route.ability.abilityId}",
        factory = viewModelFactory { SceneResponseViewModel(chapter, null, repository) },
    )
    val state by response.uiState.collectAsState()
    val speaker = rememberConnectionAwareSentenceSpeaker()
    val capturePolicy = LocalConnectionPlaybackPolicy.current
    var pendingCapturePermit by remember(repository) { mutableStateOf<(() -> Boolean)?>(null) }
    LaunchedEffect(response, capturePolicy.interrupted) {
        if (capturePolicy.interrupted) {
            pendingCapturePermit = null
            response.interruptUnsubmittedCapture()
        }
    }
    DisposableEffect(speaker) { onDispose { pendingCapturePermit = null; speaker.close(); repository.cancelCapture() } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permit = pendingCapturePermit
        pendingCapturePermit = null
        if (granted && permit?.invoke() == true && capturePolicy.isAllowed()) response.onAction(SceneResponseAction.StartRequested)
    }
    LaunchedEffect(state.taskIndex, state.roundIndex, state.audioMuted, state.tasks) {
        if (state.audioMuted) speaker.stop() else state.currentTask?.let { task ->
            val prompt = buildList { task.backgroundEn?.let(::add); addAll(task.contextLines.map { it.textEn }) }.joinToString(" ")
            if (prompt.isNotBlank()) speaker.start(prompt, speechRate = 0.9f)
        }
    }
    LaunchedEffect(response) {
        response.effects.collect { effect ->
            when (effect) {
                SceneResponseEffect.ReturnToCompleteWriting -> onBack()
                SceneResponseEffect.OpenChapterResult,
                SceneResponseEffect.ArchiveWrongAndContinue,
                -> onComplete(ReviewAbilityResultDraft(
                    route.ability.abilityId,
                    UUID.randomUUID().toString(),
                    repository.decision == SceneEvaluationDecision.PASS,
                ))
            }
        }
    }
    SceneResponseScreen(state) { action ->
        if (!capturePolicy.isAllowed()) return@SceneResponseScreen
        if (action == SceneResponseAction.StartRequested) {
            if (state.technicalRetryPending) {
                response.onAction(action)
                return@SceneResponseScreen
            }
            val permit = graph.connectionGate.interactionPermit() ?: return@SceneResponseScreen
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                if (permit()) response.onAction(action)
            } else { pendingCapturePermit = permit; permission.launch(Manifest.permission.RECORD_AUDIO) }
        } else response.onAction(action)
    }
}

internal fun completedStepSnapshot(
    chapter: com.reallanguage.app.feature.learning.domain.FixedLearningChapter,
    focusSentenceId: String,
    stepId: String,
    titleZh: String,
    explanationZh: String? = null,
    expressions: List<com.reallanguage.app.feature.learning.domain.SelectableExpression> = emptyList(),
): CompletedStepSnapshot {
    val sentence = chapter.sentences.first { it.sentenceId == focusSentenceId && it.isFocus }
    return CompletedStepSnapshot(
        stepId = stepId,
        titleZh = "第 ${chapter.chapterNumber} 章 · $titleZh",
        contentEn = sentence.textEn,
        contentZh = sentence.textZh,
        explanationZh = explanationZh,
        contextSentences = chapter.sentences,
        activeSentenceId = focusSentenceId,
        chapterId = chapter.chapterId,
        expressions = expressions,
    )
}

@Composable
internal fun CompletedStepReviewRoute(
    route: HomeDiaryRoute.CompletedStepReview,
    onReturn: () -> Unit,
    loadSnapshot: (suspend () -> CompletedStepSnapshot)? = null,
) {
    val context = LocalContext.current
    val owner = rememberEntryViewModelStoreOwner(route.snapshot)
    val speaker = rememberConnectionAwareSentenceSpeaker(route.snapshot)
    val review: CompletedStepReviewViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "completed-step:${route.snapshot.stepId}:${route.snapshot.contentEn}",
        factory = viewModelFactory { CompletedStepReviewViewModel(route.snapshot, loadSnapshot) { text -> speaker.play(text) } },
    )
    val state by review.uiState.collectAsState()
    LaunchedEffect(speaker, state.audioPlaying) {
        if (!state.audioPlaying) speaker.stop()
    }
    DisposableEffect(speaker) { onDispose { speaker.close() } }
    val currentReturn by rememberUpdatedState(onReturn)
    LaunchedEffect(review) {
        review.effects.collect { effect ->
            if (effect == CompletedStepReviewEffect.ReturnToCurrentTask) currentReturn()
        }
    }
    CompletedStepReviewScreen(state, review::onAction)
}

@Composable
private fun ChapterResultRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ChapterResult,
    onChapterSelection: () -> Unit,
    onHome: () -> Unit,
) {
    val commandId = remember(route.chapter.chapterId) {
        UUID.nameUUIDFromBytes("chapter-archive:${route.chapter.chapterId}".toByteArray()).toString()
    }
    val result: ChapterResultViewModel = viewModel(
        key = "chapter-result:${route.chapter.chapterId}",
        factory = graph.chapterResultFactory(route.chapter.chapterId, commandId),
    )
    val state by result.uiState.collectAsState()
    LaunchedEffect(result) {
        result.effects.collect { effect ->
            when (effect) {
                ChapterResultEffect.OpenChapterSelection -> onChapterSelection()
                ChapterResultEffect.ReturnHome -> onHome()
            }
        }
    }
    ChapterResultScreen(state, result::onAction)
}

@Composable
private fun ChapterSelectionRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ChapterSelection,
    onChapter: (String) -> Unit,
    onReturn: () -> Unit,
    onHome: () -> Unit,
) {
    val owner = graph.rememberChapterSelectionEntryOwner(route.boundary)
    val selection: ChapterSelectionViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "chapter-selection:${route.boundary}",
        factory = graph.chapterSelectionFactory(route.boundary),
    )
    val state by selection.uiState.collectAsState()
    val currentChapter by rememberUpdatedState(onChapter)
    val currentReturn by rememberUpdatedState(onReturn)
    val currentHome by rememberUpdatedState(onHome)
    LaunchedEffect(selection) {
        selection.effects.collect { effect ->
            when (effect) {
                is ChapterSelectionEffect.OpenChapter -> currentChapter(effect.chapterId)
                ChapterSelectionEffect.ReturnToPrevious -> currentReturn()
                ChapterSelectionEffect.ReturnHome -> currentHome()
            }
        }
    }
    ChapterSelectionScreen(state, selection::onAction)
}

@Composable
private fun ChapterAbandonRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ChapterAbandonConfirmation,
    onContinue: () -> Unit,
    onChapterSelection: (String) -> Unit,
    onHome: () -> Unit,
    aboveConnectionCover: Boolean = false,
) {
    val commandId = remember(route.chapterId) {
        UUID.nameUUIDFromBytes("chapter-abandon:${route.chapterId}".toByteArray()).toString()
    }
    val owner = rememberEntryViewModelStoreOwner(listOf(graph, route))
    val entryIdentity = remember(graph, route) { graph.currentSessionOwner() }
    val abandon: ChapterAbandonViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "chapter-abandon:${route.chapterId}",
        factory = graph.chapterAbandonFactory(route.chapterId, commandId),
    )
    val state by abandon.uiState.collectAsState()
    val currentContinue by rememberUpdatedState(onContinue)
    val currentSelection by rememberUpdatedState(onChapterSelection)
    val currentHome by rememberUpdatedState(onHome)
    BackHandler { abandon.onAction(ChapterAbandonAction.ContinueLearningRequested) }
    LaunchedEffect(abandon) {
        abandon.effects.collect { effect ->
            if (!graph.isSessionOwnerCurrent(entryIdentity)) return@collect
            when (effect) {
                ChapterAbandonEffect.ReturnToLearning -> currentContinue()
                is ChapterAbandonEffect.OpenChapterSelection -> currentSelection(effect.sourceId)
                ChapterAbandonEffect.ReturnHome -> currentHome()
            }
        }
    }
    if (aboveConnectionCover) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { abandon.onAction(ChapterAbandonAction.ContinueLearningRequested) },
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            ),
        ) {
            com.reallanguage.app.core.designsystem.RlImmersiveDialogWindow()
            ChapterAbandonScreen(state, abandon::onAction)
        }
    } else ChapterAbandonScreen(state, abandon::onAction)
}

@Composable
private fun LearningExitRoute(
    graph: ProductionAppGraph,
    checkpoint: OrdinaryLearningCheckpoint,
    repository: com.reallanguage.app.feature.learning.domain.LearningCheckpointRepository,
    onClose: () -> Unit,
    onHome: () -> Unit,
    onAbandon: () -> Unit,
) {
    LearningExitEntry(graph.learningExitFactory(checkpoint, repository), listOf(graph, checkpoint, repository),
        onClose, onHome, onAbandon)
}

@Composable
private fun SceneResponseRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.SceneResponse,
    onExit: () -> Unit,
    onReturn: () -> Unit,
    onChapterResult: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(graph, route.chapter.chapterId) { graph.sceneResponseSession() }
    val response: SceneResponseViewModel = rememberSpeakingEntryViewModel(
        key = "scene-response:${route.chapter.chapterId}",
        factory = graph.sceneResponseFactory(route, repository),
        repositoryIdentity = repository,
    )
    val state by response.uiState.collectAsState()
    val speaker = rememberConnectionAwareSentenceSpeaker()
    val capturePolicy = LocalConnectionPlaybackPolicy.current
    var pendingCapturePermit by remember(repository) { mutableStateOf<(() -> Boolean)?>(null) }
    LaunchedEffect(response, capturePolicy.interrupted) {
        if (capturePolicy.interrupted) {
            pendingCapturePermit = null
            response.interruptUnsubmittedCapture()
        }
    }
    DisposableEffect(speaker) {
        onDispose { pendingCapturePermit = null; speaker.close(); repository.cancelCapture() }
    }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val permit = pendingCapturePermit
        pendingCapturePermit = null
        if (granted && permit?.invoke() == true && capturePolicy.isAllowed()) {
            response.onAction(SceneResponseAction.StartRequested)
        }
    }
    LaunchedEffect(state.taskIndex, state.roundIndex, state.audioMuted, state.tasks) {
        if (state.audioMuted) {
            speaker.stop()
        } else {
            state.currentTask?.let { task ->
                val prompt = buildList {
                    task.backgroundEn?.let(::add)
                    addAll(task.contextLines.map { it.textEn })
                }.joinToString(" ")
                if (prompt.isNotBlank()) speaker.start(prompt, speechRate = 0.9f)
            }
        }
    }
    LaunchedEffect(response) {
        response.effects.collect { effect ->
            when (effect) {
                SceneResponseEffect.ReturnToCompleteWriting -> onReturn()
                SceneResponseEffect.OpenChapterResult,
                SceneResponseEffect.ArchiveWrongAndContinue,
                -> onChapterResult()
            }
        }
    }
    SceneResponseScreen(state, onExit = onExit) { action ->
        if (!capturePolicy.isAllowed()) return@SceneResponseScreen
        if (action == SceneResponseAction.StartRequested) {
            if (state.technicalRetryPending) { response.onAction(action); return@SceneResponseScreen }
            val permit = graph.connectionGate.interactionPermit() ?: return@SceneResponseScreen
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                if (permit()) response.onAction(action)
            } else {
                pendingCapturePermit = permit
                permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            response.onAction(action)
        }
    }
}

@Composable
private fun CompleteWritingRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.CompleteWriting,
    onReturn: () -> Unit,
    onSceneResponse: () -> Unit,
) {
    LearningContentLoader(route,
        load = { graph.loadWritingTargets(route.chapter.chapterId) }, onReturn = onReturn,
    ) { targets -> CompleteWritingReadyRoute(graph, route, targets, onReturn, onSceneResponse) }
}

@Composable
private fun CompleteWritingReadyRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.CompleteWriting,
    targets: List<com.reallanguage.app.feature.learning.domain.LockedWritingTarget>,
    onReturn: () -> Unit,
    onSceneResponse: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(route.chapter.chapterId) { graph.completeWritingSession() }
    CompleteWritingEntry(
        factory = graph.completeWritingFactory(route, repository, targets),
        entryKey = route,
        onReturn = onReturn,
        onSceneResponse = onSceneResponse,
    ) { state, onAction ->
        val speaker = rememberConnectionAwareSentenceSpeaker()
        DisposableEffect(speaker) { onDispose { speaker.close() } }
        com.reallanguage.app.feature.learning.presentation.CompleteWritingAudioPlayback(
            state = state,
            play = { text, rate -> speaker.play(text, speechRate = rate) },
            stop = speaker::stop,
            onAction = onAction,
        )
        CompleteWritingScreen(state, onAction)
    }
}

@Composable
private fun PatternSpeakingRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.PatternSpeaking,
    onReturn: () -> Unit,
    onCompleteWriting: () -> Unit,
) {
    LearningContentLoader(route,
        load = { graph.loadSpeakingPatterns(route.chapter.chapterId, route.focusSentenceId) }, onReturn = onReturn,
    ) { targets -> PatternSpeakingReadyRoute(graph, route, targets, onReturn, onCompleteWriting) }
}

@Composable
private fun PatternSpeakingReadyRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.PatternSpeaking,
    targets: List<com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern>,
    onReturn: () -> Unit,
    onCompleteWriting: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(graph, route.chapter.chapterId, route.focusSentenceId) {
        graph.patternSpeakingSession(route)
    }
    val patterns: PatternSpeakingViewModel = rememberSpeakingEntryViewModel(
        key = "pattern-speaking:${route.chapter.chapterId}:${route.focusSentenceId}",
        factory = graph.patternSpeakingFactory(route, repository, targets),
        repositoryIdentity = repository,
    )
    val state by patterns.uiState.collectAsState()
    val speaker = rememberConnectionAwareSentenceSpeaker()
    val capturePolicy = LocalConnectionPlaybackPolicy.current
    var pendingCaptureAction by remember(repository) { mutableStateOf<PatternSpeakingAction?>(null) }
    var pendingCapturePermit by remember(repository) { mutableStateOf<(() -> Boolean)?>(null) }
    LaunchedEffect(patterns, capturePolicy.interrupted) {
        if (capturePolicy.interrupted) {
            pendingCaptureAction = null
            pendingCapturePermit = null
            patterns.interruptUnsubmittedCapture()
        }
    }
    DisposableEffect(speaker) {
        onDispose {
            pendingCaptureAction = null
            pendingCapturePermit = null
            speaker.close()
            repository.cancelCapture()
        }
    }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingCaptureAction
        val permit = pendingCapturePermit
        pendingCaptureAction = null
        pendingCapturePermit = null
        if (granted && pending != null && permit?.invoke() == true && capturePolicy.isAllowed()) {
            patterns.onAction(pending)
        }
    }
    LaunchedEffect(state.audioMuted, state.stage, state.responseIndex, state.examples) {
        if (state.audioMuted) speaker.stop()
        else state.currentResponseExample?.targetResponseEn
            ?.let { speaker.start(it, speechRate = 0.82f) }
    }
    LaunchedEffect(patterns) {
        patterns.effects.collect { effect ->
            when (effect) {
                PatternSpeakingEffect.ReturnToFullSentenceSpeaking -> onReturn()
                PatternSpeakingEffect.OpenCompleteWriting -> onCompleteWriting()
            }
        }
    }
    PatternSpeakingScreen(state, onAction = { action ->
        if (!capturePolicy.isAllowed()) return@PatternSpeakingScreen
        when (action) {
            is PatternSpeakingAction.StartReadingRequested,
            PatternSpeakingAction.StartResponseRequested,
            -> {
                if (state.technicalRetryPending) {
                    patterns.onAction(action)
                    return@PatternSpeakingScreen
                }
                val permit = graph.connectionGate.interactionPermit() ?: return@PatternSpeakingScreen
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    if (permit()) patterns.onAction(action)
                } else {
                    pendingCaptureAction = action
                    pendingCapturePermit = permit
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            PatternSpeakingAction.PronounceRequested -> {
                if (state.technicalRetryPending || state.technicalRetryBlocked || state.submitting) return@PatternSpeakingScreen
                state.currentResponseExample?.targetResponseEn?.let { speaker.start(it) }
                patterns.onAction(action)
            }
            else -> patterns.onAction(action)
        }
    }, targetAssessment = { target ->
        LearningSelfAssessmentEntry(graph, route.chapter.chapterId, target.sourceSentenceId, "LR-07",
            targetId = target.patternId, enabled = !state.recording && !state.submitting && !state.progressFailed && !state.completed && !state.technicalRetryPending)
    })
}

@Composable
private fun FullSentenceSpeakingRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.FullSentenceSpeaking,
    onReturn: () -> Unit,
    onPatternSpeaking: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(graph, route.chapter.chapterId, route.focusSentenceId) {
        graph.fullSentenceSpeakingSession(route)
    }
    val speaking: FullSentenceSpeakingViewModel = rememberSpeakingEntryViewModel(
        key = "full-sentence-speaking:${route.chapter.chapterId}:${route.focusSentenceId}",
        factory = graph.fullSentenceSpeakingFactory(route, repository),
        repositoryIdentity = repository,
    )
    val state by speaking.uiState.collectAsState()
    var answerPlaybackFailed by remember(repository, state.attemptIndex) { mutableStateOf(false) }
    val capturePolicy = LocalConnectionPlaybackPolicy.current
    var pendingCapturePermit by remember(repository) { mutableStateOf<(() -> Boolean)?>(null) }
    LaunchedEffect(speaking, capturePolicy.interrupted) {
        if (capturePolicy.interrupted) {
            pendingCapturePermit = null
            speaking.interruptUnsubmittedCapture()
        }
    }
    val sentenceText = remember(route.chapter, route.focusSentenceId) {
        route.chapter.sentences.first { it.sentenceId == route.focusSentenceId }.textEn
    }
    val speaker = rememberConnectionAwareSentenceSpeaker()
    DisposableEffect(speaker) {
        onDispose {
            pendingCapturePermit = null
            speaker.close()
            repository.cancelCapture()
        }
    }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val permit = pendingCapturePermit
        pendingCapturePermit = null
        if (granted && permit?.invoke() == true && capturePolicy.isAllowed()) {
            speaking.onAction(FullSentenceSpeakingAction.StartRequested)
        }
    }
    LaunchedEffect(speaking) {
        speaking.effects.collect { effect ->
            when (effect) {
                FullSentenceSpeakingEffect.ReturnToAudioDictation -> onReturn()
                FullSentenceSpeakingEffect.OpenPatternSpeaking,
                FullSentenceSpeakingEffect.ArchiveWrongAndContinue,
                -> onPatternSpeaking()
            }
        }
    }
    FullSentenceSpeakingScreen(state.copy(answerPlaybackFailed = answerPlaybackFailed)) { action ->
        if (!capturePolicy.isAllowed()) return@FullSentenceSpeakingScreen
        when (action) {
            FullSentenceSpeakingAction.AnswerAudioRequested -> {
                if (!state.submitting && !state.evaluationReceived && !state.technicalRetryPending && !state.technicalRetryBlocked) {
                    val started = speaker.start(sentenceText)
                    answerPlaybackFailed = !started
                    if (started) speaking.onAction(action)
                }
            }
            FullSentenceSpeakingAction.StartRequested -> {
                val permit = graph.connectionGate.interactionPermit() ?: return@FullSentenceSpeakingScreen
                if (
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) { if (permit()) speaking.onAction(action) } else {
                    pendingCapturePermit = permit
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            else -> speaking.onAction(action)
        }
    }
}

@Composable
private fun DiaryInputRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.Input,
    homeRequestId: String? = null,
    onHomeRequestHandled: () -> Unit = {},
    onEvent: (HomeDiaryEvent) -> Unit,
) {
    DiaryInputEntry(
        graph.diaryInputFactory(route.text),
        route.text,
        homeRequestId,
        onHomeRequestHandled,
        onEvent,
        dictationPermit = graph.connectionGate::interactionPermit,
    )
}

@Composable
internal fun DiaryInputEntry(
    factory: ViewModelProvider.Factory,
    initialText: String,
    homeRequestId: String? = null,
    onHomeRequestHandled: () -> Unit = {},
    onEvent: (HomeDiaryEvent) -> Unit,
    dictationPermit: () -> (() -> Boolean)? = { { true } },
) {
    val context = LocalContext.current
    val owner = rememberEntryViewModelStoreOwner(initialText)
    val diary: DiaryInputViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "diary-input",
        factory = factory,
    )
    val state by diary.uiState.collectAsState()
    var awaitingDictationPermission by remember(diary) { mutableStateOf(false) }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val wasAwaiting = awaitingDictationPermission
        awaitingDictationPermission = false
        if (wasAwaiting && !granted) diary.onAction(
            com.reallanguage.app.feature.diary.presentation.DiaryInputAction.DictationPermissionDenied,
        )
    }
    DisposableEffect(diary) {
        onDispose {
            awaitingDictationPermission = false
            diary.onAction(com.reallanguage.app.feature.diary.presentation.DiaryInputAction.DictationInterrupted)
        }
    }
    BackHandler { if (!state.submitting) diary.onAction(com.reallanguage.app.feature.diary.presentation.DiaryInputAction.BackRequested) }
    LaunchedEffect(homeRequestId, state.submitting) {
        if (homeRequestId != null && !state.submitting) {
            diary.onAction(com.reallanguage.app.feature.diary.presentation.DiaryInputAction.BackRequested)
            onHomeRequestHandled()
        }
    }
    LaunchedEffect(diary) {
        diary.effects.collect { effect ->
            when (effect) {
                DiaryInputEffect.OpenHome -> onEvent(HomeDiaryEvent.ReturnHome)
                is DiaryInputEffect.OpenSafetyCheck -> onEvent(HomeDiaryEvent.Submit(effect.confirmedText))
            }
        }
    }
    DiaryInputScreen(state, onAction = { action ->
        if (action == com.reallanguage.app.feature.diary.presentation.DiaryInputAction.DictationHoldStarted) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                val permit = dictationPermit()
                if (permit?.invoke() == true) diary.onAction(action)
            } else {
                awaitingDictationPermission = true
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else diary.onAction(action)
    })
}

private data class PendingBilingualRequest(
    val visibleRoute: HomeDiaryRoute,
    val operation: HomeDiaryRoute.SemanticSplit,
)

private fun HomeDiaryRoute.SemanticSplit.toFocusSelection(units: List<SemanticUnit>) =
    HomeDiaryRoute.FocusSelection(
        text = text,
        units = units,
        sourceType = sourceType,
        contentShape = contentShape,
        sourceReturnRoute = sourceReturnRoute,
        sourceEntryId = entryId,
    )

@Composable
private fun SafetyCheckRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.SafetyCheck,
    onPassed: (ConfirmedSource) -> Unit,
    onOpenSafetyHandling: (String, String) -> Unit,
) {
    SafetyCheckEntry(route, graph::safetyCheckFactory, onPassed, onOpenSafetyHandling)
}

@Composable
internal fun SafetyCheckEntry(
    route: HomeDiaryRoute.SafetyCheck,
    factory: (ConfirmedSource, String) -> ViewModelProvider.Factory,
    onPassed: (ConfirmedSource) -> Unit,
    onOpenSafetyHandling: (String, String) -> Unit,
) {
    val source = remember(route.text, route.sourceType, route.contentShape) {
        ConfirmedSource(
            text = route.text,
            sourceType = route.sourceType,
            contentShape = route.contentShape,
        )
    }
    val commandId = remember(route) { safetyCheckCommandId(route) }
    val owner = rememberEntryViewModelStoreOwner(commandId)
    val safety: SafetyCheckViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "safety-check:$commandId",
        factory = factory(source, commandId),
    )
    val state by safety.uiState.collectAsState()
    LaunchedEffect(safety) {
        safety.effects.collect { effect ->
            when (effect) {
                is SafetyCheckEffect.OpenFullSentenceSplit -> onPassed(effect.source)
                is SafetyCheckEffect.OpenSafetyHandling -> onOpenSafetyHandling(effect.safetyCaseId, effect.source.text)
            }
        }
    }
    SafetyCheckScreen(state, safety::onAction)
}

@Composable
private fun SafetyHandlingLoadingRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.SafetyHandlingLoading,
    onReady: (com.reallanguage.app.feature.safety.domain.SafetyCaseDetails) -> Unit,
) {
    SafetyHandlingLoadingEntry(graph.safetyHandlingLoaderFactory(route.safetyCaseId), route.safetyCaseId, onReady)
}

@Composable
internal fun SafetyHandlingLoadingEntry(
    factory: ViewModelProvider.Factory,
    safetyCaseId: String,
    onReady: (com.reallanguage.app.feature.safety.domain.SafetyCaseDetails) -> Unit,
) {
    val owner = rememberEntryViewModelStoreOwner("safety-handling-loader:$safetyCaseId")
    val loader: SafetyHandlingLoaderViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "safety-handling-loader:$safetyCaseId",
        factory = factory,
    )
    val state by loader.uiState.collectAsState()
    LaunchedEffect(loader) {
        loader.effects.collect { effect ->
            if (effect is SafetyHandlingLoaderEffect.OpenHandling) onReady(effect.details)
        }
    }
    SafetyHandlingLoaderScreen(state, loader::retry)
}

@Composable
private fun SafetyHandlingRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.SafetyHandling,
    onSplit: (ConfirmedSource) -> Unit,
    onEdit: (ConfirmedSource) -> Unit,
    onReview: () -> Unit,
    onExistingReview: (String) -> Unit,
) {
    SafetyHandlingEntry(graph.safetyHandlingFactory(route.details), route, onSplit, onEdit, onReview, onExistingReview)
}

@Composable
internal fun SafetyHandlingEntry(
    factory: ViewModelProvider.Factory,
    route: HomeDiaryRoute.SafetyHandling,
    onSplit: (ConfirmedSource) -> Unit,
    onEdit: (ConfirmedSource) -> Unit,
    onReview: () -> Unit,
    onExistingReview: (String) -> Unit = {},
) {
    val sourceEditorAvailable = safetySourceEditRoute(route.details.originalSource, route.returnRoute) != null
    val owner = rememberEntryViewModelStoreOwner("safety-handling:${route.details.safetyCaseId}")
    val handling: SafetyHandlingViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "safety-handling:${route.details.safetyCaseId}",
        factory = factory,
    )
    val state by handling.uiState.collectAsState()
    LaunchedEffect(handling) {
        handling.effects.collect { effect ->
            when (effect) {
                is SafetyHandlingEffect.OpenFullSentenceSplit -> onSplit(effect.source)
                is SafetyHandlingEffect.OpenSourceEditor -> if (sourceEditorAvailable) onEdit(effect.source)
                is SafetyHandlingEffect.OpenReviewPending -> onReview()
                is SafetyHandlingEffect.OpenExistingReviewResult -> onExistingReview(effect.reviewRequestId)
            }
        }
    }
    SafetyHandlingScreen(
        state.copy(sourceEditorUnavailable = !sourceEditorAvailable),
        onAction = { action ->
            if (action != com.reallanguage.app.feature.safety.presentation.SafetyHandlingAction.ReturnToModifyRequested || sourceEditorAvailable) {
                handling.onAction(action)
            }
        },
    )
}

@Composable
private fun SilentSemanticSplitRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.SemanticSplit,
    onReady: (List<SemanticUnit>) -> Unit,
    onReturn: () -> Unit,
) {
    SilentSemanticSplitEntry(route, graph::semanticSplitFactory, onReady, onReturn)
}

@Composable
internal fun SilentSemanticSplitEntry(
    route: HomeDiaryRoute.SemanticSplit,
    factory: (ConfirmedSource, String) -> ViewModelProvider.Factory,
    onReady: (List<SemanticUnit>) -> Unit,
    onReturn: () -> Unit,
) {
    val source = remember(route.text, route.sourceType, route.contentShape) {
        ConfirmedSource(route.text, route.sourceType, route.contentShape)
    }
    val commandId = remember(route) { semanticSplitCommandId(route) }
    val owner = rememberEntryViewModelStoreOwner(commandId)
    val split: SemanticSplitViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "semantic-split:$commandId",
        factory = factory(source, commandId),
    )
    LaunchedEffect(split) {
        split.effects.collect { effect ->
            when (effect) {
                is SemanticSplitEffect.OpenFocusSelection -> onReady(effect.units)
                SemanticSplitEffect.ReturnUpstream -> onReturn()
            }
        }
    }
}

@Composable
private fun FocusSelectionRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.FocusSelection,
    onReturn: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    onStateChanged: (com.reallanguage.app.feature.formation.presentation.FocusSelectionUiState) -> Unit = {},
) {
    FocusSelectionEntry(route, graph.focusSelectionFactory(route.units, route.initialSelectedUnitIds), onReturn, onConfirm, onStateChanged)
}

@Composable
internal fun FocusSelectionEntry(
    route: HomeDiaryRoute.FocusSelection,
    factory: ViewModelProvider.Factory,
    onReturn: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    onStateChanged: (com.reallanguage.app.feature.formation.presentation.FocusSelectionUiState) -> Unit = {},
) {
    FocusSelectionProgressEntry(factory, route, onConfirm, onReturn, onStateChanged)
}

private fun formationReturnDestination(context: Context, current: HomeDiaryRoute): HomeDiaryRoute {
    val target = formationReturnTarget(current)
    if (target.editorUnavailable) {
        android.widget.Toast.makeText(context, "缺少原内容的编辑入口信息，已返回消息；本次未修改内容。", android.widget.Toast.LENGTH_LONG).show()
    }
    return target.route
}

@Composable
private fun ChapterFormationRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ChapterFormation,
    onFormed: (List<com.reallanguage.app.feature.formation.domain.ChapterDraft>) -> Unit,
    onReturn: () -> Unit,
) {
    ChapterFormationEntry(route, { command -> graph.chapterFormationFactory(route.units, route.selectedUnitIds, command) }, onFormed, onReturn)
}

@Composable
internal fun ChapterFormationEntry(
    route: HomeDiaryRoute.ChapterFormation,
    factory: (String) -> ViewModelProvider.Factory,
    onFormed: (List<com.reallanguage.app.feature.formation.domain.ChapterDraft>) -> Unit,
    onReturn: () -> Unit,
) {
    val context = LocalContext.current
    val currentFormed by rememberUpdatedState(onFormed)
    val currentReturn by rememberUpdatedState(onReturn)
    val commandId = remember(route) { chapterFormationCommandId(route) }
    val owner = rememberEntryViewModelStoreOwner(commandId)
    val formation: ChapterFormationViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "chapter-formation:$commandId",
        factory = factory(commandId),
    )
    val state by formation.uiState.collectAsState()
    LaunchedEffect(formation) {
        formation.effects.collect { effect ->
            when (effect) {
                is ChapterFormationEffect.OpenEnglishConfirmation -> currentFormed(effect.chapters)
            }
        }
    }
    LaunchedEffect(state.failed) {
        if (state.failed) {
            android.widget.Toast.makeText(
                context,
                "暂时无法准备英语表达，请重新确认重点句后重试。",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            currentReturn()
        }
    }
    ProductionLoadingScreen()
}

@Composable
private fun EnglishGenerationRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.EnglishGeneration,
    onGenerated: (List<com.reallanguage.app.feature.formation.domain.EnglishChapterDraft>) -> Unit,
    onReturn: () -> Unit,
) {
    EnglishGenerationEntry(route, { command -> graph.englishGenerationFactory(route, command) }, onGenerated, onReturn)
}

@Composable
internal fun EnglishGenerationEntry(
    route: HomeDiaryRoute.EnglishGeneration,
    factory: (String) -> ViewModelProvider.Factory,
    onGenerated: (List<com.reallanguage.app.feature.formation.domain.EnglishChapterDraft>) -> Unit,
    onReturn: () -> Unit,
) {
    val commandId = remember(route) { englishGenerationCommandId(route) }
    val owner = rememberEntryViewModelStoreOwner(commandId)
    val generation: EnglishGenerationViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "english-generation:$commandId",
        factory = factory(commandId),
    )
    val state by generation.uiState.collectAsState()
    LaunchedEffect(generation) {
        generation.effects.collect { effect ->
            when (effect) {
                is EnglishGenerationEffect.OpenConfirmation -> onGenerated(effect.chapters)
                EnglishGenerationEffect.ReturnToFocusSelection -> onReturn()
            }
        }
    }
    EnglishGenerationScreen(state, generation::onAction)
}

@Composable
private fun EnglishConfirmationRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.EnglishConfirmation,
    onTargets: (List<com.reallanguage.app.feature.formation.domain.EnglishChapterDraft>, Map<String, String>) -> Unit,
    onReturn: () -> Unit,
    onStateChanged: (com.reallanguage.app.feature.formation.presentation.EnglishConfirmationUiState) -> Unit = {},
) {
    EnglishConfirmationEntry(route, graph.englishConfirmationFactory(route.chapters, route.initialSelectedCandidateIds), onTargets, onReturn, onStateChanged)
}

@Composable
internal fun EnglishConfirmationEntry(
    route: HomeDiaryRoute.EnglishConfirmation,
    factory: ViewModelProvider.Factory,
    onTargets: (List<com.reallanguage.app.feature.formation.domain.EnglishChapterDraft>, Map<String, String>) -> Unit,
    onReturn: () -> Unit,
    onStateChanged: (com.reallanguage.app.feature.formation.presentation.EnglishConfirmationUiState) -> Unit = {},
) {
    EnglishConfirmationProgressEntry(factory, listOf(route.sourceEntryId, route.chapters, route.initialSelectedCandidateIds), onTargets, onReturn, onStateChanged)
}

@Composable
private fun LearningTargetLoadingRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.LearningTargets,
    onReady: (List<com.reallanguage.app.feature.formation.domain.LearningTarget>, List<com.reallanguage.app.feature.formation.domain.QueryableExpression>) -> Unit,
    onReturn: () -> Unit,
) {
    LearningTargetLoadingEntry(route, { command -> graph.learningTargetLoadingFactory(route, command) }, onReady, onReturn)
}

@Composable
internal fun LearningTargetLoadingEntry(
    route: HomeDiaryRoute.LearningTargets,
    factory: (String) -> ViewModelProvider.Factory,
    onReady: (List<com.reallanguage.app.feature.formation.domain.LearningTarget>, List<com.reallanguage.app.feature.formation.domain.QueryableExpression>) -> Unit,
    onReturn: () -> Unit,
) {
    val currentReady by rememberUpdatedState(onReady)
    val currentReturn by rememberUpdatedState(onReturn)
    val commandId = remember(route) { learningTargetExtractionCommandId(route) }
    val owner = rememberEntryViewModelStoreOwner(commandId)
    val loader: LearningTargetLoadingViewModel = viewModel(viewModelStoreOwner = owner, key = "learning-target-loader:$commandId", factory = factory(commandId))
    val state by loader.uiState.collectAsState()
    LaunchedEffect(loader) { loader.effects.collect { effect -> when (effect) {
        is LearningTargetLoadingEffect.OpenSelection -> currentReady(effect.targets, effect.queryableExpressions)
        LearningTargetLoadingEffect.ReturnToEnglishConfirmation -> currentReturn()
    } } }
    LearningTargetLoadingScreen(state, loader::onAction)
}

@Composable
private fun LearningTargetSelectionRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.LearningTargetSelection,
    onContinue: (Set<String>, List<com.reallanguage.app.feature.formation.domain.LearningTarget>) -> Unit,
    onReturn: () -> Unit,
    onStateChanged: (com.reallanguage.app.feature.formation.presentation.LearningTargetUiState) -> Unit = {},
) {
    LearningTargetSelectionEntry(graph.learningTargetSelectionFactory(route), route.entryId, onContinue, onReturn, onStateChanged)
}

@Composable
private fun FinalFixRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.FinalFix,
    onFixed: suspend (String) -> Unit,
    onReturn: () -> Unit,
    sourceProjection: com.reallanguage.app.feature.formation.domain.FixedSourceProjection? = null,
) {
    FinalFixEntry(graph.finalFixFactory(route, sourceProjection), route.entryId, onFixed, onReturn)
}

@Composable
private fun ChapterEntryRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ChapterLearning,
    onStart: (com.reallanguage.app.feature.learning.domain.FixedLearningChapter, String) -> Unit,
    onReturn: () -> Unit,
) {
    ChapterEntryContent(route.chapterId, graph.chapterEntryFactory(route.chapterId), onStart, onReturn)
}

@Composable
internal fun ChapterEntryContent(
    chapterId: String,
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    onStart: (com.reallanguage.app.feature.learning.domain.FixedLearningChapter, String) -> Unit,
    onReturn: () -> Unit,
) {
    val owner = rememberEntryViewModelStoreOwner("chapter-entry:$chapterId")
    val entry: ChapterEntryViewModel = viewModel(viewModelStoreOwner = owner, key = "chapter-entry:$chapterId", factory = factory)
    val state by entry.uiState.collectAsState()
    LaunchedEffect(entry) { entry.effects.collect { effect -> when (effect) {
        is ChapterEntryEffect.OpenExplanation -> onStart(effect.chapter, effect.focusSentenceId)
        ChapterEntryEffect.ReturnToPrevious -> onReturn()
    } } }
    ChapterEntryScreen(state, entry::onAction)
}

@Composable
private fun ExplanationPreparationRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ChapterExplanation,
    onReady: (String, List<com.reallanguage.app.feature.learning.domain.SelectableExpression>) -> Unit,
    onReturn: () -> Unit,
) {
    val preparation: ExplanationPreparationViewModel = viewModel(
        key = "explanation-preparation:${route.chapter.chapterId}:${route.focusSentenceId}:${route.entryId}",
        factory = graph.explanationPreparationFactory(route),
    )
    val state by preparation.uiState.collectAsState()
    LaunchedEffect(preparation) {
        preparation.effects.collect { effect ->
            when (effect) {
                is ExplanationPreparationEffect.OpenExplanation ->
                    onReady(effect.baseExplanationZh, effect.expressions)
                ExplanationPreparationEffect.ReturnToChapter -> onReturn()
            }
        }
    }
    ExplanationPreparationScreen(state, preparation::onAction)
}

@Composable
private fun ExplanationReadingRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ExplanationReading,
    onReturn: () -> Unit,
    onNext: () -> Unit,
) {
    val reading: ExplanationReadingViewModel = viewModel(
        key = "explanation-reading:${route.chapter.chapterId}:${route.focusSentenceId}:${route.entryId}",
        factory = graph.explanationReadingFactory(route),
    )
    val state by reading.uiState.collectAsState()
    val context = LocalContext.current
    val speaker = rememberConnectionAwareSentenceSpeaker()
    DisposableEffect(speaker) { onDispose { speaker.close() } }
    ExplanationAudioPlayback(state,
        play = { text, rate, language -> speaker.play(text, rate, java.util.Locale.forLanguageTag(language)) },
        stop = speaker::stop, onAction = reading::onAction)
    LaunchedEffect(reading) {
        reading.effects.collect { effect ->
            when (effect) {
                ExplanationReadingEffect.ReturnToChapterEntry -> onReturn()
                ExplanationReadingEffect.SentenceSkipped,
                ExplanationReadingEffect.OpenReadAndSpeak,
                -> onNext()
            }
        }
    }
    ExplanationReadingScreen(state, reading::onAction, targetAssessment = { display ->
        LearningSelfAssessmentEntry(graph, route.chapter.chapterId, route.focusSentenceId, "LR-02",
            display = display, enabled = !state.loading && !state.progressFailed, cardAction = true)
    })
}

@Composable
private fun LearningSelfAssessmentEntry(
    graph: ProductionAppGraph,
    chapterId: String,
    sentenceId: String,
    moduleId: String,
    display: String? = null,
    targetId: String? = null,
    enabled: Boolean,
    cardAction: Boolean = false,
) {
    val owner = rememberEntryViewModelStoreOwner(listOf(graph, chapterId, sentenceId, moduleId))
    val model: com.reallanguage.app.feature.learning.presentation.TargetSelfAssessmentViewModel = viewModel(
        viewModelStoreOwner = owner, factory = graph.targetSelfAssessmentFactory(chapterId, sentenceId, moduleId),
    )
    val state by model.uiState.collectAsState()
    com.reallanguage.app.feature.learning.presentation.TargetSelfAssessmentControls(
        state, model::onAction, display = display, targetId = targetId, enabled = enabled, cardAction = cardAction,
    )
}

@Composable
internal fun rememberReadSpeakViewModel(
    chapterId: String,
    sentenceId: String,
    factory: ViewModelProvider.Factory,
    repositoryIdentity: Any? = null,
): ReadSpeakViewModel {
    val owner = rememberEntryViewModelStoreOwner(listOf(chapterId, sentenceId, repositoryIdentity))
    return viewModel(viewModelStoreOwner = owner, key = "read-speak:$chapterId:$sentenceId", factory = factory)
}

@Composable
private fun ReadSpeakRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ReadSpeak,
    onReturn: () -> Unit,
    onHandwriting: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(graph, route.chapter.chapterId, route.focusSentenceId) {
        graph.readSpeakSession(route)
    }
    val readSpeak = rememberReadSpeakViewModel(
        route.chapter.chapterId, route.focusSentenceId, graph.readSpeakFactory(route, repository),
        repositoryIdentity = repository,
    )
    val state by readSpeak.uiState.collectAsState()
    val capturePolicy = LocalConnectionPlaybackPolicy.current
    var pendingCaptureAction by remember(repository) { mutableStateOf<ReadSpeakAction?>(null) }
    var pendingCapturePermit by remember(repository) { mutableStateOf<(() -> Boolean)?>(null) }
    var activeCapturePermit by remember(repository) { mutableStateOf<(() -> Boolean)?>(null) }
    LaunchedEffect(readSpeak, capturePolicy.interrupted) {
        if (capturePolicy.interrupted) {
            pendingCaptureAction = null
            pendingCapturePermit = null
            activeCapturePermit = null
            readSpeak.interruptUnsubmittedCapture()
        }
    }
    val sentenceText = remember(route.chapter, route.focusSentenceId) {
        route.chapter.sentences.first { it.sentenceId == route.focusSentenceId }.textEn
    }
    val speaker = rememberConnectionAwareSentenceSpeaker(repository)
    DisposableEffect(speaker, repository) {
        onDispose {
            pendingCaptureAction = null
            pendingCapturePermit = null
            activeCapturePermit = null
            speaker.close()
            repository.cancelCapture()
        }
    }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingCaptureAction
        val permit = pendingCapturePermit
        pendingCaptureAction = null
        pendingCapturePermit = null
        if (pending != null && permit?.invoke() == true && capturePolicy.isAllowed()) {
            if (granted) {
                activeCapturePermit = permit
                readSpeak.onAction(pending)
            } else readSpeak.onAction(ReadSpeakAction.PermissionOrRecognitionFailed)
        }
    }
    LaunchedEffect(repository, state.listening) {
        if (state.listening) {
            val permit = activeCapturePermit
            if (permit?.invoke() != true || !capturePolicy.isAllowed()) {
                readSpeak.interruptUnsubmittedCapture()
                return@LaunchedEffect
            }
            val prepared = repository.prepareCapture()
            if (activeCapturePermit !== permit || !permit() || !capturePolicy.isAllowed()) {
                readSpeak.interruptUnsubmittedCapture()
                return@LaunchedEffect
            }
            if (!prepared || !repository.startCapture()) {
                if (readSpeak.uiState.value.listening) readSpeak.onAction(ReadSpeakAction.PermissionOrRecognitionFailed)
            }
        }
    }
    LaunchedEffect(readSpeak) {
        readSpeak.effects.collect { effect ->
            when (effect) {
                ReadSpeakEffect.ReturnToExplanation -> {
                    repository.cancelCapture()
                    onReturn()
                }
                ReadSpeakEffect.OpenHandwriting -> onHandwriting()
            }
        }
    }
    ReadSpeakScreen(state) { action ->
        if (!capturePolicy.isAllowed()) return@ReadSpeakScreen
        if (action == ReadSpeakAction.PlayToggled) {
            if (state.audioPlaying) {
                speaker.stop()
                readSpeak.onAction(action)
            } else if (speaker.start(sentenceText)) {
                readSpeak.onAction(action)
            }
        } else if (action == ReadSpeakAction.StartRequested || action == ReadSpeakAction.RetryRequested) {
            if (action == ReadSpeakAction.RetryRequested && repository.hasPendingFeedbackRetry()) {
                readSpeak.onAction(action)
                return@ReadSpeakScreen
            }
            val permit = graph.connectionGate.interactionPermit() ?: return@ReadSpeakScreen
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                if (permit()) {
                    activeCapturePermit = permit
                    readSpeak.onAction(action)
                }
            } else {
                pendingCaptureAction = action
                pendingCapturePermit = permit
                permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            readSpeak.onAction(action)
        }
    }
}

@Composable
private fun HandwritingRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.Handwriting,
    onReturn: () -> Unit,
    onDictation: () -> Unit,
) {
    HandwritingCopyEntry(graph.handwritingFactory(route), route, onReturn, onDictation)
}

@Composable
private fun AudioDictationRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.AudioDictation,
    onReturn: () -> Unit,
    onCorrection: (String) -> Unit,
    onSpeaking: () -> Unit,
) {
    LearningContentLoader(route,
        load = { graph.loadLockedHints(route.chapter.chapterId, route.focusSentenceId) }, onReturn = onReturn,
    ) { hints -> AudioDictationReadyRoute(graph, route, hints, onReturn, onCorrection, onSpeaking) }
}

@Composable
private fun AudioDictationReadyRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.AudioDictation,
    hints: List<String>,
    onReturn: () -> Unit,
    onCorrection: (String) -> Unit,
    onSpeaking: () -> Unit,
) {
    AudioDictationEntry(graph.audioDictationFactory(route, hints), route, onReturn, onSpeaking, onCorrection) { state, onAction ->
    val context = LocalContext.current
    val speaker = rememberConnectionAwareSentenceSpeaker()
    val sentenceText = remember(route.chapter, route.focusSentenceId) {
        route.chapter.sentences.first { it.sentenceId == route.focusSentenceId }.textEn
    }
    DisposableEffect(speaker) { onDispose { speaker.close() } }
    LaunchedEffect(state.audioLooping) {
        if (!state.audioLooping) {
            speaker.stop()
        } else {
            while (true) {
                if (!speaker.play(sentenceText, speechRate = 0.72f)) {
                    onAction(AudioDictationAction.AudioPlaybackFailed)
                    break
                }
                kotlinx.coroutines.delay(900L)
            }
        }
    }
    AudioDictationScreen(state, onAction)
    }
}

@Composable
private fun ReadWriteCorrectionRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ReadWriteCorrection,
    onReturn: () -> Unit,
    onSpeaking: () -> Unit,
) {
    LearningContentLoader(route,
        load = { graph.loadLockedHints(route.chapter.chapterId, route.focusSentenceId) }, onReturn = onReturn,
    ) { hints -> ReadWriteCorrectionReadyRoute(graph, route, hints, onReturn, onSpeaking) }
}

@Composable
private fun ReadWriteCorrectionReadyRoute(
    graph: ProductionAppGraph,
    route: HomeDiaryRoute.ReadWriteCorrection,
    hints: List<String>,
    onReturn: () -> Unit,
    onSpeaking: () -> Unit,
) {
    ReadWriteCorrectionEntry(graph.correctionFactory(route, hints), route, onReturn, onSpeaking) { state, onAction ->
    val context = LocalContext.current
    val speaker = rememberConnectionAwareSentenceSpeaker()
    val sentenceText = remember(route.chapter, route.focusSentenceId) {
        route.chapter.sentences.first { it.sentenceId == route.focusSentenceId }.textEn
    }
    DisposableEffect(speaker) { onDispose { speaker.close() } }
    LaunchedEffect(state.audioLooping) {
        if (!state.audioLooping) {
            speaker.stop()
        } else {
            while (true) {
                if (!speaker.play(sentenceText, speechRate = 0.72f)) {
                    onAction(ReadWriteCorrectionAction.AudioPlaybackFailed)
                    break
                }
                kotlinx.coroutines.delay(900L)
            }
        }
    }
    ReadWriteCorrectionScreen(state) { action ->
        if (action == ReadWriteCorrectionAction.AudioToggled && state.audioLooping) {
            speaker.stop()
        }
        onAction(action)
    }
    }
}

@Composable
private fun AuthProductionFlow(
    graph: ProductionAppGraph,
    auth: AuthViewModel,
    authState: com.reallanguage.app.feature.auth.presentation.AuthUiState,
    passwordResetDeepLink: PasswordResetDeepLink? = null,
    onPasswordResetDeepLinkConsumed: () -> Unit = {},
    onAuthenticated: (Boolean) -> Unit,
) {
    val connectionIncident by graph.connectionGate.incident.collectAsState()
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(connectionIncident) {
        if (connectionIncident != null) { focus.clearFocus(force = true); keyboard?.hide() }
    }
    var route by remember {
        mutableStateOf<AuthSubroute>(
            if (passwordResetDeepLink == null) AuthSubroute.Entry else AuthSubroute.PasswordReset,
        )
    }
    LaunchedEffect(passwordResetDeepLink) {
        if (passwordResetDeepLink != null) route = AuthSubroute.PasswordReset
    }
    LaunchedEffect(auth) {
        auth.effects.collect { effect ->
            if (effect is AuthEffect.Authenticated) {
                onAuthenticated(effect.settingsCompleted)
            } else {
                authSubroute(effect)?.let { route = it }
            }
        }
    }

    Box(Modifier.fillMaxSize().then(if (connectionIncident != null) Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics {} else Modifier)) {
    when (val current = route) {
        AuthSubroute.Entry -> AuthScreen(authState, auth::onAction)
        AuthSubroute.AgeIneligible -> AgeIneligibleScreen(
            onReturnToLogin = {
                auth.onAction(
                    com.reallanguage.app.feature.auth.presentation.AuthAction.LoginRequested,
                )
                route = AuthSubroute.Entry
            },
        )
        is AuthSubroute.EmailVerification -> EmailVerificationRoute(
            graph = graph,
            route = current,
            onRegistration = {
                auth.onAction(
                    com.reallanguage.app.feature.auth.presentation.AuthAction.RegistrationRequested,
                )
                route = AuthSubroute.Entry
            },
            onVerified = {
                auth.onAction(
                    com.reallanguage.app.feature.auth.presentation.AuthAction.LoginRequested,
                )
                auth.onAction(
                    com.reallanguage.app.feature.auth.presentation.AuthAction.SubmitRequested,
                )
                route = AuthSubroute.Entry
            },
        )
        is AuthSubroute.Agreement -> AgreementRoute(
            graph = graph,
            route = current,
            draft = RegistrationDraftSnapshot(
                email = authState.email,
                password = authState.password,
                birthYearMonth = authState.birthYearMonth,
                acceptedTerms = authState.acceptedTerms,
                acceptedPrivacyPolicy = authState.acceptedPrivacyPolicy,
            ),
            onRegistration = { route = AuthSubroute.Entry },
        )
        AuthSubroute.PasswordReset -> PasswordResetRoute(
            graph = graph,
            deepLink = passwordResetDeepLink,
            onDeepLinkConsumed = onPasswordResetDeepLinkConsumed,
            onLogin = { route = AuthSubroute.Entry },
            onAuthenticated = onAuthenticated,
        )
    }
    }
    connectionIncident?.let { incident ->
        ConnectionRecoveryEntry(
            graph = graph,
            incident = incident,
            route = route,
            onFlowReturn = {
                when (route) {
                    AuthSubroute.Entry -> if (authState.mode == com.reallanguage.app.feature.auth.presentation.AuthMode.REGISTRATION) {
                        auth.onAction(com.reallanguage.app.feature.auth.presentation.AuthAction.LoginRequested)
                    } else backDispatcher?.onBackPressed()
                    AuthSubroute.AgeIneligible -> {
                        auth.onAction(com.reallanguage.app.feature.auth.presentation.AuthAction.LoginRequested)
                        route = AuthSubroute.Entry
                    }
                    is AuthSubroute.EmailVerification -> {
                        auth.onAction(com.reallanguage.app.feature.auth.presentation.AuthAction.RegistrationRequested)
                        route = AuthSubroute.Entry
                    }
                    else -> backDispatcher?.onBackPressed()
                }
            },
            onLoginRequired = {
                auth.onAction(com.reallanguage.app.feature.auth.presentation.AuthAction.LoginRequested)
                route = AuthSubroute.Entry
            },
            onInitialSettingsRequired = { onAuthenticated(false) },
        )
    }
}

@Composable
private fun PasswordResetRoute(
    graph: ProductionAppGraph,
    deepLink: PasswordResetDeepLink? = null,
    onDeepLinkConsumed: () -> Unit = {},
    onLogin: () -> Unit,
    onAuthenticated: (Boolean) -> Unit,
) {
    // A connection cover keeps this entry alive; actually leaving must discard its requests.
    val owner = rememberEntryViewModelStoreOwner(listOf(graph, "password-reset-entry"))
    val passwordReset: PasswordResetViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "password-reset",
        factory = graph.passwordResetFactory(),
    )
    val state by passwordReset.uiState.collectAsState()
    LaunchedEffect(passwordReset, deepLink) {
        if (deepLink != null) {
            passwordReset.onAction(
                com.reallanguage.app.feature.auth.presentation.PasswordResetAction.VerificationLinkOpened(
                    token = deepLink.bearer,
                    challengeId = deepLink.challengeId,
                ),
            )
            onDeepLinkConsumed()
        }
    }
    LaunchedEffect(passwordReset) {
        passwordReset.effects.collect { effect ->
            when (effect) {
                PasswordResetEffect.OpenLogin -> onLogin()
                PasswordResetEffect.OpenInitialSettings -> onAuthenticated(false)
                PasswordResetEffect.OpenHome -> onAuthenticated(true)
            }
        }
    }
    PasswordResetScreen(state, passwordReset::onAction)
}

@Composable
private fun AgreementRoute(
    graph: ProductionAppGraph,
    route: AuthSubroute.Agreement,
    draft: RegistrationDraftSnapshot,
    onRegistration: () -> Unit,
) {
    val agreement: AgreementDetailViewModel = viewModel(
        key = "agreement:${route.kind}",
        factory = graph.agreementFactory(route.kind, draft),
    )
    val state by agreement.uiState.collectAsState()
    LaunchedEffect(agreement) {
        agreement.effects.collect { effect ->
            if (effect is AgreementDetailEffect.ReturnToRegistration) onRegistration()
        }
    }
    AgreementDetailScreen(state, agreement::onAction)
}

@Composable
private fun EmailVerificationRoute(
    graph: ProductionAppGraph,
    route: AuthSubroute.EmailVerification,
    onRegistration: () -> Unit,
    onVerified: () -> Unit,
) {
    val owner = rememberEntryViewModelStoreOwner(listOf(graph, "verification", route.registrationId))
    val incident by graph.connectionGate.incident.collectAsState()
    val verification: EmailVerificationViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "verification:${route.registrationId}",
        factory = graph.emailVerificationFactory(route.registrationId, route.email),
    )
    val state by verification.uiState.collectAsState()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(verification, lifecycleOwner, incident) {
        verification.setConnectionBlocked(incident != null)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> verification.startStatusMonitoring()
                Lifecycle.Event.ON_STOP -> verification.stopStatusMonitoring()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            verification.startStatusMonitoring()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            verification.stopStatusMonitoring()
        }
    }
    LaunchedEffect(verification) {
        verification.effects.collect { effect ->
            when (effect) {
                EmailVerificationEffect.OpenRegistration -> onRegistration()
                EmailVerificationEffect.CompleteRegistration -> onVerified()
            }
        }
    }
    EmailVerificationScreen(state, verification::onAction)
}

@Composable
private fun ProductionLoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(RlColor.Background.copy(alpha = 0f)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = RlColor.Primary)
    }
}

@Composable
private fun StartupConnectionErrorScreen(onRetry: () -> Unit) {
    com.reallanguage.app.feature.error.presentation.StartupConnectionScreen(onRetry)
}

private fun <T : ViewModel> viewModelFactory(create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
    }
