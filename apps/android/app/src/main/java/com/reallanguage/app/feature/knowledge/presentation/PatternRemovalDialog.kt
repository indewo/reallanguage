package com.reallanguage.app.feature.knowledge.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlImmersiveDialogWindow
import com.reallanguage.app.core.designsystem.RlPaperPanel
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlSecondaryButton
import com.reallanguage.app.core.designsystem.RlSpace

@Composable
fun PatternRemovalDialog(
    state: PatternRemovalUiState,
    pattern: String,
    onAction: (PatternRemovalAction) -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!state.removing) onAction(PatternRemovalAction.CancelRequested) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        RlImmersiveDialogWindow(dimAmount = 0f)
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(
                start = RlSpace.Space16,
                end = RlSpace.Space24,
                top = RlSpace.Space12,
                bottom = RlSpace.Space12,
            ),
            contentAlignment = Alignment.CenterEnd,
        ) {
            val compact = maxWidth < 700.dp || LocalDensity.current.fontScale > 1.3f
            Box(Modifier.fillMaxWidth(if (compact) .94f else .55f).fillMaxHeight()
                .testTag("pattern-removal-sheet")) {
              Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null,
                  Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
              Image(painterResource(R.drawable.rl_focus_punch_holes_v2), null,
                  Modifier.align(Alignment.CenterStart).padding(start = 5.dp).width(12.dp).fillMaxHeight(.9f),
                  contentScale = ContentScale.FillBounds)
              Image(painterResource(R.drawable.rl_home_scene_paperclip), null,
                  Modifier.align(Alignment.TopEnd).offset(y = (-4).dp).padding(end = 20.dp).width(21.dp).height(56.dp),
                  contentScale = ContentScale.Fit)
              Column(Modifier.fillMaxSize().padding(start = 30.dp, end = 24.dp, top = 25.dp, bottom = 22.dp),
                  verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Only the explanation scrolls. Keep the exact two choices reachable
                // when the real system font or the selected pattern needs more room.
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.pattern_removal_page_title),
                        modifier = Modifier.fillMaxWidth(),
                        color = RlColor.Ink,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontFamily = EditorialFont.Serif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            lineHeight = 28.sp,
                        ),
                    )
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp)
                            .border(BorderStroke(1.dp, RlColor.Border)).padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            pattern,
                            color = RlColor.Secondary,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = EditorialFont.Serif,
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                            ),
                        )
                    }
                    Text(
                        stringResource(R.string.pattern_removal_explanation),
                        color = RlColor.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        ),
                    )
                    Text(
                        stringResource(R.string.pattern_removal_preserved),
                        color = RlColor.Secondary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        ),
                    )
                    Text(
                        stringResource(R.string.pattern_removal_scope_title),
                        modifier = Modifier.fillMaxWidth(),
                        color = RlColor.Ink,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = EditorialFont.Serif,
                            fontSize = 14.sp,
                            lineHeight = 19.sp,
                        ),
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 116.dp)
                            .border(BorderStroke(1.dp, RlColor.BorderStrong)),
                    ) {
                        RemovalScopeRow(android.R.drawable.ic_menu_edit, stringResource(R.string.pattern_removal_scope_status))
                        HorizontalDivider(color = RlColor.Divider)
                        RemovalScopeRow(android.R.drawable.ic_menu_recent_history, stringResource(R.string.pattern_removal_scope_successes))
                        HorizontalDivider(color = RlColor.Divider)
                        RemovalScopeRow(android.R.drawable.ic_menu_gallery, stringResource(R.string.pattern_removal_scope_examples))
                        HorizontalDivider(color = RlColor.Divider)
                        RemovalScopeRow(android.R.drawable.ic_menu_my_calendar, stringResource(R.string.pattern_removal_scope_review))
                    }
                    if (state.removeFailed) {
                        Text(stringResource(R.string.pattern_removal_failed), color = RlColor.Error)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(RlSpace.Space16),
                ) {
                    RlSecondaryButton(
                        text = stringResource(R.string.pattern_removal_cancel),
                        onClick = { onAction(PatternRemovalAction.CancelRequested) },
                        enabled = !state.removing,
                        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                            .testTag("pattern-removal-cancel"),
                    )
                    RlPrimaryButton(
                        text = stringResource(R.string.pattern_removal_confirm),
                        onClick = { onAction(PatternRemovalAction.ConfirmRequested) },
                        enabled = !state.removing,
                        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                            .testTag("pattern-removal-confirm"),
                        containerColor = RlColor.ActionVivid,
                    )
                }
              }
            }
        }
    }
}

@Composable
private fun RemovalScopeRow(iconResource: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(horizontal = RlSpace.Space12, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
    ) {
        Box(
            modifier = Modifier.size(20.dp).border(1.dp, RlColor.Secondary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = androidx.compose.ui.res.painterResource(iconResource),
                contentDescription = null,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text,
            color = RlColor.TextPrimary,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        )
    }
}
