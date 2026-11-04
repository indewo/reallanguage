package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlKeyboardAwareOutlinedTextField
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.feature.learning.domain.FinalWritingBatchResult
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence

@Composable
fun CompleteWritingScreen(
    state: CompleteWritingUiState,
    onAction: (CompleteWritingAction) -> Unit,
) {
    val finalBatch = state.phase == CompleteWritingPhase.FINAL_BATCH
    val focusManager = LocalFocusManager.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    ChapterWritingFrame(
        chapter = state.chapter,
        currentFocusSentenceId = state.currentFocusSentenceId,
        stageTitle = stringResource(R.string.complete_writing_title),
        hideAllFocusEnglish = finalBatch,
        hideCurrentFocusEnglish = !state.helpUsed,
        workspaceMinHeight = 300.dp,
        centerLeftPanelContent = true,
        onBack = { onAction(CompleteWritingAction.BackRequested) },
        leftPanelContent = {
            Text(
                if (finalBatch) "根据中文连续写出全部重点句。" else "根据中文写出当前重点句。",
                color = RlColor.OnPrimary,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = EditorialFont.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    lineHeight = 23.sp,
                ),
            )
            if (state.helpUsed) Text(
                "提示已查看",
                modifier = Modifier.padding(top = RlSpace.Space8),
                color = RlColor.OnPrimary.copy(alpha = .82f),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = EditorialFont.Serif),
            )
        },
        footer = {
            CompleteWritingActions(state, onAction, beforeSubmit = { focusManager.clearFocus() })
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().testTag("complete-writing-workspace"),
            verticalArrangement = Arrangement.spacedBy(RlSpace.Space4),
        ) {
            if (!keyboardVisible) CompleteWritingStageHeader(state, finalBatch)

            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .testTag("complete-writing-scroll-content"),
                verticalArrangement = Arrangement.spacedBy(RlSpace.Space4),
            ) {
              // In the reduced IME window, keep this explanation reachable without
              // taking fixed height away from the actual line being edited.
              if (keyboardVisible) CompleteWritingStageHeader(state, finalBatch)
              CompleteWritingStatus(state, finalBatch, onAction)
              if (finalBatch) {
                  state.focusSentences.forEachIndexed { index, sentence ->
                      FinalWritingRow(
                          index = index + 1,
                          sentence = sentence,
                          value = state.finalInputs[sentence.sentenceId].orEmpty(),
                          helpUsed = state.helpUsed,
                          enabled = !state.saving && state.finalResult == null,
                          onValueChange = {
                              onAction(CompleteWritingAction.FinalInputChanged(sentence.sentenceId, it))
                          },
                          onAudio = {
                              onAction(CompleteWritingAction.FinalAudioRequested(sentence.sentenceId))
                          },
                      )
                  }
              } else {
                  CompleteWritingSingleInput(state, onAction)
              }
            }
        }
    }
}

@Composable
private fun CompleteWritingSingleInput(state: CompleteWritingUiState, onAction: (CompleteWritingAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 360.dp * LocalDensity.current.fontScale.coerceAtMost(1.5f)
        val editor: @Composable (Modifier) -> Unit = { modifier ->
            RlKeyboardAwareOutlinedTextField(
                value = state.input,
                onValueChange = { onAction(CompleteWritingAction.InputChanged(it)) },
                modifier = modifier.heightIn(min = 140.dp).testTag("complete-writing-input-single"),
                label = { Text(stringResource(R.string.complete_writing_input)) },
                enabled = !state.attemptFinished,
                shape = RlShape.Input,
                minLines = 5,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
        val audio: @Composable () -> Unit = {
            LearningAction(stringResource(if (state.audioPlaying) R.string.learning_mute else R.string.learning_pronounce),
                { onAction(CompleteWritingAction.AudioToggled) }, Modifier.heightIn(min = 48.dp))
        }
        if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            editor(Modifier.fillMaxWidth())
            audio()
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            editor(Modifier.weight(1f))
            audio()
        }
    }
}

@Composable
private fun CompleteWritingActions(
    state: CompleteWritingUiState,
    onAction: (CompleteWritingAction) -> Unit,
    beforeSubmit: () -> Unit,
) {
    val finalBatch = state.phase == CompleteWritingPhase.FINAL_BATCH
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.phase != CompleteWritingPhase.AUDIBLE_SINGLE) {
            LearningAction(
                text = stringResource(R.string.complete_writing_view),
                onClick = { onAction(CompleteWritingAction.HelpRequested) },
                modifier = Modifier.weight(.55f),
            )
        }
        LearningAction(
            text = stringResource(
                when {
                    !finalBatch && state.attemptFinished -> R.string.complete_writing_next
                    state.finalResult != null -> R.string.complete_writing_continue
                    else -> R.string.audio_dictation_submit
                },
            ),
            enabled = !state.saving,
            onClick = {
                beforeSubmit()
                onAction(
                    when {
                        !finalBatch && state.attemptFinished -> CompleteWritingAction.NextRequested
                        state.finalResult != null -> CompleteWritingAction.ContinueRequested
                        else -> CompleteWritingAction.SubmitRequested
                    },
                )
            },
            modifier = Modifier.weight(1f), primary = true,
        )
    }
}

