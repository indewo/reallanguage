package com.reallanguage.app.feature.safety.presentation

import com.reallanguage.app.feature.formation.presentation.EditorialSheet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlSecondaryButton
import com.reallanguage.app.core.designsystem.RlLedgerWorkspace
import com.reallanguage.app.core.designsystem.RlSectionCard
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.core.designsystem.RlPaperBackground
import androidx.compose.material3.OutlinedButton

@Composable
fun ManualReviewResultScreen(
    state: ManualReviewResultUiState,
    onAction: (ManualReviewResultAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.unavailable) {
        RlLedgerWorkspace(
            title = stringResource(R.string.review_result_title),
            subtitle = "查看复核结论",
            leftTitle = "复核结果",
            leftSupporting = "",
            modifier = modifier,
            onBack = { onAction(ManualReviewResultAction.ReturnMessagesRequested) },
            fixedPaperViewport = true,
            footer = {
                RlPrimaryButton(
                    text = stringResource(R.string.review_return_messages),
                    enabled = true,
                    onClick = { onAction(ManualReviewResultAction.ReturnMessagesRequested) },
                    modifier = Modifier.testTag("manual-review-return-messages"),
                )
            },
        ) {
            Text(
                stringResource(R.string.messages_target_unavailable),
                color = RlColor.TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("manual-review-unavailable"),
            )
        }
        return
    }
    val approved = state.outcome == ManualReviewOutcome.APPROVED
    if (state.outcome != null) {
        BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
            if (maxWidth < 760.dp || LocalDensity.current.fontScale > 1.3f) {
                CompactReviewOutcome(state, onAction, approved)
            } else if (approved) ApprovedReviewResult(state, onAction)
            else RejectedReviewResult(state, onAction)
        }
        return
    }
    RlLedgerWorkspace(
        title = stringResource(R.string.review_result_title),
        subtitle = if (approved) "复核已通过" else "查看复核结论",
        leftTitle = if (approved) "已通过" else "复核结果",
        leftSupporting = if (approved) "这份内容可以继续进入学习流程。" else "请根据说明返回内容安全处理。",
        modifier = modifier,
    ) {
                if (state.loading && state.outcome == null) Text(stringResource(R.string.review_result_loading))
                else if (state.loadFailed) OutlinedButton(onClick = { onAction(ManualReviewResultAction.RetryRequested) }) { Text(stringResource(R.string.diary_retry)) }
                else RlSectionCard {
                    Text(state.summaryZh, style = MaterialTheme.typography.bodyLarge)
                    state.reasonZh?.let {
                        Text(it, color = RlColor.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (approved) "已归档，可继续拆句" else "需返回安全处理", color = if (approved) RlColor.Secondary else RlColor.ActionVivid)
                    when (state.outcome) {
                        ManualReviewOutcome.APPROVED -> RlPrimaryButton(
                            text = stringResource(R.string.review_enter_split),
                            enabled = true,
                            onClick = {
                                onAction(
                                    ManualReviewResultAction.OpenFullSentenceSplitRequested,
                                )
                            },
                        )
                        ManualReviewOutcome.NOT_APPROVED -> RlPrimaryButton(
                            text = stringResource(R.string.review_return_safety),
                            enabled = true,
                            onClick = {
                                onAction(
                                    ManualReviewResultAction.ReturnToSafetyHandlingRequested,
                                )
                            },
                        )
                        null -> Unit
                    }
                }
    }
}

