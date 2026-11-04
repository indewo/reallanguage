package com.reallanguage.app.core.app

import com.reallanguage.app.feature.formation.domain.SemanticUnit
import com.reallanguage.app.feature.formation.domain.ChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.QueryableExpression
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.SelectableExpression
import com.reallanguage.app.feature.learning.domain.ChapterSelectionBoundary
import com.reallanguage.app.feature.learning.domain.CompletedStepSnapshot
import com.reallanguage.app.feature.safety.domain.SafetyCaseDetails
import com.reallanguage.app.feature.safety.domain.SourceType
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.review.domain.ReviewAbility
import com.reallanguage.app.feature.review.domain.ReviewChapterSnapshot
import com.reallanguage.app.feature.scene.domain.SceneAnswer
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneGeneratedDraft
import com.reallanguage.app.feature.scene.domain.SceneQuestionSession
import com.reallanguage.app.feature.scene.domain.SceneStart
import com.reallanguage.app.feature.billing.domain.BalanceInsufficientContext
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft

sealed interface HomeDiaryRoute {
    data object Home : HomeDiaryRoute
    data object TodayReview : HomeDiaryRoute
    data object VocabularyBook : HomeDiaryRoute
    data object PatternBook : HomeDiaryRoute
    data class IncompleteLearning(
        val entryId: String = java.util.UUID.randomUUID().toString(),
    ) : HomeDiaryRoute
    data class Messages(val entryId: String = java.util.UUID.randomUUID().toString()) : HomeDiaryRoute
    data class ManualReviewResult(val reviewRequestId: String, val entryId: String = java.util.UUID.randomUUID().toString()) : HomeDiaryRoute
    data class Account(val entryId: String = java.util.UUID.randomUUID().toString()) : HomeDiaryRoute
    data class Settings(val entryId: String = java.util.UUID.randomUUID().toString()) : HomeDiaryRoute
    data class SettingsEffect(
        val original: InitialSettingsDraft,
        val proposed: InitialSettingsDraft,
        val entryId: String = java.util.UUID.randomUUID().toString(),
        val originalReminder: com.reallanguage.app.feature.settings.domain.ReminderSettingsDraft? = null,
        val proposedReminder: com.reallanguage.app.feature.settings.domain.ReminderSettingsDraft? = null,
        val returnRoute: HomeDiaryRoute? = null,
    ) : HomeDiaryRoute
    data class Billing(val entryId: String = java.util.UUID.randomUUID().toString()) : HomeDiaryRoute
    data class Recharge(
        val origin: HomeDiaryRoute,
        val entryId: String = java.util.UUID.randomUUID().toString(),
    ) : HomeDiaryRoute
    data class Refund(val entryId: String = java.util.UUID.randomUUID().toString()) : HomeDiaryRoute
    data class InsufficientBalance(
        val origin: HomeDiaryRoute,
        val context: BalanceInsufficientContext,
        val entryId: String = java.util.UUID.randomUUID().toString(),
    ) : HomeDiaryRoute
    data class LocalRecordings(val entryId: String = java.util.UUID.randomUUID().toString()) : HomeDiaryRoute
    data class ResumeLearning(val chapterId: String, val moduleId: String, val stepId: String?) : HomeDiaryRoute
    data object RecordingIntro : HomeDiaryRoute
    data class ActiveRecording(
        val entryId: String = java.util.UUID.randomUUID().toString(),
        val returnRoute: HomeDiaryRoute = Home,
    ) : HomeDiaryRoute
    data class RecordingConfirmation(val recordingId: String, val returnRoute: HomeDiaryRoute? = null) : HomeDiaryRoute
    data class RecordingTranscription(
        val recordingId: String,
        val durationSeconds: Int,
        val endReason: com.reallanguage.app.feature.recording.domain.RecordingEndReason = com.reallanguage.app.feature.recording.domain.RecordingEndReason.USER_STOPPED,
        val entryId: String = java.util.UUID.randomUUID().toString(),
        val returnRoute: HomeDiaryRoute? = null,
    ) : HomeDiaryRoute
    data class TranscriptResult(
        val recordingId: String,
        val transcriptId: String,
        val returnRoute: HomeDiaryRoute? = null,
    ) : HomeDiaryRoute
    data class TranscriptionFailure(val recordingId: String, val durationSeconds: Int,
        val endReason: com.reallanguage.app.feature.recording.domain.RecordingEndReason = com.reallanguage.app.feature.recording.domain.RecordingEndReason.USER_STOPPED,
        val returnRoute: HomeDiaryRoute? = null,
    ) : HomeDiaryRoute
    data class MicrophonePermission(
        val canRequestDirectly: Boolean,
        val returnRoute: HomeDiaryRoute = Home,
    ) : HomeDiaryRoute
    data class SceneHome(val preservedStart: SceneStart? = null) : HomeDiaryRoute
    data class SceneDirectDescription(
        val text: String = "",
        val entryId: String = java.util.UUID.randomUUID().toString(),
    ) : HomeDiaryRoute
    data class SceneTypeSelection(val start: SceneStart) : HomeDiaryRoute
    data class SceneQuestions(
        val start: SceneStart,
        val contentType: SceneContentType,
        val operationId: String = java.util.UUID.randomUUID().toString(),
        val initialAnswers: List<SceneAnswer> = emptyList(),
    ) : HomeDiaryRoute
    data class SceneGeneration(
        val session: SceneQuestionSession,
        val entryId: String = java.util.UUID.randomUUID().toString(),
        val previousUnits: List<com.reallanguage.app.feature.scene.domain.SceneDraftUnit> = emptyList(),
        /** Only an explicit unchanged-answer submission may resume this failed attempt. */
        val retryOnEntry: Boolean = false,
    ) : HomeDiaryRoute
    data class SceneChineseReview(
        val session: SceneQuestionSession,
        val draft: SceneGeneratedDraft,
    ) : HomeDiaryRoute
    data class ReviewChapter(
        val reviewChapterId: String,
        val entryId: String = java.util.UUID.randomUUID().toString(),
    ) : HomeDiaryRoute
    data class ReviewAbilityExecution(
        val reviewChapterId: String,
        val entryId: String,
        val chapter: ReviewChapterSnapshot,
        val ability: ReviewAbility,
    ) : HomeDiaryRoute
    data class ReviewResult(val reviewSessionId: String) : HomeDiaryRoute
    data class Input(val text: String = "") : HomeDiaryRoute
    data class SafetyCheck(
        val text: String,
        val sourceType: SourceType = SourceType.DIARY,
        val contentShape: SourceContentShape = SourceContentShape.ARTICLE,
        val returnRoute: HomeDiaryRoute? = null,
        val entryId: String = java.util.UUID.randomUUID().toString(),
    ) : HomeDiaryRoute
    data class SemanticSplit(val text: String, val sourceType: SourceType = SourceType.DIARY, val contentShape: SourceContentShape = SourceContentShape.ARTICLE, val sourceReturnRoute: HomeDiaryRoute? = null, val entryId: String = java.util.UUID.randomUUID().toString()) : HomeDiaryRoute
    data class FocusSelection(val text: String, val units: List<SemanticUnit>, val sourceType: SourceType = SourceType.DIARY, val contentShape: SourceContentShape = SourceContentShape.ARTICLE, val sourceReturnRoute: HomeDiaryRoute? = null, val initialSelectedUnitIds: Set<String> = emptySet(), val sourceEntryId: String = java.util.UUID.randomUUID().toString()) : HomeDiaryRoute
    data class SafetyHandlingLoading(val safetyCaseId: String, val text: String, val returnRoute: HomeDiaryRoute? = null) : HomeDiaryRoute
    data class SafetyHandling(val details: SafetyCaseDetails, val returnRoute: HomeDiaryRoute? = null) : HomeDiaryRoute
    data class ReviewPending(val sourceType: SourceType) : HomeDiaryRoute
    data class ChapterFormation(val text: String, val units: List<SemanticUnit>, val selectedUnitIds: List<String>, val sourceType: SourceType = SourceType.DIARY, val contentShape: SourceContentShape = SourceContentShape.ARTICLE, val sourceReturnRoute: HomeDiaryRoute? = null, val sourceEntryId: String = java.util.UUID.randomUUID().toString()) : HomeDiaryRoute
    data class EnglishGeneration(val text: String, val units: List<SemanticUnit>, val selectedUnitIds: List<String>, val chapters: List<ChapterDraft>, val sourceType: SourceType = SourceType.DIARY, val contentShape: SourceContentShape = SourceContentShape.ARTICLE, val sourceReturnRoute: HomeDiaryRoute? = null, val sourceEntryId: String = java.util.UUID.randomUUID().toString()) : HomeDiaryRoute
    data class EnglishConfirmation(val text: String, val units: List<SemanticUnit>, val chapters: List<EnglishChapterDraft>, val sourceType: SourceType = SourceType.DIARY, val contentShape: SourceContentShape = SourceContentShape.ARTICLE, val sourceReturnRoute: HomeDiaryRoute? = null, val sourceEntryId: String = java.util.UUID.randomUUID().toString(), val initialSelectedCandidateIds: Map<String, String> = emptyMap()) : HomeDiaryRoute
    data class LearningTargets(val text: String, val units: List<SemanticUnit>, val chapters: List<EnglishChapterDraft>, val selections: Map<String, String>, val sourceType: SourceType = SourceType.DIARY, val contentShape: SourceContentShape = SourceContentShape.ARTICLE, val sourceReturnRoute: HomeDiaryRoute? = null, val sourceEntryId: String = java.util.UUID.randomUUID().toString()) : HomeDiaryRoute
    data class LearningTargetSelection(val text: String, val units: List<SemanticUnit>, val chapters: List<EnglishChapterDraft>, val selections: Map<String, String>, val targets: List<LearningTarget>, val queryableExpressions: List<QueryableExpression>, val initialSelectedTargetIds: Set<String>? = null, val sourceType: SourceType = SourceType.DIARY, val contentShape: SourceContentShape = SourceContentShape.ARTICLE, val entryId: String = java.util.UUID.randomUUID().toString(), val sourceReturnRoute: HomeDiaryRoute? = null, val sourceEntryId: String = java.util.UUID.randomUUID().toString(), val initialSentenceIndex: Int = 0) : HomeDiaryRoute
    data class FinalFix(val text: String, val units: List<SemanticUnit>, val chapters: List<EnglishChapterDraft>, val selections: Map<String, String>, val targets: List<LearningTarget>, val selectedTargetIds: Set<String>, val sourceType: SourceType = SourceType.DIARY, val contentShape: SourceContentShape = SourceContentShape.ARTICLE, val entryId: String = java.util.UUID.randomUUID().toString(), val sourceReturnRoute: HomeDiaryRoute? = null, val sourceEntryId: String = java.util.UUID.randomUUID().toString()) : HomeDiaryRoute
    data class ChapterLearning(
        val chapterId: String,
        /** The visible node that opened this chapter entry. */
        val returnRoute: HomeDiaryRoute = Home,
    ) : HomeDiaryRoute
    data class ChapterExplanation(val chapter: FixedLearningChapter, val focusSentenceId: String, val entryId: String = java.util.UUID.randomUUID().toString()) : HomeDiaryRoute
    data class ExplanationReading(val chapter: FixedLearningChapter, val focusSentenceId: String, val baseExplanationZh: String, val expressions: List<SelectableExpression>, val entryId: String = java.util.UUID.randomUUID().toString()) : HomeDiaryRoute
    data class ReadSpeak(
        val chapter: FixedLearningChapter,
        val focusSentenceId: String,
        val baseExplanationZh: String,
        val expressions: List<SelectableExpression>,
    ) : HomeDiaryRoute
    data class Handwriting(
        val chapter: FixedLearningChapter,
        val focusSentenceId: String,
        val baseExplanationZh: String,
        val expressions: List<SelectableExpression>,
    ) : HomeDiaryRoute
    data class AudioDictation(
        val chapter: FixedLearningChapter,
        val focusSentenceId: String,
        val baseExplanationZh: String,
        val expressions: List<SelectableExpression>,
        val input: String = "",
    ) : HomeDiaryRoute
    data class ReadWriteCorrection(
        val chapter: FixedLearningChapter,
        val focusSentenceId: String,
        val baseExplanationZh: String,
        val expressions: List<SelectableExpression>,
        val dictationInput: String,
    ) : HomeDiaryRoute
    data class FullSentenceSpeaking(
        val chapter: FixedLearningChapter,
        val focusSentenceId: String,
        val baseExplanationZh: String,
        val expressions: List<SelectableExpression>,
    ) : HomeDiaryRoute
    data class PatternSpeaking(
        val chapter: FixedLearningChapter,
        val focusSentenceId: String,
        val baseExplanationZh: String,
        val expressions: List<SelectableExpression>,
    ) : HomeDiaryRoute
    data class CompleteWriting(
        val chapter: FixedLearningChapter,
        val focusSentenceId: String,
        val baseExplanationZh: String,
        val expressions: List<SelectableExpression>,
    ) : HomeDiaryRoute
    data class SceneResponse(
        val chapter: FixedLearningChapter,
        val focusSentenceId: String,
        val baseExplanationZh: String,
        val expressions: List<SelectableExpression>,
    ) : HomeDiaryRoute
    data class ChapterResult(
        val chapter: FixedLearningChapter,
        val returnRoute: HomeDiaryRoute? = null,
    ) : HomeDiaryRoute
    data class ChapterSelection(
        val boundary: ChapterSelectionBoundary,
        val returnRoute: HomeDiaryRoute? = null,
    ) : HomeDiaryRoute
    data class CompletedStepReview(
        val snapshot: CompletedStepSnapshot,
        val currentTask: HomeDiaryRoute,
    ) : HomeDiaryRoute
    data class ChapterAbandonConfirmation(
        val chapterId: String,
        val returnRoute: HomeDiaryRoute,
    ) : HomeDiaryRoute
}

