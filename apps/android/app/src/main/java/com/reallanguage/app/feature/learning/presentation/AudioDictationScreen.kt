package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlKeyboardAwareOutlinedTextField
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace

@Composable
fun AudioDictationScreen(
    state: AudioDictationUiState,
    onAction: (AudioDictationAction) -> Unit,
) {
    ChapterLearningFrame(
        chapter = state.chapter,
        currentFocusSentenceId = state.currentFocusSentenceId,
        stageTitle = stringResource(R.string.audio_dictation_title),
        onBack = { onAction(AudioDictationAction.BackRequested) },
        leftPaneWeight = .64f,
        rightPaneWeight = 1.56f,
        leftArtworkRes = R.drawable.rl_v73_learning_cassette,
        leftArtworkPreferredHeight = 224.dp,
        modulePaperMinHeight = 168.dp,
        leftPanelContent = {
            Text(
                if (state.audioLooping) "慢速循环播放中" else "已静音",
                modifier = Modifier.fillMaxWidth().testTag("audio-dictation-loop-status"),
                color = RlColor.OnPrimary,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = EditorialFont.Serif,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                ),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        },
        interactiveEnglishContent = { _, modifier ->
            Row(
                modifier = modifier.clickable { onAction(AudioDictationAction.AudioToggled) }
                    .padding(vertical = RlSpace.Space4),
                horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Headphones,
                    contentDescription = null,
                    tint = RlColor.ActionVivid,
                )
                Text(
                    "点击播放，听写当前句",
                    color = RlColor.ActionVivid,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = EditorialFont.Serif,
                    ),
                )
            }
        },
        footer = {
            OutlinedButton(
                onClick = { onAction(AudioDictationAction.AudioToggled) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(
                        if (state.audioLooping) R.string.learning_mute
                        else R.string.learning_pronounce,
                    ),
                )
            }
            OutlinedButton(
                onClick = { onAction(AudioDictationAction.HintToggled) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.Lightbulb, contentDescription = null)
                Text(if (state.hintExpanded) "收起提示" else stringResource(R.string.audio_dictation_hint))
            }
            RlPrimaryButton(
                text = stringResource(if (state.correct) R.string.audio_dictation_continue else R.string.audio_dictation_submit),
                enabled = !state.submitting,
                onClick = { onAction(if (state.correct) AudioDictationAction.ContinueRequested else AudioDictationAction.SubmitRequested) },
                modifier = Modifier.weight(1.4f).heightIn(min = 48.dp),
                containerColor = RlColor.ActionVivid,
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().testTag("audio-dictation-input-panel"),
            verticalArrangement = Arrangement.spacedBy(RlSpace.Space4),
        ) {
            DictationFeedback(state)
            if (state.hintExpanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().testTag("audio-dictation-hint-panel"),
                    verticalArrangement = Arrangement.spacedBy(RlSpace.Space4),
                ) {
                    Text(
                        "本句已选学习内容",
                        color = RlColor.ActionVivid,
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = EditorialFont.Serif),
                    )
                    if (state.lockedHintItems.isEmpty()) {
                        Text(
                            "本句没有已选择的重点词义、固定搭配或句式。请继续听音输入；提示不会临时增加学习目标。",
                            color = RlColor.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        state.lockedHintItems.forEach { item ->
                            Text("• $item", color = RlColor.TextSecondary)
                        }
                    }
                }
            }
            Text(
                "请连续输入完整英文，大小写差异不算错。",
                color = RlColor.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            RlKeyboardAwareOutlinedTextField(
                value = state.input,
                enabled = !state.submitting && !state.correct,
                onValueChange = { onAction(AudioDictationAction.InputChanged(it)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp),
                label = { Text(stringResource(R.string.audio_dictation_input)) },
                trailingIcon = {
                    val punctuation = state.chapter.sentences
                        .firstOrNull { it.sentenceId == state.currentFocusSentenceId }
                        ?.textEn?.lastOrNull()?.takeIf { !it.isLetterOrDigit() }?.toString()
                    if (punctuation != null) Text(punctuation)
                },
                isError = state.emptySubmission || state.incorrect,
                shape = RlShape.Input,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 17.sp,
                ),
            )
            Text(
                "固定标点（点击输入）",
                color = RlColor.TextSecondary,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = EditorialFont.Serif),
            )
            Row(
                modifier = Modifier.fillMaxWidth().testTag("audio-dictation-punctuation"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(".", ",", "?", "!", ";", ":", "-", "'").forEachIndexed { index, mark ->
                    OutlinedButton(
                        onClick = { onAction(AudioDictationAction.InputChanged(state.input + mark)) },
                        enabled = !state.submitting && !state.correct,
                        modifier = Modifier.weight(1f).height(34.dp)
                            .testTag("audio-dictation-punctuation-$index"),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(mark, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DictationFeedback(state: AudioDictationUiState) {
    if (state.audioFailed) {
        Text(stringResource(R.string.learning_audio_failed), color = RlColor.Error)
    }
    when {
        state.saveFailed -> Text(stringResource(R.string.audio_dictation_save_failed), color = RlColor.Error)
        state.emptySubmission -> Text(stringResource(R.string.audio_dictation_empty), color = RlColor.Error)
        state.correct -> Text(stringResource(R.string.audio_dictation_correct), color = RlColor.Secondary)
        state.incorrect -> Text(stringResource(R.string.audio_dictation_incorrect), color = RlColor.Error)
    }
}
