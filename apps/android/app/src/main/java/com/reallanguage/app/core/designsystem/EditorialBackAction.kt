package com.reallanguage.app.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared visual language; destination, confirmation and enabled state stay with the caller. */
@Composable
fun EditorialBackAction(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    label: String = "返回", contentDescription: String = label) {
    TextButton(onClick, modifier.heightIn(min = 44.dp).semantics { this.contentDescription = contentDescription }, enabled = enabled) {
        val ink = if (enabled) RlColor.Ink else RlColor.TextSecondary
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(24.dp), tint = ink)
        Spacer(Modifier.width(6.dp))
        Text(label, color = ink, fontFamily = EditorialFont.Serif, fontSize = 16.sp, lineHeight = 22.sp)
    }
}
