package com.reallanguage.app.feature.auth.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*
import com.reallanguage.app.feature.auth.domain.AgreementKind
import com.reallanguage.app.feature.billing.presentation.BillingAction
import kotlinx.coroutines.launch

@Composable
fun AgreementDetailScreen(state: AgreementDetailUiState, onAction: (AgreementDetailAction) -> Unit) {
    BackHandler { onAction(AgreementDetailAction.ReturnRequested) }
    val sectionList = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selectedSection by remember(state.kind) { mutableIntStateOf(0) }
    val sections = state.document?.sections.orEmpty()
    val labels = agreementTocLabels(state.kind, sections.size)
    val subtitle = state.updatedDateZh?.let { "更新日期：$it" }
        ?: state.document?.let { stringResource(R.string.agreement_detail_version, it.version) }
        ?: "RealLanguage 法律文件"
    val select: (Int) -> Unit = { index -> selectedSection = index; scope.launch { sectionList.animateScrollToItem(index) } }
    RlPaperBackground(showCornerCollage = false) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 720.dp && maxHeight >= 380.dp && LocalDensity.current.fontScale <= 1.35f
            Image(painterResource(R.drawable.rl_v73_billing_ledger_background), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            if (wide) {
                // The contents leaf, document heading and footer have independent anchors
                // in 51; a shared top-to-bottom column incorrectly pushes the TOC down.
                val pageWidth = maxWidth
                IconButton({ onAction(AgreementDetailAction.ReturnRequested) },
                    Modifier.padding(start = pageWidth * .126f, top = 23.dp).size(48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink)
                }
                Column(Modifier.fillMaxWidth().padding(start = pageWidth * .383f, end = pageWidth * .183f, top = 39.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.document?.titleZh ?: stringResource(R.string.agreement_detail_title), color = RlColor.Ink,
                        fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 36.sp)
                    Image(editorialUnderlinePainter(), null, Modifier.width(130.dp).height(5.dp), contentScale = ContentScale.FillBounds)
                    Text(subtitle, Modifier.padding(top = 6.dp), color = RlColor.TextSecondary,
                        fontFamily = EditorialFont.Serif, fontSize = 11.sp, lineHeight = 17.sp)
                }
                Column(Modifier.fillMaxHeight().padding(start = pageWidth * .12f, top = 60.dp, bottom = 24.dp)
                    .width(pageWidth * .177f).verticalScroll(rememberScrollState()).testTag("agreement-toc")) {
                    labels.forEachIndexed { index, label ->
                        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { select(index) }
                            .testTag("agreement-toc-item-$index"), contentAlignment = Alignment.CenterStart) {
                            if (selectedSection == index) Image(painterResource(R.drawable.rl_torn_terracotta), null,
                                Modifier.fillMaxWidth().height(36.dp).align(Alignment.Center), contentScale = ContentScale.FillBounds)
                            Text("${index + 1}. $label", Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                color = if (selectedSection == index) RlColor.OnActionVivid else RlColor.Ink,
                                fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 22.sp)
                        }
                        HorizontalDivider(color = RlColor.Divider)
                    }
                }
                Box(Modifier.fillMaxSize().padding(start = pageWidth * .383f, end = pageWidth * .151f, top = 125.dp, bottom = 84.dp)) {
                    if (state.loadFailed) BillingAction(stringResource(R.string.diary_retry), { onAction(AgreementDetailAction.RetryRequested) }, Modifier.align(Alignment.Center))
                    else LazyColumn(Modifier.fillMaxSize().testTag("agreement-section-list"), state = sectionList, contentPadding = PaddingValues(top = 7.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(sections) { index, section ->
                            Column(Modifier.fillMaxWidth().testTag("agreement-section"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${index + 1}. ${labels.getOrElse(index) { "第 ${index + 1} 节" }}", color = RlColor.ActionVivid,
                                    fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 25.sp)
                                Text(section, color = RlColor.TextPrimary, fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 23.sp)
                                HorizontalDivider(Modifier.padding(top = 8.dp), color = RlColor.Divider)
                            }
                        }
                    }
                }
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = pageWidth * .383f, end = pageWidth * .151f, bottom = 26.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    BillingAction(stringResource(R.string.agreement_detail_return), { onAction(AgreementDetailAction.ReturnRequested) },
                        Modifier.weight(.55f).heightIn(min = 48.dp).testTag("agreement-return"))
                    Text("返回后保留已经填写的\n注册内容与勾选状态。", Modifier.weight(.45f), color = RlColor.TextSecondary,
                        fontFamily = EditorialFont.Serif, fontSize = 11.sp, lineHeight = 18.sp)
                }
            } else Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ onAction(AgreementDetailAction.ReturnRequested) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.document?.titleZh ?: stringResource(R.string.agreement_detail_title), color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 36.sp)
                        Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(130.dp).height(5.dp), contentScale = ContentScale.FillBounds)
                        Text(subtitle, color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 11.sp, lineHeight = 17.sp)
                    }
                    Spacer(Modifier.width(48.dp))
                }
                if (!wide) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("agreement-toc"), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    labels.forEachIndexed { index, label -> FilterChip(selectedSection == index, { select(index) }, label = { Text("${index + 1}. $label") }) }
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    if (wide) Column(Modifier.weight(.27f).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("agreement-toc"), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("条款目录", Modifier.padding(vertical = 10.dp), color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 20.sp, lineHeight = 27.sp)
                        labels.forEachIndexed { index, label ->
                            Box(Modifier.fillMaxWidth().clickable { select(index) }.testTag("agreement-toc-item-$index")) {
                                Image(painterResource(if (selectedSection == index) R.drawable.rl_torn_terracotta else R.drawable.rl_vocabulary_card_paper_r11), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                                Text("${index + 1}. $label", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = if (selectedSection == index) RlColor.OnActionVivid else RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 20.sp)
                            }
                        }
                    }
                    Box(Modifier.weight(if (wide) .73f else 1f).fillMaxHeight()) {
                        Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                        if (state.loadFailed) BillingAction(stringResource(R.string.diary_retry), { onAction(AgreementDetailAction.RetryRequested) }, Modifier.align(Alignment.Center))
                        else LazyColumn(Modifier.fillMaxSize().padding(horizontal = if (wide) 24.dp else 16.dp).testTag("agreement-section-list"), state = sectionList, contentPadding = PaddingValues(vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            itemsIndexed(sections) { index, section ->
                                Column(Modifier.fillMaxWidth().testTag("agreement-section"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${index + 1}. ${labels.getOrElse(index) { "第 ${index + 1} 节" }}", color = RlColor.ActionVivid, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 25.sp)
                                    Text(section, color = RlColor.TextPrimary, fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 23.sp)
                                    HorizontalDivider(Modifier.padding(top = 8.dp), color = RlColor.Divider)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BillingAction(stringResource(R.string.agreement_detail_return), { onAction(AgreementDetailAction.ReturnRequested) }, Modifier.weight(.4f).testTag("agreement-return"))
                    Text("返回后保留已经填写的注册内容与勾选状态。", Modifier.weight(.6f), color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 11.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

private fun agreementTocLabels(kind: AgreementKind, count: Int): List<String> {
    val labels = if (kind == AgreementKind.USER_AGREEMENT) listOf("服务说明", "账号与年龄", "用户内容", "AI 服务与费用", "知识产权", "责任边界", "联系我们")
    else listOf("收集范围", "使用目的", "本机边界", "服务商处理", "保存与删除", "你的权利", "儿童与联系方式")
    return List(count) { index -> labels.getOrElse(index) { "第 ${index + 1} 节" } }
}
