package com.reallanguage.app.core.app

import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Use IME insets to resize Compose's available area, never pan the entire lesson. */
fun configureFocusedLearningWindow(window: Window) {
    val adjustmentMask = WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
    val resize = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    if (window.attributes.softInputMode and adjustmentMask != resize) {
        window.setSoftInputMode((window.attributes.softInputMode and adjustmentMask.inv()) or resize)
    }
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowCompat.getInsetsController(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
