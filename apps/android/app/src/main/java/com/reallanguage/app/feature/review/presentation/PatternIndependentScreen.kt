package com.reallanguage.app.feature.review.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import com.reallanguage.app.feature.learning.presentation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlSecondaryButton
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.feature.review.domain.PatternIndependentDecision

@Composable
fun PatternIndependentScreen(
    state: PatternIndependentUiState,
    onAction: (PatternIndependentAction) -> Unit,
) {
    LearningPage {
      Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        LearningHeading("回顾 · 句式独立回应", onBack = { onAction(PatternIndependentAction.BackRequested) })
        Text("请根据新场景自然回应，目标英文不会显示或播放。", Modifier.align(Alignment.CenterHorizontally), color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 21.sp)
        LearningTwoPane(leftWeight = .42f, contentFirstWhenStacked = true, left = { pane ->
            Box(pane.height(312.dp)) {
                Image(painterResource(R.drawable.rl_v73_learning_seated_conversation), null,
                    Modifier.fillMaxWidth().height(276.dp).align(Alignment.TopCenter), contentScale = ContentScale.Fit)
                Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.rl_scene_response_microphone), null, Modifier.size(96.dp), contentScale = ContentScale.Fit)
                    Text(if (state.recording) "正在收音" else "自然说出你的回应", color = RlColor.Secondary, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }, right = { pane -> Column(pane, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.example != null && !state.loading) {
            LearningPaper(Modifier.fillMaxWidth(), PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LearningRibbon("独立回应")
                    Text(state.example.sceneZh, Modifier.weight(1f), color = RlColor.Ink, fontSize = 15.sp, lineHeight = 21.sp)
                }
            }
            LearningPaper(Modifier.fillMaxWidth(), PaddingValues(14.dp), ruled = true) {
                Text(state.example.aiPromptEn, color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 18.sp, lineHeight = 25.sp)
            }
        }
        LearningPaper(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        when {
            state.loading || state.evaluating -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(if (state.evaluating) "正在核对本次回应…" else "正在准备独立回应场景…", fontSize = 15.sp, lineHeight = 22.sp)
            }
            state.notNaturallyVerifiable -> {
                Text("当前句式暂时无法生成自然的独立回应场景。")
                LearningAction("继续", { onAction(PatternIndependentAction.ContinueRequested) }, Modifier.fillMaxWidth(), primary = true)
            }
            state.failed -> {
                Text(when {
                    state.technicalRetryBlocked -> stringResource(R.string.full_sentence_speaking_retry_blocked)
                    state.technicalRetryPending -> stringResource(R.string.learning_audio_retry_pending)
                    else -> "本次处理失败，未产生有效结果。"
                }, color = RlColor.Error)
                if (state.technicalRetryBlocked) {
                    LearningAction(stringResource(R.string.full_sentence_speaking_new_recording),
                        { onAction(PatternIndependentAction.StartRequested) }, Modifier.fillMaxWidth(), primary = true)
                } else {
                    LearningAction("重试", { onAction(PatternIndependentAction.RetryRequested) }, Modifier.fillMaxWidth(), primary = true)
                }
            }
            state.decision == PatternIndependentDecision.INCONCLUSIVE -> {
                Text(state.feedbackZh.orEmpty())
                LearningAction("重试", { onAction(PatternIndependentAction.RetryRequested) }, Modifier.fillMaxWidth(), primary = true)
            }
            state.decision != null -> {
                Text(state.feedbackZh.orEmpty())
                LearningAction("继续", { onAction(PatternIndependentAction.ContinueRequested) }, Modifier.fillMaxWidth(), primary = true)
            }
            state.example != null -> {
                Box(Modifier.fillMaxWidth().background(Color(0xFF314E67)).padding(16.dp)) {
                    Row(Modifier.heightIn(min = 38.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Mic, null, tint = Color.White)
                        Text(if (state.recording) "正在收音，请自然完成你的回应。" else "请直接说出你的回应。", color = Color.White, fontSize = 16.sp, lineHeight = 23.sp)
                    }
                }
                LearningAction(
                    text = if (state.recording) "说完了" else "开始回应",
                    enabled = true,
                    onClick = { onAction(if (state.recording) PatternIndependentAction.FinishedRequested else PatternIndependentAction.StartRequested) },
                    modifier = Modifier.fillMaxWidth(), primary = true,
                )
            }
        }
        }
        LearningAction("返回回顾章节", { onAction(PatternIndependentAction.BackRequested) }, Modifier.fillMaxWidth())
        } })
      }
    }
}
