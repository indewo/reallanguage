package com.reallanguage.app.feature.learning.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Real repository/JSON and private SQL; isolated journal/HTTP/NOT_AUDIO are not AI quality evidence. */
class AndroidSceneDurableRecoveryTest {
    @Test fun restoringEncryptedEvidenceDoesNotLeaveAnotherPlainAudioFileOnDisk() = runBlocking {
        Fixture().use { f ->
            val first = f.prepared(0)
            f.failurePath = TRANSCRIBE
            first.transcribeResponse(f.task.taskId)
            first.cancelCapture()
            val before = f.base.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("scene-response-recovered-") }.map { it.name }.toSet()
            val second = f.repository()
            second.restoreTasks(f.chapter)
            assertEquals(SceneResponseRecovery.TranscriptionPending, second.restoreAttempt(f.chapter, f.task, 0))
            val after = f.base.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("scene-response-recovered-") }.map { it.name }.toSet()
            assertEquals(before, after)
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun cachedPendingStillChecksCurrentJournalAuthorityBeforeRetryHttp() = runBlocking {
        Fixture().use { f ->
            val repository = f.prepared(0)
            f.failurePath = TRANSCRIBE
            repository.transcribeResponse(f.task.taskId)
            assertEquals(1, f.requests.size)
            f.journal.unavailable = true
            f.failurePath = null
            assertEquals(SpeakingTranscriptionResult.Failed, repository.transcribeResponse(f.task.taskId))
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun cancelledJournalReadCannotReviveTheDetachedAttempt() = runBlocking {
        Fixture().use { f ->
            val first = f.prepared(0)
            f.failurePath = TRANSCRIBE
            first.transcribeResponse(f.task.taskId)
            first.cancelCapture()
            val second = f.repository()
            second.restoreTasks(f.chapter)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.journal.beforeLoad = { entered.complete(Unit); release.await() }
            val restoring = async { second.restoreAttempt(f.chapter, f.task, 0) }
            entered.await()
            second.cancelCapture()
            release.complete(Unit)
            assertEquals(SceneResponseRecovery.Unavailable, restoring.await())
            assertFalse(second.hasPendingTranscriptionRetry())
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun cancelledCapturePreparationCannotStartAMicrophoneOnLateSave() = runBlocking {
        Fixture().use { f ->
            val repository = f.prepared(0)
            repository.cancelCapture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.journal.beforeSave = { entered.complete(Unit); release.await() }
            val preparing = async { repository.prepareCapture(f.chapter, f.task, 0) }
            entered.await()
            repository.cancelCapture()
            release.complete(Unit)
            assertFalse(preparing.await())
            assertFalse(repository.startCapture())
            assertEquals(1, f.starts)
        }
    }

    @Test fun unknownTranscriptionRestoresItsOriginalRequestAfterRepositoryAndAudioAreGone() = runBlocking {
        Fixture().use { f ->
            val first = f.prepared(0)
            f.failurePath = TRANSCRIBE
            assertEquals(SpeakingTranscriptionResult.Failed, first.transcribeResponse(f.task.taskId))
            val body = f.requests.single().body
            first.cancelCapture()
            val second = f.repository()
            assertTrue(second.restoreTasks(f.chapter) is SceneTaskGenerationResult.Ready)
            assertEquals(SceneResponseRecovery.TranscriptionPending, second.restoreAttempt(f.chapter, f.task, 0))
            assertEquals(1, f.requests.size)
            assertTrue(second.hasPendingTranscriptionRetry())
            f.failurePath = null
            assertTrue(second.transcribeResponse(f.task.taskId) is SpeakingTranscriptionResult.Ready)
            assertEquals(body, f.requests.last().body)
            assertEquals(1, f.starts)
        }
    }

    @Test fun unknownTrainingAndEvaluationRestoreConfirmedEvidenceAndDoNotRetranscribe() = runBlocking {
        for (round in listOf(0, 2)) Fixture().use { f ->
            val first = f.prepared(round)
            assertTrue(first.transcribeResponse(f.task.taskId) is SpeakingTranscriptionResult.Ready)
            f.failurePath = if (round == 2) EVALUATE else TRAINING
            f.feedback(first, round)
            assertEquals(2, f.requests.size)
            val body = f.requests.last().body
            first.cancelCapture()
            val second = f.repository()
            assertTrue(second.restoreTasks(f.chapter) is SceneTaskGenerationResult.Ready)
            assertEquals(SceneResponseRecovery.FeedbackPending(TEXT), second.restoreAttempt(f.chapter, f.task, round))
            assertEquals(2, f.requests.size)
            assertTrue(second.hasPendingEvaluationRetry())
            f.failurePath = null
            f.feedback(second, round)
            assertEquals(3, f.requests.size)
            assertEquals(body, f.requests.last().body)
            assertEquals(1, f.starts)
            val third = f.repository()
            assertTrue(third.restoreTasks(f.chapter) is SceneTaskGenerationResult.Ready)
            val delivered = third.restoreAttempt(f.chapter, f.task, round)
            assertTrue(if (round == 2) delivered is SceneResponseRecovery.EvaluationReady else delivered is SceneResponseRecovery.TrainingReady)
            assertEquals(3, f.requests.size)
            assertNull(f.journal.snapshot?.feedbackBody)
            assertNull(f.journal.snapshot?.transcriptionBody)
        }
    }

    @Test fun deliveredTranscriptionRestoresOriginalAudioForConfirmedFeedbackWithoutNewAsr() = runBlocking {
        Fixture().use { f ->
            val first = f.prepared(2)
            assertTrue(first.transcribeResponse(f.task.taskId) is SpeakingTranscriptionResult.Ready)
            val originalId = first.currentRecordingId()
            first.cancelCapture()
            val second = f.repository()
            second.restoreTasks(f.chapter)
            val restored = second.restoreAttempt(f.chapter, f.task, 2) as SceneResponseRecovery.TranscriptReady
            assertEquals(TEXT, restored.value.transcript)
            assertEquals(originalId, restored.recordingId)
            assertTrue(second.evaluateFinalRound(f.task.taskId, "confirmed manual text") is SceneEvaluationResult.Ready)
            assertEquals(listOf(TRANSCRIBE, EVALUATE), f.requests.map { it.path })
            assertEquals(1, f.starts)
        }
    }

    @Test fun failedPendingAcknowledgementCannotStartAnyProviderRequest() = runBlocking {
        Fixture().use { f ->
            val repository = f.prepared(0)
            f.journal.writable = false
            assertEquals(SpeakingTranscriptionResult.Failed, repository.transcribeResponse(f.task.taskId))
            assertTrue(f.requests.isEmpty())
            assertEquals(1, f.starts)
        }
    }

    @Test fun corruptedOrWrongTargetJournalCannotBecomeAnEmptyCaptureAttempt() = runBlocking {
        Fixture().use { f ->
            val first = f.prepared(2)
            first.transcribeResponse(f.task.taskId)
            f.failurePath = EVALUATE
            first.evaluateFinalRound(f.task.taskId, TEXT)
            first.cancelCapture()
            f.journal.snapshot = requireNotNull(f.journal.snapshot).copy(taskId = "unrelated-task")
            val second = f.repository()
            second.restoreTasks(f.chapter)
            assertEquals(SceneResponseRecovery.Unavailable, second.restoreAttempt(f.chapter, f.task, 2))
            assertFalse(second.prepareCapture(f.chapter, f.task, 2))
            assertFalse(second.startCapture())
            assertEquals(2, f.requests.size)
            assertEquals(1, f.starts)
        }
    }

    @Test fun explicitNewRecordingCannotReuseAPriorPendingOrTerminalRequestIdentity() = runBlocking {
        Fixture().use { f ->
            val first = f.prepared(0)
            f.terminal = true
            first.transcribeResponse(f.task.taskId)
            val old = f.requests.single().body
            first.cancelCapture()
            val second = f.repository()
            second.restoreTasks(f.chapter)
            assertEquals(SceneResponseRecovery.Blocked, second.restoreAttempt(f.chapter, f.task, 0))
            assertTrue(second.technicalRetryBlocked())
            assertFalse(second.hasPendingTranscriptionRetry())
            assertTrue(second.prepareCapture(f.chapter, f.task, 0))
            assertTrue(second.startCapture())
            f.terminal = false
            assertTrue(second.transcribeResponse(f.task.taskId) is SpeakingTranscriptionResult.Ready)
            assertNotEquals(old, f.requests.last().body)
            assertEquals(2, f.starts)
        }
    }

    private class Journal : SceneResponseRecoveryJournal {
        var snapshot: SceneResponseRecoverySnapshot? = null
        private var revision = 0L
        var writable = true
        var unavailable = false
        var beforeLoad: suspend () -> Unit = {}
        var beforeSave: suspend () -> Unit = {}
        override suspend fun load(): SceneResponseRecoveryLoad {
            beforeLoad()
            if (unavailable) return SceneResponseRecoveryLoad.Unavailable
            return snapshot?.let { SceneResponseRecoveryLoad.Loaded(revision, it) } ?: SceneResponseRecoveryLoad.Missing
        }
        override suspend fun save(expectedRevision: Long?, snapshot: SceneResponseRecoverySnapshot): Long? {
            beforeSave()
            if (unavailable) return null
            if (!writable || expectedRevision != if (this.snapshot == null) null else revision) return null
            this.snapshot = snapshot
            return ++revision
        }
    }

    private class Fixture : Closeable {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val label = "scene-durable-${UUID.randomUUID()}"
        val context = IsolatedLearningDatabaseContext(base, label)
        val directory = File(base.cacheDir, label).apply { check(mkdir()) }
        val runtime = SessionRuntime().apply { update("isolated-session", label) }
        val chapter = FixedLearningChapter("chapter", 1, listOf(FixedLearningSentence("sentence", TEXT, "我喝茶。", true)))
        val task = FixedSceneResponseTask("task", "sentence", "Drinks", listOf(SceneDialogueLine("AI", "What do you drink?")), TEXT)
        val journal = Journal()
        val requests = mutableListOf<HttpRequest>()
        var failurePath: String? = null
        var terminal = false
        var starts = 0
        private val repositories = mutableListOf<AndroidSceneResponseRepository>()
        private var seeded = false
        fun repository() = AndroidSceneResponseRepository(context, HttpTransport { request ->
            requests += request
            when {
                terminal -> HttpResponse(410, "")
                request.path == failurePath -> HttpResponse(503, "")
                request.path == TRANSCRIBE -> HttpResponse(200, """{"transcript":"$TEXT","lowConfidenceSegments":[],"confidence":null}""")
                request.path == TRAINING -> HttpResponse(200, """{"status":"READY","feedbackZh":"隔离训练反馈","pronunciationConfidence":0.9}""")
                request.path == EVALUATE -> HttpResponse(200, """{"decision":"PASS","correctTurn":true,"targetSentenceComplete":true,"pronunciationIntelligible":true,"feedbackZh":"隔离评价候选","pronunciationConfidence":0.9}""")
                else -> error("Unexpected isolated HTTP")
            }
        }, runtime, object : ReadSpeakAudioCapture {
            var file: File? = null
            override fun start(): Boolean { starts++; file = File(directory, "audio-$starts.test").apply { writeText("NOT_AUDIO:$label:$starts") }; return true }
            override fun finish(): CapturedReadSpeakAudio? = file?.let { file = null; CapturedReadSpeakAudio(it) }
            override fun cancel() { file?.delete(); file = null }
        }, recoveryJournalFactory = { _, _, _ -> journal }).also { repositories += it }

        suspend fun prepared(round: Int): AndroidSceneResponseRepository {
            if (!seeded) withContext(Dispatchers.IO) {
                FormationDatabase(context).use { helper ->
                    val db = helper.writableDatabase
                    db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离测试')", arrayOf(label, label))
                    db.execSQL("insert into chapters values(?,?,?,0,'IN_PROGRESS')", arrayOf(label, label, chapter.chapterId))
                    db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,'sentence',0,'我喝茶。',?,'FOCUS')", arrayOf(label, chapter.chapterId, TEXT))
                    db.execSQL("insert into scene_response_tasks(account_id,chapter_id,task_id,sentence_id,sentence_order,background_en,context_lines,target_sentence_en) values(?,?,'task','sentence',0,'Drinks',?,?)", arrayOf(label, chapter.chapterId, "AI\u001fWhat do you drink?", TEXT))
                }
                seeded = true
            }
            return repository().also { repository ->
                assertTrue(repository.restoreTasks(chapter) is SceneTaskGenerationResult.Ready)
                assertEquals(SceneResponseRecovery.None, repository.restoreAttempt(chapter, task, round))
                assertTrue(repository.prepareCapture(chapter, task, round))
                assertTrue(repository.startCapture())
            }
        }
        suspend fun feedback(repository: AndroidSceneResponseRepository, round: Int): Any = if (round == 2)
            repository.evaluateFinalRound(task.taskId, TEXT) else repository.trainingFeedback(task.taskId, round, TEXT)
        override fun close() {
            repositories.forEach { it.cancelCapture() }
            runtime.clear()
            directory.listFiles().orEmpty().forEach { check(it.canonicalFile.parentFile == directory.canonicalFile); check(it.delete()) }
            check(context.deleteDatabase("real-language-learning.db"))
            check(directory.delete())
        }
    }

    companion object {
        const val TRANSCRIBE = "/api/v1/learning/speaking-transcriptions"
        const val TRAINING = "/api/v1/learning/scene-response-training-feedback"
        const val EVALUATE = "/api/v1/learning/scene-response-evaluations"
        const val TEXT = "I drink tea."
    }
}
