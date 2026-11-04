package com.reallanguage.app.feature.review.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.feature.learning.presentation.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlImmersiveDialogWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlSpace

@Composable
fun ReviewExitDialog(
    state: ReviewExitUiState,
    onAction: (ReviewExitAction) -> Unit,
) {
    Dialog(
        onDismissRequest = { onAction(ReviewExitAction.ContinueReviewRequested) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        RlImmersiveDialogWindow()
        LearningArchivePage(Modifier.testTag("review-exit-sheet"), startFraction = .1f,
            backgroundRes = R.drawable.rl_v73_learning_exit_background) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(RlSpace.Space8),
            ) {
                LearningHeading(stringResource(R.string.review_exit_page_title))
                Text(
                    stringResource(R.string.review_exit_boundary),
                    color = RlColor.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val compact = maxWidth < 560.dp * LocalDensity.current.fontScale
                    val books: @Composable (Modifier) -> Unit = { modifier ->
                        Box(modifier.height(if (compact) 200.dp else 180.dp), contentAlignment = Alignment.Center) {
                            Image(painterResource(R.drawable.rl_v73_review_boundary_books), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            Text("保\n存\n边\n界", color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.offset(x = 3.dp), fontSize = 13.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
                        }
                    }
                    val completed: @Composable (Modifier) -> Unit = { modifier -> Text(stringResource(R.string.review_exit_completed), modifier,
                        color = RlColor.Secondary, textAlign = TextAlign.Center, fontSize = 16.sp, lineHeight = 24.sp) }
                    val incomplete: @Composable (Modifier) -> Unit = { modifier -> Text(stringResource(R.string.review_exit_incomplete), modifier,
                        color = RlColor.ActionVivid, textAlign = TextAlign.Center, fontSize = 16.sp, lineHeight = 24.sp) }
                    if (compact) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        completed(Modifier.fillMaxWidth())
                        books(Modifier.fillMaxWidth())
                        incomplete(Modifier.fillMaxWidth())
                    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        completed(Modifier.weight(.2f))
                        books(Modifier.weight(.6f))
                        incomplete(Modifier.weight(.2f))
                    }
                }
                Text(
                    if (state.discardFailed) stringResource(R.string.review_exit_failed)
                    else if (state.completedChapterCount < 0) stringResource(R.string.review_exit_explanation_unknown_count)
                    else stringResource(R.string.review_exit_explanation, state.completedChapterCount),
                    color = if (state.discardFailed) RlColor.Error else RlColor.TextSecondary,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RlSpace.Space20),
                ) {
                    LearningAction(
                        text = stringResource(R.string.review_exit_continue),
                        onClick = { onAction(ReviewExitAction.ContinueReviewRequested) },
                        enabled = !state.discarding && !state.completed,
                        modifier = Modifier.weight(1f).testTag("review-exit-continue"),
                        primary = true, color = RlColor.Secondary,
                    )
                    LearningAction(
                        text = stringResource(R.string.review_exit_confirm),
                        onClick = { onAction(ReviewExitAction.ConfirmExitRequested) },
                        enabled = !state.discarding && !state.completed,
                        modifier = Modifier.weight(1f).testTag("review-exit-confirm"),
                        primary = true,
                    )
                }
            }
        }
    }
}
