package com.reallanguage.app.feature.billing.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlKeyboardAwareOutlinedTextField
import com.reallanguage.app.core.designsystem.RlKeyboardAwareBasicTextField
import androidx.compose.foundation.border
import com.reallanguage.app.feature.billing.domain.PaymentMethod
import com.reallanguage.app.feature.billing.domain.RechargeOrderStatus
import java.util.Locale

@Composable
fun RechargeScreen(state: RechargeUiState, onAction: (RechargeAction) -> Unit) {
    BillingEditorialPage(stringResource(R.string.recharge_title), { onAction(RechargeAction.ReturnRequested) }) { wide ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            BillingAmount(stringResource(R.string.recharge_current_balance), state.balanceAmountFen?.asYuan() ?: "—", Modifier.weight(1f))
            if (wide) BillingPaper(Modifier.weight(.9f)) {
                Text("让每一次真实表达，\n都有继续练习的空间。", color = RlColor.Primary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 16.sp, lineHeight = 25.sp)
            }
        }
        if (state.loadFailed) BillingAction(stringResource(R.string.diary_retry), { onAction(RechargeAction.RetryLoadRequested) })
        else {
            if (wide) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                state.recommendedOptions.forEach { option ->
                    RechargeAmountCard(option.amountFen, option.visionZh, state.selectedAmountFen == option.amountFen && state.customAmountText.isEmpty(),
                        Modifier.weight(1f).fillMaxHeight()) { onAction(RechargeAction.RecommendedAmountSelected(option.amountFen)) }
                }
                RechargeCustomAmount(state, onAction, Modifier.weight(1f).fillMaxHeight())
            } else {
                state.recommendedOptions.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { option -> RechargeAmountCard(option.amountFen, option.visionZh,
                            state.selectedAmountFen == option.amountFen && state.customAmountText.isEmpty(), Modifier.weight(1f).fillMaxHeight()) {
                            onAction(RechargeAction.RecommendedAmountSelected(option.amountFen))
                        } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                RechargeCustomAmount(state, onAction, Modifier.fillMaxWidth())
            }
            val payment: @Composable ColumnScope.() -> Unit = {
                BillingLabel(stringResource(R.string.recharge_payment_method))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PaymentMethod.entries.forEach { method ->
                        Surface(onClick = { onAction(RechargeAction.PaymentMethodSelected(method)) },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("recharge-payment-${method.name}"),
                            color = Color.Transparent, border = BorderStroke(.7.dp, if (state.selectedMethod == method) RlColor.ActionVivid else RlColor.Border)) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Outlined.AccountBalanceWallet, null, Modifier.size(19.dp), tint = if (method == PaymentMethod.WECHAT_PAY) RlColor.Secondary else RlColor.Primary)
                                Text(stringResource(if (method == PaymentMethod.WECHAT_PAY) R.string.recharge_wechat else R.string.recharge_alipay), Modifier.weight(1f), fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
                                RadioButton(selected = state.selectedMethod == method, onClick = null, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                state.orderStatus?.let { Text(it.statusText(), color = if (it == RechargeOrderStatus.FAILED) RlColor.Error else RlColor.TextSecondary) }
                if (state.submitFailed) Text(stringResource(R.string.recharge_submit_failed), color = RlColor.Error)
                BillingNotice(stringResource(R.string.recharge_success_return))
            }
            val actions: @Composable ColumnScope.() -> Unit = {
                BillingAction(state.selectedAmountFen?.let { stringResource(R.string.recharge_confirm_amount, it.asYuan()) }
                    ?: stringResource(R.string.recharge_submit), { onAction(RechargeAction.SubmitRequested) }, Modifier.fillMaxWidth(), enabled = state.submitEnabled)
                BillingAction(stringResource(R.string.recharge_return), { onAction(RechargeAction.ReturnRequested) }, Modifier.fillMaxWidth(), secondary = true)
            }
            if (wide) Row(Modifier.fillMaxWidth().padding(top = 7.dp, end = 26.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), content = payment)
                Column(Modifier.width(180.dp).padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = actions)
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = payment)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), content = actions)
            }
        }
    }
}

@Composable private fun RechargeAmountCard(amount: Long, vision: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    BillingPaper(modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .border(if (selected) 1.dp else 0.dp, if (selected) RlColor.ActionVivid else Color.Transparent, RoundedCornerShape(3.dp))
        .testTag("recharge-amount-$amount"), PaddingValues(10.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Text(if (amount % 100L == 0L) "￥${amount / 100}" else amount.asYuan(), Modifier.align(Alignment.Center).padding(vertical = 9.dp), color = RlColor.ActionVivid,
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 25.sp, lineHeight = 31.sp)
            if (selected) Icon(Icons.Outlined.CheckCircle, "已选择", Modifier.align(Alignment.TopEnd).size(18.dp), tint = RlColor.ActionVivid)
        }
        if (vision.isNotBlank()) Text(vision, Modifier.align(Alignment.CenterHorizontally), color = RlColor.TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
    }
}
@Composable private fun RechargeCustomAmount(state: RechargeUiState, onAction: (RechargeAction) -> Unit, modifier: Modifier) {
    BillingPaper(modifier.testTag("recharge-custom-amount"), PaddingValues(12.dp)) {
        Text(stringResource(R.string.recharge_custom_amount), color = RlColor.Ink, fontSize = 13.sp, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif)
        RlKeyboardAwareBasicTextField(value = state.customAmountText,
            onValueChange = { onAction(RechargeAction.CustomAmountChanged(it)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp).border(.7.dp, RlColor.Border).padding(horizontal = 8.dp, vertical = 6.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp, color = RlColor.Ink),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
            decorationBox = { inner -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("￥", fontSize = 13.sp, lineHeight = 18.sp)
                Box(Modifier.weight(1f)) { if (state.customAmountText.isEmpty()) Text("输入金额", fontSize = 11.sp, lineHeight = 18.sp, color = RlColor.TextMuted); inner() }
            } }, singleLine = true)
    }
}
private fun Long.asYuan() = "￥%.2f".format(Locale.CHINA, this / 100.0)
private fun RechargeOrderStatus.statusText(): String = when (this) {
    RechargeOrderStatus.CREATED -> "充值订单已创建"
    RechargeOrderStatus.PENDING_PAYMENT -> "等待支付"
    RechargeOrderStatus.IN_PROGRESS -> "支付处理中"
    RechargeOrderStatus.PAID -> "充值成功"
    RechargeOrderStatus.FAILED -> "支付失败，请重试或返回"
    RechargeOrderStatus.CANCELLED -> "支付已取消，可重试或返回"
}
