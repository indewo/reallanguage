package com.reallanguage.app.feature.error.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*
import com.reallanguage.app.feature.error.domain.ServiceFailureCategory

/** Reference 50. Decorative folder and independently measured notes never constrain the live text. */
@Composable
fun ServiceFailureScreen(state: ServiceFailureUiState, onAction: (ServiceFailureAction) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Image(painterResource(R.drawable.rl_v73_error_page_background), null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val compact = maxWidth < 720.dp || maxHeight < 350.dp || LocalDensity.current.fontScale > 1.3f ||
                state.preservedItemsZh.size > 2 || state.preservedItemsZh.any { it.length > 90 } ||
                state.context.preservedDataLabelZh.length > 40
            val pageHeight = maxHeight
            val pageWidth = maxWidth
            if (!compact) ServiceFailurePress(state,
                Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = pageHeight * .105f)
                    .width(pageWidth * .355f).height(pageHeight * .85f))
            if (!compact) ServiceFailureFolder(state,
                Modifier.align(Alignment.TopEnd).offset(x = (-12).dp, y = pageHeight * .105f)
                    .width(pageWidth * .315f), minimumHeight = pageHeight * .82f)
            Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 16.dp else 24.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Column(Modifier.fillMaxWidth().weight(1f)
                    .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    IconButton({ onAction(ServiceFailureAction.ReturnRequested) }, Modifier.align(Alignment.CenterStart)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink)
                    }
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.service_failure_formation_title), fontFamily = EditorialFont.Serif,
                            fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, color = RlColor.Ink)
                        Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null,
                            Modifier.width(140.dp).height(6.dp), contentScale = ContentScale.FillBounds,
                            colorFilter = ColorFilter.tint(RlColor.Ink))
                    }
                }
                if (compact) {
                    ServiceFailureMessage(state, Modifier.fillMaxWidth())
                    ServiceFailureFolder(state, Modifier.fillMaxWidth())
                } else {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        ServiceFailureMessage(state, Modifier.align(Alignment.TopStart)
                            .padding(top = 8.dp).offset(x = pageWidth * .314f)
                            .width(pageWidth * .335f).heightIn(min = pageHeight * .60f))
                    }
                }
                }
                Row(Modifier.fillMaxWidth(if (compact) 1f else .43f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ErrorPaperAction(stringResource(if (state.context.category == ServiceFailureCategory.RECORDING_TRANSCRIPTION)
                        R.string.service_failure_open_transcription else R.string.service_failure_retry_formation),
                        enabled = !state.retrying, onClick = { onAction(ServiceFailureAction.RetryRequested) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("service-failure-retry"))
                    ErrorPaperAction(stringResource(R.string.service_failure_return_selection), filled = false,
                        onClick = { onAction(ServiceFailureAction.ReturnRequested) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("service-failure-return"))
                }
                Text(stringResource(R.string.service_failure_footer), color = RlColor.TextSecondary,
                    fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ServiceFailurePress(state: ServiceFailureUiState, modifier: Modifier) {
    val bitmap = ImageBitmap.imageResource(R.drawable.rl_service_failure_press_v3)
    val painter = remember(bitmap) { BitmapPainter(bitmap, IntOffset(33, 166), IntSize(961, 1086)) }
    BoxWithConstraints(modifier) {
        Image(painter, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.offset(x = maxWidth * .405f, y = maxHeight * .163f)
            .width(maxWidth * .34f).heightIn(min = maxHeight * .15f),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(state.context.stepLabelZh, color = RlColor.Ink, fontFamily = EditorialFont.Serif,
                fontSize = 14.sp, lineHeight = 19.sp)
            Text("WORK ORDER", color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif,
                fontSize = 9.sp, lineHeight = 13.sp)
            Text("— 暂停中 —", color = RlColor.ActionVivid, fontFamily = EditorialFont.Serif,
                fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun ServiceFailureMessage(state: ServiceFailureUiState, modifier: Modifier) {
    Box(modifier.testTag("service-failure-boundary")) {
        Image(painterResource(R.drawable.rl_v73_error_rust_note), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.PauseCircle, null, tint = RlColor.OnActionVivid, modifier = Modifier.size(34.dp))
            Text(stringResource(R.string.service_failure_unavailable), color = RlColor.OnActionVivid,
                fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                lineHeight = 27.sp, textAlign = TextAlign.Center)
            androidx.compose.material3.HorizontalDivider(color = RlColor.OnActionVivid.copy(alpha = .45f))
            listOf(stringResource(R.string.service_failure_not_completed),
                stringResource(R.string.service_failure_preserved, state.context.preservedDataLabelZh),
                stringResource(R.string.service_failure_no_false_results)).forEach { label ->
                Text(label, color = RlColor.OnActionVivid, fontFamily = EditorialFont.Serif,
                    fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ServiceFailureFolder(state: ServiceFailureUiState, modifier: Modifier, minimumHeight: Dp = 260.dp) {
    Box(modifier.heightIn(min = minimumHeight).testTag("service-failure-preserved")) {
        Image(painterResource(R.drawable.rl_v73_error_dossier_blank), null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxWidth().rotate(6f).padding(start = 26.dp, end = 34.dp, top = 30.dp, bottom = 70.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("已保留的内容", color = Color(0xfff3ecdb), fontFamily = EditorialFont.Serif,
                fontSize = 15.sp, lineHeight = 20.sp)
            val items = state.preservedItemsZh.ifEmpty { listOf(state.context.preservedDataLabelZh,
                stringResource(R.string.service_failure_safe_data)) }
            items.forEachIndexed { index, text ->
                Box(Modifier.fillMaxWidth()) {
                    Image(painterResource(R.drawable.rl_torn_note), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 13.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}", color = RlColor.ActionVivid, fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp)
                        Icon(Icons.Outlined.CheckCircle, null, tint = RlColor.ActionVivid, modifier = Modifier.size(20.dp))
                        Text(text, color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 12.sp,
                            lineHeight = 20.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (state.retryFailed) Text(stringResource(R.string.service_failure_retry_failed),
                color = Color(0xfff3ecdb), fontSize = 12.sp, lineHeight = 18.sp)
        }
        Text(stringResource(R.string.service_failure_safe_data).replace("，", "\n"), color = RlColor.Ink,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 30.dp, bottom = 28.dp).fillMaxWidth(.50f),
            fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
    }
}
