package com.reallanguage.app.feature.learning.presentation

import android.content.pm.ActivityInfo
import android.util.Log
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Live system IME, no artificial inset/height, no storage, microphone, or judgement. */
class LearningKeyboardReachabilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val chapter = FixedLearningChapter("isolated-ime", 1, listOf(
        FixedLearningSentence("s1", "I drink green tea.", "我喝绿茶。", true),
        FixedLearningSentence("s2", "I take a long walk.", "我长距离散步。", true),
    ))

    @Before fun useTheSameWindowPolicyAsMainActivity() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.activityRule.scenario.onActivity { activity ->
            configureFocusedLearningWindow(activity.window)
        }
    }

    @Test fun dictationSubmitRemainsTouchableWhileTheActualKeyboardIsOpen() {
        verifyDictation(verifyCurrentLine = false)
    }

    @Test fun dictationCurrentInputLineCanBeReadAfterReturningFromContext() {
        verifyDictation(verifyCurrentLine = true)
    }

    private fun verifyDictation(verifyCurrentLine: Boolean) {
        val state = mutableStateOf(AudioDictationUiState(chapter, "s2", lockedHintItems = listOf("long walk：长距离散步"), audioLooping = false))
        var submissions = 0
        compose.setContent { RlTheme { AudioDictationScreen(state.value) { action ->
            when (action) {
                is AudioDictationAction.InputChanged -> state.value = state.value.copy(input = action.value)
                AudioDictationAction.SubmitRequested -> submissions++
                else -> Unit
            }
        } } }
        exerciseLiveKeyboard("26-dictation") {
            val input = compose.onNode(hasSetTextAction())
            input.performClick()
            awaitActualIme()
            input.performTextInput("I take a long walk.")
            input.assertIsDisplayed()
            assertContextCanStillBeRead("我长距离散步。")
            if (verifyCurrentLine) assertCurrentEditedLineIsFullyVisible(input)
            assertImeSubmissionReachable("26-dictation")
            assertEquals("The actual visible button must dispatch exactly once", 1, submissions)
            assertEquals("I take a long walk.", state.value.input)
        }
    }

    @Test fun silentSingleWritingSubmitRemainsTouchableWithoutRevealingTheAnswer() {
        verifySilentSingleWriting(verifyCurrentLine = false)
    }

    @Test fun silentSingleCurrentInputLineCanBeReadAfterReturningFromContext() {
        verifySilentSingleWriting(verifyCurrentLine = true)
    }

    private fun verifySilentSingleWriting(verifyCurrentLine: Boolean) {
        val state = mutableStateOf(CompleteWritingUiState(chapter, emptyList(),
            phase = CompleteWritingPhase.SILENT_SINGLE, audioPlaying = false))
        var submissions = 0
        compose.setContent { RlTheme { CompleteWritingScreen(state.value) { action ->
            when (action) {
                is CompleteWritingAction.InputChanged -> state.value = state.value.copy(input = action.value)
                CompleteWritingAction.SubmitRequested -> submissions++
                else -> Unit
            }
        } } }
        exerciseLiveKeyboard("29-single") {
            val input = compose.onNodeWithTag("complete-writing-input-single")
            input.performClick()
            awaitActualIme()
            input.performTextInput("My preserved input.")
            input.assertIsDisplayed()
            assertContextCanStillBeRead("我长距离散步。")
            if (verifyCurrentLine) assertCurrentEditedLineIsFullyVisible(input)
            compose.onNodeWithText("I drink green tea.").assertDoesNotExist()
            assertImeSubmissionReachable("29-single")
            assertEquals(1, submissions)
            assertEquals("My preserved input.", state.value.input)
            assertFalse(state.value.helpUsed)
        }
    }

    @Test fun finalWritingCanEditTheLastSentenceAndSubmitWithTheKeyboardStillOpen() {
        verifyFinalWriting(verifyCurrentLine = false)
    }

    @Test fun finalWritingCurrentInputLineCanBeReadAfterReturningFromContext() {
        verifyFinalWriting(verifyCurrentLine = true)
    }

    private fun verifyFinalWriting(verifyCurrentLine: Boolean) {
        val state = mutableStateOf(CompleteWritingUiState(chapter, emptyList(),
            phase = CompleteWritingPhase.FINAL_BATCH, audioPlaying = false))
        var submissions = 0
        compose.setContent { RlTheme { CompleteWritingScreen(state.value) { action ->
            when (action) {
                is CompleteWritingAction.FinalInputChanged -> state.value = state.value.copy(finalInputs = state.value.finalInputs + (action.sentenceId to action.value))
                CompleteWritingAction.SubmitRequested -> submissions++
                else -> Unit
            }
        } } }
        exerciseLiveKeyboard("29-final") {
            val input = compose.onNodeWithTag("complete-writing-input-s2")
            input.performScrollTo().performClick()
            awaitActualIme()
            input.performTextInput("My second preserved input.")
            input.assertIsDisplayed()
            assertContextCanStillBeRead("我长距离散步。")
            if (verifyCurrentLine) assertCurrentEditedLineIsFullyVisible(input)
            compose.onNodeWithText("I drink green tea.").assertDoesNotExist()
            compose.onNodeWithText("I take a long walk.").assertDoesNotExist()
            assertImeSubmissionReachable("29-final")
            assertEquals(1, submissions)
            assertEquals(mapOf("s2" to "My second preserved input."), state.value.finalInputs)
            assertFalse(state.value.helpUsed)
        }
    }

    private fun awaitActualIme() {
        compose.waitUntil(8_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
    }

    private fun assertImeSubmissionReachable(label: String) {
        assertTrue("Do not hide the keyboard to make the footer test pass",
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
        val submit = compose.onNodeWithText("提交")
        submit.assertIsEnabled().assertIsDisplayed()
        val bounds = submit.fetchSemanticsNode().boundsInRoot
        val minimumHeight = 40 * compose.activity.resources.displayMetrics.density
        assertTrue("The action must not be a clipped sliver: $bounds", bounds.height >= minimumHeight)
        captureActualWindow("$label-reachable")
        submit.performClick()
        compose.waitForIdle()
    }

    private fun assertContextCanStillBeRead(text: String) {
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("learning-keyboard-context")))
            .performScrollTo().assertIsDisplayed()
    }

    private fun assertCurrentEditedLineIsFullyVisible(input: SemanticsNodeInteraction) {
        input.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        input.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        val fieldId = input.fetchSemanticsNode().id
        val field = compose.onNode(SemanticsMatcher("the same field's unmerged coordinates") {
            it.id == fieldId
        }, useUnmergedTree = true).fetchSemanticsNode()
        val selection = field.config[SemanticsProperties.TextSelectionRange]
        val offset = selection.end.coerceIn(0, layout.layoutInput.text.length)
        val line = layout.getLineForOffset(offset)
        // An OutlinedTextField's outer semantics include border/label padding, so
        // that origin is not the paragraph origin. Resolve its real inner text
        // layer by the exact measured paragraph width, not a guessed dp inset.
        val textLayers = descendants(field).filter {
            it.children.isEmpty() && it.layoutInfo.width == layout.size.width &&
                it.layoutInfo.height >= layout.size.height &&
                !it.config.contains(SemanticsProperties.Text)
        }.toList()
        assertEquals("The actual paragraph coordinate layer must be unambiguous: layout=${layout.size}, candidates=${textLayers.map { it.layoutInfo.width to it.layoutInfo.height }}", 1, textLayers.size)
        val coordinates = textLayers.single().layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
        val lineTop = origin.y + layout.getLineTop(line)
        val lineBottom = origin.y + layout.getLineBottom(line)
        val cursor = layout.getCursorRect(offset)
        val diagnostic = "currentLine=$line textLayout=${layout.size} origin=$origin clip=$clip line=$lineTop..$lineBottom cursor=$cursor"
        assertTrue("The currently edited line must be complete, not merely displayed: $diagnostic",
            lineTop >= clip.top - 1f && lineBottom <= clip.bottom + 1f &&
                origin.x + layout.getLineLeft(line) >= clip.left - 1f &&
                origin.x + layout.getLineRight(line) <= clip.right + 1f)
        assertTrue("The actual caret must remain inside the visible text area: $diagnostic",
            origin.y + cursor.top >= clip.top - 1f && origin.y + cursor.bottom <= clip.bottom + 1f &&
                origin.x + cursor.left >= clip.left - 1f && origin.x + cursor.right <= clip.right + 2f)
        assertTrue("Reading the text must not dismiss the real keyboard",
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
    }

    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> =
        node.children.asSequence().flatMap { sequenceOf(it) + descendants(it) }

    private inline fun exerciseLiveKeyboard(label: String, block: () -> Unit) {
        try { block() }
        catch (failure: Throwable) {
            val evidence = captureActualWindow(label)
            throw AssertionError("Live IME reachability failed; $evidence\n${compose.onRoot(useUnmergedTree = true).printToString()}", failure)
        }
    }

    private fun captureActualWindow(label: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val view = compose.activity.window.decorView
        val drawn = AtomicBoolean()
        val listener = ViewTreeObserver.OnDrawListener { drawn.set(true) }
        instrumentation.runOnMainSync {
            view.viewTreeObserver.addOnDrawListener(listener)
            view.postInvalidateOnAnimation()
        }
        try { compose.waitUntil(3_000) { drawn.get() } }
        finally { instrumentation.runOnMainSync { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener) } }
        // A draw barrier and bounded accessibility idle, not a blind delay or keyboard hide.
        runCatching { instrumentation.uiAutomation.waitForIdle(150, 3_000) }
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa/$label-ime-${UUID.randomUUID()}.png")
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        val summary = "file=${file.absolutePath} softInputMode=${compose.activity.window.attributes.softInputMode} ime=${insets?.getInsets(WindowInsetsCompat.Type.ime())}"
        Log.i("RealLanguageKeyboard", summary)
        return summary
    }
}
