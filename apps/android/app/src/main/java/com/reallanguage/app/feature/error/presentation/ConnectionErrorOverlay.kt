package com.reallanguage.app.feature.error.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlSecondaryButton
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.core.designsystem.RlPaperPanel

@Composable
fun ConnectionErrorOverlay(
    state: ConnectionErrorUiState,
    onAction: (ConnectionErrorAction) -> Unit,
    handleSystemBack: Boolean = true,
) {
    BackHandler(enabled = handleSystemBack) { onAction(ConnectionErrorAction.ReturnRequested) }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        val enlarged = LocalDensity.current.fontScale > 1.3f
        val fullWidthActions = enlarged || maxWidth < 450.dp
        Box(Modifier.widthIn(max = 450.dp).fillMaxWidth()
            .height(if (enlarged) maxHeight - 16.dp else (maxHeight * .81f).coerceIn(275.dp, 390.dp))
            .testTag("connection-error-sheet")) {
            Image(painterResource(R.drawable.rl_v73_error_connection_paper), null,
                Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 35.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .testTag("connection-error-copy"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.rl_connection_unavailable),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp).align(Alignment.CenterHorizontally),
                )
                Text(
                    text = stringResource(R.string.connection_error_title),
                    modifier = Modifier.fillMaxWidth(),
                    color = RlColor.Ink,
                    textAlign = TextAlign.Center,
                    fontFamily = EditorialFont.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                )
                HorizontalDivider(color = RlColor.Border)
                Text(
                    stringResource(R.string.connection_error_explanation),
                    modifier = Modifier.fillMaxWidth(),
                    color = RlColor.TextPrimary,
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Text(
                    stringResource(R.string.connection_error_no_offline),
                    modifier = Modifier.fillMaxWidth(),
                    color = RlColor.TextPrimary,
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                state.resumePoint.temporaryTextInput?.let {
                    Text(
                        stringResource(R.string.connection_error_temporary_input_kept),
                        modifier = Modifier.fillMaxWidth(),
                        color = RlColor.TextSecondary,
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("connection-error-status"),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.reconnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = RlColor.TextSecondary,
                            strokeWidth = 1.5.dp,
                        )
                        Spacer(Modifier.widthIn(min = RlSpace.Space8))
                    }
                    Text(
                        text = when {
                            state.reconnecting -> stringResource(R.string.connection_error_checking)
                            state.reconnectFailed -> stringResource(R.string.connection_error_retry_failed)
                            else -> stringResource(R.string.connection_error_ready)
                        },
                        color = if (state.reconnectFailed) RlColor.Error else RlColor.TextSecondary,
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
                HorizontalDivider(color = RlColor.Divider)
                Row(
                    modifier = Modifier.fillMaxWidth(if (fullWidthActions) 1f else .74f)
                        .align(Alignment.CenterHorizontally),
                    horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
                ) {
                    ErrorPaperAction(
                        text = stringResource(R.string.connection_error_return),
                        onClick = { onAction(ConnectionErrorAction.ReturnRequested) },
                        filled = false,
                        modifier = Modifier.weight(0.72f).heightIn(min = 48.dp).testTag("connection-error-return"),
                    )
                    ErrorPaperAction(
                        text = stringResource(R.string.connection_error_reconnect),
                        enabled = !state.reconnecting,
                        onClick = { onAction(ConnectionErrorAction.ReconnectRequested) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("connection-error-reconnect"),
                    )
                }
                Text(
                    stringResource(R.string.connection_error_restore_note),
                    modifier = Modifier.fillMaxWidth(),
                    color = RlColor.TextSecondary,
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}
