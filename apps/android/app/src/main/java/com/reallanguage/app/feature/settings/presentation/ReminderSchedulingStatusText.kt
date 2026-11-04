package com.reallanguage.app.feature.settings.presentation

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlSpace

/** Status of the saved configuration, not an acknowledgement for an edited settings draft. */
@Composable
internal fun ReminderSchedulingStatusText(message: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RlSpace.Space4)) {
        Text("已保存配置的系统状态", color = RlColor.TextSecondary, style = MaterialTheme.typography.labelSmall)
        Text(message, modifier = Modifier.fillMaxWidth().testTag("reminder-scheduling-message"),
            color = RlColor.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}
