package com.reallanguage.app.feature.billing.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*
import com.reallanguage.app.feature.billing.domain.DailyAiCharge
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun AiBillingScreen(state: AiBillingUiState, onAction: (AiBillingAction) -> Unit) {
    val groups = remember(state.charges, state.dailyCharges) {
        if (state.dailyCharges.isNotEmpty()) state.dailyCharges else state.charges.groupBy {
            Instant.ofEpochMilli(it.occurredAtEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString() to it.operationLabelZh
        }.map { (key, values) -> DailyAiCharge(key.first, key.second, values.size,
            values.fold(0L) { sum, row -> Math.addExact(sum, row.amountFen) }) }
            .sortedWith(compareByDescending<DailyAiCharge> { it.date }.thenBy { it.operationLabelZh })
    }
    NotebookPage("AI 费用", { onAction(AiBillingAction.ReturnAccountRequested) }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 520.dp
            val fontScale = LocalDensity.current.fontScale.coerceAtMost(1.6f)
            NotebookPanel(Modifier.fillMaxWidth().height(if (compact) 140.dp else 92.dp * fontScale)) {
                if (compact) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("账户余额", color = RlColor.TextSecondary, fontSize = 13.sp)
                        Text(state.balanceAmountFen?.yuan() ?: "—", color = RlColor.ActionVivid, fontSize = 22.sp,
                            modifier = Modifier.testTag("ai-billing-balance"))
                    }
                    BillingNavigationRow(onAction, Modifier.fillMaxWidth())
                } else Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Image(painterResource(R.drawable.rl_billing_receipt_20261124), null,
                        Modifier.width(90.dp).fillMaxHeight(), contentScale = ContentScale.Fit)
                    Column(Modifier.weight(1f)) { BalanceValue(state.balanceAmountFen) }
                    BillingNavigationRow(onAction, Modifier.width(280.dp))
                }
            }
        }
        NotebookPanel(Modifier.weight(1f).fillMaxWidth().testTag("ai-billing-ledger")) {
            Box(Modifier.editorialPaper(RlColor.Secondary)) {
                Text("消费明细", Modifier.padding(horizontal = 14.dp, vertical = 3.dp), fontSize = 14.sp, color = androidx.compose.ui.graphics.Color.White)
            }
            LedgerColumns(listOf("日期", "用途", "次数", "费用"), true)
            when {
                state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                state.loadFailed -> {
                    Text("费用记录加载失败", color = RlColor.Error)
                    BillingAction("重试", { onAction(AiBillingAction.RetryRequested) })
                }
                groups.isEmpty() -> Text("暂无消费记录", color = RlColor.TextSecondary)
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("ai-billing-daily-list")) {
                    items(groups, key = { it.date to it.operationLabelZh }) { group ->
                        Column(Modifier.fillMaxWidth().testTag("ai-daily-${group.date}-${group.operationLabelZh}")) {
                            LedgerColumns(listOf(group.date.displayDate(), group.operationLabelZh,
                                group.count.toString(), "-" + group.amountFen.yuan()))
                            HorizontalDivider(color = RlColor.Border.copy(alpha = .5f), thickness = .5.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun BalanceValue(amount: Long?) {
    Text("账户余额", color = RlColor.TextSecondary, fontSize = 13.sp)
    Text(amount?.yuan() ?: "—", color = RlColor.ActionVivid, fontSize = 28.sp, lineHeight = 35.sp,
        modifier = Modifier.testTag("ai-billing-balance"))
}
@Composable private fun BillingNavigationRow(onAction: (AiBillingAction) -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NotebookAction("申请退款", { onAction(AiBillingAction.RefundRequested) }, Modifier.weight(1f))
        NotebookAction("充值", { onAction(AiBillingAction.RechargeRequested) }, Modifier.weight(1f), primary = true)
    }
}
@Composable private fun LedgerColumns(values: List<String>, heading: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = if (heading) 4.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val weights = listOf(.2f, .43f, .12f, .25f)
        values.forEachIndexed { index, value ->
            Text(value, Modifier.weight(weights[index]), fontSize = if (heading) 12.sp else 14.sp,
                lineHeight = 21.sp, color = if (heading) RlColor.Secondary else if (index == 3) RlColor.ActionVivid else RlColor.Ink,
                textAlign = if (index == 3) androidx.compose.ui.text.style.TextAlign.End else if (index == 2)
                    androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start)
        }
    }
}
private fun Long.yuan() = "￥" + java.math.BigDecimal.valueOf(this, 2).toPlainString()
private fun String.displayDate(): String = LocalDate.parse(this).let {
    if (it.year == LocalDate.now().year) "${it.monthValue}月${it.dayOfMonth}日" else "${it.year}/${it.monthValue}/${it.dayOfMonth}"
}

@Composable internal fun BillingNotice(text: String, modifier: Modifier = Modifier) {
    BillingPaper(modifier, PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = RlColor.TextSecondary)
            Text(text, Modifier.weight(1f), color = RlColor.TextSecondary,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 19.sp))
        }
    }
}