sealed interface HomeDiaryEvent {
    data object OpenDiary : HomeDiaryEvent
    data object ReturnHome : HomeDiaryEvent
    data class Submit(val text: String) : HomeDiaryEvent
    data class SafetyPassed(val text: String) : HomeDiaryEvent
    data class SplitReady(val text: String, val units: List<SemanticUnit>) : HomeDiaryEvent
}

fun homeDiaryRoute(current: HomeDiaryRoute, event: HomeDiaryEvent): HomeDiaryRoute =
    when {
        current is HomeDiaryRoute.Home && event is HomeDiaryEvent.OpenDiary ->
            HomeDiaryRoute.Input()
        current is HomeDiaryRoute.Input && event is HomeDiaryEvent.ReturnHome ->
            HomeDiaryRoute.Home
        current is HomeDiaryRoute.Input && event is HomeDiaryEvent.Submit ->
            HomeDiaryRoute.SafetyCheck(event.text)
        current is HomeDiaryRoute.SafetyCheck && event is HomeDiaryEvent.SafetyPassed ->
            HomeDiaryRoute.SemanticSplit(
                event.text, current.sourceType, current.contentShape,
                sourceReturnRoute = safetySourceEditRoute(
                    com.reallanguage.app.feature.safety.domain.ConfirmedSource(event.text, current.sourceType, current.contentShape),
                    current.returnRoute,
                ) ?: current.returnRoute,
                entryId = current.entryId,
            )
        current is HomeDiaryRoute.SemanticSplit && event is HomeDiaryEvent.SplitReady ->
            HomeDiaryRoute.FocusSelection(event.text, event.units, current.sourceType, current.contentShape, sourceReturnRoute = current.sourceReturnRoute, sourceEntryId = current.entryId)
        else -> current
    }
