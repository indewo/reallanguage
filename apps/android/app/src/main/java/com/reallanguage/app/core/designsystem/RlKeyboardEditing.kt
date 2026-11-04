package com.reallanguage.app.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

private data class KeyboardEditingSession(
    val identity: Any,
    val value: TextFieldValue,
    val update: (TextFieldValue) -> Unit,
    val visualTransformation: VisualTransformation,
)

@Stable
private class KeyboardEditingController {
    var session by mutableStateOf<KeyboardEditingSession?>(null)
        private set

    fun activate(identity: Any, value: TextFieldValue, update: (TextFieldValue) -> Unit, visualTransformation: VisualTransformation) {
        session = KeyboardEditingSession(identity, value, update, visualTransformation)
    }

    fun synchronize(identity: Any, value: TextFieldValue, update: (TextFieldValue) -> Unit, visualTransformation: VisualTransformation) {
        if (session?.identity == identity) session = KeyboardEditingSession(identity, value, update, visualTransformation)
    }

    fun updateFromIme(identity: Any, value: TextFieldValue) {
        val active = session ?: return
        // Ignore a late callback from an input connection that already lost focus.
        if (active.identity != identity) return
        // Update the floating editor immediately; do not wait for the ViewModel echo.
        session = active.copy(value = value)
        active.update(value)
    }
}

private val LocalKeyboardEditingController = staticCompositionLocalOf<KeyboardEditingController?> { null }

/**
 * Adds a single-line, horizontally scrollable editing surface above the IME in landscape.
 * The source field remains the owner of the text and selection; this bar mirrors that exact
 * [TextFieldValue], so moving the caret in either surface updates the same editing position.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun RlKeyboardEditingHost(
    imeVisibleOverride: Boolean? = null,
    imeBottomOverride: Dp? = null,
    content: @Composable () -> Unit,
) {
    val controller = remember { KeyboardEditingController() }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val imeBottom = imeBottomOverride ?: with(density) { WindowInsets.ime.getBottom(this).toDp() }
    // Floating keyboards can be visible while reporting a zero bottom inset. Treat
    // visibility and occluding height as separate signals instead of hiding the editor.
    val imeVisible = imeVisibleOverride ?: (WindowInsets.isImeVisible || imeBottom > 0.dp)
    CompositionLocalProvider(LocalKeyboardEditingController provides controller) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            content()
            if (landscape && imeVisible) {
                controller.session?.let { session ->
                    // A docked IME occupies a material part of the window height.
                    // Floating IMEs often report only the navigation-area inset (a
                    // small non-zero value), so zero alone is not a safe discriminator.
                    val dockedIme = imeBottom >= maxHeight * .20f
                    val placement = if (dockedIme) {
                        Modifier.align(Alignment.BottomCenter).padding(bottom = imeBottom)
                    } else {
                        // Android and third-party IMEs do not expose a reliable floating
                        // keyboard rectangle. Keep the mirror in the upper safe band so it
                        // remains reachable instead of disappearing behind the floating IME.
                        Modifier.align(Alignment.TopCenter).padding(top = maxHeight * .16f)
                    }
                    Surface(
                        modifier = placement.fillMaxWidth().heightIn(min = 54.dp)
                            .testTag("landscape-keyboard-editing-bar"),
                        color = RlColor.PaperWarm,
                        contentColor = RlColor.Ink,
                        shape = RlShape.Input,
                        border = BorderStroke(1.5.dp, RlColor.ActionVivid),
                        shadowElevation = 5.dp,
                    ) {
                        BasicTextField(
                            value = session.value,
                            onValueChange = { controller.updateFromIme(session.identity, it) },
                            singleLine = true,
                            visualTransformation = session.visualTransformation,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = RlColor.Ink),
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp)
                                .testTag("landscape-keyboard-editing-input"),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (session.value.text.isEmpty()) {
                                        Text("编辑当前输入", color = RlColor.TextMuted)
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

fun Modifier.rlKeyboardEditingSource(
    value: TextFieldValue,
    update: (TextFieldValue) -> Unit,
    visualTransformation: VisualTransformation = VisualTransformation.None,
): Modifier = composed {
    val identity = remember { Any() }
    val controller = LocalKeyboardEditingController.current
    LaunchedEffect(value, visualTransformation) { controller?.synchronize(identity, value, update, visualTransformation) }
    onFocusChanged { focus ->
        if (focus.isFocused) controller?.activate(identity, value, update, visualTransformation)
    }
}

/**
 * Keeps an IME's composing range alive while the same text travels through a ViewModel.
 * Voice IMEs repeatedly replace one composing phrase ("今" -> "今天" -> "今天我…").
 * Rebuilding a TextFieldValue for every echo commits each partial phrase and makes the
 * next recognition result append to it, which produces staircase duplication.
 */
fun reconcileImeTextFieldValue(
    local: TextFieldValue,
    externalText: String,
    externalSelectionStart: Int = externalText.length,
    externalSelectionEnd: Int = externalSelectionStart,
): TextFieldValue {
    if (local.text == externalText) return local
    val start = externalSelectionStart.coerceIn(0, externalText.length)
    val end = externalSelectionEnd.coerceIn(0, externalText.length)
    return TextFieldValue(externalText, TextRange(start, end))
}

/** A production text field that also participates in the landscape IME editing surface. */
@Composable
fun RlKeyboardAwareOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        fieldValue = reconcileImeTextFieldValue(fieldValue, value)
    }
    val update: (TextFieldValue) -> Unit = {
        val textChanged = it.text != fieldValue.text
        fieldValue = it
        if (textChanged) onValueChange(it.text)
    }
    OutlinedTextField(
        value = fieldValue,
        onValueChange = update,
        modifier = modifier.rlKeyboardEditingSource(fieldValue, update, visualTransformation),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        shape = shape,
        colors = colors,
    )
}

/** BasicTextField counterpart for editorial inputs that cannot use Material decoration. */
@Composable
fun RlKeyboardAwareBasicTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource? = null,
    cursorBrush: Brush = SolidColor(Color.Black),
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit = { it() },
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        fieldValue = reconcileImeTextFieldValue(fieldValue, value)
    }
    val update: (TextFieldValue) -> Unit = {
        val textChanged = it.text != fieldValue.text
        fieldValue = it
        if (textChanged) onValueChange(it.text)
    }
    BasicTextField(
        value = fieldValue,
        onValueChange = update,
        modifier = modifier.rlKeyboardEditingSource(fieldValue, update, visualTransformation),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        cursorBrush = cursorBrush,
        decorationBox = decorationBox,
    )
}
