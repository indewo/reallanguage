package com.reallanguage.app.feature.knowledge.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.reallanguage.app.R
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyEntry
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real Screen and physical actions, with only expandedSenseId controlled locally.
 * Stored pronunciation/IPA values are synthetic metadata, not an AI or TTS verdict.
 * No ViewModel, repository, database, account, HTTP, audio or credentials are used.
 * Root supplies the actual 1829x860 / d320 / system-font1 window.
 */
class VocabularyExpandedPronunciationIdentityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun differentStoredPhoneticsKeepTheirOwnSenseSpeakersEvenWhenWordTextIsIdentical() {
        // The identical stored TTS text must not override the two distinct IPAs.
        val senses = listOf(
            sense("lead-guide", "lead", "带领", "/liːd/", "lead", 1),
            sense("lead-metal", "lead", "铅", "/lɛd/", "lead", 2),
        )
        val state = render(senses)
        assertSeparatePhonetics(senses)
        assertNoSharedSpeaker(senses)
        assertWordSpeakerCount(2)
        senses.forEach { click(senseSpeaker(it.senseId)) }
        assertEquals(senses.map { VocabularyBookAction.SensePronunciationRequested(it.senseId) }, actions)
        actions.clear()

        click(senseRow(senses[1].senseId))
        assertEquals(senses[1].senseId, state.value.expandedSenseId)
        // Both stored identities remain visible in their own rows; the selected
        // IPA on an unrelated featured card cannot satisfy this requirement.
        assertSeparatePhonetics(senses)
        assertNoSharedSpeaker(senses)
        assertWordSpeakerCount(2)
        senses.forEach { click(senseSpeaker(it.senseId)) }
        assertEquals(listOf(
            VocabularyBookAction.SenseSelected(senses[1].senseId),
            VocabularyBookAction.SensePronunciationRequested(senses[0].senseId),
            VocabularyBookAction.SensePronunciationRequested(senses[1].senseId),
        ), actions)
        assertEquals(senses, state.value.entries.single().senses)

