package com.reallanguage.app.core.designsystem

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.reallanguage.app.R

@Composable
fun RlPaperBackground(
    modifier: Modifier = Modifier,
    showCornerCollage: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().background(RlColor.Background)) {
        Image(
            painter = painterResource(R.drawable.rl_paper_background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.62f,
        )
        if (showCornerCollage) Image(
            painter = painterResource(R.drawable.rl_editorial_corner_collage),
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomStart).width(300.dp).height(190.dp),
            contentScale = ContentScale.FillBounds,
            alpha = 0.82f,
        )
        content()
    }
}

@Composable
fun RlScrollablePage(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 1480.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    RlPaperBackground(modifier) {
        Box(
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
                .padding(horizontal = RlSpace.Space32, vertical = RlSpace.Space8),
            contentAlignment = Alignment.TopCenter,
        ) {
            RlPaperPanel(
                modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth()
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                contentPadding = RlSpace.Space12,
                content = content,
            )
        }
    }
}

@Composable
fun RlEditorialHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    supportingText: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RlSpace.Space4),
    ) {
        eyebrow?.let {
            Text(
                text = it.uppercase(),
                color = RlColor.ActionVivid,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            )
        }
        Text(
            text = title,
            color = RlColor.Ink,
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
            ),
        )
        supportingText?.let {
            Text(text = it, color = RlColor.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(RlSpace.Space4)) {
            Image(
                painter = painterResource(R.drawable.rl_torn_terracotta),
                contentDescription = null,
                modifier = Modifier.width(96.dp).height(10.dp),
                contentScale = ContentScale.Crop,
            )
            Image(
                painter = painterResource(R.drawable.rl_torn_blue),
                contentDescription = null,
                modifier = Modifier.width(52.dp).height(10.dp),
                contentScale = ContentScale.Crop,
                alpha = 0.78f,
            )
        }
    }
}

