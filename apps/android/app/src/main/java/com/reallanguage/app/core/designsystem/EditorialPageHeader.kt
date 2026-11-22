package com.reallanguage.app.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EditorialPageHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, backEnabled: Boolean = true,
    backLabel: String = "返回", backContentDescription: String = backLabel, backTestTag: String = "editorial-page-back",
    trailing: (@Composable () -> Unit)? = null, titleTestTag: String = "editorial-page-title") {
    Box(modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        EditorialBackAction(onBack, Modifier.align(Alignment.CenterStart).testTag(backTestTag), backEnabled, backLabel, backContentDescription)
        Column(Modifier.align(Alignment.Center).padding(horizontal = if (trailing == null) 124.dp else 180.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, Modifier.testTag(titleTestTag), color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 29.sp)
            Image(editorialUnderlinePainter(), null, Modifier.width(100.dp).height(5.dp), contentScale = ContentScale.FillBounds)
        }
        if (trailing != null) Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}
