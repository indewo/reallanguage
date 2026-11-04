package com.reallanguage.app.feature.review.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.core.designsystem.RlLedgerWorkspace

@Composable
fun ReviewResultScreen(state: ReviewResultUiState, onAction: (ReviewResultAction) -> Unit) {
    LearningArchivePage {
      Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
       Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
           .testTag("review-result-scroll-viewport"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LearningHeading(stringResource(R.string.review_day_result_title))
        when {
            state.settling -> CircularProgressIndicator()
            state.settlementFailed -> {
                Text(stringResource(R.string.review_day_result_failed), color = RlColor.Error)
                RlPrimaryButton(
                    text = stringResource(R.string.diary_retry),
                    enabled = true,
                    onClick = { onAction(ReviewResultAction.RetryRequested) },
                )
            }
            state.summary != null -> {
                Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = RlColor.Secondary)
                    Text("本次回顾已完成", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 22.sp, lineHeight = 29.sp)
                }
                LearningTwoPane(Modifier.testTag("review-result-summary"), leftWeight = .5f,
                    left = { pane -> Column(pane.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        // The summary exposes only completed/remaining focus sentence counts.
                        // Per-ability totals drawn in reference 39 must not be invented by presentation.
                        Text(stringResource(R.string.review_day_result_completed, state.summary.completedFocusSentenceCount), fontFamily = EditorialFont.Serif, fontSize = 20.sp, lineHeight = 28.sp)
                        androidx.compose.material3.HorizontalDivider(color = RlColor.Border)
                        Text(stringResource(R.string.review_day_result_remaining, state.summary.remainingFocusSentenceCount), color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 20.sp, lineHeight = 28.sp)
                    } }, right = { pane -> Column(pane) {
                      LearningPaper(Modifier.fillMaxWidth(), PaddingValues(16.dp), contentSpacing = 6.dp) {
                        LearningRibbon(if (state.summary.cycleCompleted) "本轮回顾已完成" else "继续巩固")
                        Text(
                            stringResource(if (state.summary.cycleCompleted) R.string.review_day_result_cycle_complete else R.string.review_day_result_cycle_continues),
                            color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 16.sp, lineHeight = 23.sp,
                        )
                        Text("完成的能力会留下记录，未完成部分会进入下一次回顾。", Modifier.testTag("review-result-cycle-note"),
                            fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
                      }
                      Image(painterResource(R.drawable.rl_v73_learning_archive_seal), null,
                          Modifier.align(Alignment.End).height(62.dp).width(58.dp), contentScale = ContentScale.Fit)
                    } })
            }
        }
       }
       // Preserve the settled result and its original action; only its layout
       // leaves the scroll body so it remains reachable on short/large-text screens.
       if (!state.settling && !state.settlementFailed && state.summary != null) {
            LearningAction(
                    text = stringResource(R.string.review_return_home),
                    enabled = true,
                    onClick = { onAction(ReviewResultAction.ReturnHomeRequested) },
                    modifier = Modifier.fillMaxWidth().testTag("review-result-home"), primary = true,
                )
       }
      }
    }
}