@Composable
private fun RejectedReviewResult(
    state: ManualReviewResultUiState,
    onAction: (ManualReviewResultAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    RlPaperBackground(modifier) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(.86f).fillMaxHeight().padding(start = 38.dp, top = 32.dp, end = 24.dp, bottom = 26.dp)
                    .testTag("review-rejected-guidance"),
            ) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("人工复核结果", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("暂时不能进入学习", color = RlColor.ActionVivid, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(220.dp).height(7.dp), contentScale = ContentScale.FillBounds)
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 154.dp).testTag("review-rejected-opinion")) {
                    Image(painterResource(R.drawable.rl_torn_note), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.PersonOutline, null, tint = RlColor.ActionVivid, modifier = Modifier.size(20.dp))
                            Text("审核意见", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                        }
                        Text(state.summaryZh, color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp, lineHeight = 23.sp)
                        state.reasonZh?.let { Text(it, color = RlColor.ActionVivid, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 21.sp) }
                        Text("为保护你和他人的隐私安全，该内容暂时无法进入学习。", color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                }
                }
                ReviewResultActions(approved = false, onAction = onAction)
                Text("下一步：前往安全处理工作台，选择脱敏或修改内容。", color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
            }
            Box(Modifier.weight(1.14f).fillMaxHeight()) {
                Image(painterResource(R.drawable.rl_v73_review_dossier), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                Column(
                    Modifier.align(Alignment.TopCenter).padding(top = 66.dp).fillMaxWidth(.76f).heightIn(min = 200.dp)
                        .testTag("review-rejected-source"),
                ) {
                    Text("内容档案", color = RlColor.ActionVivid, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 10.sp)
                    Text("原内容仍包含需要保护的具体信息", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                    Text(state.reasonZh.orEmpty(), color = RlColor.ActionVivid, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 14.dp))
                    Text("该内容需要先完成安全处理，再重新提交。", color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 10.sp, modifier = Modifier.padding(top = 14.dp))
                }
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).fillMaxWidth(.86f).heightIn(min = 132.dp)
                        .testTag("review-rejected-explanation"),
                ) {
                    Image(painterResource(R.drawable.rl_diary_processing_analysis_note), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                    Column(Modifier.fillMaxWidth().padding(start = 44.dp, end = 28.dp, top = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.WarningAmber, null, tint = RlColor.ActionVivid, modifier = Modifier.size(21.dp))
                            Text("审核说明", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                        }
                        Text(state.reasonZh.orEmpty(), color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 20.sp)
                        Text("建议对相关信息进行脱敏或修改后再提交。", color = RlColor.ActionVivid, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovedReviewResult(
    state: ManualReviewResultUiState,
    onAction: (ManualReviewResultAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    RlPaperBackground(modifier) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(.86f).fillMaxHeight().padding(start = 38.dp, top = 36.dp, end = 24.dp, bottom = 28.dp)
                    .testTag("review-approved-guidance"),
            ) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("人工复核结果", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("复核已通过", color = RlColor.Secondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(150.dp).height(7.dp), contentScale = ContentScale.FillBounds)
                Spacer(Modifier.height(20.dp))
                ApprovedGuideRow(Icons.Outlined.CheckCircle, "内容已通过人工复核")
                ApprovedGuideRow(Icons.Outlined.Description, "这份内容可以继续进入学习准备。")
                ApprovedGuideRow(Icons.Outlined.PersonOutline, "审核人员确认：当前内容无需额外处理。")
                }
                ReviewResultActions(approved = true, onAction = onAction)
            }
            Box(Modifier.weight(1.14f).fillMaxHeight()) {
                Image(painterResource(R.drawable.rl_v73_review_dossier), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                Column(
                    Modifier.align(Alignment.TopCenter).padding(top = 72.dp).fillMaxWidth(.78f).heightIn(min = 184.dp)
                        .testTag("review-approved-certificate"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("人工复核结果通知书", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(160.dp).height(6.dp), contentScale = ContentScale.FillBounds)
                    Text("经人工复核，确认该内容真实完整，", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.align(Alignment.Start).padding(top = 18.dp, start = 24.dp))
                    Text("复核已通过。", color = RlColor.Secondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start).padding(start = 34.dp, top = 6.dp))
                    Text(state.summaryZh, color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.align(Alignment.Start).padding(start = 24.dp, top = 6.dp, end = 60.dp))
                }
                Image(painterResource(R.drawable.rl_safety_privacy_stamp), null, Modifier.align(Alignment.Center).offset(x = 116.dp, y = (-32).dp).size(88.dp))
                NextStepStrip(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).fillMaxWidth(.92f).heightIn(min = 112.dp))
            }
        }
    }
}

@Composable
private fun CompactReviewOutcome(state: ManualReviewResultUiState, onAction: (ManualReviewResultAction) -> Unit, approved: Boolean) {
    RlPaperBackground {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("人工复核结果", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
            Text(if (approved) "复核已通过" else "暂时不能进入学习", color = if (approved) RlColor.Secondary else RlColor.ActionVivid,
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 23.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold)
            EditorialSheet(contentPadding = 24.dp, contentSpacing = 16.dp, showSpine = true) {
                Text(if (approved) "人工复核结果通知书" else "审核意见", color = RlColor.Ink,
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 21.sp, lineHeight = 31.sp)
                Text(state.summaryZh, color = RlColor.Ink, fontSize = 17.sp, lineHeight = 28.sp)
                state.reasonZh?.takeIf { it.isNotBlank() }?.let { Text(it, color = RlColor.TextSecondary, fontSize = 15.sp, lineHeight = 25.sp) }
                Image(painterResource(R.drawable.rl_safety_privacy_stamp), null, Modifier.size(78.dp).align(Alignment.End))
            }
            Text(if (approved) "这份内容可以继续进入学习准备。" else "下一步：前往安全处理工作台，选择脱敏或修改内容。",
                color = RlColor.TextSecondary, fontSize = 15.sp, lineHeight = 24.sp)
            ReviewResultActions(approved, onAction)
        }
    }
}

@Composable
private fun ReviewResultActions(
    approved: Boolean,
    onAction: (ManualReviewResultAction) -> Unit,
) {
    val primary: @Composable (Modifier) -> Unit = { buttonModifier ->
        RlPrimaryButton(
            text = stringResource(if (approved) R.string.review_enter_split else R.string.review_return_safety),
            enabled = true,
            onClick = {
                onAction(if (approved) ManualReviewResultAction.OpenFullSentenceSplitRequested
                    else ManualReviewResultAction.ReturnToSafetyHandlingRequested)
            },
            containerColor = if (approved) RlColor.Secondary else RlColor.ActionVivid,
            modifier = buttonModifier.heightIn(min = 48.dp),
        )
    }
    val secondary: @Composable (Modifier) -> Unit = { buttonModifier ->
        RlSecondaryButton(
            text = stringResource(R.string.review_return_messages),
            onClick = { onAction(ManualReviewResultAction.ReturnMessagesRequested) },
            modifier = buttonModifier.heightIn(min = 48.dp).testTag("manual-review-return-messages"),
        )
    }
    if (LocalDensity.current.fontScale >= 1.3f) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RlSpace.Space8)) {
            primary(Modifier.fillMaxWidth())
            secondary(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
            verticalAlignment = Alignment.CenterVertically) {
            primary(Modifier.weight(1.7f))
            secondary(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ApprovedGuideRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = RlColor.Secondary, modifier = Modifier.size(32.dp))
        Text(text, color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun NextStepStrip(modifier: Modifier = Modifier) {
    Box(modifier.testTag("review-approved-next-step")) {
        Image(painterResource(R.drawable.rl_torn_note), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text("下一步示意：进入拆句后选择重点句", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 20.sp)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..6).forEach { index ->
                    Surface(
                        modifier = Modifier.weight(1f).height(58.dp),
                        color = if (index == 1) RlColor.Secondary else RlColor.PaperWarm,
                        border = BorderStroke(1.dp, RlColor.Border), shape = RlShape.Small,
                    ) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("重点句", color = if (index == 1) RlColor.OnSecondary else RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 10.sp)
                            Text(index.toString(), color = if (index == 1) RlColor.OnSecondary else RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}
