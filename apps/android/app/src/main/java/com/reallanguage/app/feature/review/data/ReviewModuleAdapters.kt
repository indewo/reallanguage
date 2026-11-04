package com.reallanguage.app.feature.review.data

import com.reallanguage.app.feature.learning.domain.CompleteWritingRepository
import com.reallanguage.app.feature.learning.domain.CompleteWritingSaveResult
import com.reallanguage.app.feature.learning.domain.FinalWritingBatchResult
import com.reallanguage.app.feature.learning.domain.FullSentenceSpeakingRepository
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackResult
import com.reallanguage.app.feature.learning.domain.SceneEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SceneEvaluationResult
import com.reallanguage.app.feature.learning.domain.SceneResponseRepository
import com.reallanguage.app.feature.learning.domain.SceneResponseSaveResult
import com.reallanguage.app.feature.learning.domain.SceneTaskGenerationResult
import com.reallanguage.app.feature.learning.domain.SceneTrainingFeedbackResult
import com.reallanguage.app.feature.learning.domain.SpeakingDecisionSaveResult
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationResult
import com.reallanguage.app.feature.learning.domain.SpeakingTrainingMode
import com.reallanguage.app.feature.learning.domain.SpeakingTranscriptionResult
import com.reallanguage.app.feature.review.domain.ReviewAbility
import com.reallanguage.app.feature.review.domain.ReviewAbilityModule
import com.reallanguage.app.feature.review.domain.ReviewAbilityResultDraft
import com.reallanguage.app.feature.review.domain.ReviewResultQualification
import com.reallanguage.app.feature.learning.domain.WritingTargetType

class ReviewWritingAdapter : CompleteWritingRepository {
    var result: FinalWritingBatchResult? = null
        private set

    override suspend fun saveFinalBatch(
        chapterId: String,
        result: FinalWritingBatchResult,
    ): CompleteWritingSaveResult {
        this.result = result
        return CompleteWritingSaveResult.Saved
    }

    fun resultDraft(ability: ReviewAbility, evidenceId: String): ReviewAbilityResultDraft? {
        if (ability.abilityId.isBlank() || evidenceId.isBlank() || !ability.module.supportsWritingPractice) return null
        val saved = result ?: return null
        if (saved == FinalWritingBatchResult.SupportedLearningOnly) {
            return ReviewAbilityResultDraft(ability.abilityId, evidenceId, false, ReviewResultQualification.SUPPORTED)
        }
        val passed = if (saved is FinalWritingBatchResult.Valid) {
            val sentence = saved.sentenceResults.singleOrNull { it.sentenceId == ability.sentenceId } ?: return null
            when (ability.module) {
                ReviewAbilityModule.COMPLETE_WRITING -> sentence.wholeSentencePassed
                else -> {
                    val type = if (ability.module == ReviewAbilityModule.WORD_MEANING) WritingTargetType.MEANING else WritingTargetType.PATTERN
                    sentence.targetResults.singleOrNull { it.targetId == ability.targetId && it.type == type }?.passed ?: return null
                }
            }
        } else return null
        return ReviewAbilityResultDraft(ability.abilityId, evidenceId, passed)
    }
}

class ReviewSpeakingAdapter(
    private val delegate: FullSentenceSpeakingRepository,
) : FullSentenceSpeakingRepository by delegate {
    var decision: SpeakingEvaluationDecision? = null
        private set

    override suspend fun saveIndependentDecision(
        sentenceId: String,
        decision: SpeakingEvaluationDecision,
    ): SpeakingDecisionSaveResult {
        this.decision = decision
        return SpeakingDecisionSaveResult.Saved
    }
}

class ReviewSceneResponseAdapter(
    private val delegate: SceneResponseRepository,
) : SceneResponseRepository {
    var decision: SceneEvaluationDecision? = null
        private set

    override suspend fun generateTasks(chapter: com.reallanguage.app.feature.learning.domain.FixedLearningChapter): SceneTaskGenerationResult =
        delegate.restoreTasks(chapter)
    override suspend fun restoreTasks(chapter: com.reallanguage.app.feature.learning.domain.FixedLearningChapter): SceneTaskGenerationResult =
        delegate.restoreTasks(chapter)
    override fun startCapture(): Boolean = delegate.startCapture()
    override fun cancelCapture() = delegate.cancelCapture()
    override fun hasPendingTranscriptionRetry(): Boolean = delegate.hasPendingTranscriptionRetry()
    override fun hasPendingEvaluationRetry(): Boolean = delegate.hasPendingEvaluationRetry()
    override fun technicalRetryBlocked(): Boolean = delegate.technicalRetryBlocked()
    override suspend fun transcribeResponse(taskId: String): SpeakingTranscriptionResult =
        delegate.transcribeResponse(taskId)
    override suspend fun trainingFeedback(
        taskId: String,
        roundIndex: Int,
        confirmedTranscript: String,
    ): SceneTrainingFeedbackResult = delegate.trainingFeedback(taskId, roundIndex, confirmedTranscript)
    override suspend fun evaluateFinalRound(
        taskId: String,
        confirmedTranscript: String,
    ): SceneEvaluationResult = delegate.evaluateFinalRound(taskId, confirmedTranscript)
    override suspend fun saveFinalDecision(
        chapterId: String,
        sentenceId: String,
        decision: SceneEvaluationDecision,
    ): SceneResponseSaveResult {
        this.decision = decision
        return SceneResponseSaveResult.Saved
    }
}
