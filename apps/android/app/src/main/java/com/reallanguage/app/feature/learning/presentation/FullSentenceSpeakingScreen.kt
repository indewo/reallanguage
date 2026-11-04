package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlKeyboardAwareOutlinedTextField
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SpeakingTrainingMode
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackAspect
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackDetail
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackEvidence

@Composable
fun FullSentenceSpeakingScreen(
    state: FullSentenceSpeakingUiState,
    onAction: (FullSentenceSpeakingAction) -> Unit,
) {
    val retryCompletedCheckpoint = state.finalDecision == SpeakingEvaluationDecision.PASS && state.progressFailed
    val attemptTitle = when (state.trainingMode) {
        SpeakingTrainingMode.BILINGUAL_FOLLOW -> "中英跟读 · 第 ${state.attemptIndex + 1} 次"
        SpeakingTrainingMode.ENGLISH_READING -> "英文朗读 · 第 ${state.attemptIndex + 1} 次"
        null -> "独立复述 · 第 ${state.attemptIndex + 1} 次"
    }
    ChapterLearningFrame(
        chapter = state.chapter,
        currentFocusSentenceId = state.currentFocusSentenceId,
        stageTitle = stringResource(R.string.full_sentence_speaking_title),
        onBack = { onAction(FullSentenceSpeakingAction.BackRequested) },
        leftPaneWeight = .80f,
        rightPaneWeight = 1.40f,
        leftArtworkRes = R.drawable.rl_v73_learning_empty_meeting_polaroid,
        leftArtworkPreferredHeight = 176.dp,
        leftArtworkOverlayRes = R.drawable.rl_scene_response_microphone,
        leftArtworkOverlaySize = 108.dp,
        leftArtworkOverlayReserve = 62.dp,
        modulePaperMinHeight = 204.dp,
        showCurrentFocusChinese = state.showChinese,
        showCurrentFocusEnglish = state.showEnglish,
        leftPanelContent = {
          Box(
              Modifier.fillMaxWidth().testTag("full-sentence-attempt-module"),
              contentAlignment = Alignment.Center,
          ) {
                Text(
                    attemptTitle,
                    modifier = Modifier.fillMaxWidth()
                        .testTag("full-sentence-attempt"),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = RlColor.OnPrimary,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = EditorialFont.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                )
            if (state.recording) {
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd).testTag("full-sentence-recording-status"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(18.dp), tint = RlColor.OnPrimary)
                    Text("正在收音", color = RlColor.OnPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
          }
        },
        footer = {
            OutlinedButton(
                onClick = { onAction(FullSentenceSpeakingAction.AnswerAudioRequested) },
                enabled = !state.submitting && !state.evaluationReceived && !state.technicalRetryPending && !state.technicalRetryBlocked,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.PlayCircleOutline, contentDescription = null)
                Text(stringResource(R.string.full_sentence_speaking_play_answer))
            }
            RlPrimaryButton(
                text = stringResource(
                    when {
                        retryCompletedCheckpoint -> R.string.full_sentence_speaking_retry_save
                        state.technicalRetryPending -> R.string.read_speak_retry
                        state.technicalRetryBlocked -> R.string.full_sentence_speaking_new_recording
                        state.evaluationReceived && state.finalDecision == null -> R.string.full_sentence_speaking_retry_save
                        state.transcriptReadyForConfirmation && state.finalDecision == null ->
                            R.string.full_sentence_speaking_confirm_transcript
                        state.trainingCanContinue -> R.string.full_sentence_speaking_continue
                        state.finalDecision == SpeakingEvaluationDecision.FAIL ->
                            R.string.full_sentence_speaking_retry
                        state.finalDecision == SpeakingEvaluationDecision.INCONCLUSIVE ->
                            R.string.read_speak_retry
                        state.recording && state.isIndependentRetelling ->
                            R.string.full_sentence_speaking_retell_finished
                        state.recording && state.trainingMode == SpeakingTrainingMode.BILINGUAL_FOLLOW ->
                            R.string.full_sentence_speaking_follow_finished
                        state.recording -> R.string.full_sentence_speaking_read_finished
                        state.isIndependentRetelling -> R.string.full_sentence_speaking_start_retell
                        state.trainingMode == SpeakingTrainingMode.BILINGUAL_FOLLOW ->
                            R.string.full_sentence_speaking_start_follow
                        else -> R.string.full_sentence_speaking_start_read
                    },
                ),
                enabled = when {
                    retryCompletedCheckpoint -> !state.submitting
                    state.transcriptReadyForConfirmation && state.finalDecision == null ->
                        state.transcript.isNotBlank() && !state.submitting
                    state.trainingCanContinue -> true
                    state.finalDecision == SpeakingEvaluationDecision.FAIL ||
                        state.finalDecision == SpeakingEvaluationDecision.INCONCLUSIVE -> true
                    else -> !state.submitting && state.finalDecision == null
                },
                onClick = {
                    onAction(
                        when {
                            retryCompletedCheckpoint -> FullSentenceSpeakingAction.RetryRequested
                            state.technicalRetryPending -> FullSentenceSpeakingAction.RetryRequested
                            state.transcriptReadyForConfirmation && state.finalDecision == null ->
                                FullSentenceSpeakingAction.ConfirmTranscriptRequested
                            state.trainingCanContinue ->
                                FullSentenceSpeakingAction.ContinueTrainingRequested
                            state.finalDecision == SpeakingEvaluationDecision.FAIL ||
                                state.finalDecision == SpeakingEvaluationDecision.INCONCLUSIVE ->
                                FullSentenceSpeakingAction.RetryRequested
                            state.recording -> FullSentenceSpeakingAction.FinishedRequested
                            else -> FullSentenceSpeakingAction.StartRequested
                        },
                    )
                },
                modifier = Modifier.weight(1.4f).heightIn(min = 48.dp),
                containerColor = RlColor.ActionVivid,
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().testTag("full-sentence-workspace"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (state.progressFailed) Text(stringResource(R.string.full_sentence_speaking_progress_failed), color = RlColor.Error)
            if (state.isIndependentRetelling) {
                if (state.decisionSaveFailed) Text(stringResource(R.string.full_sentence_speaking_decision_save_failed), color = RlColor.Error)
                if (state.needsNewRecording) Text(stringResource(R.string.full_sentence_speaking_needs_recording), color = RlColor.TextSecondary)
                Text(
                    stringResource(R.string.full_sentence_speaking_scene,
                        state.chapter.sentences.joinToString("\n") { it.textZh }),
                    modifier = Modifier.testTag("full-sentence-scene"),
                    color = RlColor.Ink,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.sp, lineHeight = 18.sp),
                )
                RlKeyboardAwareOutlinedTextField(
                    value = state.transcript,
                    onValueChange = { onAction(FullSentenceSpeakingAction.TranscriptChanged(it)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    label = { Text(stringResource(R.string.full_sentence_speaking_transcript)) },
                    enabled = !state.submitting && !state.evaluationReceived && !state.technicalRetryPending && !state.technicalRetryBlocked,
                    shape = RlShape.Input,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = RlColor.Ink,
                        unfocusedTextColor = RlColor.Ink,
                        disabledTextColor = RlColor.Ink,
                        focusedLabelColor = RlColor.Primary,
                        unfocusedLabelColor = RlColor.TextSecondary,
                        disabledLabelColor = RlColor.TextSecondary,
                        cursorColor = RlColor.Primary,
                    ),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
            ) {
                repeat(5) { index ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = if (index == state.attemptIndex) RlColor.ActionVivid
                        else RlColor.NoteContainer,
                        shape = RlShape.Small,
                    ) {
                        Text(
                            "第 ${index + 1} 次",
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = if (index == state.attemptIndex) RlColor.OnPrimary else RlColor.Ink,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
            if (state.answerAudioPlayed && state.isIndependentRetelling) {
                Text(
                    stringResource(R.string.full_sentence_speaking_answer_disqualifies),
                    color = RlColor.TextSecondary,
                )
            }
            if (state.answerPlaybackFailed) {
                Text(stringResource(R.string.full_sentence_speaking_answer_failed), color = RlColor.Error)
            }
            if (state.transcriptReadyForConfirmation && state.lowConfidenceSegments.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.full_sentence_speaking_low_confidence,
                        state.lowConfidenceSegments.joinToString("、"),
                    ),
                    color = RlColor.TextSecondary,
                )
            }
            state.feedbackZh?.let { feedback ->
                if (state.trainingMode != null) {
                    AiReadingFeedback(
                        summary = feedback,
                        details = state.feedbackDetails,
                        confidence = state.feedbackConfidence,
                    )
                } else {
                    Text(feedback, color = RlColor.Ink)
                }
            }
            if (state.technicalRetryBlocked) {
                Text(stringResource(R.string.full_sentence_speaking_retry_blocked), color = RlColor.Error)
            } else if ((!state.technicalRetryPending && state.captureFailed && !state.decisionSaveFailed) ||
                state.finalDecision == SpeakingEvaluationDecision.INCONCLUSIVE) {
                Text(
                    stringResource(R.string.full_sentence_speaking_retry_capture),
                    color = RlColor.Error,
                )
            }
            if (state.finalDecision == SpeakingEvaluationDecision.FAIL) {
                TextButton(
                    onClick = { onAction(FullSentenceSpeakingAction.StopPractisingRequested) },
                ) { Text(stringResource(R.string.full_sentence_speaking_stop)) }
            }
        }
    }
}

@Composable
private fun AiReadingFeedback(
    summary: String,
    details: List<ReadingFeedbackDetail>,
    confidence: Double?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("full-sentence-ai-feedback"),
        color = RlColor.PaperWarm,
        shape = RlShape.Small,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = RlColor.Primary,
                )
                Text(
                    "AI 朗读反馈",
                    color = RlColor.Ink,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                )
                Text(
                    "基于本次录音与识别文本",
                    modifier = Modifier.weight(1f),
                    color = RlColor.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            Text(
                summary,
                modifier = Modifier.testTag("full-sentence-ai-summary"),
                color = RlColor.Ink,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            if (details.isEmpty()) {
                Text(
                    "旧记录只保留了本轮总评；下一次录音会显示词或短语、节奏和完整度依据。",
                    color = RlColor.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            } else {
                details.forEachIndexed { index, detail ->
                    if (index > 0) androidx.compose.material3.HorizontalDivider(color = RlColor.Divider)
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("full-sentence-ai-detail-${detail.aspect.name}"),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Surface(color = detail.aspect.accentColor(), shape = RlShape.Small) {
                            Text(
                                detail.aspect.label(),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = RlColor.Ink,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                detail.targetText?.let { target ->
                                    Text(
                                        "“$target”  ",
                                        color = RlColor.Primary,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Text(
                                    detail.observationZh,
                                    color = RlColor.Ink,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                )
                            }
                            Text(
                                "建议：${detail.suggestionZh}",
                                color = RlColor.TextSecondary,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                            )
                        }
                        Text(
                            detail.evidence.label(),
                            color = RlColor.TextSecondary,
                            fontSize = 9.sp,
                            lineHeight = 13.sp,
                        )
                    }
                }
            }
            confidence?.let {
                Text(
                    "AI 反馈置信度：${if (it >= .8) "较高" else if (it >= .6) "一般" else "有限"} · 仅供本轮练习参考",
                    color = RlColor.TextSecondary,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                )
            }
        }
    }
}

private fun ReadingFeedbackAspect.label() = when (this) {
    ReadingFeedbackAspect.WORD_OR_PHRASE -> "词/短语"
    ReadingFeedbackAspect.RHYTHM -> "节奏"
    ReadingFeedbackAspect.COMPLETENESS -> "完整度"
}

private fun ReadingFeedbackAspect.accentColor() = when (this) {
    ReadingFeedbackAspect.WORD_OR_PHRASE -> RlColor.PrimaryContainer
    ReadingFeedbackAspect.RHYTHM -> RlColor.NoteContainer
    ReadingFeedbackAspect.COMPLETENESS -> RlColor.SecondaryContainer
}

private fun ReadingFeedbackEvidence.label() = when (this) {
    ReadingFeedbackEvidence.AUDIO -> "原音依据"
    ReadingFeedbackEvidence.TRANSCRIPT -> "识别文本"
    ReadingFeedbackEvidence.GENERAL -> "练习建议"
}
