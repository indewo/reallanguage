package com.reallanguage.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Compact ink/paper face, with a separate full-height accessible touch target. */
@Composable
fun EditorialCompactAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    color: Color = RlColor.ActionVivid,
) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier.heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 36.dp)
                .background(if (!enabled) RlColor.DisabledContainer else if (primary) color else RlColor.Surface.copy(alpha = .66f), shape)
                .border(1.dp, if (!enabled) RlColor.Border else color.copy(alpha = .8f), shape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = if (!enabled) RlColor.TextSecondary else if (primary) Color.White else RlColor.Ink,
                fontFamily = EditorialFont.Serif, fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        }
    }
}
