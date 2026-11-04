package com.reallanguage.app.feature.knowledge.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*

/** Reference 42/43: physical book decoration is separate from the adaptive reading surface. */
@Composable
internal fun PatternBookFrame(query: String, onQueryChanged: (String) -> Unit, onBack: () -> Unit,
    scrollState: ScrollState, detailVisible: Boolean = false,
    feedback: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageWidth = maxWidth
        val pageHeight = maxHeight
        val compact = maxWidth < 700.dp || fontScale > 1.3f
        Image(painterResource(R.drawable.rl_v73_knowledge_pattern_ledger), null,
            Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
            .padding(start = if (compact) 56.dp else maxWidth * .105f,
                end = if (compact) 28.dp else maxWidth * .09f,
                top = if (compact) 15.dp else if (detailVisible) 8.dp else 36.dp,
                bottom = if (compact) 18.dp else if (detailVisible) 12.dp else 26.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                IconButton(onBack, Modifier.align(Alignment.CenterStart).size(48.dp).testTag("ledger-back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink)
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("句式本", color = RlColor.Ink, fontFamily = EditorialFont.Serif,
                        fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp)
                    Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null,
                        Modifier.width(93.dp).height(6.dp), contentScale = ContentScale.FillBounds)
                }
                if (!compact) PatternSearch(query, onQueryChanged,
                    Modifier.align(Alignment.CenterEnd).padding(end = 22.dp).width(177.dp),
                    onSearch = { keyboard?.hide(); focus.clearFocus() })
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp,
                bottom = if (detailVisible) 4.dp else if (compact) 10.dp else 18.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (!compact) Image(patternBotanicalPainter(), null,
                Modifier.padding(end = 12.dp).size(if (detailVisible) 18.dp else 26.dp).scale(scaleX = -1f, scaleY = 1f))
            Text("展示全部已收录句式，不受当前词汇范围过滤", color = RlColor.TextSecondary,
                fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
                modifier = if (compact) Modifier.weight(1f) else Modifier)
            if (!compact) Image(patternBotanicalPainter(), null,
                Modifier.padding(start = 12.dp).size(if (detailVisible) 18.dp else 26.dp))
            }
            if (compact) PatternSearch(query, onQueryChanged,
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                onSearch = { keyboard?.hide(); focus.clearFocus() })
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState)
                .padding(start = if (compact) 0.dp else pageWidth * if (detailVisible) .04f else .171f), content = content)
            if (!compact && !detailVisible) Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.width(68.dp), color = RlColor.Border)
                Image(patternBotanicalPainter(), null, Modifier.padding(horizontal = 8.dp).size(20.dp)
                    .scale(scaleX = -1f, scaleY = 1f))
                Text("点击一行展开详情", color = RlColor.TextSecondary,
                    fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp,
                    textAlign = TextAlign.Center)
                Image(patternBotanicalPainter(), null, Modifier.padding(horizontal = 8.dp).size(20.dp))
                HorizontalDivider(Modifier.width(68.dp), color = RlColor.Border)
            }
            Column(Modifier.fillMaxWidth()) { feedback() }
        }
        if (!compact) Image(painterResource(R.drawable.rl_home_scene_paperclip), null,
            Modifier.align(Alignment.TopEnd).padding(top = pageHeight * .04f, end = pageWidth * .068f)
                .width(18.dp).height(47.dp).rotate(12f), contentScale = ContentScale.FillBounds)
        if (!compact) Column(Modifier.align(Alignment.TopEnd).width(pageWidth * .055f)
            .padding(top = pageHeight * .14f, end = pageWidth * .02f)) {
            listOf("基\n础\n句\n式", "功\n能\n句\n式", "过\n渡\n衔\n接", "高\n级\n表\n达").forEach { label ->
                Box(Modifier.fillMaxWidth().height(pageHeight * .18f), contentAlignment = Alignment.Center) {
                    Text(label, color = RlColor.OnSecondary, fontFamily = EditorialFont.Serif,
                        fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun patternBotanicalPainter() = BitmapPainter(
    ImageBitmap.imageResource(R.drawable.rl_v73_pattern_botanical_v2),
    srcOffset = IntOffset(150, 180), srcSize = IntSize(990, 860),
)

@Composable
private fun PatternSearch(query: String, onQueryChanged: (String) -> Unit, modifier: Modifier,
    onSearch: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val paperHeight = if (fontScale > 1.3f) 40.dp * fontScale else 36.dp
    Box(modifier.heightIn(min = maxOf(48.dp, paperHeight)), contentAlignment = Alignment.Center) {
        Image(BitmapPainter(ImageBitmap.imageResource(R.drawable.rl_v73_knowledge_search_paper),
            IntOffset(37, 166), IntSize(2100, 388)), null, Modifier.fillMaxWidth().height(paperHeight),
            contentScale = ContentScale.FillBounds)
        RlKeyboardAwareBasicTextField(query, onQueryChanged,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pattern-book-search"),
            singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = RlColor.Ink,
                fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 19.sp),
            cursorBrush = SolidColor(RlColor.Primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { inner ->
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Search, null, tint = RlColor.TextSecondary, modifier = Modifier.size(15.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("搜索句式或功能", color = RlColor.TextSecondary,
                            fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 19.sp)
                        inner()
                    }
                }
            })
    }
}
