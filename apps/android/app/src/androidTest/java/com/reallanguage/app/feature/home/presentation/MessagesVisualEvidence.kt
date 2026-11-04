package com.reallanguage.app.feature.home.presentation

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Reuses the native visual-qa capture switch; never modifies the app's production data. */
internal fun captureMessagesVisualEvidence(name: String, capture: (() -> Bitmap)? = null) {
    if (InstrumentationRegistry.getArguments().getString("captureVisualEvidence") != "true") return
    require(name.matches(Regex("[a-z0-9-]+")))
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    val bitmap = capture?.invoke() ?: requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa/$name.png")
        check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
        output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally {
        bitmap.recycle()
    }
}
