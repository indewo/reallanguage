package com.reallanguage.app.feature.capture

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.capture.data.ScreenshotOcr
import com.reallanguage.app.feature.capture.presentation.ScreenshotImportScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ScreenshotImportDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val resolver get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val created = mutableListOf<Uri>()

    @After fun removeOwnFixtures() {
        created.forEach { resolver.delete(it, null, null) }
    }

    @Test fun longScreenshotAndMixedScriptsReadInSubmittedOrderOnDevice() = runBlocking {
        val long = image("long", 4800, listOf(220 to "今天下午开会", 1800 to "Meet me on Friday", 4300 to "谢谢"))
        val short = image("short", 600, listOf(220 to "See you tomorrow"))
        val text = ScreenshotOcr.read(resolver, listOf(short, long))
        val words = text.replace(Regex("\\s"), "").lowercase()
        var previous = -1
        for (expected in listOf("seeyoutomorrow", "今天下午开会", "meetmeonfriday", "谢谢")) {
            val index = words.indexOf(expected)
            assertTrue("Missing or out of order: $expected in fixture result: $text", index > previous)
            previous = index
        }
        assertEquals(1, Regex("meetmeonfriday").findAll(words).count())
    }

    @Test fun tintedChatBubbleBorderIsNotPartOfItsChineseReply() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = java.io.File.createTempFile("bubble", ".png", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("capture/meeting_chat_bubbles.png").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val result = ScreenshotOcr.read(resolver, listOf(Uri.fromFile(file)))
            assertEquals("可以,我两点有空。", result.lines()[1].replace('，', ','))
        } finally {
            file.delete()
        }
    }

    @Test fun systemDocumentPickerImportsAnActualImageAndShowsInlinePendingUntilHandoff() {
        val name = "RLcapture" + UUID.randomUUID().toString().replace("-", "").take(8)
        image(name, 720, listOf(135 to "What time works for you?", 320 to "周五见"))
        val delivered = CompletableDeferred<String>()
        val finish = CompletableDeferred<Unit>()
        compose.setContent {
            RlTheme { ScreenshotImportScreen({}, { text -> delivered.complete(text); finish.await() }) }
        }
        compose.onNodeWithTag("screenshot_import_submit").assertIsNotEnabled()
        compose.onNodeWithText("添加截图").performClick()
        val bounds = waitForPickerItem(name)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        check(!bounds.isEmpty)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("input tap ${bounds.left + bounds.width()/3} ${bounds.centerY()}")
        ).use { it.readBytes() }
        // Some platform pickers immediately return a single selection, others require an Open button.
        for (attempt in 0 until 20) {
            val root = automation.rootInActiveWindow
            if (root?.packageName?.contains("documentsui") != true) break
            val open = listOf("android:id/button1", "com.google.android.documentsui:id/action_menu_select",
                "com.android.documentsui:id/action_menu_select").flatMap(root::findAccessibilityNodeInfosByViewId)
                .firstOrNull { it.isEnabled && it.isClickable }
            open?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(100)
        }
        compose.waitUntil(12_000) {
            compose.onAllNodes(hasContentDescription("第 1 张截图"))
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithText("识别上传截图").performClick()
        compose.waitUntil(45_000) { delivered.isCompleted }
        compose.onNodeWithText("正在导入中").assertIsDisplayed()
        val text = runBlocking { delivered.await() }.replace(Regex("\\s"), "").lowercase()
        assertTrue(text.contains("whattimeworksforyou"))
        assertTrue(text.contains("周五见"))
        finish.complete(Unit)
    }

    private fun waitForPickerItem(name: String): Rect {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 12_000
        var searched = false
        var previous: Rect? = null
        var stableSince = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root?.packageName?.contains("documentsui") == true) {
                if (!searched) {
                    val search = root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/option_menu_search")
                        .firstOrNull()
                    if (search?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                        SystemClock.sleep(250)
                        for (command in listOf("input text $name", "input keyevent KEYCODE_ENTER")) {
                            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
                        }
                        searched = true
                    }
                } else {
                    val tile = root.findAccessibilityNodeInfosByText(name).firstOrNull { node ->
                        val label = node.contentDescription?.toString().orEmpty()
                        node.isVisibleToUser && label.startsWith("$name.png", ignoreCase = true)
                    }
                    val bounds = tile?.let { Rect().also(it::getBoundsInScreen) }
                    if (bounds != null && bounds.height() > 50) {
                        if (bounds == previous && SystemClock.uptimeMillis() - stableSince >= 600) return bounds
                        if (bounds != previous) {
                            previous = bounds
                            stableSince = SystemClock.uptimeMillis()
                        }
                    }
                }
            }
            SystemClock.sleep(120)
        }
        error("The system picker did not expose the newly created image")
    }

    private fun image(name: String, height: Int, lines: List<Pair<Int, String>>): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        created += uri
        val bitmap = Bitmap.createBitmap(960, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 52f }
            for ((y, content) in lines) canvas.drawText(content, 66f, y.toFloat(), ink)
            checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}
