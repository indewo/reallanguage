package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.learning.presentation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real local progress persistence, isolated generated content/feedback. No claim of AI/audio quality. */
class PatternProcessRecoveryTest {
    @Test fun practiceAndResponseSurviveSeparateProcesses() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("patternPhase")
        assumeTrue(phase in setOf("write", "read", "complete"))
        val account = requireNotNull(args.getString("progressAccount")).also { require(it.matches(Regex("process-test-[a-z0-9-]+"))) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = SessionRuntime().apply { update("isolated-token-not-real", account) }
        val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I used to write.", "我过去常常写字。", true)))
        val pattern = LockedSpeakingPattern("p1", "s1", "used to + verb", "过去习惯")
        val examples = listOf(
            PatternPracticeExample("e1", "上学方式", "How did you travel?", "I used to walk.", "我过去常常走路。"),
            PatternPracticeExample("e2", "课后活动", "What did you do?", "I used to read.", "我过去常常阅读。"),
        )
        if (phase == "write") FormationDatabase(context).use { helper ->
            helper.writableDatabase.execSQL("insert into chapters values (?, 'process-test-flow', 'c1', 0, 'NOT_STARTED')", arrayOf(account))
            helper.writableDatabase.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, 'c1', 's1', 0, '我过去常常写字。', 'I used to write.', 'FOCUS')", arrayOf(account))
        }
        var requests = 0
        var restoredExamples: List<PatternPracticeExample>? = null
        val repository = object : PatternSpeakingRepository {
            override fun startCapture() = true
            override fun cancelCapture() = Unit
            override fun restorePractice(pattern: LockedSpeakingPattern, practiceSet: PatternPracticeSet) { restoredExamples = practiceSet.examples }
            override suspend fun generate(pattern: LockedSpeakingPattern): PatternPracticeGenerationResult {
                check(phase == "write"); requests++
                return PatternPracticeGenerationResult.Ready(PatternPracticeSet(pattern.patternId, examples))
            }
            override suspend fun readingFeedback(patternId: String, exampleId: String): PatternPracticeFeedbackResult {
                check(phase == "write"); requests++
                return PatternPracticeFeedbackResult.Ready(PatternPracticeFeedback("隔离测试指读反馈", false))
            }
            override suspend fun responseFeedback(patternId: String, exampleId: String): PatternPracticeFeedbackResult {
                check(phase == "write"); requests++
                return PatternPracticeFeedbackResult.Ready(PatternPracticeFeedback("隔离测试回应反馈", true, "I used to walk."))
            }
        }
        val store = AndroidLearningProgressStore(context, runtime, "c1", "s1", "LR-07")
        val vm = withContext(Dispatchers.Main) { PatternSpeakingViewModel(chapter, "s1", listOf(pattern), repository, store) }
        suspend fun settled() = withTimeout(5_000) { vm.uiState.first { !it.submitting } }.also { assertFalse(it.progressFailed) }
        suspend fun act(action: PatternSpeakingAction) { withContext(Dispatchers.Main) { vm.onAction(action) }; settled() }
        settled()
        when (phase) {
            "write" -> {
                examples.forEach {
                    act(PatternSpeakingAction.StartReadingRequested(it.exampleId))
                    act(PatternSpeakingAction.ReadingFinished(it.exampleId))
                }
                act(PatternSpeakingAction.StartResponsesRequested)
                act(PatternSpeakingAction.StartResponseRequested)
                act(PatternSpeakingAction.FinishResponseRequested)
                act(PatternSpeakingAction.BackRequested)
                assertEquals(PatternSpeakingEffect.ReturnToFullSentenceSpeaking, withTimeout(5_000) { vm.effects.first() })
                assertEquals(4, requests)
                assertEquals("true", store.read()?.get("responseCompleted"))
            }
            "read" -> {
                assertEquals(examples, restoredExamples)
                assertEquals(setOf("e1", "e2"), vm.uiState.value.completedReadingExampleIds)
                assertTrue(vm.uiState.value.currentResponseCompleted)
                assertTrue(vm.uiState.value.repeatRecommended)
                assertEquals("I used to walk.", vm.uiState.value.transcript)
                act(PatternSpeakingAction.NextSceneRequested)
                assertEquals(1, vm.uiState.value.responseIndex)
                act(PatternSpeakingAction.SkipSceneRequested)
                assertEquals(PatternSpeakingEffect.OpenCompleteWriting, withTimeout(5_000) { vm.effects.first() })
                assertEquals("true", store.read()?.get("completed"))
                assertEquals(0, requests)
            }
            "complete" -> {
                assertTrue(vm.uiState.value.completed)
                assertEquals(PatternSpeakingEffect.OpenCompleteWriting, withTimeout(5_000) { vm.effects.first() })
                assertEquals(0, requests)
                FormationDatabase(context).use { helper ->
                    listOf("learning_module_progress", "learning_checkpoints", "fixed_sentences", "chapters").forEach {
                        helper.writableDatabase.delete(it, "account_id=?", arrayOf(account))
                    }
                }
            }
        }
    }
}
