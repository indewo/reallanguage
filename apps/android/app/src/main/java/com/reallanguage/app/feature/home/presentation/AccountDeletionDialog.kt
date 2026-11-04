package com.reallanguage.app.feature.home.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlImmersiveDialogWindow
import com.reallanguage.app.feature.billing.presentation.BillingAction

@Composable
fun AccountDeletionDialog(state: AccountDeletionUiState, onAction: (AccountDeletionAction) -> Unit) {
    Dialog(onDismissRequest = { if (!state.deleting) onAction(AccountDeletionAction.CancelRequested) }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        RlImmersiveDialogWindow()
        AccountDeletionContent(state, onAction)
    }
}

@Composable
internal fun AccountDeletionContent(state: AccountDeletionUiState, onAction: (AccountDeletionAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(18.dp), contentAlignment = Alignment.Center) {
        val wide = maxWidth >= 650.dp
        Box(Modifier.widthIn(max = 610.dp).fillMaxWidth(if (wide) .67f else 1f).heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
            Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            val stitch = RlColor.ActionVivid.copy(alpha = .7f)
            Canvas(Modifier.matchParentSize()) {
                val inset = 14.dp.toPx()
                drawRoundRect(stitch, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2), CornerRadius(14.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 4.dp.toPx()))))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = if (wide) 42.dp else 28.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(stringResource(R.string.account_delete_confirm_title), fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 35.sp, color = RlColor.ActionVivid, textAlign = TextAlign.Center)
                Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(138.dp).height(6.dp), contentScale = ContentScale.FillBounds)
                Text(stringResource(if (state.failed) R.string.account_delete_failed else R.string.account_delete_confirm_body), color = if (state.failed) RlColor.Error else RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 16.sp, lineHeight = 26.sp, textAlign = TextAlign.Center)
                Text("请确认是否继续注销。", color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BillingAction(stringResource(R.string.account_delete_cancel), { onAction(AccountDeletionAction.CancelRequested) }, Modifier.weight(1f).fillMaxHeight(), !state.deleting, secondary = true)
                    BillingAction(stringResource(if (state.deleting) R.string.account_delete_deleting else R.string.account_delete_confirm), { onAction(AccountDeletionAction.ConfirmRequested) }, Modifier.weight(1f).fillMaxHeight(), !state.deleting)
                }
            }
            Image(painterResource(R.drawable.rl_black_binder_clip), null, Modifier.align(Alignment.TopStart).offset(x = 35.dp, y = (-9).dp).size(35.dp), contentScale = ContentScale.Fit)
        }
    }
}
