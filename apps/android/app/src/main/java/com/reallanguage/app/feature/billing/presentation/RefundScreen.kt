package com.reallanguage.app.feature.billing.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.feature.billing.domain.RefundRequestStatus
import java.util.Locale

@Composable
fun RefundScreen(state: RefundUiState, onAction: (RefundAction) -> Unit) {
    BillingEditorialPage(stringResource(R.string.refund_title), { onAction(RefundAction.ReturnBillingRequested) }) { wide ->
        val amountAndForm: @Composable ColumnScope.() -> Unit = {
        BillingAmount("可退款余额", state.refundableAmountFen?.asYuan() ?: "—")
        BillingNotice(stringResource(R.string.refund_consumed_not_refundable))
        if (state.loadFailed) {
            BillingAction(stringResource(R.string.diary_retry), { onAction(RefundAction.RetryLoadRequested) }, enabled = !state.loading)
        } else {
            BillingPaper(Modifier.fillMaxWidth().testTag("refund-form"), PaddingValues(18.dp)) {
                BillingLabel("退款金额")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Icon(Icons.Outlined.AccountBalanceWallet, null, Modifier.size(34.dp), tint = RlColor.ActionVivid)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.refund_available), color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif)
                        Text(state.refundableAmountFen?.asYuan() ?: "—", color = RlColor.ActionVivid, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 28.sp, lineHeight = 36.sp)
                        state.refundMethodLabelZh?.let {
                            Text(stringResource(R.string.refund_method, it), color = RlColor.Ink, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, lineHeight = 23.sp))
                        }
                    }
                }
                state.requestStatus?.let { Text(it.label(), color = if (it == RefundRequestStatus.FAILED || it == RefundRequestStatus.REJECTED) RlColor.Error else RlColor.Secondary) }
                if (state.submitFailed) Text(stringResource(R.string.refund_submit_failed), color = RlColor.Error)
            }
        }
        BillingNotice("提交后可在费用页面或消息中查看结果")
        }
        val supportAndActions: @Composable ColumnScope.() -> Unit = {
        BillingPaper(Modifier.fillMaxWidth()) {
            BillingLabel("人工处理通道")
            Text("未成年人充值问题", fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 17.sp, lineHeight = 25.sp, color = RlColor.Ink)
            Text("如涉及未成年人充值，请提交帮助申请。", fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, color = RlColor.TextSecondary)
            state.supportCaseId?.let { Text(stringResource(R.string.refund_minor_support_submitted), color = RlColor.Secondary) }
            if (state.supportFailed) Text(stringResource(R.string.refund_minor_support_failed), color = RlColor.Error)
            BillingAction(stringResource(R.string.refund_minor_support), { onAction(RefundAction.MinorRechargeSupportRequested) },
                Modifier.testTag("refund-support"), enabled = !state.supportSubmitting && state.supportCaseId == null, secondary = true)
        }
        BillingAction(stringResource(R.string.refund_submit), { onAction(RefundAction.SubmitRequested) }, Modifier.fillMaxWidth().testTag("refund-submit"), enabled = state.submitEnabled)
        BillingAction(stringResource(R.string.refund_return_billing), { onAction(RefundAction.ReturnBillingRequested) }, Modifier.fillMaxWidth(), secondary = true)
        }
        if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1.3f).padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = amountAndForm)
            Column(Modifier.weight(1f).padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = supportAndActions)
        } else {
            amountAndForm()
            supportAndActions()
        }
    }
}

private fun Long.asYuan(): String = "￥%.2f".format(Locale.CHINA, this / 100.0)
private fun RefundRequestStatus.label(): String = when (this) {
    RefundRequestStatus.REQUESTED -> "退款申请已提交"
    RefundRequestStatus.REVIEWING -> "退款审核中"
    RefundRequestStatus.APPROVED -> "退款已批准"
    RefundRequestStatus.REFUNDED -> "退款已原路退回"
    RefundRequestStatus.REJECTED -> "退款申请未通过"
    RefundRequestStatus.FAILED -> "退款处理失败"
}
