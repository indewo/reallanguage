package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.core.designsystem.RlShape

@Suppress("DEPRECATION")
@Composable
fun ExplanationReadingScreen(state: ExplanationReadingUiState, onAction: (ExplanationReadingAction) -> Unit) {
    ExplanationReadingScreen(state, onAction, targetAssessment = {})
}

@Suppress("DEPRECATION")
@Composable
fun ExplanationReadingScreen(
    state: ExplanationReadingUiState,
    onAction: (ExplanationReadingAction) -> Unit,
    targetAssessment: @Composable (String) -> Unit,
) {
    ChapterLearningFrame(
        chapter = state.chapter,
        currentFocusSentenceId = state.currentFocusSentenceId,
        stageTitle = stringResource(R.string.explanation_reading_title),
        leftPaneWeight = .98f,
        rightPaneWeight = 1.26f,
        englishPaneWeight = 1.4f,
        leftContentEndPadding = RlSpace.Space16,
        modulePaper = false,
        contextTopAccent = false,
        focusSentenceMinHeight = 52.dp,
        // On the 845.5dp-wide / 429.5dp-high device, its 25dp cutout leaves
        // a 337.5dp body viewport. The full three-row ledger used 168.5dp;
        // compact row spacing recovers 19dp while retaining 20sp focus text
        // and a >=52dp focus target. The formula itself was not overflowing:
        // its 40px layout had only 29px visible because its parent clipped it.
        compactContextRowsOnShortScreen = true,
        focusOnlyContext = true,
        fixedPageWithModuleOverflow = true,
        leftPanelContent = {
            val activeId = state.activeExpressionId ?: state.selectedExpression?.expressionId
            val phrase = state.expressions.firstOrNull { it.expressionId == activeId }?.display
                ?: state.expressions.firstOrNull()?.display
                ?: stringResource(R.string.explanation_reading_title)
            Box(Modifier.fillMaxSize().testTag("explanation_expression_card_content")) {
              Column(
                  Modifier.fillMaxWidth().padding(bottom = 48.dp),
                  verticalArrangement = Arrangement.spacedBy(2.dp),
              ) {
                Text(
                    phrase,
                    color = RlColor.OnPrimary,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontFamily = EditorialFont.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 21.sp,
                        lineHeight = 25.sp,
                    ),
                )
                state.selectedExpression?.phonetic?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(start = 20.dp, top = 2.dp),
                        color = RlColor.OnPrimary.copy(alpha = .86f),
                        fontSize = 13.sp, lineHeight = 17.sp,
                    )
                }
                val explanationText = state.selectedExpression?.contextualMeaningZh ?: state.selectedExpression?.explanationZh
                    ?: if (activeId != null && state.loading) "正在获取当前词语解释…"
                    else if (activeId != null && state.loadingFailed) "当前词语解释暂时不可用，请重新点击该词重试。"
                    else null
                explanationText?.let { Text(
                    it,
                    modifier = Modifier.padding(start = 40.dp, top = 2.dp),
                    color = RlColor.OnPrimary.copy(alpha = .86f),
                    style = if (state.selectedExpression == null) {
                        MaterialTheme.typography.bodyMedium
                    } else {
                        MaterialTheme.typography.titleMedium.copy(fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 19.sp)
                    },
                ) }
              }
              Box(
                  Modifier.align(Alignment.BottomEnd).testTag("explanation_assessment_corner"),
              ) { targetAssessment(phrase) }
            }
        },
        interactiveEnglishContent = { sentence, modifier ->
            val annotatedSentence = buildAnnotatedString {
                append(sentence.textEn)
                state.expressions.forEach { expression ->
                    if (
                        expression.startOffset >= 0 &&
                        expression.endOffset <= sentence.textEn.length &&
                        expression.startOffset < expression.endOffset
                    ) {
                        addStringAnnotation(
                            tag = "expression",
                            annotation = expression.expressionId,
                            start = expression.startOffset,
                            end = expression.endOffset,
                        )
                        addStyle(
                            SpanStyle(
                                color = RlColor.Primary,
                                textDecoration = TextDecoration.Underline,
                            ),
                            expression.startOffset,
                            expression.endOffset,
                        )
                    }
                }
            }
            ClickableText(
                text = annotatedSentence,
                modifier = modifier,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color.Unspecified,
                    fontFamily = EditorialFont.Serif,
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                ),
                onClick = { offset ->
                    annotatedSentence.getStringAnnotations("expression", offset, offset)
                        .firstOrNull()
                        ?.let {
                            onAction(ExplanationReadingAction.ExpressionRequested(it.item))
                        }
                },
            )
        },
        footer = {
            ExplanationControls(state, onAction)
        },
    ) {
        Column(
            modifier = Modifier.testTag("explanation_content"),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (state.audioFailed) Text(stringResource(R.string.learning_audio_failed), color = RlColor.Error)
            if (!state.voiceEnabled) Text("语音已在设置中关闭，可继续阅读文字。", color = RlColor.TextSecondary)
            // Temporary reading hints are not the selected expression's usage or formula.
            state.currentIncrementalHint?.let {
                Surface(color = RlColor.Secondary, shape = RlShape.Small) {
                    Text(it, modifier = Modifier.fillMaxWidth().padding(RlSpace.Space8),
                        color = RlColor.OnSecondary, fontFamily = EditorialFont.Serif,
                        fontSize = 14.sp, lineHeight = 18.sp)
                }
            }
            state.selectedExpression?.let { explanation ->
                ExplanationDetailRow(stringResource(R.string.completed_step_usage), example = false) {
                    Text(explanation.usageZh ?: stringResource(R.string.expression_usage_not_provided),
                        color = if (explanation.usageZh == null) RlColor.TextSecondary else RlColor.Ink,
                        fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
                }
                ExplanationDetailRow("自然例句", example = true) {
                        val example = explanation.naturalExample
                        if (example == null) {
                            Text("此份讲解未包含例句。", color = RlColor.TextSecondary)
                        } else {
                            Text(example.textEn, color = RlColor.Ink, fontFamily = EditorialFont.Serif,
                                fontSize = 14.sp, lineHeight = 18.sp)
                            Text(example.textZh, color = RlColor.Ink, fontFamily = EditorialFont.Serif,
                                fontSize = 13.sp, lineHeight = 18.sp)
                        }
                }
                explanation.expressionHintZh?.let { ExpressionUsageHint(it) }
            }
            // Keep the first-round explanation readable; an identical paragraph already
            // shown above needs no second copy, but distinct sentence context must remain.
            if (state.baseExplanationZh.isNotBlank() && state.baseExplanationZh != state.selectedExpression?.usageZh) {
                Text(stringResource(R.string.explanation_reading_title), color = RlColor.Ink,
                    fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(state.baseExplanationZh, color = RlColor.Ink,
                    fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
            }
            if (state.loadingFailed) {
                Text(
                    stringResource(R.string.explanation_loading_failed),
                    color = RlColor.Error,
                )
            }
            if (state.progressFailed) {
                Text(stringResource(R.string.learning_exit_save_failed), color = RlColor.Error)
                TextButton(onClick = { onAction(ExplanationReadingAction.RetryProgressRequested) }) {
                    Text(stringResource(R.string.diary_retry))
                }
            }
        }
    }
}

@Composable
internal fun ExpressionUsageHint(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(stringResource(R.string.expression_usage_hint), Modifier.width(82.dp), color = RlColor.Ink,
            fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 18.sp)
        Surface(Modifier.weight(1f), color = RlColor.Secondary, shape = RlShape.Small) {
            Text(text, Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = RlColor.OnSecondary,
                fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
internal fun ExplanationDetailRow(title: String, example: Boolean, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        LearningSeal(if (example) Icons.Filled.FormatQuote else Icons.Outlined.Lightbulb,
            if (example) RlColor.ActionVivid else RlColor.Secondary, Modifier.size(28.dp))
        Text(title, modifier = Modifier.width(76.dp), color = RlColor.Ink,
            fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp)
        Column(Modifier.weight(1f)) { content() }
    }
    HorizontalDivider(color = RlColor.TextSecondary.copy(alpha = .35f))
}

@Composable
private fun ExplanationControls(
    state: ExplanationReadingUiState,
    onAction: (ExplanationReadingAction) -> Unit,
) {
  Box(Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
    ) {
        LearningAction(
            text = stringResource(R.string.formation_back),
            onClick = { onAction(ExplanationReadingAction.BackRequested) },
            enabled = !state.loading && !state.progressFailed,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        LearningAction(
            text = stringResource(if (state.audioPlaying) R.string.learning_mute else R.string.learning_pronounce),
            onClick = { onAction(ExplanationReadingAction.AudioToggled) },
            enabled = state.voiceEnabled && !state.loading && !state.progressFailed,
            modifier = Modifier.weight(1f).fillMaxHeight().testTag("explanation_audio_toggle"),
        )
        LearningAction(
            text = stringResource(R.string.explanation_understood),
            enabled = !state.loading && !state.progressFailed,
            onClick = { onAction(ExplanationReadingAction.UnderstoodRequested) },
            modifier = Modifier.weight(1f).fillMaxHeight().testTag("explanation_understood"),
            primary = true,
        )
    }
  }
}
