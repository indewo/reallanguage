package com.reallanguage.app.feature.settings.presentation

import android.os.Build
import android.widget.NumberPicker
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.reallanguage.app.core.designsystem.*
import java.time.LocalTime

/** Platform wheel controls keep touch, fling and accessibility semantics. */
@Composable
internal fun ReminderWheelTimeDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = RlColor.PaperWarm,
        title = { Text("选择提醒时间", color = RlColor.Ink, fontFamily = EditorialFont.Serif) },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                TimeWheel(23, initial.hour, "时", "reminder-hour-wheel") { hour = it }
                Text(":", color = RlColor.Ink, modifier = Modifier.padding(horizontal = 12.dp))
                TimeWheel(59, initial.minute, "分", "reminder-minute-wheel") { minute = it }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消", color = RlColor.TextSecondary) } },
        confirmButton = { TextButton({ onConfirm(LocalTime.of(hour, minute)) }, Modifier.testTag("reminder-time-confirm")) {
            Text("确定", color = RlColor.Secondary)
        } })
}

@Composable
private fun TimeWheel(maximum: Int, initial: Int, unit: String, tag: String, onChange: (Int) -> Unit) {
    val action by rememberUpdatedState(onChange)
    AndroidView(modifier = Modifier.width(100.dp).height(180.dp).testTag(tag), factory = { context ->
        NumberPicker(context).apply {
            minValue = 0; maxValue = maximum; value = initial
            displayedValues = Array(maximum + 1) { "%02d%s".format(it, unit) }
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            contentDescription = if (maximum == 23) "小时" else "分钟"
            if (Build.VERSION.SDK_INT >= 29) {
                textColor = RlColor.Ink.toArgb()
                textSize = 20 * resources.displayMetrics.scaledDensity
                selectionDividerHeight = 1
            }
            setOnValueChangedListener { _, _, value -> action(value) }
        }
    })
}
