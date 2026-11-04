package com.reallanguage.app.core.app

import android.content.Context
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.learning.presentation.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real entry lifetimes and SQLite progress; capture/AI are explicit isolated test doubles. */
class SpeakingEntryLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val chapter = FixedLearningChapter("entry-chapter", 1,
        listOf(FixedLearningSentence("s1", "I used to write.", "我过去常常写字。", true)))

    @Test fun fullSpeakingReentryUsesItsCurrentCaptureAndRestoresSavedRound() = fixture("LR-06") { store ->
        val saved = mapOf("version" to "1", "attemptIndex" to "2", "answerAudioPlayed" to "false",
            "transcript" to "", "feedbackZh" to "已保存的训练反馈", "trainingCanContinue" to "false", "finalDecision" to "")
        assertTrue(runBlocking { store.write(saved) })
        val shown = mutableStateOf(true)
        val traces = mutableListOf<CaptureTrace>()
        var created = 0
        var current: FullSentenceSpeakingViewModel? = null
        compose.setContent {
            if (shown.value) {
                val trace = remember { CaptureTrace().also(traces::add) }
                val repository = remember { IsolatedFullSpeaking(trace) }
                DisposableEffect(repository) { onDispose { repository.cancelCapture() } }
                current = rememberSpeakingEntryViewModel("full-entry", factory {
                    created++; FullSentenceSpeakingViewModel(chapter, "s1", repository, store)
                }, repository)
            }
        }
        try {
            compose.waitUntil(5_000) { current?.uiState?.value?.let { !it.submitting && it.attemptIndex == 2 } == true }
            val first = current
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            compose.runOnIdle { shown.value = true }
            compose.waitUntil(5_000) { traces.size == 2 && current?.uiState?.value?.submitting == false }
            compose.runOnIdle { current!!.onAction(FullSentenceSpeakingAction.StartRequested) }
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            assertTrue("Leaving must stop the same recorder used by the retained task", traces.none { it.recording })
            assertEquals(2, created)
            assertNotSame(first, current)
            assertEquals(2, current!!.uiState.value.attemptIndex)
            assertEquals("2", runBlocking { store.read() }?.get("attemptIndex"))
            assertEquals(1, traces[1].starts)
        } finally {
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            traces.forEach(CaptureTrace::cancel)
        }
    }

    @Test fun patternReentryUsesItsCurrentCaptureAndRestoresExactSavedExamples() = fixture("LR-07") { store ->
        val pattern = LockedSpeakingPattern("p1", "s1", "used to + verb", "过去习惯")
        val examples = listOf(
            PatternPracticeExample("e1", "旧习惯", "What did you do?", "I used to write.", "我过去常常写字。"),
            PatternPracticeExample("e2", "旧习惯", "What did you do?", "I used to read.", "我过去常常阅读。"))
        val saved = encodePatternProgress(PatternSpeakingUiState(chapter, "s1", listOf(pattern),
            stage = PatternSpeakingStage.READING, examples = examples, completedReadingExampleIds = setOf("e1")))
        assertTrue(runBlocking { store.write(saved) })
        val shown = mutableStateOf(true)
        val traces = mutableListOf<CaptureTrace>()
        val restored = mutableListOf<PatternPracticeSet>()
        var created = 0
        var current: PatternSpeakingViewModel? = null
        compose.setContent {
            if (shown.value) {
                val trace = remember { CaptureTrace().also(traces::add) }
                val repository = remember { IsolatedPatternSpeaking(trace, restored::add) }
                DisposableEffect(repository) { onDispose { repository.cancelCapture() } }
                current = rememberSpeakingEntryViewModel("pattern-entry", factory {
                    created++; PatternSpeakingViewModel(chapter, "s1", listOf(pattern), repository, store)
                }, repository)
            }
        }
        try {
            compose.waitUntil(5_000) { current?.uiState?.value?.let { !it.submitting && it.examples.size == 2 } == true }
            val first = current
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            compose.runOnIdle { shown.value = true }
            compose.waitUntil(5_000) { traces.size == 2 && current?.uiState?.value?.submitting == false }
            compose.runOnIdle { current!!.onAction(PatternSpeakingAction.StartReadingRequested("e2")) }
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            assertTrue("Leaving must stop the active pattern recorder", traces.none { it.recording })
            assertEquals(2, created)
            assertNotSame(first, current)
            assertEquals(setOf("e1"), current!!.uiState.value.completedReadingExampleIds)
            assertEquals(listOf(examples, examples), restored.map { it.examples })
            assertEquals("true", runBlocking { store.read() }?.get("example.0.read"))
            assertEquals(1, traces[1].starts)
        } finally {
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            traces.forEach(CaptureTrace::cancel)
        }
    }

    @Test fun sceneReentryUsesItsCurrentCaptureAndRestoresSavedRoundWithoutGeneration() = fixture("LR-09") { store ->
        val tasks = listOf(FixedSceneResponseTask("t1", "s1", "Discussing past habits.", emptyList(), "I used to write."))
        val saved = encodeSceneProgress(SceneResponseUiState(chapter, tasks, roundIndex = 1), null)
        assertTrue(runBlocking { store.write(saved) })
        val shown = mutableStateOf(true)
        val traces = mutableListOf<CaptureTrace>()
        var created = 0
        var restores = 0
        var current: SceneResponseViewModel? = null
        compose.setContent {
            if (shown.value) {
                val trace = remember { CaptureTrace().also(traces::add) }
                val repository = remember { IsolatedSceneResponse(trace, tasks) { restores++ } }
                DisposableEffect(repository) { onDispose { repository.cancelCapture() } }
                current = rememberSpeakingEntryViewModel("scene-entry", factory {
                    created++; SceneResponseViewModel(chapter, null, repository, store)
                }, repository)
            }
        }
        try {
            compose.waitUntil(5_000) { current?.uiState?.value?.let { !it.loading && it.roundIndex == 1 } == true }
            val first = current
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            compose.runOnIdle { shown.value = true }
            compose.waitUntil(5_000) { traces.size == 2 && current?.uiState?.value?.loading == false }
            compose.runOnIdle { current!!.onAction(SceneResponseAction.StartRequested) }
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            assertTrue("Leaving must stop the active scene recorder", traces.none { it.recording })
            assertEquals(2, created)
            assertNotSame(first, current)
            assertEquals(2, restores)
            assertEquals(1, current!!.uiState.value.roundIndex)
            assertEquals("1", runBlocking { store.read() }?.get("roundIndex"))
            assertEquals(1, traces[1].starts)
        } finally {
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            traces.forEach(CaptureTrace::cancel)
        }
    }

    private fun fixture(module: String, block: (AndroidLearningProgressStore) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = "capture-entry-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-not-a-network-session", account) }
        FormationDatabase(context).use { helper ->
            helper.writableDatabase.execSQL("insert into chapters values (?, 'isolated-flow', 'entry-chapter', 0, 'NOT_STARTED')", arrayOf(account))
            helper.writableDatabase.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, 'entry-chapter', 's1', 0, '我过去常常写字。', 'I used to write.', 'FOCUS')", arrayOf(account))
        }
        try { block(AndroidLearningProgressStore(context, runtime, "entry-chapter", "s1", module)) }
        finally {
            FormationDatabase(context).use { helper ->
                listOf("learning_module_progress", "learning_checkpoints", "fixed_sentences", "chapters").forEach {
                    helper.writableDatabase.delete(it, "account_id=?", arrayOf(account))
                }
            }
        }
    }

    private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
}

