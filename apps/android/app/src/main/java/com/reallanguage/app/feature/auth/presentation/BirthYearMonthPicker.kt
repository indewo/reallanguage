package com.reallanguage.app.feature.auth.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.core.designsystem.RlImmersiveDialogWindow
import com.reallanguage.app.core.designsystem.editorialUnderlinePainter
import java.time.DateTimeException
import java.time.YearMonth
import java.util.Locale

@Composable
fun BirthYearMonthPicker(
    value: String,
    enabled: Boolean,
    isError: Boolean,
    supportingMessage: String?,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    editorial: Boolean = false,
    referenceStyle: Boolean = false,
    referenceScale: Float = .5f,
    referenceVisualOffset: Dp = 0.dp,
) {
    var dialogVisible by remember { mutableStateOf(false) }
    val parsedValue = parseYearMonth(value)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(RlSpace.Space4)) {
      if (editorial) {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
            verticalArrangement = Arrangement.spacedBy(RlSpace.Space8),
            maxItemsInEachRow = if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.35f) 1 else 2,
        ) {
            val yearText = parsedValue?.let { "${it.year} 年" }
                ?: stringResource(R.string.auth_birth_year_month_select)
            val monthText = parsedValue?.let {
                String.format(Locale.ROOT, "%02d 月", it.monthValue)
            } ?: stringResource(R.string.auth_birth_month)
            EditorialPickerButton(
                text = yearText,
                enabled = enabled,
                isError = isError,
                onClick = { dialogVisible = true },
                modifier = Modifier.weight(1f).testTag("birth-year-picker"),
                referenceStyle = referenceStyle,
                referenceScale = referenceScale,
                referenceVisualOffset = referenceVisualOffset,
            )
            EditorialPickerButton(
                text = monthText,
                enabled = enabled,
                isError = isError,
                onClick = { dialogVisible = true },
                modifier = Modifier.weight(1f).testTag("birth-month-picker"),
                referenceStyle = referenceStyle,
                referenceScale = referenceScale,
                referenceVisualOffset = referenceVisualOffset,
            )
        }
        supportingMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else RlColor.TextSecondary,
            )
        }
      } else {
      Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
      ) {
        Text(
            text = stringResource(R.string.auth_birth_year_month),
            modifier = Modifier.width(92.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (isError) MaterialTheme.colorScheme.error else RlColor.TextSecondary,
        )
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 48.dp) {
            OutlinedButton(
                onClick = { dialogVisible = true },
                enabled = enabled,
                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                shape = RlShape.Input,
                border = BorderStroke(
                    1.dp,
                    when {
                        isError -> MaterialTheme.colorScheme.error
                        enabled -> RlColor.BorderStrong
                        else -> RlColor.Border
                    },
                ),
            ) {
                Text(
                    text = parsedValue?.let {
                        String.format(Locale.ROOT, "%04d 年 %02d 月", it.year, it.monthValue)
                    } ?: stringResource(R.string.auth_birth_year_month_select),
                    color = if (parsedValue == null) RlColor.TextSecondary else Color.Unspecified,
                )
            }
        }
      }
        supportingMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else RlColor.TextSecondary,
            )
        }
      }
    }

    if (dialogVisible) {
        BirthYearMonthDialog(
            initialValue = parsedValue,
            onDismiss = { dialogVisible = false },
            onConfirm = {
                onValueChange(String.format(Locale.ROOT, "%04d-%02d", it.year, it.monthValue))
                dialogVisible = false
            },
        )
    }
}

@Composable
private fun EditorialPickerButton(
    text: String,
    enabled: Boolean,
    isError: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    referenceStyle: Boolean = false,
    referenceScale: Float = .5f,
    referenceVisualOffset: Dp = 0.dp,
) {
    val outline = if (isError) MaterialTheme.colorScheme.error else RlColor.BorderStrong
    if (referenceStyle) {
        Box(modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
            Box(Modifier.offset(y = referenceVisualOffset).fillMaxWidth().heightIn(min = 58.dp * referenceScale)
                .border(.5.dp, outline.copy(alpha = .65f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
            Row(Modifier.offset(y = referenceVisualOffset).fillMaxWidth().padding(start = 18.dp * referenceScale, end = 10.dp * referenceScale), verticalAlignment = Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f), fontFamily = EditorialFont.Serif,
                    fontSize = (30 * referenceScale).coerceAtLeast(14f).sp,
                    lineHeight = (40 * referenceScale).coerceAtLeast(22f).sp,
                    color = if (enabled) RlColor.Ink else RlColor.TextSecondary)
                Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.width(26.dp * referenceScale), tint = RlColor.Primary)
            }
        }
        return
    }
    Box(
        modifier = modifier.heightIn(min = 48.dp)
            .clip(RlShape.Small)
            .background(RlColor.PaperWarm.copy(alpha = .36f))
            .border(1.dp, outline, RlShape.Small)
            .semantics(mergeDescendants = true) {}
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 3.dp),
            maxLines = 1,
            color = if (enabled) RlColor.TextPrimary else RlColor.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Icon(
            imageVector = Icons.Outlined.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.align(Alignment.CenterEnd).width(24.dp),
            tint = RlColor.Primary,
        )
    }
}