@Composable
private fun CompleteWritingStageHeader(state: CompleteWritingUiState, finalBatch: Boolean) {
    if (!finalBatch) {
        Text(stringResource(R.string.complete_writing_title), color = RlColor.Ink,
            fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp)
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (finalBatch) "连续书写" else stringResource(R.string.complete_writing_title),
            color = RlColor.Ink,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = EditorialFont.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            ),
        )
        Text(
            stringResource(
                when (state.phase) {
                    CompleteWritingPhase.AUDIBLE_SINGLE -> R.string.complete_writing_audible
                    CompleteWritingPhase.SILENT_SINGLE -> R.string.complete_writing_silent
                    CompleteWritingPhase.FINAL_BATCH -> R.string.complete_writing_final
                },
            ),
            color = RlColor.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun FinalWritingRow(
    index: Int,
    sentence: FixedLearningSentence,
    value: String,
    helpUsed: Boolean,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onAudio: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        border = BorderStroke(1.dp, RlColor.BorderStrong.copy(alpha = .72f)),
        shape = RlShape.Small,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = RlSpace.Space8),
            verticalArrangement = Arrangement.spacedBy(RlSpace.Space4),
        ) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.width(32.dp),
                color = RlColor.Primary,
                contentColor = RlColor.OnPrimary,
                shape = RlShape.Small,
            ) {
                Text(
                    index.toString().padStart(2, '0'),
                    modifier = Modifier.padding(vertical = RlSpace.Space8),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = EditorialFont.Serif),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    sentence.textZh,
                    color = RlColor.Ink,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = EditorialFont.Serif),
                )
                if (helpUsed) {
                    Text(
                        sentence.textEn,
                        color = RlColor.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            OutlinedButton(enabled = enabled, onClick = onAudio) {
                Text(stringResource(R.string.learning_pronounce))
            }
          }
            RlKeyboardAwareOutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("complete-writing-input-${sentence.sentenceId}"),
                placeholder = { Text(stringResource(R.string.complete_writing_input)) },
                enabled = enabled,
                shape = RlShape.Input,
                minLines = 1,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun CompleteWritingStatus(
    state: CompleteWritingUiState,
    finalBatch: Boolean,
    onAction: (CompleteWritingAction) -> Unit,
) {
    if (state.helpUsed) {
        Text(stringResource(R.string.complete_writing_supported_only), color = RlColor.TextSecondary)
    }
    if (state.emptySubmission) {
        Text(stringResource(R.string.complete_writing_empty), color = RlColor.Error)
    }
    if (state.saving) Text(stringResource(R.string.complete_writing_saving))
    if (state.saveFailed) {
        Text(stringResource(R.string.complete_writing_save_failed), color = RlColor.Error)
    }
    if (state.audioFailed) {
        Text(stringResource(R.string.learning_audio_failed), color = RlColor.Error)
    }
    if (state.evaluationUnavailable) {
        Text(stringResource(R.string.complete_writing_evaluation_unavailable), color = RlColor.Error)
    }
    state.currentAttemptCorrect?.let { correct ->
        Text(
            stringResource(
                if (correct) R.string.complete_writing_correct
                else R.string.complete_writing_needs_work,
            ),
            color = if (correct) RlColor.Secondary else RlColor.Error,
        )
    }
    when (state.finalResult) {
        is FinalWritingBatchResult.Valid -> Text(stringResource(R.string.complete_writing_final_recorded))
        FinalWritingBatchResult.SupportedLearningOnly -> Text(
            stringResource(R.string.complete_writing_final_supported),
            color = RlColor.TextSecondary,
        )
        null -> Unit
    }
    if (!finalBatch && state.attemptFinished) {
        OutlinedButton(onClick = { onAction(CompleteWritingAction.RetryCurrentRequested) }) {
            Text(stringResource(R.string.complete_writing_retry))
        }
    }
}
