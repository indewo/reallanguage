package com.reallanguage.app.feature.formation.presentation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlShape

/** Only the normal-width 20/21 actions use their source-image painted dimensions. */
@Composable
internal fun FormationReferencePrimaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    sourceWidth: Dp,
    sourceHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelLarge
    val measuredText = rememberTextMeasurer().measure(text, style, softWrap = false)
    // A changing loading label or a larger system font may need more room. Source
    // geometry is a minimum, never a reason to shrink or clip the actual action text.
    val paintedWidth = maxOf(sourceWidth, with(density) { measuredText.size.width.toDp() } + 16.dp)
    val paintedHeight = maxOf(sourceHeight, with(density) { measuredText.size.height.toDp() } + 8.dp)
    Button(
        onClick = onClick,
        enabled = enabled,
        // The outer minimum-interactive modifier reserves the 48dp touch area;
        // only the inner painted body uses the smaller source-image height.
        modifier = modifier.width(paintedWidth).minimumInteractiveComponentSize().height(paintedHeight),
        shape = RlShape.Input,
        colors = ButtonDefaults.buttonColors(
            containerColor = RlColor.ActionVivid,
            contentColor = RlColor.OnPrimary,
            disabledContainerColor = RlColor.DisabledContainer,
            disabledContentColor = RlColor.TextSecondary,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(text, Modifier.fillMaxWidth(), style = style, textAlign = TextAlign.Center)
    }
}
