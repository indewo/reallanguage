package com.reallanguage.app.feature.learning.data

import android.database.Cursor
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeExample
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingAction
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingEffect
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingStage
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingUiState
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingViewModel
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real SQLite/store/VM/repository/JSON recovery. Provider replies and capture bytes are explicit
 * local fixtures, not recorded speech, AI quality evidence, or a cross-process/whole-journey claim. */
class PatternSpeakingReorderedProgressPersistenceTest {
    private val runId = UUID.randomUUID().toString()
    private val account = "pattern-progress-$runId"
    private val chapterId = "chapter-$runId"
    private val sentenceId = "sentence-$runId"
    private val source = "I used to write by hand."
    private val patternA = LockedSpeakingPattern("z-a-$runId", sentenceId, "used to + verb", "表达过去的习惯")
    private val patternB = LockedSpeakingPattern("a-b-$runId", sentenceId, "by + noun", "说明方式")
    private val patterns = listOf(patternA, patternB)
    private val chapter = FixedLearningChapter(chapterId, 1,
        listOf(FixedLearningSentence(sentenceId, source, "我过去常常手写。", true)))
    private val acceptedB = listOf(
        PatternPracticeExample("b1-$runId", "谈上学方式", "How do you go to school?", "I go to school by bus.", "我乘公交车上学。"),
        PatternPracticeExample("b2-$runId", "谈旅行方式", "How do you travel?", "I travel by train.", "我乘火车旅行。"),
    )
    private val generatedA = listOf(
        PatternPracticeExample("a1-$runId", "谈过去的上学方式", "How did you get to school?", "I used to walk to school.", "我过去常走路上学。"),
        PatternPracticeExample("a2-$runId", "谈过去的周末习惯", "What did you do on weekends?", "I used to read at home.", "我过去常在家读书。"),
    )
    private lateinit var context: IsolatedLearningDatabaseContext
    private lateinit var database: FormationDatabase
    private lateinit var captureDirectory: File

