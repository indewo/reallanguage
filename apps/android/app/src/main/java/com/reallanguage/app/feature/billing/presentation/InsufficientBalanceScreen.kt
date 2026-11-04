package com.reallanguage.app.feature.billing.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.feature.billing.domain.BalanceOriginState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun InsufficientBalanceScreen(state: InsufficientBalanceUiState, onAction: (InsufficientBalanceAction) -> Unit) {
    BillingEditorialPage("", { onAction(InsufficientBalanceAction.LeaveRequested) }) { wide ->
        if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(34.dp), verticalAlignment = Alignment.Top) {
            InsufficientLedger(state, Modifier.weight(1.15f).padding(top = 32.dp), compactGaps = true)
            PausedOperation(state, onAction, Modifier.weight(1f))
        } else {
            PausedOperation(state, onAction, Modifier.fillMaxWidth())
            InsufficientLedger(state, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun InsufficientLedger(state: InsufficientBalanceUiState, modifier: Modifier, compactGaps: Boolean = false) {
    Column(modifier.testTag("insufficient-balance-ledger"), verticalArrangement = Arrangement.spacedBy(if (compactGaps) 2.dp else 6.dp)) {
        BillingLabel("账户余额")
        BillingPaper(Modifier.fillMaxWidth(), PaddingValues(12.dp), spacing = 2.dp) {
            BillingAmount("当前余额", state.balanceAmountFen?.asYuan() ?: "—")
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text("日期", Modifier.weight(.8f), fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif)
                Text("类型", Modifier.weight(1.5f), fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif)
                Text("模型", Modifier.weight(1f), fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif)
            }
            state.recentCharges.forEach { charge ->
                HorizontalDivider(color = RlColor.Border.copy(alpha = .65f))
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).testTag("insufficient-charge-${charge.chargeId}"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(charge.occurredAtEpochMillis.asShortDate(), Modifier.weight(.8f), fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp)
                    Text(charge.operationLabelZh, Modifier.weight(1.5f), fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 18.sp)
                    Text(charge.modelSource, Modifier.weight(1f), fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
        BillingNotice("充值成功后将回到原操作。")
    }
}

@Composable
private fun PausedOperation(state: InsufficientBalanceUiState, onAction: (InsufficientBalanceAction) -> Unit, modifier: Modifier) {
    BillingPaper(modifier.testTag("insufficient-balance-paused-operation"), PaddingValues(12.dp), spacing = 4.dp) {
        BillingLabel("暂停中的操作", RlColor.ActionVivid)
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("当前操作：", color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 18.sp)
                Text(state.context.originState.operationLabel(), color = RlColor.ActionVivid, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 23.sp, lineHeight = 29.sp)
            }
            Icon(Icons.Outlined.PauseCircle, null, Modifier.size(34.dp), tint = RlColor.ActionVivid)
        }
        BillingPaper(Modifier.fillMaxWidth(), PaddingValues(horizontal = 8.dp, vertical = 6.dp), spacing = 4.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Wallet, null, Modifier.size(28.dp), tint = RlColor.ActionVivid)
                Text("余额不足", fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 18.sp, lineHeight = 25.sp, color = RlColor.Ink)
            }
            Text(stringResource(R.string.insufficient_balance_explanation), color = RlColor.Ink, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp))
            HorizontalDivider(color = RlColor.Border)
            Text("不会显示 Token 余额，也不会产生单次付费确认。", color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 11.sp, lineHeight = 17.sp)
            if (state.exitFailed) Text(stringResource(R.string.insufficient_balance_exit_failed), color = RlColor.Error)
            BillingAction("去充值", { onAction(InsufficientBalanceAction.RechargeRequested) }, Modifier.fillMaxWidth().testTag("insufficient-balance-recharge"), enabled = !state.applyingExitPolicy)
            BillingAction(stringResource(R.string.insufficient_balance_leave), { onAction(InsufficientBalanceAction.LeaveRequested) }, Modifier.fillMaxWidth().testTag("insufficient-balance-leave"), enabled = !state.applyingExitPolicy, secondary = true)
        }
    }
}

private fun Long.asYuan(): String = "￥%.2f".format(Locale.CHINA, this / 100.0)
private fun Long.asShortDate(): String = SimpleDateFormat("M月d日", Locale.CHINA).format(Date(this))
private fun BalanceOriginState.operationLabel(): String = when (this) {
    BalanceOriginState.NORMAL_LEARNING -> "章节学习"
    BalanceOriginState.UNIFIED_REVIEW -> "统一回顾"
    BalanceOriginState.UNFIXED_DIARY_OR_AI_SCENE -> "场景生成"
    BalanceOriginState.RECORDING_AWAITING_TRANSCRIPTION -> "录音转写"
    BalanceOriginState.FIXED_UNSTARTED_CHAPTER -> "固定章节"
}
