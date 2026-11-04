package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlPaperPanel
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlSecondaryButton
import com.reallanguage.app.core.designsystem.RlSpace

@Composable
fun ChapterAbandonScreen(
    state: ChapterAbandonUiState,
    onAction: (ChapterAbandonAction) -> Unit,
) {
    val chapterNumber = state.chapterId.takeLastWhile(Char::isDigit).toIntOrNull()
    LearningArchivePage(Modifier.testTag("chapter-abandon-sheet"), startFraction = .105f) {
        LearningTwoPane(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), leftWeight = .25f, contentFirstWhenStacked = true, left = { pane ->
            Column(
                modifier = pane.padding(top = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.chapter_abandon_archive_title),
                    color = RlColor.Secondary,
                    fontFamily = EditorialFont.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp, lineHeight = 25.sp,
                )
                Text(
                    stringResource(R.string.chapter_abandon_archive_protected),
                    modifier = Modifier.padding(top = RlSpace.Space8),
                    color = RlColor.TextSecondary,
                    fontFamily = EditorialFont.Serif,
                    fontSize = 15.sp, lineHeight = 21.sp,
                )
                Image(
                    painter = painterResource(R.drawable.rl_v73_learning_empty_meeting_polaroid),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(170.dp),
                    contentScale = ContentScale.Fit,
                )
                Image(painterResource(R.drawable.rl_v73_learning_archive_seal), null,
                    Modifier.size(92.dp), contentScale = ContentScale.Fit)
            }
        }, right = { pane -> Box(pane) {
            Image(painterResource(R.drawable.rl_v73_learning_confirmation_sheet), null,
                Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            // The curled lower-right edge slopes inward before the bottom gutter;
            // keep the button's right outline inside that opaque paper as well.
            Column(Modifier.fillMaxWidth().padding(start = 27.dp, end = 40.dp, top = 32.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    chapterNumber?.let { "确认放弃第 $it 章？" }
                        ?: stringResource(R.string.chapter_abandon_title),
                    modifier = Modifier.fillMaxWidth(),
                    color = RlColor.Ink,
                    textAlign = TextAlign.Center,
                    fontFamily = EditorialFont.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp, lineHeight = 29.sp,
                )
                Text(
                    stringResource(R.string.chapter_abandon_delete_label),
                    color = RlColor.ActionVivid,
                    fontFamily = EditorialFont.Serif,
                    fontSize = 14.sp, lineHeight = 20.sp,
                )
                ChapterRemovalRow("01", stringResource(R.string.chapter_abandon_fixed_sentences))
                ChapterRemovalRow("02", stringResource(R.string.chapter_abandon_learning_targets))
                ChapterRemovalRow("03", stringResource(R.string.chapter_abandon_temporary_progress))
                ChapterRemovalRow("04", stringResource(R.string.chapter_abandon_temporary_results))
                HorizontalDivider(color = RlColor.Divider)
                Text(
                    stringResource(R.string.chapter_abandon_unaffected_scope),
                    color = RlColor.TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
                Text(
                    stringResource(R.string.chapter_abandon_excluded),
                    color = RlColor.ActionVivid,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
                when {
                    state.deleteFailed -> Text(stringResource(R.string.chapter_abandon_failed), color = RlColor.Error)
                    state.alreadyArchived -> Text(stringResource(R.string.chapter_abandon_archived), color = RlColor.Error)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RlSpace.Space16),
                ) {
                    LearningAction(
                        text = stringResource(R.string.learning_exit_continue),
                        enabled = !state.deleting,
                        onClick = { onAction(ChapterAbandonAction.ContinueLearningRequested) },
                        modifier = Modifier.weight(1f),
                        primary = true, color = RlColor.Secondary,
                    )
                    LearningAction(
                        text = stringResource(R.string.chapter_abandon_confirm),
                        onClick = { onAction(ChapterAbandonAction.ConfirmAbandonRequested) },
                        modifier = Modifier.weight(1f)
                            .testTag("chapter-abandon-confirm"),
                        enabled = !state.deleting && !state.alreadyArchived,
                    )
                }
        } } })
    }
}

@Composable
private fun ChapterRemovalRow(number: String, label: String) {
    LearningPaper(
        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
        padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        contentSpacing = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space16),
        ) {
            Text(number, color = RlColor.Secondary, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
            Text(label, color = RlColor.TextPrimary, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}
