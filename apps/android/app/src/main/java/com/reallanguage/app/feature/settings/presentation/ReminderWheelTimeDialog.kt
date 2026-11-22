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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import com.reallanguage.app.core.designsystem.*
import java.time.LocalTime

/** Platform wheel controls keep touch, fling and accessibility semantics. */
@Composable
internal fun ReminderWheelTimeDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.widthIn(max = 300.dp), shape = RlShape.Small, color = RlColor.PaperWarm,
            border = BorderStroke(.6.dp, RlColor.Border), shadowElevation = 4.dp) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("选择提醒时间", Modifier.align(Alignment.CenterHorizontally), color = RlColor.Ink,
                    fontFamily = EditorialFont.Serif, fontSize = 18.sp, lineHeight = 24.sp)
                HorizontalDivider(color = RlColor.Border)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth().height(28.dp).background(Color(0xFFD9DFE4)))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TimeWheel(23, initial.hour, "时", "reminder-hour-wheel") { hour = it }
                        Box(Modifier.width(.6.dp).height(116.dp).background(RlColor.Border))
                TimeWheel(59, initial.minute, "分", "reminder-minute-wheel") { minute = it }
                    }
                }
                HorizontalDivider(color = RlColor.Border)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onDismiss, Modifier.weight(1f), shape = RlShape.Small, border = BorderStroke(.6.dp, RlColor.Border)) {
                        Text("取消", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 14.sp)
                    }
                    Button({ onConfirm(LocalTime.of(hour, minute)) }, Modifier.weight(1f).testTag("reminder-time-confirm"),
                        shape = RlShape.Small, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFAD583A))) {
                        Text("确定", fontFamily = EditorialFont.Serif, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeWheel(maximum: Int, initial: Int, unit: String, tag: String, onChange: (Int) -> Unit) {
    val action by rememberUpdatedState(onChange)
    AndroidView(modifier = Modifier.width(96.dp).height(132.dp).testTag(tag), factory = { context ->
        NumberPicker(context).apply {
            minValue = 0; maxValue = maximum; value = initial
            displayedValues = Array(maximum + 1) { "%02d%s".format(it, unit) }
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            contentDescription = if (maximum == 23) "小时" else "分钟"
            if (Build.VERSION.SDK_INT >= 29) {
                textColor = RlColor.Ink.toArgb()
                textSize = 16 * resources.displayMetrics.scaledDensity
                selectionDividerHeight = 0
            }
            setOnValueChangedListener { _, _, value -> action(value) }
        }
    })
}