private class CaptureTrace {
    var recording = false
    var starts = 0
    fun start(): Boolean { starts++; recording = true; return true }
    fun cancel() { recording = false }
}

private class IsolatedFullSpeaking(private val capture: CaptureTrace) : FullSentenceSpeakingRepository {
    override fun startCapture() = capture.start()
    override fun cancelCapture() = capture.cancel()
    override suspend fun trainingFeedback(sentenceId: String, mode: SpeakingTrainingMode): ReadingFeedbackResult = error("No AI call is allowed during reentry")
    override suspend fun transcribeIndependentRetelling(sentenceId: String): SpeakingTranscriptionResult = error("No transcription is requested")
    override suspend fun evaluateIndependentRetelling(sentenceId: String, confirmedTranscript: String, answerAudioPlayed: Boolean): SpeakingEvaluationResult = error("No evaluation is requested")
    override suspend fun saveIndependentDecision(sentenceId: String, decision: SpeakingEvaluationDecision): SpeakingDecisionSaveResult = error("No result submission is requested")
}

private class IsolatedPatternSpeaking(private val capture: CaptureTrace, private val restored: (PatternPracticeSet) -> Unit) : PatternSpeakingRepository {
    override fun startCapture() = capture.start()
    override fun cancelCapture() = capture.cancel()
    override fun restorePractice(pattern: LockedSpeakingPattern, practiceSet: PatternPracticeSet) { restored(practiceSet) }
    override suspend fun generate(pattern: LockedSpeakingPattern): PatternPracticeGenerationResult = error("Saved practice must not generate again")
    override suspend fun readingFeedback(patternId: String, exampleId: String): PatternPracticeFeedbackResult = error("No feedback is requested")
    override suspend fun responseFeedback(patternId: String, exampleId: String): PatternPracticeFeedbackResult = error("No feedback is requested")
}

private class IsolatedSceneResponse(private val capture: CaptureTrace, private val tasks: List<FixedSceneResponseTask>, private val restored: () -> Unit) : SceneResponseRepository {
    override fun startCapture() = capture.start()
    override fun cancelCapture() = capture.cancel()
    override suspend fun generateTasks(chapter: FixedLearningChapter): SceneTaskGenerationResult = error("Saved tasks must not generate again")
    override suspend fun restoreTasks(chapter: FixedLearningChapter): SceneTaskGenerationResult { restored(); return SceneTaskGenerationResult.Ready(tasks) }
    override suspend fun transcribeResponse(taskId: String): SpeakingTranscriptionResult = error("No transcription is requested")
    override suspend fun trainingFeedback(taskId: String, roundIndex: Int, confirmedTranscript: String): SceneTrainingFeedbackResult = error("No feedback is requested")
    override suspend fun evaluateFinalRound(taskId: String, confirmedTranscript: String): SceneEvaluationResult = error("No evaluation is requested")
    override suspend fun saveFinalDecision(chapterId: String, sentenceId: String, decision: SceneEvaluationDecision): SceneResponseSaveResult = error("No result submission is requested")
}