@Composable
fun RlProcessWorkspace(
    title: String,
    supportingText: String,
    stages: List<String>,
    activeStage: Int,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
    horizontalStages: Boolean = true,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    RlPaperBackground(modifier) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
                .padding(horizontal = RlSpace.Space16, vertical = RlSpace.Space4),
            verticalArrangement = Arrangement.spacedBy(RlSpace.Space4),
        ) {
            Text(
                text = title,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = if (failed) RlColor.ActionVivid else RlColor.Ink,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(RlColor.BorderStrong.copy(alpha = .48f)))
            Row(
                modifier = Modifier.widthIn(max = 1480.dp).fillMaxWidth().weight(1f)
                    .align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
            ) {
                Box(Modifier.weight(.68f).fillMaxHeight().clipToBounds()) {
                    Image(
                        painter = painterResource(R.drawable.rl_editorial_corner_collage),
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.76f),
                        contentScale = ContentScale.Crop,
                    )
                    Image(
                        painter = painterResource(R.drawable.rl_process_documents_collage),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(
                            start = RlSpace.Space24,
                            end = RlSpace.Space40,
                            top = 92.dp,
                            bottom = RlSpace.Space8,
                        ),
                        contentScale = ContentScale.Fit,
                    )
                    Column(
                        modifier = Modifier.fillMaxSize().padding(
                            start = RlSpace.Space24,
                            end = RlSpace.Space40,
                            top = RlSpace.Space16,
                            bottom = RlSpace.Space16,
                        ),
                        verticalArrangement = Arrangement.Top,
                    ) {
                        Text(
                            text = if (failed) "内容已妥善保留" else "正在整理",
                            color = RlColor.Ink,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            text = supportingText,
                            modifier = Modifier.padding(top = RlSpace.Space8),
                            color = RlColor.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1.32f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(RlSpace.Space4),
                ) {
                    RlPaperPanel(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = RlSpace.Space8,
                        contentSpacing = RlSpace.Space4,
                        showSpine = false,
                    ) {
                        if (horizontalStages) {
                            Row(
                                modifier = Modifier.fillMaxWidth().height(96.dp),
                                horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
                            ) {
                                stages.forEachIndexed { index, stage ->
                                    RlProcessStage(
                                        index = index,
                                        stage = stage,
                                        activeStage = activeStage,
                                        failed = failed,
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                    )
                                }
                            }
                        } else {
                            stages.forEachIndexed { index, stage ->
                                RlProcessStage(
                                    index = index,
                                    stage = stage,
                                    activeStage = activeStage,
                                    failed = failed,
                                    modifier = Modifier.fillMaxWidth().height(64.dp),
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(RlColor.Divider))
                        content()
                    }
                    footer?.let {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
                            content = it,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RlProcessStage(
    index: Int,
    stage: String,
    activeStage: Int,
    failed: Boolean,
    modifier: Modifier = Modifier,
) {
    val complete = index < activeStage && !failed
    val current = index == activeStage
    Surface(
        modifier = modifier,
        color = when {
            failed && current -> RlColor.ActionVividContainer
            current -> RlColor.NoteContainer
            complete -> RlColor.SecondaryContainer
            else -> RlColor.Surface.copy(alpha = .62f)
        },
        shape = RlShape.Small,
        border = BorderStroke(
            1.dp,
            when {
                failed && current -> RlColor.ActionVivid
                current -> RlColor.NoteAccent
                complete -> RlColor.Secondary
                else -> RlColor.Border
            },
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(RlSpace.Space12),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                modifier = Modifier.width(28.dp).height(28.dp),
                color = when {
                    failed && current -> RlColor.ActionVivid
                    complete -> RlColor.Secondary
                    else -> RlColor.Primary
                },
                shape = RlShape.Small,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = (index + 1).toString(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                text = stage,
                modifier = Modifier.padding(top = RlSpace.Space8),
                color = RlColor.Ink,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = when {
                    failed && current -> "暂未完成"
                    complete -> "已完成"
                    current -> "正在进行"
                    else -> "等待中"
                },
                color = RlColor.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
fun RlLedgerWorkspace(
    title: String,
    subtitle: String,
    leftTitle: String,
    leftSupporting: String,
    modifier: Modifier = Modifier,
    leftWeight: Float = .72f,
    rightWeight: Float = 1.28f,
    leftBlueBackground: Boolean = false,
    onBack: (() -> Unit)? = null,
    leftContent: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    fixedPaperViewport: Boolean = false,
    contentScrollState: ScrollState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = contentScrollState ?: rememberScrollState()
    RlPaperBackground(modifier) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
                .padding(horizontal = RlSpace.Space24, vertical = RlSpace.Space8),
            verticalArrangement = Arrangement.spacedBy(RlSpace.Space8),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.align(Alignment.CenterStart).testTag("ledger-back"),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = RlColor.Ink)
                    }
                }
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        title,
                        color = RlColor.Ink,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp,
                            lineHeight = 22.sp,
                        ),
                    )
                    Text(subtitle, color = RlColor.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(
                modifier = Modifier.widthIn(max = 1480.dp).fillMaxWidth().weight(1f)
                    .align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
            ) {
                Box(Modifier.weight(leftWeight).fillMaxHeight().clipToBounds()) {
                    if (leftBlueBackground) {
                        Image(
                            painter = painterResource(R.drawable.rl_editorial_blue_panel),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                        )
                    } else {
                        Image(
                            painter = painterResource(R.drawable.rl_editorial_corner_collage),
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.76f),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Image(
                        painter = painterResource(R.drawable.rl_process_documents_collage),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(
                            start = RlSpace.Space12,
                            end = RlSpace.Space20,
                            top = 58.dp,
                            bottom = RlSpace.Space4,
                        ),
                        contentScale = ContentScale.Fit,
                    )
                    Column(
                        modifier = Modifier.fillMaxSize().padding(
                            start = RlSpace.Space24,
                            end = RlSpace.Space40,
                            top = RlSpace.Space20,
                            bottom = RlSpace.Space16,
                        ),
                        verticalArrangement = Arrangement.Top,
                    ) {
                        if (leftContent != null) {
                            leftContent()
                        } else {
                            Text(
                                leftTitle,
                                color = RlColor.Ink,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.Bold,
                                ),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Box(
                                Modifier.padding(vertical = RlSpace.Space8).width(92.dp).height(2.dp)
                                    .background(RlColor.NoteAccent),
                            )
                            Text(
                                leftSupporting,
                                color = RlColor.TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(rightWeight).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(RlSpace.Space4),
                ) {
                    RlPaperPanel(
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .then(if (fixedPaperViewport) Modifier else Modifier.verticalScroll(scrollState)),
                        contentPadding = RlSpace.Space8,
                        contentSpacing = RlSpace.Space4,
                        showSpine = false,
                        contentModifier = if (fixedPaperViewport) Modifier.fillMaxSize().verticalScroll(scrollState) else Modifier,
                        content = content,
                    )
                    footer?.let {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
                            content = it,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RlPaperPanel(
    modifier: Modifier = Modifier,
    contentPadding: Dp = RlSpace.Space24,
    contentSpacing: Dp = RlSpace.Space12,
    showSpine: Boolean = true,
    contentModifier: Modifier = Modifier,
    contentArrangement: Arrangement.Vertical = Arrangement.spacedBy(contentSpacing),
    showTopAccent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier) {
        Image(
            painter = painterResource(R.drawable.rl_torn_note),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds,
        )
        if (showTopAccent) {
            Image(
                painter = painterResource(R.drawable.rl_torn_terracotta),
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = RlSpace.Space32)
                    .width(76.dp).height(18.dp),
                contentScale = ContentScale.Crop,
                alpha = 0.88f,
            )
        }
        if (showSpine) {
            // Decoration follows the measured paper; its painter must not set a
            // scroll item's natural height when the vertical axis is unbounded.
            Box(Modifier.matchParentSize()) {
                Image(
                    painter = painterResource(R.drawable.rl_torn_blue),
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.CenterStart).padding(vertical = RlSpace.Space16)
                        .width(10.dp).fillMaxHeight(),
                    contentScale = ContentScale.Crop,
                    alpha = 0.82f,
                )
            }
        }
        Column(
            modifier = contentModifier.padding(
                horizontal = contentPadding + RlSpace.Space12,
                vertical = contentPadding + RlSpace.Space8,
            ),
            verticalArrangement = contentArrangement,
            content = content,
        )
    }
}

@Composable
fun RlSectionCard(
    modifier: Modifier = Modifier,
    containerColor: Color = RlColor.Surface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    RlPaperPanel(
        modifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick),
        contentPadding = RlSpace.Space12,
        contentSpacing = RlSpace.Space8,
        content = content,
    )
}

@Composable
fun RlPrimaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = RlColor.Primary,
    disabledContainerColor: Color = RlColor.DisabledContainer,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 36.dp),
        shape = RlShape.Input,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = RlColor.OnPrimary,
            disabledContainerColor = disabledContainerColor,
            disabledContentColor = RlColor.TextSecondary,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        contentPadding = contentPadding,
    ) {
        Text(text = text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp))
    }
}

@Composable
fun RlSecondaryButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 36.dp),
        shape = RlShape.Input,
        border = BorderStroke(1.5.dp, if (enabled) RlColor.Primary else RlColor.Border),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = RlColor.Primary,
            disabledContentColor = RlColor.TextSecondary,
        ),
        contentPadding = contentPadding,
    ) {
        Text(text = text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp))
    }
}

/**
 * Full-canvas editorial confirmation surface used for consequential choices.
 *
 * The approved design language treats these moments as a torn note placed over
 * the page, rather than a platform-default alert.  Keeping it here also means
 * every action remains a real Compose control with normal semantics and tests.
 */
@Composable
fun RlEditorialDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable ColumnScope.() -> Unit,
    primaryText: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
    primaryDanger: Boolean = false,
    secondaryText: String,
    onSecondary: () -> Unit,
    secondaryEnabled: Boolean = true,
    tertiaryText: String? = null,
    onTertiary: (() -> Unit)? = null,
    tertiaryEnabled: Boolean = true,
    primaryTestTag: String? = null,
    secondaryTestTag: String? = null,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        RlImmersiveDialogWindow()
        Box(
            modifier = modifier.fillMaxSize().padding(horizontal = 176.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            RlPaperPanel(
                modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
                contentPadding = RlSpace.Space16,
                contentSpacing = RlSpace.Space8,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RlSpace.Space16),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.width(7.dp).height(44.dp)
                            .background(if (primaryDanger) RlColor.ActionVivid else RlColor.Primary),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            color = if (primaryDanger) RlColor.ActionVivid else RlColor.Ink,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                lineHeight = 26.sp,
                            ),
                        )
                        Text(
                            text = if (primaryDanger) "需要你的确认" else "RealLanguage · 继续之前",
                            color = RlColor.TextMuted,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(RlColor.Divider))
                body()
                Row(
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
                ) {
                    RlEditorialAction(
                        text = secondaryText,
                        enabled = secondaryEnabled,
                        filled = false,
                        onClick = onSecondary,
                        modifier = Modifier.weight(1f).fillMaxHeight().then(
                            if (secondaryTestTag != null) Modifier.testTag(secondaryTestTag) else Modifier,
                        ),
                    )
                    RlEditorialAction(
                        text = primaryText,
                        enabled = primaryEnabled,
                        filled = true,
                        danger = primaryDanger,
                        onClick = onPrimary,
                        modifier = Modifier.weight(1f).fillMaxHeight().then(
                            if (primaryTestTag != null) Modifier.testTag(primaryTestTag) else Modifier,
                        ),
                    )
                }
                if (tertiaryText != null && onTertiary != null) {
                    RlEditorialAction(
                        text = tertiaryText,
                        enabled = tertiaryEnabled,
                        filled = false,
                        onClick = onTertiary,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun RlImmersiveDialogWindow(dimAmount: Float? = null) {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dimAmount?.let(window::setDimAmount)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun RlEditorialAction(
    text: String,
    enabled: Boolean,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val accent = if (danger) RlColor.ActionVivid else RlColor.Primary
    Box(
        modifier = modifier
            .clip(RlShape.Input)
            .then(
                if (filled) Modifier.background(if (enabled) accent else RlColor.Border)
                else Modifier.background(RlColor.PaperWarm.copy(alpha = .58f))
                    .border(1.5.dp, if (enabled) accent else RlColor.Border, RlShape.Input),
            )
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
            color = when {
                !enabled -> RlColor.TextMuted
                filled -> RlColor.OnPrimary
                else -> accent
            },
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
fun RlTextField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingMessage: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    RlKeyboardAwareOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        isError = isError,
        label = { Text(label) },
        supportingText = if (supportingMessage == null) null else {
            { Text(supportingMessage) }
        },
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        shape = RlShape.Input,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = RlColor.TextPrimary,
            unfocusedTextColor = RlColor.TextPrimary,
            errorTextColor = RlColor.TextPrimary,
            focusedBorderColor = RlColor.Primary,
            unfocusedBorderColor = RlColor.BorderStrong,
            errorBorderColor = RlColor.Error,
            disabledBorderColor = RlColor.Border,
            disabledTextColor = RlColor.TextSecondary,
            cursorColor = RlColor.Primary,
            focusedLabelColor = RlColor.Primary,
            unfocusedLabelColor = RlColor.TextSecondary,
            errorLabelColor = RlColor.Error,
            focusedSupportingTextColor = RlColor.TextSecondary,
            unfocusedSupportingTextColor = RlColor.TextSecondary,
            errorSupportingTextColor = RlColor.Error,
            focusedContainerColor = RlColor.PaperWarm,
            unfocusedContainerColor = RlColor.PaperWarm,
        ),
    )
}