@Composable
internal fun BirthYearMonthDialog(
    initialValue: YearMonth?,
    onDismiss: () -> Unit,
    onConfirm: (YearMonth) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        RlImmersiveDialogWindow(dimAmount = .4f)
        BirthYearMonthDialogContent(initialValue, onDismiss, onConfirm)
    }
}

/** The same real picker body is used by the production dialog and notch-free design fixture. */
@Composable
internal fun BirthYearMonthDialogContent(
    initialValue: YearMonth?,
    onDismiss: () -> Unit,
    onConfirm: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = YearMonth.now()
    var selectedYear by remember(initialValue) {
        mutableStateOf(initialValue?.year ?: current.minusYears(18).year)
    }
    var selectedMonth by remember(initialValue) {
        mutableStateOf(initialValue?.monthValue ?: current.monthValue)
    }

    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.Center) {
        val availableHeight = maxHeight
        BoxWithConstraints(Modifier.widthIn(max = 452.dp).fillMaxWidth().heightIn(max = availableHeight).testTag("birth-dialog-paper")) {
            val stacked = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.35f
            Image(painterResource(R.drawable.rl_v73_birth_picker_paper), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = if (stacked) 24.dp else 39.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.auth_birth_year_month_dialog_title), color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 35.sp, textAlign = TextAlign.Center)
                Image(editorialUnderlinePainter(), null, Modifier.width(156.dp).height(7.dp), contentScale = ContentScale.FillBounds)
                Spacer(Modifier.height(12.dp))
                Text("出生年月只用于年龄确认", color = RlColor.Primary, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = if (stacked) 1 else 2) {
                PickerMenu(
                    label = stringResource(R.string.auth_birth_year),
                    selected = selectedYear,
                    values = (current.year downTo 1900).toList(),
                    valueText = { "$it 年" },
                    onSelected = { selectedYear = it },
                    modifier = (if (stacked) Modifier.fillMaxWidth() else Modifier.weight(1f)).testTag("birth-dialog-year"),
                )
                PickerMenu(
                    label = stringResource(R.string.auth_birth_month),
                    selected = selectedMonth,
                    values = (1..12).toList(),
                    valueText = { String.format(Locale.ROOT, "%02d 月", it) },
                    onSelected = { selectedMonth = it },
                    modifier = (if (stacked) Modifier.fillMaxWidth() else Modifier.weight(1f)).testTag("birth-dialog-month"),
                )
                }
                Spacer(Modifier.height(22.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = if (stacked) 1 else 2) {
                    OutlinedButton(onDismiss, (if (stacked) Modifier.fillMaxWidth() else Modifier.weight(1f)).heightIn(min = 48.dp).testTag("birth-dialog-cancel"), shape = RlShape.Small, border = BorderStroke(1.dp, RlColor.Primary)) {
                        Text(stringResource(R.string.auth_birth_picker_cancel), color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 18.sp, lineHeight = 26.sp)
                    }
                    EditorialRoundedAction(stringResource(R.string.auth_birth_picker_confirm), true, { onConfirm(YearMonth.of(selectedYear, selectedMonth)) }, (if (stacked) Modifier.fillMaxWidth() else Modifier.weight(1f)).heightIn(min = 48.dp).testTag("birth-dialog-confirm"))
                }
            }
        }
    }
}

@Composable
private fun PickerMenu(
    label: String,
    selected: Int,
    values: List<Int>,
    valueText: (Int) -> String,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RlShape.Small).border(.6.dp, RlColor.BorderStrong, RlShape.Small)
                .clickable(role = Role.Button) { expanded = true }.padding(horizontal = 13.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 17.sp)
                    Text(valueText(selected), color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 27.sp)
                }
                Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.width(24.dp), tint = RlColor.Primary)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 280.dp),
            ) {
                values.forEach { value ->
                    DropdownMenuItem(
                        text = { Text(valueText(value)) },
                        onClick = {
                            onSelected(value)
                            expanded = false
                        },
                    )
                }
            }
    }
}

private fun parseYearMonth(value: String): YearMonth? = try {
    YearMonth.parse(value)
} catch (_: DateTimeException) {
    null
}
