package com.reallanguage.app.feature.knowledge.presentation

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*

/** The same fixed navigation and category regions serve list, empty and expanded states. */
@Composable
internal fun PatternBookFrame(query: String, onQueryChanged: (String) -> Unit, onBack: () -> Unit,
    scrollState: ScrollState, detailVisible: Boolean = false, hasVisiblePatterns: Boolean,
    feedback: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 700.dp || LocalDensity.current.fontScale > 1.3f
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Image(painterResource(R.drawable.rl_pattern_clean_book_20261123), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
            .padding(start = 20.dp, end = 14.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorialPageHeader("句式本", onBack, backContentDescription = "返回首页", backTestTag = "ledger-back",
                trailing = if (compact) null else ({ PatternSearch(query, onQueryChanged, Modifier.width(170.dp)) { keyboard?.hide(); focus.clearFocus() } }))
            if (compact) PatternSearch(query, onQueryChanged, Modifier.fillMaxWidth()) { keyboard?.hide(); focus.clearFocus() }
            if (!keyboardVisible) Text("展示全部已收录句式，不受当前词汇范围过滤", Modifier.fillMaxWidth(), color = RlColor.TextSecondary,
                fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
            if (compact || keyboardVisible) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PatternCategories.forEachIndexed { index, label -> PatternCategory(index, if (keyboardVisible) label.replace("\n", "") else label, Modifier.weight(1f).heightIn(min = 44.dp)) }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 21.sp, color = RlColor.Ink)) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scrollState).padding(horizontal = 6.dp, vertical = 6.dp), content = content)
                }
                if (!compact && !keyboardVisible) BoxWithConstraints(Modifier.width(44.dp).fillMaxHeight()) {
                    val categoryHeight = maxOf(44.dp, (maxHeight - 18.dp) / 4)
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PatternCategories.forEachIndexed { index, label -> PatternCategory(index, label, Modifier.height(categoryHeight).fillMaxWidth()) }
                    }
                }
            }
            if (!keyboardVisible && !detailVisible && hasVisiblePatterns) Text("点击一行展开详情", Modifier.fillMaxWidth(), color = RlColor.TextSecondary,
                fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
            feedback()
        }
    }
}

private val PatternCategories = listOf("基础\n句式", "功能\n句式", "过渡\n衔接", "高级\n表达")
private val PatternCategoryColors = listOf(Color(0xFF747B52), Color(0xFFB45E40), Color(0xFFB59148), Color(0xFF667D87))

@Composable
private fun PatternCategory(index: Int, label: String, modifier: Modifier) {
    Box(modifier.editorialPaper(PatternCategoryColors[index]).testTag("pattern-category-$index"), contentAlignment = Alignment.Center) {
        Text(label, Modifier.padding(4.dp), color = Color.White, fontFamily = EditorialFont.Serif,
            fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
    }
}

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