        click(compose.onNodeWithTag("vocabulary-detail-collapse"))
        assertEquals(null, state.value.expandedSenseId)
        assertSeparatePhonetics(senses)
        assertNoSharedSpeaker(senses)
        assertWordSpeakerCount(2)
    }

    @Test
    fun missingFirstSenseMetadataNeverBorrowsTheSecondSensePhoneticOrPlaybackIdentity() {
        val missing = sense("run-unrecorded", "run", "跑步", null, null, 1)
        val available = sense("run-business", "run", "经营", "/rʌn/", "run", 2)
        val senses = listOf(missing, available)
        val state = render(senses)
        assertPartialMetadata(missing, available)
        click(senseRow(missing.senseId))
        assertEquals(missing.senseId, state.value.expandedSenseId)
        assertPartialMetadata(missing, available)
        click(senseSpeaker(available.senseId))
        assertEquals(listOf(
            VocabularyBookAction.SenseSelected(missing.senseId),
            VocabularyBookAction.SensePronunciationRequested(available.senseId),
        ), actions)

        click(senseRow(available.senseId))
        assertEquals(available.senseId, state.value.expandedSenseId)
        assertPartialMetadata(missing, available)
        click(senseSpeaker(available.senseId))
        assertEquals(VocabularyBookAction.SensePronunciationRequested(available.senseId), actions.last())
        assertEquals(2, actions.count { it == VocabularyBookAction.SensePronunciationRequested(available.senseId) })
        assertEquals(null, state.value.entries.single().senses.first().phonetic)
        assertEquals(null, state.value.entries.single().senses.first().pronunciationText)
        assertEquals(senses, state.value.entries.single().senses)
    }

    @Test
    fun matchingStoredPhoneticAndPlaybackUseOneSharedWordSpeakerBeforeAndAfterExpansion() {
        val senses = listOf(
            sense("run-move", "run", "跑步", "/rʌn/", "run", 1),
            sense("run-manage", "run", "经营", "/rʌn/", "run", 2),
        )
        val state = render(senses)
        assertOneSharedSpeaker(senses)
        click(senseRow(senses[1].senseId))
        assertEquals(senses[1].senseId, state.value.expandedSenseId)
        assertOneSharedSpeaker(senses)
        // Its explicit source is the first sense only because all stored IPA
        // and pronunciation values have already been proven identical.
        click(compose.onNodeWithTag("vocabulary-group-pronunciation-${senses[0].senseId}"))
        assertEquals(listOf(
            VocabularyBookAction.SenseSelected(senses[1].senseId),
            VocabularyBookAction.SensePronunciationRequested(senses[0].senseId),
        ), actions)
        assertEquals(senses, state.value.entries.single().senses)
        click(compose.onNodeWithTag("vocabulary-detail-collapse"))
        assertEquals(null, state.value.expandedSenseId)
        assertOneSharedSpeaker(senses)
    }

    private val actions = mutableListOf<VocabularyBookAction>()

    private fun render(senses: List<VocabularySenseRecord>): MutableState<VocabularyBookUiState> {
        val state = mutableStateOf(VocabularyBookUiState("CET_4",
            listOf(VocabularyEntry(senses.first().englishForm, senses)), loading = false))
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.window.decorView.width == 1829 && compose.activity.window.decorView.height == 860
        }
        compose.setContent { RlTheme { RlPaperBackground {
            VocabularyBookScreen(state.value) { action ->
                actions += action
                when (action) {
                    is VocabularyBookAction.SenseSelected -> state.value = state.value.copy(expandedSenseId = action.senseId)
                    VocabularyBookAction.CollapseRequested -> state.value = state.value.copy(expandedSenseId = null)
                    else -> Unit
                }
            }
        } } }
        compose.waitForIdle()
        assertEquals(320, compose.activity.resources.configuration.densityDpi)
        assertEquals(1f, compose.activity.resources.configuration.fontScale, .001f)
        assertEquals(2f, compose.activity.resources.displayMetrics.density, .001f)
        val root = compose.onRoot().fetchSemanticsNode().layoutInfo
        assertEquals(1829, root.width)
        assertEquals(860, root.height)
        return state
    }

    private fun sense(id: String, english: String, meaning: String, ipa: String?, speech: String?, order: Long) =
        VocabularySenseRecord(id, english, meaning, KnowledgeStatus.LEARNING, 0, setOf("CET_4"), order,
            phonetic = ipa, pronunciationText = speech)

    private fun rowMatcher(id: String) = hasTestTag("vocabulary-sense-$id") or hasTestTag("vocabulary-detail-sense-$id")
    private fun senseRow(id: String) = compose.onNode(rowMatcher(id))
    private fun senseSpeaker(id: String) = compose.onNodeWithTag("vocabulary-sense-pronunciation-$id")

    private fun assertSeparatePhonetics(senses: List<VocabularySenseRecord>) {
        senses.forEach { sense ->
            val ipa = requireNotNull(sense.phonetic)
            compose.onAllNodesWithText(ipa, useUnmergedTree = true).assertCountEquals(1)
            val text = compose.onNode(hasText(ipa) and hasAnyAncestor(rowMatcher(sense.senseId)), useUnmergedTree = true)
            reach(text)
            text.assertIsDisplayed()
            reach(senseSpeaker(sense.senseId))
            senseSpeaker(sense.senseId).assertIsDisplayed().assertIsEnabled()
        }
    }

    private fun assertPartialMetadata(missing: VocabularySenseRecord, available: VocabularySenseRecord) {
        val ipa = requireNotNull(available.phonetic)
        compose.onAllNodesWithText(ipa, useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(hasText(ipa) and hasAnyAncestor(rowMatcher(missing.senseId)), useUnmergedTree = true).assertDoesNotExist()
        val ownPhonetic = compose.onNode(hasText(ipa) and hasAnyAncestor(rowMatcher(available.senseId)), useUnmergedTree = true)
        reach(ownPhonetic)
        ownPhonetic.assertIsDisplayed()
        senseSpeaker(missing.senseId).assertDoesNotExist()
        assertNoSharedSpeaker(listOf(missing, available))
        assertWordSpeakerCount(1)
        reach(senseSpeaker(available.senseId))
        senseSpeaker(available.senseId).assertIsDisplayed().assertIsEnabled()
    }

    private fun assertNoSharedSpeaker(senses: List<VocabularySenseRecord>) {
        senses.forEach { compose.onNodeWithTag("vocabulary-group-pronunciation-${it.senseId}").assertDoesNotExist() }
    }

    private fun assertOneSharedSpeaker(senses: List<VocabularySenseRecord>) {
        compose.onAllNodesWithText(requireNotNull(senses.first().phonetic), useUnmergedTree = true).assertCountEquals(1)
        assertWordSpeakerCount(1)
        senses.forEach { senseSpeaker(it.senseId).assertDoesNotExist() }
        val shared = compose.onNodeWithTag("vocabulary-group-pronunciation-${senses.first().senseId}")
        reach(shared)
        shared.assertIsDisplayed().assertIsEnabled()
    }

    private fun assertWordSpeakerCount(count: Int) {
        compose.onAllNodesWithText(compose.activity.getString(R.string.vocabulary_detail_pronunciation),
            useUnmergedTree = true).assertCountEquals(count)
    }

    /** Reach only through the production node's genuinely scrollable ancestor. */
    private fun reach(node: SemanticsNodeInteraction) {
        repeat(12) {
            val semantic = node.fetchSemanticsNode()
            val coordinates = semantic.layoutInfo.coordinates
            val center = coordinates.localToRoot(Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
            val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
                .intersect(semantic.boundsInRoot).intersect(windowBounds)
            if (clip.width > 1f && clip.height > 1f && clip.contains(center)) return
            val ancestor = generateSequence(semantic.parent) { it.parent }.firstOrNull {
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange) &&
                    it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f
            } ?: error("The exact stored metadata/action is clipped without a real scrolling ancestor")
            val scroll = compose.onNode(SemanticsMatcher("Actual scroll ancestor ${ancestor.id}") { it.id == ancestor.id }, useUnmergedTree = true)
            val area = ancestor.boundsInRoot.intersect(windowBounds)
            assertTrue("An actual readable scroll region is required", area.height > 40f && area.width > 40f)
            val range = ancestor.config[SemanticsProperties.VerticalScrollAxisRange]
            val before = range.value()
            val upwards = center.y > area.center.y
            val from = Offset(area.center.x, if (upwards) area.bottom - 12f else area.top + 12f)
            val to = Offset(area.center.x, if (upwards) area.top + 12f else area.bottom - 12f)
            val rootOrigin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
            compose.onRoot().performTouchInput {
                down(from - rootOrigin)
                repeat(20) { step -> moveTo(from + (to - from) * ((step + 1) / 20f) - rootOrigin, 60L) }
                repeat(6) { advanceEventTime(40L); moveTo(to - rootOrigin, 0L) }
                up()
            }
            compose.waitForIdle()
            val after = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("A dispatched drag must actually move toward the requested identity", if (upwards) after > before else after < before)
        }
        error("Stored pronunciation metadata was not reachable after bounded physical scrolling")
    }

    private fun click(node: SemanticsNodeInteraction) {
        reach(node)
        val semantic = node.assertIsDisplayed().assertIsEnabled().fetchSemanticsNode()
        assertTrue(semantic.config.contains(SemanticsActions.OnClick))
        val coordinates = semantic.layoutInfo.coordinates
        val center = coordinates.localToRoot(Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(semantic.boundsInRoot).intersect(windowBounds)
        assertTrue("Physical input must hit the real visible action", clip.contains(center))
        val rootOrigin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        compose.onRoot().performTouchInput { click(center - rootOrigin) }
        compose.waitForIdle()
    }

    private companion object {
        val windowBounds = Rect(0f, 0f, 1829f, 860f)
    }
}
