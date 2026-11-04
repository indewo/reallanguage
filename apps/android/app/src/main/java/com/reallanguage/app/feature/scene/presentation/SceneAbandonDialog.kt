package com.reallanguage.app.feature.scene.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlImmersiveDialogWindow
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneStart

/**
 * The complete stationery composition scales from available width and height together.
 * Copy and actions remain live so session data and accessibility are never baked into art.
 */
@Composable
fun SceneAbandonDialog(
    state: SceneAbandonUiState,
    onAction: (SceneAbandonAction) -> Unit,
) {
    Dialog(
        onDismissRequest = {
            if (!state.deleting) onAction(SceneAbandonAction.ContinueRequested)
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        RlImmersiveDialogWindow(dimAmount = .66f)
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val panelHeight = minOf(392.dp, maxHeight * .97f)
            val panelWidth = minOf(500.dp, maxWidth * .94f, panelHeight * 1.276f)
            val scale = panelWidth / 500.dp

            Box(
                modifier = Modifier.width(panelWidth).height(panelHeight),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.rl_scene_abandon_modal_paper),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                Image(
                    painter = painterResource(R.drawable.rl_scene_abandon_brass_clip),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = 90.dp * scale, y = 7.dp * scale)
                        .size(60.dp * scale),
                    contentScale = ContentScale.Fit,
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = 11.dp * scale)
                        .width(356.dp * scale)
                        .heightIn(max = panelHeight - 66.dp * scale)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.scene_abandon_title),
                        color = SceneAbandonTerracotta,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 25.sp * scale,
                            lineHeight = 29.sp * scale,
                        ),
                    )
                    Image(
                        painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
                        contentDescription = null,
                        modifier = Modifier.width(92.dp * scale).height(10.dp * scale),
                        contentScale = ContentScale.FillBounds,
                    )
                    Spacer(Modifier.height(3.dp * scale))
                    Text(
                        text = stringResource(R.string.scene_abandon_explanation),
                        modifier = Modifier.fillMaxWidth(),
                        color = RlColor.TextPrimary,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp * scale,
                            lineHeight = 20.sp * scale,
                        ),
                    )
                    Spacer(Modifier.height(11.dp * scale))
                    SceneAbandonSummaryStrip(
                        summary = stringResource(
                            R.string.scene_abandon_summary_dynamic,
                            state.session.start.sceneAbandonLabel(),
                            stringResource(
                                if (state.session.contentType == SceneContentType.DIALOGUE) {
                                    R.string.scene_type_dialogue
                                } else {
                                    R.string.scene_type_article
                                },
                            ),
                            state.session.answers.size,
                        ),
                        scale = scale,
                    )
                    Spacer(Modifier.height(4.dp * scale))
                    SceneAbandonWarning(scale)
                    if (state.error != null) {
                        Text(
                            text = stringResource(R.string.scene_abandon_failed),
                            modifier = Modifier.fillMaxWidth(),
                            color = RlColor.Error,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 10.sp * scale,
                                lineHeight = 12.sp * scale,
                            ),
                        )
                    } else {
                        Spacer(Modifier.height(4.dp * scale))
                    }
                    SceneAbandonActions(
                        deleting = state.deleting,
                        scale = scale,
                        onCancel = { onAction(SceneAbandonAction.ContinueRequested) },
                        onConfirm = { onAction(SceneAbandonAction.ConfirmRequested) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SceneAbandonSummaryStrip(summary: String, scale: Float) {
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp * scale),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.rl_torn_note),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp * scale),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(28.dp * scale).clip(CircleShape)
                    .background(SceneAbandonTerracotta),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Assignment,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp * scale),
                )
            }
            Spacer(Modifier.width(10.dp * scale))
            Text(
                text = summary,
                color = RlColor.Ink,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp * scale),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 11.5.sp * scale,
                    lineHeight = 18.sp * scale,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

@Composable
private fun SceneAbandonWarning(scale: Float) {
    Box(
        modifier = Modifier.width(190.dp * scale).heightIn(min = 42.dp * scale),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.rl_scene_abandon_warning_tape),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds,
        )
        Row(
            modifier = Modifier.padding(horizontal = 10.dp * scale, vertical = 8.dp * scale),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = SceneAbandonTerracotta,
                modifier = Modifier.size(15.dp * scale),
            )
            Spacer(Modifier.width(5.dp * scale))
            Text(
                text = stringResource(R.string.scene_abandon_no_draft),
                color = RlColor.Primary,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 12.sp * scale,
                    lineHeight = 18.sp * scale,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp * scale,
                ),
            )
        }
    }
}

@Composable
private fun SceneAbandonActions(
    deleting: Boolean,
    scale: Float,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 40.dp * scale),
        horizontalArrangement = Arrangement.spacedBy(14.dp * scale),
    ) {
        SceneAbandonAction(
            text = stringResource(R.string.scene_abandon_continue),
            enabled = !deleting,
            filled = false,
            onClick = onCancel,
            modifier = Modifier.weight(1f).fillMaxHeight().testTag("scene-abandon-cancel"),
            scale = scale,
        )
        SceneAbandonAction(
            text = stringResource(R.string.scene_abandon_confirm),
            enabled = !deleting,
            filled = true,
            onClick = onConfirm,
            modifier = Modifier.weight(1f).fillMaxHeight().testTag("scene-abandon-confirm"),
            scale = scale,
        )
    }
}

@Composable
private fun SceneAbandonAction(
    text: String,
    enabled: Boolean,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    scale: Float,
) {
    val shape = RoundedCornerShape(6.dp * scale)
    val accent = if (enabled) SceneAbandonTerracotta else RlColor.Border
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                color = if (filled) accent else RlColor.PaperWarm.copy(alpha = .42f),
                shape = shape,
            )
            .then(if (filled) Modifier else Modifier.border(1.2.dp * scale, RlColor.Primary, shape))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp * scale, vertical = 10.dp * scale),
            color = when {
                !enabled -> RlColor.TextMuted
                filled -> Color.White
                else -> RlColor.Primary
            },
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                fontSize = 14.sp * scale,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

private fun SceneStart.sceneAbandonLabel(): String = when (this) {
    is SceneStart.Recommendation -> recommendation.shortTopicZh
    is SceneStart.Template -> template.titleZh
    is SceneStart.DirectDescription -> description
}.ifBlank { "本次场景" }

private val SceneAbandonTerracotta = Color(0xFFB65337)
