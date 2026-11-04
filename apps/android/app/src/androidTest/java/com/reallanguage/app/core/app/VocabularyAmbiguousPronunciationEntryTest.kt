package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyBookRepository
import com.reallanguage.app.feature.knowledge.domain.VocabularyExample
import com.reallanguage.app.feature.knowledge.domain.VocabularyLoadResult
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
import com.reallanguage.app.feature.knowledge.presentation.VocabularyBookViewModel
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real Entry -> Screen -> ViewModel, with an isolated saved-record repository.
 * The player records unexpected calls and returns false, never synthetic audio
 * success. No database, credential port, HTTP, microphone, TTS or AI is used.
 * Existing pronunciation-identity tests cover IPA/speaker grouping separately.
 */
class VocabularyAmbiguousPronunciationEntryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun physicalAmbiguousWordAndRetryStayUnavailableWithoutPlaybackWhileExamplesAndReturnWork() {
        val records = records()
        val loads = AtomicInteger()
        val creations = AtomicInteger()
        val homes = AtomicInteger()
        val played = CopyOnWriteArrayList<String>()
        var model: VocabularyBookViewModel? = null
        val repository = object : VocabularyBookRepository {
            override suspend fun loadAllRecords(): VocabularyLoadResult {
                loads.incrementAndGet()
                return VocabularyLoadResult.Ready(setOf("CET_4"), records)
            }
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                check(modelClass == VocabularyBookViewModel::class.java)
                creations.incrementAndGet()
                return VocabularyBookViewModel(repository).also { model = it } as T
            }
        }
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.window.decorView.width == WIDTH && compose.activity.window.decorView.height == HEIGHT
        }
        compose.setContent { RlTheme { RlPaperBackground {
            VocabularyBookEntry(factory, { homes.incrementAndGet() }, { text ->
                played += text
                false // A routing regression cannot be masked by a successful fixture player.
            })
        } } }
        compose.waitUntil(5_000) { model?.uiState?.value?.loading == false }
        compose.waitForIdle()
        assertActualWindow()
        val vm = requireNotNull(model)
        val initial = vm.uiState.value.entries
        assertEquals(records, initial.flatMap { it.senses })
        assertEquals(listOf("lead", "lead"), records.map { it.pronunciationText })
        assertEquals(listOf("/liːd/", "/lɛd/"), records.map { it.phonetic })
        compose.onNodeWithTag("vocabulary-detail-sheet").assertDoesNotExist()

        val selected = records[1]
        physicalClick("vocabulary-sense-pronunciation-${selected.senseId}")
        capture("word-attempt") // Capture the real isolated frame before the failure verdict.
        compose.waitUntil(5_000) { vm.uiState.value.pronunciationFailed || played.isNotEmpty() }
        assertUnavailable(vm, played)
        assertEquals(initial, vm.uiState.value.entries)

        physicalClick("vocabulary-pronunciation-retry")
        capture("retry")
        assertUnavailable(vm, played)
        assertEquals(initial, vm.uiState.value.entries)
        compose.onNodeWithTag("vocabulary-detail-sheet").assertDoesNotExist()

        // Unavailable word sound must not block the already stored explanation.
        physicalClick("vocabulary-sense-${selected.senseId}")
        compose.waitUntil(5_000) { vm.uiState.value.expandedSenseId == selected.senseId }
        compose.waitForIdle()
        compose.onNodeWithTag("vocabulary-detail-sheet").assertIsDisplayed()
        selected.examples.forEach { example ->
            val english = compose.onNodeWithText(example.textEn, useUnmergedTree = true)
            revealCenter(english)
            english.assertIsDisplayed()
            val chinese = compose.onNodeWithText(example.textZh, useUnmergedTree = true)
            revealCenter(chinese)
            chinese.assertIsDisplayed()
        }
        capture("examples")
        assertEquals(selected, vm.uiState.value.expandedSense)
        assertEquals(initial, vm.uiState.value.entries)
        assertTrue("Looking at stored examples must not start either a word or example playback", played.isEmpty())

        physicalClick("vocabulary-book-back")
        compose.waitUntil(5_000) { homes.get() == 1 }
        compose.runOnIdle {
            assertEquals(1, homes.get())
            assertEquals(1, loads.get())
            assertEquals(1, creations.get())
            assertEquals(records, vm.uiState.value.entries.flatMap { it.senses })
            assertTrue("No text may reach the player during the entire unavailable/retry/read/return flow", played.isEmpty())
            assertFalse(vm.uiState.value.pronunciationBusy)
            assertFalse(vm.uiState.value.pronunciationFailed)
        }
    }

    private fun assertUnavailable(vm: VocabularyBookViewModel, played: List<String>) {
        compose.onNodeWithText(compose.activity.getString(R.string.vocabulary_playback_failed)).assertIsDisplayed()
        compose.onNodeWithTag("vocabulary-pronunciation-retry").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.vocabulary_playback_retry)).assertIsDisplayed()
        assertTrue("The guard must fail before asking the native text player to choose an ambiguous reading", played.isEmpty())
        assertTrue(vm.uiState.value.pronunciationFailed)
        assertFalse(vm.uiState.value.pronunciationBusy)
    }

    private fun physicalClick(tag: String) {
        val interaction = compose.onNodeWithTag(tag)
        revealCenter(interaction)
        val node = interaction.assertIsDisplayed().assertIsEnabled().fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsActions.OnClick))
        val coordinates = node.layoutInfo.coordinates
        val center = coordinates.localToRoot(Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(node.boundsInRoot).intersect(windowBounds)
        assertTrue("Click the real visible action, not its hidden semantic callback: $tag center=$center clip=$clip", clip.contains(center))
        val rootOrigin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        compose.onRoot().performTouchInput { click(center - rootOrigin) }
        compose.waitForIdle()
    }

    private fun revealCenter(interaction: SemanticsNodeInteraction) {
        repeat(12) { attempt ->
            val target = interaction.fetchSemanticsNode()
            val coordinates = target.layoutInfo.coordinates
            val center = coordinates.localToRoot(Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
            val visible = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
                .intersect(target.boundsInRoot).intersect(windowBounds)
            if (visible.width > 0f && visible.height > 0f && visible.contains(center)) return
            val scroll = generateSequence(target.parent) { it.parent }.filter {
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange) &&
                    it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f
            }.lastOrNull() ?: error("The original viewport cannot reveal its saved content: target=${target.id}")
            val scrollCoordinates = scroll.layoutInfo.coordinates
            val viewport = scrollCoordinates.findRootCoordinates().localBoundingBoxOf(scrollCoordinates, clipBounds = true)
                .intersect(scroll.boundsInRoot).intersect(windowBounds)
            assertTrue("A physical drag needs a real original scroll viewport: $viewport", viewport.width > 32f && viewport.height > 64f)
            val upward = center.y > viewport.center.y
            val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
            val before = range.value()
            val start = Offset(viewport.center.x, if (upward) viewport.bottom - 12f else viewport.top + 12f)
            val end = Offset(viewport.center.x, if (upward) viewport.top + 12f else viewport.bottom - 12f)
            val rootOrigin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
            compose.onRoot().performTouchInput {
                down(start - rootOrigin)
                repeat(20) { step -> moveTo(start + (end - start) * ((step + 1) / 20f) - rootOrigin, 60L) }
                repeat(6) { advanceEventTime(40L); moveTo(end - rootOrigin, 0L) }
                up()
            }
            compose.waitForIdle()
            assertTrue("Physical scrolling must advance toward the target: attempt=$attempt before=$before after=${range.value()}",
                if (upward) range.value() > before else range.value() < before)
        }
        error("Saved content was not reachable within twelve physical drags")
    }

    private fun capture(step: String) {
        val phase = InstrumentationRegistry.getArguments().getString("vocabularyAmbiguousCapture") ?: return
        require(phase in setOf("before", "after") && step in setOf("word-attempt", "retry", "examples"))
        compose.waitForIdle()
        assertActualWindow()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val decor = compose.activity.window.decorView
        val drawn = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { drawn.countDown() }
        instrumentation.runOnMainSync {
            check(decor.hasWindowFocus())
            decor.viewTreeObserver.addOnDrawListener(listener)
            decor.postInvalidateOnAnimation()
        }
        try { check(drawn.await(10, TimeUnit.SECONDS)) { "The isolated vocabulary Entry did not draw" } }
        finally { instrumentation.runOnMainSync { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener) } }
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(WIDTH, bitmap.width)
            assertEquals(HEIGHT, bitmap.height)
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            val output = File(directory, "41-vocabulary-ambiguous-entry-$step-$phase.png")
            check(output.canonicalFile.parentFile == directory.canonicalFile)
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun assertActualWindow() {
        val resources = compose.activity.resources
        assertEquals(320, resources.configuration.densityDpi)
        assertEquals(2f, resources.displayMetrics.density, .001f)
        assertEquals(1f, resources.configuration.fontScale, .001f)
        assertEquals(WIDTH, compose.activity.window.decorView.width)
        assertEquals(HEIGHT, compose.activity.window.decorView.height)
        val root = compose.onRoot().fetchSemanticsNode().layoutInfo
        assertEquals(WIDTH, root.width)
        assertEquals(HEIGHT, root.height)
    }

    private fun records() = listOf(
        VocabularySenseRecord("lead-guide", "lead", "带领", KnowledgeStatus.MASTERED, 2, setOf("CET_4"), 1,
            phonetic = "/liːd/", pronunciationText = "lead", representativeScene = "带领团队参观。",
            examples = listOf(
                VocabularyExample("I lead our team.", "我带领我们的团队。", "I lead our team."),
                VocabularyExample("We lead the tour.", "我们带队参观。", "We lead the tour."),
                VocabularyExample("They lead the way.", "他们带路。", "They lead the way."),
            ), focusOccurrenceCount = 3, contextOccurrenceCount = 2),
        VocabularySenseRecord("lead-metal", "lead", "铅", KnowledgeStatus.LEARNING, 0, setOf("CET_4"), 2,
            phonetic = "/lɛd/", pronunciationText = "lead", representativeScene = "辨认金属样品。",
            examples = listOf(
                VocabularyExample("This is lead.", "这是铅。", "This is lead."),
                VocabularyExample("Lead is heavy.", "铅很重。", "Lead is heavy."),
                VocabularyExample("The sample has lead.", "样品含铅。", "The sample has lead."),
            ), focusOccurrenceCount = 2, contextOccurrenceCount = 4),
    )

    private companion object {
        const val WIDTH = 1829
        const val HEIGHT = 860
        val windowBounds = Rect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
    }
}
