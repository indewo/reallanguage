package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyBookRepository
import com.reallanguage.app.feature.knowledge.domain.VocabularyLoadResult
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
import com.reallanguage.app.feature.knowledge.presentation.VocabularyBookViewModel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real Entry -> Screen -> ViewModel -> playback callback -> failure/retry feedback.
 * Only the repository and Boolean player are isolated fixtures: no database,
 * credential, HTTP, real TTS/audio output or pronunciation quality assessment.
 */
class VocabularyGroupPlaybackEntryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun secondGroupPhysicalSpeakerFailureAndRetryKeepItsStoredWordAndAllLearningRecords() {
        val records = records()
        val loads = AtomicInteger()
        val creations = AtomicInteger()
        val homeCalls = AtomicInteger()
        val played = CopyOnWriteArrayList<String>()
        var model: VocabularyBookViewModel? = null
        val repository = object : VocabularyBookRepository {
            override suspend fun loadAllRecords(): VocabularyLoadResult {
                loads.incrementAndGet()
                return VocabularyLoadResult.Ready(setOf("CET4"), records)
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
            compose.activity.window.decorView.width == 1829 && compose.activity.window.decorView.height == 860
        }
        compose.setContent { RlTheme { RlPaperBackground {
            VocabularyBookEntry(factory, { homeCalls.incrementAndGet() }, { text ->
                played += text
                check(played.size <= 2) { "The isolated fixture permits only the requested playback and its retry" }
                played.size == 2
            })
        } } }
        compose.waitUntil(5_000) { model?.uiState?.value?.entries?.size == 2 }
        compose.waitForIdle()
        assertActualWindow()
        val before = requireNotNull(model).uiState.value.entries
        assertEquals(listOf("run", "light"), before.map { it.englishForm })
        assertEquals(listOf(2, 2), before.map { it.senses.size })
        before.forEach { entry ->
            assertEquals("Each group fixture has one shared reliable phonetic", 1, entry.senses.map { it.phonetic }.distinct().size)
            assertEquals("Each group fixture has one stored word pronunciation", 1, entry.senses.map { it.pronunciationText }.distinct().size)
        }
        assertNull(model?.uiState?.value?.expandedSenseId)
        assertTrue(played.isEmpty())

        val secondGroupSpeaker = "vocabulary-group-pronunciation-light-bright"
        bringListActionCenterIntoView(secondGroupSpeaker)
        physicalClick(secondGroupSpeaker)
        compose.waitUntil(5_000) { played.size == 1 && model?.uiState?.value?.pronunciationFailed == true }
        compose.onNodeWithTag("vocabulary-pronunciation-failed").assertIsDisplayed()
        compose.onNodeWithTag("vocabulary-pronunciation-retry").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("vocabulary-detail-sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("light"), played.toList())
            assertFalse(requireNotNull(model).uiState.value.pronunciationBusy)
            assertNull(model?.uiState?.value?.expandedSenseId)
            assertEquals(before, model?.uiState?.value?.entries)
        }

        // Physical retry follows the real false result through the production
        // Entry. No direct ViewModel action or semantic click shortcut is used.
        physicalClick("vocabulary-pronunciation-retry")
        compose.waitUntil(5_000) {
            played.size == 2 && model?.uiState?.value?.pronunciationBusy == false &&
                model?.uiState?.value?.pronunciationFailed == false
        }
        compose.waitForIdle()
        compose.onNodeWithTag("vocabulary-pronunciation-failed").assertDoesNotExist()
        compose.onNodeWithTag("vocabulary-pronunciation-retry").assertDoesNotExist()
        compose.onNodeWithTag("vocabulary-detail-sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("light", "light"), played.toList())
            assertNull(model?.uiState?.value?.expandedSenseId)
            assertEquals("Playback does not change status, effective counts, occurrences or saved texts", before, model?.uiState?.value?.entries)
            assertEquals(records, model?.uiState?.value?.entries?.flatMap { it.senses })
            assertEquals(1, loads.get())
            assertEquals(1, creations.get())
            assertEquals(0, homeCalls.get())
        }
    }

