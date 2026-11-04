package com.reallanguage.app.feature.safety.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.feature.safety.domain.SourceType

@Composable
fun ManualReviewPendingScreen(
    state: ManualReviewPendingUiState,
    onAction: (ManualReviewPendingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    RlPaperBackground(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (maxWidth < 760.dp || LocalDensity.current.fontScale > 1.3f) CompactPending(state, onAction)
            else WidePending(state, onAction)
        }
    }
}

@Composable
private fun WidePending(state: ManualReviewPendingUiState, onAction: (ManualReviewPendingAction) -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(.8f).fillMaxHeight().padding(start = 38.dp, top = 30.dp, end = 22.dp, bottom = 28.dp)
                .testTag("review-pending-guidance"),
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 70.dp)) {
                Image(painterResource(R.drawable.rl_diary_botanical_sprig), null, Modifier.align(Alignment.TopStart).width(38.dp).height(58.dp).offset(x = (-22).dp, y = (-6).dp), contentScale = ContentScale.Fit)
                Column(Modifier.padding(start = 30.dp)) {
                    Text("人工复核", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 31.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                    Text("已提交，等待结果", color = RlColor.ActionVivid, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 18.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                    Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(202.dp).height(7.dp), contentScale = ContentScale.FillBounds)
                }
            }
            Spacer(Modifier.height(10.dp))
            GuidanceRow(Icons.Outlined.Lock, RlColor.ActionVivid, "这份内容暂时锁定", "复核完成后会通过消息通知你。")
            GuidanceRow(Icons.Outlined.AutoStories, RlColor.Secondary, "你仍然可以", "创建和学习其他已经通过检查的内容。")
            GuidanceRow(Icons.Outlined.VerifiedUser, RlColor.NoteAccent, "只有这份主动提交的内容", "会提供给审核人员。")
            Spacer(Modifier.weight(1f))
            RlPrimaryButton(
                text = stringResource(R.string.review_return_home), enabled = true,
                onClick = { onAction(ManualReviewPendingAction.ReturnHomeRequested) },
                containerColor = RlColor.ActionVivid,
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            )
        }
        ReviewDossier(state, Modifier.weight(1.2f).fillMaxHeight().testTag("review-pending-dossier"))
    }
}

@Composable
private fun CompactPending(state: ManualReviewPendingUiState, onAction: (ManualReviewPendingAction) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().testTag("review-pending-guidance")) {
            Text("人工复核", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("已提交，等待结果", color = RlColor.ActionVivid, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            GuidanceRow(Icons.Outlined.Lock, RlColor.ActionVivid, "这份内容暂时锁定", "复核完成后会通过消息通知你。")
            GuidanceRow(Icons.Outlined.AutoStories, RlColor.Secondary, "你仍然可以", "创建和学习其他已经通过检查的内容。")
            GuidanceRow(Icons.Outlined.VerifiedUser, RlColor.NoteAccent, "只有这份主动提交的内容", "会提供给审核人员。")
        }
        ReviewDossier(state, Modifier.fillMaxWidth().height(440.dp * LocalDensity.current.fontScale).testTag("review-pending-dossier"))
        RlPrimaryButton(stringResource(R.string.review_return_home), true, { onAction(ManualReviewPendingAction.ReturnHomeRequested) }, containerColor = RlColor.ActionVivid)
    }
}

@Composable
private fun GuidanceRow(icon: ImageVector, color: androidx.compose.ui.graphics.Color, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(34.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp)
            Text(body, color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun ReviewDossier(state: ManualReviewPendingUiState, modifier: Modifier = Modifier) {
    Box(modifier) {
        Image(
            painterResource(R.drawable.rl_v73_review_dossier), null,
            Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
        )
        Text(
            "内容档案", color = RlColor.ActionVivid, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 104.dp, top = 46.dp),
        )
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 70.dp).fillMaxWidth(.74f).heightIn(min = 100.dp)
                .padding(horizontal = 30.dp, vertical = 18.dp)
                .testTag("review-pending-certificate"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                when (state.sourceType) {
                    SourceType.DIARY -> stringResource(R.string.review_pending_diary)
                    SourceType.RECORDING -> stringResource(R.string.review_pending_recording)
                    SourceType.AI_SCENE -> stringResource(R.string.review_pending_ai_scene)
                },
                color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, tint = RlColor.ActionVivid, modifier = Modifier.size(19.dp))
                Text("状态：已提交 · 等待复核", color = RlColor.ActionVivid, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Text(
            "已提交", color = RlColor.ActionVivid, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 60.dp).rotate(8f)
                .border(2.dp, RlColor.ActionVivid).padding(horizontal = 12.dp, vertical = 5.dp),
        )
        Image(
            painterResource(R.drawable.rl_safety_privacy_stamp), null,
            Modifier.align(Alignment.Center).offset(x = 48.dp, y = 78.dp).size(76.dp),
        )
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NoticePaper(Modifier.weight(1.1f).heightIn(min = 112.dp))
            QueueTicket(Modifier.weight(1f).heightIn(min = 112.dp))
        }
    }
}

@Composable
private fun NoticePaper(modifier: Modifier = Modifier) {
    Box(modifier.testTag("review-pending-notice")) {
        Image(painterResource(R.drawable.rl_torn_note), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.Notifications, null, tint = RlColor.ActionVivid, modifier = Modifier.size(21.dp))
            Text("结果会通过消息通知你", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("你可以继续探索学习，\n好内容值得被认真打磨。", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun QueueTicket(modifier: Modifier = Modifier) {
    Box(modifier.rotate(5f).testTag("review-pending-ticket")) {
        Image(painterResource(R.drawable.rl_email_status_ticket), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 34.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("人工复核排队中", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("已提交 · 等待处理", color = RlColor.ActionVivid, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.review_pending_explanation), color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}
