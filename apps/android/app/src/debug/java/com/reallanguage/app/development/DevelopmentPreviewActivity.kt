package com.reallanguage.app.development

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.designsystem.RlKeyboardEditingHost
import com.reallanguage.app.feature.diary.presentation.DiaryDictationState
import com.reallanguage.app.feature.diary.presentation.DiaryInputUiState
import com.reallanguage.app.feature.home.presentation.DailyReminderIntroContent
import com.reallanguage.app.feature.home.presentation.DailyReminderIntroUiState

class DevelopmentPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterVisualAuditMode()
        setContent {
            val density = LocalDensity.current
            // The original artwork is a notch-free canvas. This DEBUG-ONLY switch
            // allows that reference state; regular previews and production retain cutout safety.
            val referenceCanvas = intent.getBooleanExtra("reference_canvas", false)
            val fontScale = intent.getFloatExtra("preview_font_scale", density.fontScale)
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            Box(Modifier.fillMaxSize().then(if (referenceCanvas)
                Modifier.consumeWindowInsets(WindowInsets.displayCutout) else Modifier)) {
            RlTheme {
                RlKeyboardEditingHost {
                    // A Dialog owns a second window/inset tree. Draw its identical content
                    // in this debug-only reference canvas; production keeps the safe Dialog.
                    if (renderExtendedEditorialPreview(intent.getStringExtra(EXTRA_PREVIEW_DESTINATION), referenceCanvas)) {
                        // The debug-only adapter rendered this exact UI state.
                    } else if (referenceCanvas && intent.getStringExtra(EXTRA_PREVIEW_DESTINATION) == "DAILY_REMINDER_INTRO") {
                        DailyReminderIntroContent(DailyReminderIntroUiState(), {})
                    } else DevelopmentPreviewScreen(
                        initialDestinationName = intent.getStringExtra(EXTRA_PREVIEW_DESTINATION),
                        diaryInputState = if (intent.getStringExtra(EXTRA_DIARY_STATE) == "recording") {
                            DiaryInputUiState(
                                dictationState = DiaryDictationState.RECORDING,
                                dictationElapsedSeconds = 3,
                            )
                        } else null,
                    )
                }
            }
            }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterVisualAuditMode()
    }

    private fun enterVisualAuditMode() {
        configureFocusedLearningWindow(window)
    }

    private companion object {
        const val EXTRA_PREVIEW_DESTINATION = "preview_destination"
        const val EXTRA_DIARY_STATE = "diary_state"
    }
}
