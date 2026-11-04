package com.reallanguage.app.feature.billing.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.feature.billing.domain.AiChargeType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AiBillingScreen(state: AiBillingUiState, onAction: (AiBillingAction) -> Unit) {
    BillingEditorialPage(stringResource(R.string.ai_billing_title), { onAction(AiBillingAction.ReturnAccountRequested) }, wideContentTop = 44.dp) { wide ->
        val amount = state.balanceAmountFen?.asYuan() ?: "—"
        if (wide) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.weight(1.6f).padding(start = 70.dp, top = 38.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.ai_billing_balance_label), color = RlColor.TextSecondary,
                            fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 18.sp)
                        Text(amount, color = RlColor.ActionVivid,
                            fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 29.sp, lineHeight = 36.sp)
                    }
                }
                BillingBalanceNote(amount, Modifier.weight(.65f).offset(x = 3.dp))
                BillingNavigation(onAction, Modifier.weight(.55f))
            }
        } else {
            BillingAmount(stringResource(R.string.ai_billing_balance_label), amount)
            BillingNavigation(onAction, Modifier.fillMaxWidth(), horizontal = true)
        }
        Box(Modifier.fillMaxWidth().padding(end = if (wide) 38.dp else 0.dp)) {
        BillingPaper(Modifier.fillMaxWidth().testTag("ai-billing-ledger"),
            PaddingValues(start = 14.dp, end = if (wide) 26.dp else 14.dp, top = 10.dp, bottom = 10.dp), spacing = 0.dp) {
            if (wide) Spacer(Modifier.height(12.dp))
            else BillingLabel(stringResource(R.string.ai_billing_details_title))
            if (wide) BillingColumns(listOf("日期", "类型", "模型", "费用（人民币）"), true)
            when {
                state.loading -> {
                    Text(stringResource(R.string.ai_billing_loading), color = RlColor.TextSecondary)
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = RlColor.Primary, trackColor = RlColor.Border)
                }
                state.loadFailed -> {
                    Text(stringResource(R.string.ai_billing_failed), color = RlColor.Error)
                    BillingAction(stringResource(R.string.diary_retry), { onAction(AiBillingAction.RetryRequested) })
                }
                state.charges.isEmpty() -> Text(stringResource(R.string.ai_billing_empty), color = RlColor.TextSecondary)
                else -> state.charges.forEach { charge ->
                    Column(Modifier.fillMaxWidth().testTag("ai-charge-${charge.chargeId}")) {
                        if (wide) BillingColumns(listOf(charge.occurredAtEpochMillis.asShortDate(), charge.operationLabelZh,
                            charge.modelSource, "-" + charge.amountFen.asYuan()), operationDetail = stringResource(charge.callType.labelResource()),
                            operationVisual = charge.operationLabelZh.billingVisual(charge.callType))
                        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(charge.operationLabelZh, color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 18.sp)
                                Text("${charge.occurredAtEpochMillis.asShortDate()} · ${charge.modelSource}", color = RlColor.TextSecondary, fontSize = 13.sp)
                                Text(stringResource(charge.callType.labelResource()), color = RlColor.TextSecondary, fontSize = 12.sp)
                            }
                            Text("-" + charge.amountFen.asYuan(), color = RlColor.ActionVivid, fontSize = 18.sp, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif)
                        }
                        Canvas(Modifier.fillMaxWidth().height(1.dp)) {
                            drawLine(RlColor.Border.copy(alpha = .7f), Offset.Zero, Offset(size.width, 0f), strokeWidth = .5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())))
                        }
                    }
                }
            }
        }
            // The printed section tab straddles the ledger edge in the reference.
            // It must not consume another full row above the real table heading.
            if (wide) Box(Modifier.align(Alignment.TopStart).offset(x = 10.dp, y = (-5).dp)) {
                BillingLabel(stringResource(R.string.ai_billing_details_title))
            }
        }
        if (wide) Row(Modifier.fillMaxWidth().padding(end = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BillingNotice(stringResource(R.string.ai_billing_non_ai_free), Modifier.weight(1f))
            BillingNotice(stringResource(R.string.ai_billing_no_duplicate_charge), Modifier.weight(1f))
        } else {
            BillingNotice(stringResource(R.string.ai_billing_non_ai_free))
            BillingNotice(stringResource(R.string.ai_billing_no_duplicate_charge))
        }
    }
}

@Composable private fun BillingBalanceNote(amount: String, modifier: Modifier) {
    Box(modifier.heightIn(min = 104.dp)) {
        Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.matchParentSize().padding(horizontal = 9.dp, vertical = 9.dp)) {
            var y = 13.dp.toPx()
            while (y < size.height) {
                drawLine(RlColor.Border.copy(alpha = .5f), Offset(0f, y), Offset(size.width, y), strokeWidth = .4.dp.toPx())
                y += 13.dp.toPx()
            }
        }
        Image(painterResource(R.drawable.rl_v73_focus_sprig), null, Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 3.dp).width(34.dp).height(60.dp), alpha = .42f, contentScale = ContentScale.Fit)
        Column(Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 28.dp, bottom = 15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("账户余额", color = RlColor.TextSecondary, fontSize = 13.sp, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif)
            Text(amount, color = RlColor.ActionVivid, fontSize = 23.sp, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif)
        }
        Image(painterResource(R.drawable.rl_home_scene_paperclip), null, Modifier.align(Alignment.TopEnd).offset(x = (-22).dp, y = (-17).dp).width(14.dp).height(40.dp), contentScale = ContentScale.Fit)
    }
}

