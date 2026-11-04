package com.reallanguage.app.feature.learning.presentation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExplanationAudioPlaybackTest {
    @get:Rule val compose = createComposeRule()
    private fun state() = ExplanationReadingUiState(FixedLearningChapter("c1", 1, listOf(
        FixedLearningSentence("context", "Other context.", "其他语境。", false),
        FixedLearningSentence("s1", "I felt calm.", "我很平静。", true),
    )), "s1", "基础讲解。", expressions = emptyList())

    @Test fun completeEnglishSentenceIsReadTwiceThenStopsWithoutSpeakingChineseExplanation() {
        val finished = CompletableDeferred<Boolean>()
        val spoken = mutableListOf<String>()
        val actions = mutableListOf<ExplanationReadingAction>()
        compose.setContent {
            ExplanationAudioPlayback(state(), { text, _, language ->
                spoken += "$language:$text"
                if (spoken.size == 1) finished.await() else true
            }, {}, actions::add)
        }
        compose.waitForIdle()
        assertEquals(listOf("en-US:I felt calm."), spoken)
        assertTrue(actions.isEmpty())
        finished.complete(true)
        compose.waitUntil { actions.isNotEmpty() }
        assertEquals(List(2) { "en-US:I felt calm." }, spoken)
        assertEquals(listOf(ExplanationReadingAction.ExplanationFinished), actions)
    }

    @Test fun explicitPronunciationTapReplaysSentenceOnceThenStops() {
        val spoken = mutableListOf<String>()
        val actions = mutableListOf<ExplanationReadingAction>()
        compose.setContent {
            ExplanationAudioPlayback(
                state().copy(pronunciationText = "I felt calm."),
                { text, _, language -> spoken += "$language:$text"; true },
                {},
                actions::add,
            )
        }
        compose.waitUntil { actions.isNotEmpty() }
        assertEquals(listOf("en-US:I felt calm."), spoken)
        assertEquals(listOf(ExplanationReadingAction.ExplanationFinished), actions)
    }

    @Test fun enteringAnotherSentenceStartsASeparateTwoReadBatch() {
        val nextChapter = FixedLearningChapter("c1", 1, listOf(
            FixedLearningSentence("s1", "I felt calm.", "我很平静。", true),
            FixedLearningSentence("s2", "That's nice.", "那挺好的。", true),
        ))
        val spoken = mutableListOf<String>()
        val actions = mutableListOf<ExplanationReadingAction>()
        compose.setContent {
            ExplanationAudioPlayback(
                state().copy(chapter = nextChapter, currentFocusSentenceId = "s2"),
                { text, _, _ -> spoken += text; true },
                {},
                actions::add,
            )
        }
        compose.waitUntil { actions.isNotEmpty() }
        assertEquals(listOf("That's nice.", "That's nice."), spoken)
        assertEquals(listOf(ExplanationReadingAction.ExplanationFinished), actions)
    }

    @Test fun incrementalChineseHintRemainsVisualAndFailureStopsEnglishSequence() {
        val spoken = mutableListOf<String>()
        val actions = mutableListOf<ExplanationReadingAction>()
        compose.setContent {
            ExplanationAudioPlayback(state().copy(currentIncrementalHint = "本轮新增提示。"), { text, _, _ ->
                spoken += text; false
            }, {}, actions::add)
        }
        compose.waitUntil { actions.isNotEmpty() }
        assertEquals(listOf("I felt calm."), spoken)
        assertEquals(listOf(ExplanationReadingAction.AudioPlaybackFailed), actions)
    }

    @Test fun mutedStateCancelsPendingSpeechWithoutFalseCompletion() {
        val value = mutableStateOf(state())
        val pending = CompletableDeferred<Boolean>()
        var started = false
        var stopped = 0
        val actions = mutableListOf<ExplanationReadingAction>()
        compose.setContent { ExplanationAudioPlayback(value.value, { _, _, _ -> started = true; pending.await() }, { stopped++ }, actions::add) }
        compose.waitUntil { started }
        compose.runOnIdle { value.value = value.value.copy(audioPlaying = false) }
        compose.waitUntil { stopped > 0 }
        pending.complete(true)
        compose.waitForIdle()
        assertTrue(actions.isEmpty())
    }

    @Test fun phoneticSymbolsInVisualExplanationNeverReplaceSentenceAudio() {
        val actions = mutableListOf<ExplanationReadingAction>()
        var calls = 0
        compose.setContent { ExplanationAudioPlayback(state().copy(baseExplanationZh = "读音 /kɑːm/"), { _, _, _ -> calls++; true }, {}, actions::add) }
        compose.waitUntil { actions.isNotEmpty() }
        assertEquals(2, calls)
        assertEquals(listOf(ExplanationReadingAction.ExplanationFinished), actions)
    }
}
