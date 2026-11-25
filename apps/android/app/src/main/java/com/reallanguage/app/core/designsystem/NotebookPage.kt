package com.reallanguage.app.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R

// Bring the cream sheet closer to the app's warm off-white without flattening its fibers.
internal val NeutralNotebookPaper = ColorFilter.colorMatrix(ColorMatrix().apply {
    this[0, 0] = .99f
    this[1, 1] = 1.025f
    this[2, 2] = 1.07f
})

/** Shared blank material, with actual text and controls laid out above it. */
@Composable
internal fun NotebookPage(title: String, onBack: () -> Unit, backLabel: String = "返回",
    footer: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Image(painterResource(R.drawable.rl_notebook_blank_20261124), null,
            Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
            .padding(start = 28.dp, end = 20.dp, top = 4.dp, bottom = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            EditorialPageHeader(title, onBack, backLabel = backLabel, backTestTag = "ledger-back")
            ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(fontFamily = EditorialFont.Serif,
                fontSize = 14.sp, lineHeight = 21.sp, color = RlColor.Ink)) {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
                footer()
            }
        }
    }
}

@Composable
internal fun NotebookAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, primary: Boolean = false, leadingIcon: ImageVector? = null, neutral: Boolean = false) {
    Box(modifier.heightIn(min = 50.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        val paper = ImageBitmap.imageResource(R.drawable.rl_notebook_panel_20261124)
        Image(BitmapPainter(paper, srcOffset = IntOffset(15, 50), srcSize = IntSize(paper.width - 30, paper.height - 80)),
            null, Modifier.matchParentSize().clip(RoundedCornerShape(7.dp)), contentScale = ContentScale.FillBounds, alpha = if (enabled) 1f else .45f,
            colorFilter = if (neutral) null else ColorFilter.tint(if (primary) Color(0xFFB55232) else Color(0xFF777C4B), androidx.compose.ui.graphics.BlendMode.Modulate))
        Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          leadingIcon?.let { Icon(it, null, Modifier.size(18.dp), tint = Color.White) }
          Text(text, color = if (neutral) RlColor.Ink else Color.White,
            fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
internal fun NotebookTwoPane(modifier: Modifier, left: @Composable (Modifier) -> Unit,
    right: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier) {
        if (maxWidth < 520.dp * LocalDensity.current.fontScale.coerceAtMost(1.5f)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                left(Modifier.fillMaxWidth().weight(.28f))
                right(Modifier.fillMaxWidth().weight(.72f))
            }
        } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            left(Modifier.weight(.25f).fillMaxHeight())
            right(Modifier.weight(.75f).fillMaxHeight())
        }
    }
}

@Composable
internal fun NotebookPanel(modifier: Modifier = Modifier, scroll: ScrollState? = null,
    tint: Color? = null, padding: PaddingValues? = null, scrollTag: String? = null,
    header: (@Composable () -> Unit)? = null,
    trimOuterMargin: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val safePadding = padding ?: PaddingValues(start = 18.dp, end = 18.dp, top = maxHeight * .11f, bottom = maxHeight * .13f)
        val paper = if (trimOuterMargin) {
            val bitmap = ImageBitmap.imageResource(R.drawable.rl_notebook_panel_20261124)
            val top = (bitmap.height * .085f).toInt()
            val bottom = (bitmap.height * .095f).toInt()
            BitmapPainter(bitmap, srcOffset = IntOffset(0, top), srcSize = IntSize(bitmap.width, bitmap.height - top - bottom))
        } else painterResource(R.drawable.rl_notebook_panel_20261124)
        Image(paper, null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds,
            colorFilter = tint?.let { ColorFilter.tint(it, androidx.compose.ui.graphics.BlendMode.Modulate) }
                ?: NeutralNotebookPaper.takeIf { trimOuterMargin })
        Column(Modifier.fillMaxSize().padding(safePadding).clipToBounds(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            header?.invoke()
            Column(Modifier.weight(1f).fillMaxWidth().clipToBounds()
                .then(if (scroll != null) Modifier.verticalScroll(scroll) else Modifier)
                .then(if (scrollTag != null) Modifier.testTag(scrollTag) else Modifier),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}