    private fun bringListActionCenterIntoView(tag: String) {
        repeat(16) { attempt ->
            val target = compose.onNodeWithTag(tag).fetchSemanticsNode()
            val coordinates = target.layoutInfo.coordinates
            val center = coordinates.localToRoot(Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
            val targetClip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
                .intersect(target.boundsInRoot).intersect(windowBounds)
            if (targetClip.width > 1f && targetClip.height > 1f && targetClip.contains(center)) return

            val viewport = compose.onNodeWithTag("vocabulary-sense-list").fetchSemanticsNode()
            val range = viewport.config[SemanticsProperties.VerticalScrollAxisRange]
            val before = range.value()
            val viewportCoordinates = viewport.layoutInfo.coordinates
            val clip = viewportCoordinates.findRootCoordinates().localBoundingBoxOf(viewportCoordinates, clipBounds = true)
                .intersect(viewport.boundsInRoot).intersect(windowBounds)
            assertTrue("The real list must provide a visible touch viewport: attempt=$attempt clip=$clip", clip.width > 80f && clip.height > 80f)
            val upward = center.y > clip.center.y
            val start = Offset(clip.center.x, if (upward) clip.bottom - 16f else clip.top + 16f)
            val end = Offset(clip.center.x, if (upward) clip.top + 16f else clip.bottom - 16f)
            val rootOrigin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
            compose.onRoot().performTouchInput {
                down(start - rootOrigin)
                repeat(20) { step ->
                    moveTo(start + (end - start) * ((step + 1) / 20f) - rootOrigin, 60L)
                }
                repeat(6) { advanceEventTime(40L); moveTo(end - rootOrigin, 0L) }
                up()
            }
            compose.waitForIdle()
            val after = compose.onNodeWithTag("vocabulary-sense-list").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("A physical list drag must advance toward the requested group: attempt=$attempt before=$before after=$after", if (upward) after > before else after < before)
        }
        error("Second group speaker was not physically reachable within the bounded real list drags")
    }

    private fun physicalClick(tag: String) {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsActions.OnClick))
        val coordinates = node.layoutInfo.coordinates
        val center = coordinates.localToRoot(Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(node.boundsInRoot).intersect(windowBounds)
        assertTrue("Physical action center must be inside the actual visible clip: tag=$tag center=$center clip=$clip", clip.contains(center))
        val rootOrigin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        compose.onRoot().performTouchInput { click(center - rootOrigin) }
        compose.waitForIdle()
    }

    private fun assertActualWindow() {
        val resources = compose.activity.resources
        assertEquals(320, resources.configuration.densityDpi)
        assertEquals(2f, resources.displayMetrics.density, .001f)
        assertEquals(1f, resources.configuration.fontScale, .001f)
        assertEquals(1829, compose.activity.window.decorView.width)
        assertEquals(860, compose.activity.window.decorView.height)
        val root = compose.onRoot().fetchSemanticsNode().layoutInfo
        assertEquals(1829, root.width)
        assertEquals(860, root.height)
    }

    private fun records() = listOf(
        VocabularySenseRecord("run-move", "run", "跑步", KnowledgeStatus.MASTERED, 3, setOf("CET4"), 1,
            phonetic = "/rʌn/", pronunciationText = "run", focusOccurrenceCount = 4, contextOccurrenceCount = 2),
        VocabularySenseRecord("run-business", "run", "经营", KnowledgeStatus.LEARNING, 1, setOf("CET4"), 2,
            phonetic = "/rʌn/", pronunciationText = "run", focusOccurrenceCount = 2, contextOccurrenceCount = 3),
        VocabularySenseRecord("light-bright", "light", "光", KnowledgeStatus.TENTATIVE, 2, setOf("CET4"), 3,
            phonetic = "/laɪt/", pronunciationText = "light", focusOccurrenceCount = 3, contextOccurrenceCount = 1),
        VocabularySenseRecord("light-weight", "light", "轻的", KnowledgeStatus.NOT_STARTED, 0, setOf("CET4"), 4,
            phonetic = "/laɪt/", pronunciationText = "light", focusOccurrenceCount = 1, contextOccurrenceCount = 4),
    )

    companion object {
        private val windowBounds = Rect(0f, 0f, 1829f, 860f)
    }
}
