package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace

@Composable
fun ReadSpeakScreen(
    state: ReadSpeakUiState,
    onAction: (ReadSpeakAction) -> Unit,
) {
    ChapterLearningFrame(
        chapter = state.chapter,
        currentFocusSentenceId = state.currentFocusSentenceId,
        stageTitle = stringResource(R.string.read_speak_title),
        showModuleSidebar = false,
        fingerReadingFocusStyle = true,
        preserveLedgerHoleAspect = true,
        interactiveEnglishContent = { sentence, modifier ->
            ReadSpeakWordTrack(
                sentence = sentence,
                listening = state.listening,
                highlightedWordCount = state.highlightedWordCount,
                modifier = modifier,
                onAction = onAction,
            )
        },
        footer = {
            ReadSpeakControls(state, onAction)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(RlSpace.Space12)) {
            if (state.progressFailed) {
                Text(
                    stringResource(R.string.full_sentence_speaking_progress_failed),
                    color = RlColor.Error,
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("read_speak_status"),
                color = RlColor.NoteContainer,
                shape = RlShape.Small,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(RlSpace.Space12),
                    horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
                ) {
                    Icon(
                        Icons.Outlined.Mic,
                        contentDescription = null,
                        tint = if (state.captureFailed) RlColor.Error else RlColor.Secondary,
                    )
                    Column {
                        Text(
                            stringResource(
                                when {
                                    state.submitting -> R.string.read_speak_processing
                                    state.pendingFeedbackRetry -> R.string.learning_audio_retry_pending
                                    state.completed -> R.string.pattern_speaking_read_complete
                                    state.feedbackRetryBlocked -> R.string.full_sentence_speaking_retry_blocked
                                    state.captureFailed -> R.string.read_speak_failed
                                    state.listening -> R.string.read_speak_listening
                                    else -> R.string.read_speak_ready
                                },
                            ),
                            color = if (state.captureFailed) RlColor.Error else RlColor.Ink,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        state.feedbackZh
                            ?.takeUnless(String::isReadSpeakNoAudioCompletionCopy)
                            ?.let { Text(it, color = RlColor.TextSecondary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadSpeakControls(state: ReadSpeakUiState, onAction: (ReadSpeakAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
    ) {
        LearningAction(stringResource(R.string.formation_back), { onAction(ReadSpeakAction.BackRequested) }, Modifier.weight(.7f).fillMaxHeight())
        LearningAction(
            text = stringResource(if (state.audioPlaying) R.string.learning_mute else R.string.read_speak_play),
            onClick = { onAction(ReadSpeakAction.PlayToggled) },
            enabled = !state.submitting,
            modifier = Modifier.weight(.7f).fillMaxHeight(),
        )
        if (state.listening) {
            LearningAction(
                text = stringResource(R.string.read_speak_manual_finished),
                enabled = !state.submitting,
                onClick = { onAction(ReadSpeakAction.ManualFinished) },
                modifier = Modifier.weight(1.8f).fillMaxHeight().testTag("read_speak_manual_finished"),
                primary = true,
            )
        } else if (state.captureFailed) {
            LearningAction(
                text = stringResource(R.string.read_speak_manual_finished),
                onClick = { onAction(ReadSpeakAction.ManualFinished) },
                enabled = !state.submitting,
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("read_speak_manual_finished"),
            )
            LearningAction(
                text = stringResource(if (state.feedbackRetryBlocked) R.string.full_sentence_speaking_new_recording else R.string.read_speak_retry),
                enabled = !state.submitting,
                onClick = { onAction(if (state.feedbackRetryBlocked) ReadSpeakAction.StartRequested else ReadSpeakAction.RetryRequested) },
                modifier = Modifier.weight(1.8f).fillMaxHeight(),
                primary = true,
            )
        } else if (state.completed) {
            LearningAction(
                text = stringResource(
                    if (state.progressFailed) R.string.full_sentence_speaking_retry_save
                    else R.string.read_speak_continue,
                ),
                enabled = !state.submitting,
                onClick = { onAction(ReadSpeakAction.ContinueRequested) },
                modifier = Modifier.weight(1.8f).fillMaxHeight(),
                primary = true,
            )
        } else {
            LearningAction(
                text = stringResource(R.string.read_speak_manual_finished),
                enabled = false,
                onClick = {},
                modifier = Modifier.weight(1.8f).fillMaxHeight().testTag("read_speak_manual_finished"),
                primary = true,
            )
        }
    }
}
