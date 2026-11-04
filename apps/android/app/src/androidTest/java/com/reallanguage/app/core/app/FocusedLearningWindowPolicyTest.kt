package com.reallanguage.app.core.app

import android.content.ComponentName
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Window-policy evidence only; never launches authenticated Main or opens a user database. */
class FocusedLearningWindowPolicyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resize = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    private val mask = WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST

    @Test fun productionManifestRequestsInsetsResizingWithoutOpeningASession() {
        val info = context.packageManager.getActivityInfo(ComponentName(context.packageName,
            "com.reallanguage.app.MainActivity"), 0)
        assertEquals(resize, info.softInputMode and mask)
    }

    @Test fun visualAuditManifestMustUseTheSameKeyboardPolicyAsProduction() {
        val info = context.packageManager.getActivityInfo(ComponentName(context.packageName,
            "com.reallanguage.app.development.DevelopmentPreviewActivity"), 0)
        assertEquals(resize, info.softInputMode and mask)
    }

    @Test fun realAuditWindowDoesNotOverrideTheProductionPolicyWhenItRegainsFocus() {
        val intent = Intent().setComponent(ComponentName(context.packageName,
            "com.reallanguage.app.development.DevelopmentPreviewActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ComponentActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.onWindowFocusChanged(true)
                assertEquals(resize, activity.window.attributes.softInputMode and mask)
            }
        }
    }
}
