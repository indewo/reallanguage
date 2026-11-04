package com.reallanguage.app.feature.error.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.core.designsystem.RlColor

/** Scoped to error pages; does not restyle the protected home or diary controls. */
@Composable
internal fun ErrorPaperAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, filled: Boolean = true,
) {
    val material = ImageBitmap.imageResource(R.drawable.rl_v73_error_rust_note)
    val texture = remember(material) { BitmapPainter(material,
        IntOffset(material.width / 4, material.height / 4),
        IntSize(material.width / 2, material.height / 2)) }
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(6.dp), color = if (filled) RlColor.ActionVivid else Color.Transparent,
        border = if (filled) null else BorderStroke(1.dp, RlColor.Primary)) {
        Box(contentAlignment = Alignment.Center) {
            if (filled) Image(texture, null,
                Modifier.matchParentSize(), contentScale = ContentScale.Crop, alpha = if (enabled) 1f else .5f)
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                color = if (filled) RlColor.OnActionVivid else RlColor.Primary,
                fontFamily = EditorialFont.Serif, fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp, lineHeight = 23.sp, textAlign = TextAlign.Center)
        }
    }
}