    @Before fun seedOnlyUuidPrivateAcceptedFixedContent() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        context = IsolatedLearningDatabaseContext(base, "pattern-reordered-progress-$runId")
        database = FormationDatabase(context)
        captureDirectory = File(base.cacheDir, "pattern-reordered-capture-$runId").also { check(it.mkdir()) }
        val db = database.writableDatabase
        db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离进度恢复夹具')", arrayOf(account, "flow-$runId"))
        db.execSQL("insert into chapters values(?,?,?,0,'NOT_STARTED')", arrayOf(account, "flow-$runId", chapterId))
        db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,?,0,?,?,'FOCUS')",
            arrayOf(account, chapterId, sentenceId, chapter.sentences.single().textZh, source))
        patterns.forEach { target ->
            val surface = if (target == patternA) "used to write" else "by hand"
            val start = source.indexOf(surface)
            val evidence = JSONObject().put("version", 1).put("startUtf16", start).put("endUtf16", start + surface.length)
                .put("surfaceForm", surface).put("normalizedForm", target.structure).put("allowedForms", JSONArray().put(surface))
            db.execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary,source_evidence) " +
                "values(?,?,?,?,'PATTERN',?,?,'','',?)", arrayOf(account, chapterId, target.patternId, sentenceId, target.structure, target.explanationZh, evidence.toString()))
        }
    }

    @After fun deleteOnlyThisTestsUuidArtifacts() {
        if (::database.isInitialized) database.close()
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        if (::context.isInitialized) {
            val file = context.getDatabasePath("real-language-learning.db").canonicalFile
            check(file.parentFile == base.cacheDir.canonicalFile && file.name.startsWith("pattern-reordered-progress-$runId-"))
            check(context.deleteDatabase("real-language-learning.db"))
        }
        if (::captureDirectory.isInitialized) {
            check(captureDirectory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            check(captureDirectory.name == "pattern-reordered-capture-$runId")
            captureDirectory.listFiles().orEmpty().forEach { file ->
                check(file.isFile && file.canonicalFile.parentFile == captureDirectory.canonicalFile && file.name.startsWith("synthetic-"))
                check(file.delete())
            }
            check(captureDirectory.delete())
        }
    }

    @Test fun legacyCurrentBMovesToAAndSurvivesARealStoreAndViewModelRecreationWithoutRepeatingPaidWork() = runJourney(false)

    @Test fun recordingSkipOfTheLastLegacySceneAllowsTheNextPatternAndDurableCompletion() = runJourney(true)

    private fun runJourney(skipLastWhileRecording: Boolean) = runBlocking {
        val baseline = databaseSnapshot().filterKeys { it !in mutableTables }
        val firstRuntime = runtime()
        val firstStore = progress(firstRuntime)
        val legacyJson = legacyProgressJson()
        val legacy = stringMap(legacyJson)
        assertTrue(firstStore.write(legacy))
        assertEquals(legacy, firstStore.read())
        assertEquals(legacy, savedSqlProgress())
        assertEquals("1", savedSqlProgress()["version"])

        val requests = CopyOnWriteArrayList<String>()
        val capture = SyntheticCapture()
        val transport = fixtureTransport(requests)
        val firstModels = ViewModelStore()
        val secondModels = ViewModelStore()
        var firstEffects: Job? = null
        var secondEffects: Job? = null
        try {
            val firstRepository = AndroidPatternSpeakingRepository(transport, firstRuntime, chapter, capture)
            val first = withContext(Dispatchers.Main) {
                PatternSpeakingViewModel(chapter, sentenceId, patterns, firstRepository, firstStore).also { firstModels.put("first", it) }
            }
            val earlyEvents = CopyOnWriteArrayList<PatternSpeakingEffect>()
            firstEffects = launch(Dispatchers.Main) { first.effects.collect { earlyEvents.add(it) } }
            val restoredB = awaitState(first) { it.stage == PatternSpeakingStage.RESPONDING && it.currentPattern == patternB }
            assertEquals(1, restoredB.patternIndex)
            assertEquals(acceptedB, restoredB.examples)
            assertEquals(acceptedB.map { it.exampleId }.toSet(), restoredB.completedReadingExampleIds)
            assertEquals(acceptedB.lastIndex, restoredB.responseIndex)
            assertEquals("I travel by train.", restoredB.transcript)
            assertEquals("已保存的隔离客观反馈", restoredB.feedbackZh)
            assertTrue(restoredB.repeatRecommended && restoredB.currentResponseCompleted && restoredB.audioMuted)
            assertTrue(requests.isEmpty())
            assertEquals(0, capture.starts)
            awaitSql { it["version"] == "2" && processedIds(it).isEmpty() }

            if (skipLastWhileRecording) {
                withContext(Dispatchers.Main) {
                    first.onAction(PatternSpeakingAction.RetryResponseRequested)
                    first.onAction(PatternSpeakingAction.StartResponseRequested)
                }
                assertTrue(first.uiState.value.recording && capture.active)
                val previousCancels = capture.cancels
                withContext(Dispatchers.Main) { first.onAction(PatternSpeakingAction.SkipSceneRequested) }
                assertTrue("Skipping cancels the actual active capture", capture.cancels > previousCancels)
            } else withContext(Dispatchers.Main) { first.onAction(PatternSpeakingAction.NextSceneRequested) }
            val nextPattern = awaitState(first) { it.stage == PatternSpeakingStage.READING && it.currentPattern == patternA }
            assertFalse("The next pattern cannot inherit the cancelled recording", nextPattern.recording)
            assertNull(nextPattern.readingExampleId)
            assertFalse(capture.active)
            val savedA = awaitSql { it["version"] == "2" && it["patternIndex"] == "0" && it["stage"] == "READING" &&
                processedIds(it) == setOf(patternB.patternId) }
            assertEquals(generatedA.map { it.exampleId }, (0 until generatedA.size).map { savedA["example.$it.id"] })
            assertEquals(listOf("generate:${patternA.patternId}"), requests.toList())
            assertTrue(earlyEvents.isEmpty())
            assertEquals(savedA, firstStore.read())
            withContext(Dispatchers.Main) { firstModels.clear() }
            firstEffects.cancel()
            assertTrue("The first VM is actually cleared, not merely abandoned as another live observer", capture.cancels > 0)
            database.close()
            database = FormationDatabase(context)

            val newRuntime = runtime()
            val newStore = progress(newRuntime)
            assertEquals(savedA, newStore.read())
            val newRepository = AndroidPatternSpeakingRepository(transport, newRuntime, chapter, capture)
            val reopened = withContext(Dispatchers.Main) {
                PatternSpeakingViewModel(chapter, sentenceId, patterns, newRepository, newStore).also { secondModels.put("reopened", it) }
            }
            val events = CopyOnWriteArrayList<PatternSpeakingEffect>()
            secondEffects = launch(Dispatchers.Main) { reopened.effects.collect { events.add(it) } }
            val restoredA = awaitState(reopened) { it.stage == PatternSpeakingStage.READING && it.currentPattern == patternA }
            assertEquals(generatedA, restoredA.examples)
            assertEquals(setOf(patternB.patternId), restoredA.processedPatternIds)
            assertEquals(listOf("generate:${patternA.patternId}"), requests.toList())
            assertEquals(savedA, savedSqlProgress())

            generatedA.forEach { example ->
                withContext(Dispatchers.Main) { reopened.onAction(PatternSpeakingAction.StartReadingRequested(example.exampleId)) }
                awaitState(reopened) { it.recording && it.readingExampleId == example.exampleId }
                withContext(Dispatchers.Main) { reopened.onAction(PatternSpeakingAction.ReadingFinished(example.exampleId)) }
                awaitState(reopened) { example.exampleId in it.completedReadingExampleIds }
            }
            withContext(Dispatchers.Main) { reopened.onAction(PatternSpeakingAction.StartResponsesRequested) }
            awaitState(reopened) { it.stage == PatternSpeakingStage.RESPONDING }
            withContext(Dispatchers.Main) { reopened.onAction(PatternSpeakingAction.SkipSceneRequested) }
            awaitState(reopened) { it.responseIndex == 1 }
            withContext(Dispatchers.Main) { reopened.onAction(PatternSpeakingAction.SkipSceneRequested) }
            awaitState(reopened) { it.completed }
            val complete = awaitSql { it["completed"] == "true" && processedIds(it) == patterns.map { p -> p.patternId }.toSet() }
            withTimeout(5_000) { while (events.size != 1) delay(10) }
            withContext(Dispatchers.Main) { reopened.onAction(PatternSpeakingAction.NextSceneRequested) }
            assertEquals(listOf(PatternSpeakingEffect.OpenCompleteWriting), events.toList())
            assertEquals(complete, newStore.read())
            assertEquals(listOf("generate:${patternA.patternId}", "read:${generatedA[0].exampleId}", "read:${generatedA[1].exampleId}"), requests.toList())
            assertEquals(if (skipLastWhileRecording) 3 else 2, capture.starts)
            assertTrue(capture.files.all { !it.exists() })
            assertEquals(baseline, databaseSnapshot().filterKeys { it !in mutableTables })
            assertEquals("The pre-migration JSON fixture was never rewritten in place", legacy, stringMap(legacyJson))
        } finally {
            withContext(Dispatchers.Main) { firstModels.clear(); secondModels.clear() }
            firstEffects?.cancel()
            secondEffects?.cancel()
        }
    }

    private fun fixtureTransport(requests: MutableList<String>) = object : HttpTransport {
        override suspend fun execute(request: HttpRequest): HttpResponse {
            assertEquals("POST", request.method)
            val body = JSONObject(requireNotNull(request.body))
            assertEquals(chapterId, body.getString("chapterId"))
            assertEquals(patternA.patternId, body.getString("patternId"))
            assertTrue(body.getString("commandId").isNotBlank())
            return when (request.path) {
                "/api/v1/learning/pattern-practice-sets" -> {
                    assertEquals(sentenceId, body.getString("sourceSentenceId"))
                    assertEquals(source, body.getString("sourceTextEn"))
                    assertEquals(patternA.structure, body.getString("structure"))
                    requests.add("generate:${patternA.patternId}")
                    HttpResponse(200, JSONObject().put("status", "READY").put("patternId", patternA.patternId)
                        .put("examples", JSONArray().apply { generatedA.forEach { put(exampleJson(it)) } }).toString())
                }
                "/api/v1/learning/pattern-practice-feedback" -> {
                    assertEquals("READING", body.getString("mode"))
                    val example = generatedA.single { it.exampleId == body.getString("exampleId") }
                    assertEquals(example.targetResponseEn, body.getString("targetTextEn"))
                    assertTrue(body.getBoolean("targetVisible"))
                    assertEquals("NOT_AUDIO:$runId", String(Base64.getDecoder().decode(body.getString("audioBase64")), Charsets.UTF_8))
                    requests.add("read:${example.exampleId}")
                    HttpResponse(200, JSONObject().put("status", "READY").put("feedbackZh", "隔离指读反馈")
                        .put("repeatRecommended", false).put("transcript", example.targetResponseEn).put("confidence", 0.9).toString())
                }
                else -> error("Unexpected local fixture request")
            }
        }
    }

    private inner class SyntheticCapture : ReadSpeakAudioCapture {
        var starts = 0
        var cancels = 0
        var active = false
        val files = mutableListOf<File>()
        override fun start(): Boolean { check(!active); active = true; starts++; return true }
        override fun finish(): CapturedReadSpeakAudio {
            check(active); active = false
            return CapturedReadSpeakAudio(File(captureDirectory, "synthetic-$starts.bin").also {
                check(!it.exists()); it.writeBytes("NOT_AUDIO:$runId".toByteArray()); files.add(it)
            })
        }
        override fun cancel() { active = false; cancels++ }
    }

    private fun runtime() = SessionRuntime().apply { update("isolated-not-real-session", account) }
    private fun progress(runtime: SessionRuntime) = AndroidLearningProgressStore(context, runtime, chapterId, sentenceId, "LR-07")
    private suspend fun awaitState(vm: PatternSpeakingViewModel, condition: (PatternSpeakingUiState) -> Boolean): PatternSpeakingUiState =
        withTimeout(5_000) { vm.uiState.first { it.progressFailed || (!it.submitting && condition(it)) } }.also { assertFalse(it.progressFailed) }
    private suspend fun awaitSql(condition: (Map<String, String>) -> Boolean): Map<String, String> = withTimeout(5_000) {
        var saved = savedSqlProgress()
        while (!condition(saved)) { delay(10); saved = savedSqlProgress() }
        saved
    }
    private fun processedIds(saved: Map<String, String>): Set<String> {
        val count = saved.getValue("processedPatternCount").toInt()
        val ids = (0 until count).map { saved.getValue("processedPattern.$it.id") }
        assertEquals(ids.size, ids.toSet().size)
        return ids.toSet()
    }
    private fun savedSqlProgress(): Map<String, String> = database.readableDatabase.rawQuery(
        "select payload from learning_module_progress where account_id=? and chapter_id=? and sentence_id=? and module_id='LR-07'",
        arrayOf(account, chapterId, sentenceId),
    ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        stringMap(cursor.getString(0)).also { assertFalse(cursor.moveToNext()) }
    }
    private fun stringMap(json: String): Map<String, String> = JSONObject(json).let { value ->
        value.keys().asSequence().associateWith { key -> value.get(key).also { require(it is String) } as String }
    }
    private fun exampleJson(example: PatternPracticeExample) = JSONObject().put("exampleId", example.exampleId)
        .put("sceneZh", example.sceneZh).put("aiPromptEn", example.aiPromptEn)
        .put("targetResponseEn", example.targetResponseEn).put("targetResponseZh", example.targetResponseZh)
    private fun legacyProgressJson(): String = JSONObject().apply {
        put("version", "1"); put("patternCount", "2")
        listOf(patternB, patternA).forEachIndexed { index, pattern ->
            put("pattern.$index.id", pattern.patternId); put("pattern.$index.source", pattern.sourceSentenceId)
            put("pattern.$index.structure", pattern.structure); put("pattern.$index.explanation", pattern.explanationZh)
        }
        put("patternIndex", "0"); put("stage", "RESPONDING"); put("exampleCount", "2")
        acceptedB.forEachIndexed { index, example ->
            put("example.$index.id", example.exampleId); put("example.$index.scene", example.sceneZh)
            put("example.$index.prompt", example.aiPromptEn); put("example.$index.en", example.targetResponseEn)
            put("example.$index.zh", example.targetResponseZh); put("example.$index.read", "true")
        }
        put("responseIndex", "1"); put("transcript", "I travel by train."); put("feedback", "已保存的隔离客观反馈")
        put("repeat", "true"); put("responseCompleted", "true"); put("muted", "true"); put("completed", "false")
    }.toString()

    private fun databaseSnapshot(): Map<String, List<List<String?>>> {
        val db = database.readableDatabase
        val tables = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' and name!='android_metadata' order by name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return tables.associateWith { table ->
            check(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.rawQuery("select rowid,* from $table order by rowid", null).use { cursor -> buildList {
                add(cursor.columnNames.toList())
                while (cursor.moveToNext()) add(List(cursor.columnCount) { index -> when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_BLOB -> "blob:" + Base64.getEncoder().encodeToString(cursor.getBlob(index))
                    else -> "${cursor.getType(index)}:${cursor.getString(index)}"
                } })
            } }
        }
    }
    private val mutableTables = setOf("learning_module_progress", "learning_checkpoints")
}