private data class BillingOperationVisual(val icon: ImageVector, val color: Color)
private fun String.billingVisual(type: AiChargeType): BillingOperationVisual = when {
    type == AiChargeType.AUTOMATIC_RETRY || type == AiChargeType.USER_RETRY || type == AiChargeType.PROVIDER_SWITCH -> BillingOperationVisual(Icons.Outlined.Sync, RlColor.TextSecondary)
    contains("安全") || contains("审核") -> BillingOperationVisual(Icons.Outlined.VerifiedUser, RlColor.NoteAccent)
    contains("日记") -> BillingOperationVisual(Icons.AutoMirrored.Outlined.MenuBook, RlColor.Secondary)
    contains("场景") -> BillingOperationVisual(Icons.Outlined.Image, RlColor.ActionVivid)
    else -> BillingOperationVisual(Icons.Outlined.Info, RlColor.Primary)
}

@Composable private fun BillingNavigation(onAction: (AiBillingAction) -> Unit, modifier: Modifier, horizontal: Boolean = false) {
    if (horizontal) Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BillingAction(stringResource(R.string.ai_billing_recharge), { onAction(AiBillingAction.RechargeRequested) }, Modifier.weight(1f))
        BillingAction(stringResource(R.string.ai_billing_refund), { onAction(AiBillingAction.RefundRequested) }, Modifier.weight(1f), color = RlColor.Secondary)
    } else Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BillingAction(stringResource(R.string.ai_billing_recharge), { onAction(AiBillingAction.RechargeRequested) }, Modifier.fillMaxWidth())
        BillingAction(stringResource(R.string.ai_billing_refund), { onAction(AiBillingAction.RefundRequested) }, Modifier.fillMaxWidth(), color = RlColor.Secondary)
    }
}

@Composable internal fun BillingNotice(text: String, modifier: Modifier = Modifier) {
    BillingPaper(modifier, PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = RlColor.TextSecondary)
            Text(text, Modifier.weight(1f), color = RlColor.TextSecondary,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 19.sp))
        }
    }
}

@Composable private fun BillingColumns(values: List<String>, heading: Boolean = false, operationDetail: String? = null, operationVisual: BillingOperationVisual? = null) {
    val weights = listOf(.65f, 1.32f, .96f, 1.07f)
    Row(Modifier.fillMaxWidth().heightIn(min = if (heading) 25.dp else 33.dp).drawBehind {
        val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))
        for (fraction in listOf(.1625f, .4925f, .7325f)) drawLine(RlColor.Border.copy(alpha = .7f), Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height), strokeWidth = .5.dp.toPx(), pathEffect = dash)
    }, verticalAlignment = Alignment.CenterVertically) {
        values.forEachIndexed { index, value ->
            Column(Modifier.weight(weights[index]).padding(horizontal = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (index == 3 && !heading) Surface(color = Color.Transparent, border = BorderStroke(1.dp, RlColor.ActionVivid)) {
                    Text(value, Modifier.padding(horizontal = 12.dp, vertical = 2.dp), color = RlColor.ActionVivid,
                        fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 16.sp, lineHeight = 20.sp)
                } else if (index == 1 && operationVisual != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(20.dp).background(operationVisual.color, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(operationVisual.icon, null, Modifier.size(15.dp), tint = Color.White)
                    }
                    Text(value, Modifier.weight(1f), textAlign = TextAlign.Center, color = RlColor.Ink,
                        fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
                } else Text(value, textAlign = TextAlign.Center, color = if (heading) RlColor.TextSecondary else RlColor.Ink,
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = if (heading) 13.sp else 14.sp, lineHeight = 20.sp)
                if (index == 1 && operationDetail != null) Text(operationDetail, textAlign = TextAlign.Center,
                    color = RlColor.TextSecondary, fontSize = 10.sp, lineHeight = 13.sp)
            }
        }
    }
}
private fun AiChargeType.labelResource(): Int = when (this) {
    AiChargeType.INITIAL_CALL -> R.string.ai_billing_initial_call
    AiChargeType.AUTOMATIC_RETRY -> R.string.ai_billing_automatic_retry
    AiChargeType.USER_RETRY -> R.string.ai_billing_user_retry
    AiChargeType.PROVIDER_SWITCH -> R.string.ai_billing_provider_switch
}
private fun Long.asYuan() = "￥%.2f".format(Locale.CHINA, this / 100.0)
private fun Long.asShortDate() = SimpleDateFormat("M 月 d 日", Locale.CHINA).format(Date(this))
