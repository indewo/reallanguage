package com.reallanguage.app.feature.scene.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlEditorialHeader
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlScrollablePage
import com.reallanguage.app.core.designsystem.RlSpace

@Composable
fun SceneQuestionsScreen(state: SceneQuestionsUiState, onAction: (SceneQuestionsAction) -> Unit) {
    BackHandler { onAction(SceneQuestionsAction.BackRequested) }
    SceneEditorialFrame(
        title = stringResource(R.string.scene_questions_title),
        supporting = "让场景更像\n你真实经历的",
        footer = {
            TextButton(
                onClick = { onAction(SceneQuestionsAction.ExitRequested) },
                modifier = Modifier.weight(.4f),
            ) { Text(stringResource(R.string.scene_questions_exit)) }
            if (state.history.isNotEmpty() && state.error != SceneQuestionsError.INSUFFICIENT_INFORMATION) {
                OutlinedButton(
                    onClick = { onAction(SceneQuestionsAction.PreviousRequested) },
                    modifier = Modifier.weight(.45f).testTag("scene-questions-previous"),
                ) { Text(stringResource(R.string.scene_questions_previous)) }
            }
            RlPrimaryButton(
                text = stringResource(
                    when (state.error) {
                        SceneQuestionsError.INSUFFICIENT_INFORMATION -> R.string.scene_questions_return_edit
                        null -> R.string.scene_questions_submit
                        else -> R.string.diary_retry
                    },
                ),
                enabled = state.error != null || state.answerText.isNotBlank(),
                onClick = {
                    onAction(
                        when (state.error) {
                            SceneQuestionsError.INSUFFICIENT_INFORMATION -> SceneQuestionsAction.PreviousRequested
                            null -> SceneQuestionsAction.AnswerSubmitted
                            else -> SceneQuestionsAction.RetryRequested
                        },
                    )
                },
                modifier = Modifier.weight(1f).testTag("scene-questions-submit"),
            )
        },
    ) {
        SceneQuestionProgress(state.confirmedAnswers.size)
        when {
            state.loading -> Text(stringResource(R.string.scene_questions_loading))
            state.error != null -> {
                Text(
                    stringResource(
                        when (state.error) {
                            SceneQuestionsError.INVALID_RESPONSE -> R.string.scene_questions_invalid
                            SceneQuestionsError.INSUFFICIENT_INFORMATION -> R.string.scene_questions_insufficient
                            else -> R.string.scene_questions_failed
                        },
                    ),
                    color = RlColor.Error,
                    modifier = Modifier.testTag("scene-questions-error"),
                )
                if (state.error != SceneQuestionsError.INSUFFICIENT_INFORMATION) {
                    OutlinedButton(onClick = { onAction(SceneQuestionsAction.RetryRequested) }) {
                        Text(stringResource(R.string.diary_retry))
                    }
                }
            }
            state.currentQuestion != null -> {
                Text(state.currentQuestion.questionZh, color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                if (state.currentQuestion.options.isNotEmpty()) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val optionWidth = if (maxWidth >= 400.dp) (maxWidth - 16.dp) / 3 else (maxWidth - 8.dp) / 2
                    FlowRow(
                        modifier = Modifier.testTag("scene-questions-options"),
                        horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
                    ) {
                        state.currentQuestion.options.forEach { option ->
                            val selected = state.answerText == option
                            Box(Modifier.width(optionWidth).heightIn(min = 90.dp).padding(bottom = 8.dp)
                                .clickable { onAction(SceneQuestionsAction.AnswerChanged(option)) }
                                .testTag("scene-option-$option")) {
                                Image(painterResource(if (selected) R.drawable.rl_scene_recent_ticket_sage_v2 else R.drawable.rl_scene_recent_ticket_blue_v2),
                                    null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.ChatBubbleOutline,
                                        if (selected) "已选择" else null, tint = if (selected) RlColor.Secondary else RlColor.Primary,
                                        modifier = Modifier.size(26.dp))
                                    Text(option, color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 18.sp,
                                        lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    }
                }
                com.reallanguage.app.core.designsystem.RlKeyboardAwareOutlinedTextField(
                    value = state.answerText,
                    onValueChange = { onAction(SceneQuestionsAction.AnswerChanged(it)) },
                    label = { Text(stringResource(R.string.scene_questions_answer_label)) },
                    minLines = 1,
                    modifier = Modifier.fillMaxWidth().testTag("scene-questions-answer"),
                )
            }
        }
        if (state.confirmedAnswers.isNotEmpty()) {
            androidx.compose.material3.HorizontalDivider(color = RlColor.Border.copy(alpha = .5f))
            Text("${stringResource(R.string.scene_questions_answered)}：${state.confirmedAnswers.joinToString(" · ") { it.answerZh }}",
                color = RlColor.TextSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        }
    }
}

/** Reflects the existing ten-question contract; the visual draft's five dots are not a new limit. */
@Composable
private fun SceneQuestionProgress(confirmedCount: Int) {
    val maximum = 10
    val current = (confirmedCount + 1).coerceIn(1, maximum)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 420.dp || LocalDensity.current.fontScale > 1.35f
        val label: @Composable (Modifier) -> Unit = { labelModifier ->
            Text("问题 $current / 最多 $maximum 个", color = RlColor.Ink,
                modifier = labelModifier.testTag("scene-questions-progress"),
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                fontSize = 15.sp, lineHeight = 21.sp)
        }
        val indicators: @Composable () -> Unit = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                repeat(maximum) { index ->
                    Box(Modifier.size(12.dp).testTag("scene-question-progress-${index + 1}")
                        .border(1.dp, RlColor.Border, androidx.compose.foundation.shape.CircleShape)
                        .background(if (index + 1 == current) RlColor.ActionVivid else androidx.compose.ui.graphics.Color.Transparent,
                            androidx.compose.foundation.shape.CircleShape))
                }
            }
        }
        if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            label(Modifier.fillMaxWidth())
            indicators()
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            label(Modifier.weight(1f))
            indicators()
        }
    }
}
