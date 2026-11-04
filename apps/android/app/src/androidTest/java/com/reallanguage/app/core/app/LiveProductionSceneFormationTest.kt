package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.core.session.UrlConnectionSessionTransport
import com.reallanguage.app.feature.formation.data.AndroidFixedChapterRepository
import com.reallanguage.app.feature.formation.data.AndroidFixedSourceRepository
import com.reallanguage.app.feature.formation.data.AndroidFormationPayloadKeys
import com.reallanguage.app.feature.formation.data.AndroidFixedSourcePayloadKeys
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.formation.presentation.*
import com.reallanguage.app.feature.learning.domain.FixedChapterLoadResult
import com.reallanguage.app.feature.learning.presentation.ChapterEntryAction
import com.reallanguage.app.feature.learning.presentation.ChapterEntryEffect
import com.reallanguage.app.feature.learning.presentation.ChapterEntryViewModel
import com.reallanguage.app.feature.safety.presentation.SafetyCheckEffect
import com.reallanguage.app.feature.safety.presentation.SafetyCheckViewModel
import com.reallanguage.app.feature.scene.domain.*
import com.reallanguage.app.feature.scene.presentation.*
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in real-provider journey, using the isolated server account supplied by
 * tooling/check-live-formation.mts --serve-android. No user's credentials or database are read.
 * Unlike an HTTP fixture test, every generation and safety response comes from that real API.
 */
class LiveProductionSceneFormationTest {
    @Test(timeout = 570_000)
    fun realSceneReachesEncryptedRecoveryAtomicFixAndColdLearningEntry() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSceneFormation") == "true")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val credentials = File(base.filesDir, "live-formation-session.json")
        val supplied = JSONObject(credentials.readText())
        check(credentials.delete()) { "Could not remove isolated session handoff" }
        val account = supplied.getString("accountId")
        val token = supplied.getString("accessToken")
        val runId = UUID.randomUUID().toString()
        val directory = File(base.cacheDir, "live-scene-$runId")
        val isolated = IsolatedLearningDatabaseContext(base, "live-scene-$runId")
        val preferences = Collections.synchronizedSet(mutableSetOf<String>())
        val context = object : ContextWrapper(isolated) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = File(directory, "private")
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val privateName = "live-scene-$runId-$name"
                preferences.add(privateName)
                return base.getSharedPreferences(privateName, mode)
            }
        }
        val store = ViewModelStore()
        val sequence = AtomicInteger()
        val requestCount = AtomicInteger()
        val requests = Collections.synchronizedList(mutableListOf<JSONObject>())
        val saves = mutableListOf<String>()
        var stage = "initializing"
        var lastStatus = "none"
        var success = false
        val summary = JSONObject().put("realProvider", true).put("fixtureResponses", false)
        val resultFile = File(base.filesDir, "live-scene-formation-result.json")
        val delegate = UrlConnectionSessionTransport("http://10.0.2.2:3001", true)
        val transport = HttpTransport { request ->
            requestCount.incrementAndGet()
            try {
                val response = delegate.execute(request)
                lastStatus = "${request.method} ${request.path}: ${response.statusCode}"
                requests.add(JSONObject().put("path", request.path).put("status", response.statusCode))
                response
            } catch (error: Exception) {
                lastStatus = "${request.method} ${request.path}: ${error.javaClass.simpleName}"
                throw error
            }
        }
        fun session() = SessionRuntime().apply { update(token, account) }
        fun graph() = ProductionAppGraph(context, session(), transport, object : SessionCredentialStore {
            override suspend fun load(): StoredSession? = error("Unexpected credential read")
            override suspend fun save(session: StoredSession): Unit = error("Unexpected credential write")
            override suspend fun clear(): Unit = error("Unexpected credential change")
        })
        suspend fun saveAndColdRead(route: HomeDiaryRoute): FormationRecoverySnapshot {
            val graph = graph()
            val saved = graph.prepareFormation(route)
            assertNotNull("Encrypted persistence failed at $stage", saved)
            val before = requestCount.get()
            val restored = graph().readFormation(requireNotNull(saved).sourceEntryId)
            assertEquals("Cold encrypted read changed accepted content at $stage", saved, restored)
            assertEquals("Recovery must not send another provider request", before, requestCount.get())
            val origin = saved.origin as FormationSourceOrigin.Scene
            assertTrue("Question text was dropped from scene persistence", origin.session.answers.all { !it.questionZh.isNullOrBlank() })
            saves.add(saved.phase.name)
            return saved
        }
        suspend fun <T : ViewModel> model(type: Class<T>, factory: ViewModelProvider.Factory): T =
            withContext(Dispatchers.Main) { ViewModelProvider(store, factory)["live-${sequence.incrementAndGet()}", type] }
        try {
            withTimeout(550_000) {
                // These are explicit synthetic answers, not invented production facts. Starting
                // from an answered session bounds paid requests and exercises the formerly lost
                // questionZh field on the exact production generation + save path.
                val sceneSession = SceneQuestionSession(
                    start = SceneStart.DirectDescription("我和朋友约好见面。刚才手机没电了，现在我在苏州街找到地方充上电，正在微信联系朋友，想告诉他我可能晚到，请他等我一会儿。"),
                    contentType = SceneContentType.DIALOGUE,
                    answers = listOf(
                        SceneAnswer("q1", "我正在用微信联系等我见面的朋友，想说明自己会迟到并请他等等。", "你现在在和谁沟通，希望对方做什么？"),
                        SceneAnswer("q2", "手机刚充上电能开机了，我在苏州街。", "手机现在能用了吗，你现在在哪里？"),
                        SceneAnswer("q3", "大约晚十分钟。朋友会问我在哪里，我告诉他我在苏州街。", "你想告诉对方多久能到，对方可能会确认什么？"),
                    ),
                    collectedFacts = listOf("微信联系朋友", "手机没电后刚充上电并开机", "现在在苏州街", "约见朋友会晚十分钟", "想让朋友等一会儿"),
                    missingNonBlockingFacts = listOf("朋友姓名未提供"),
                    operationId = UUID.randomUUID().toString(),
                )
                val graph = graph()
                stage = "scene_generation"
                val generationVm = model(SceneGenerationViewModel::class.java, graph.sceneGenerationFactory(HomeDiaryRoute.SceneGeneration(sceneSession)))
                val generated = event(generationVm.effects, generationVm.uiState, { it.error != null }, { "$stage; $lastStatus" }) as SceneGenerationEffect.OpenChineseReview
                assertTrue("Real scene must contain at least three short turns", generated.draft.units.size >= 3)
                assertTrue("Real scene must contain both speakers", generated.draft.units.map { it.speaker }.toSet().containsAll(listOf(SceneSpeaker.USER, SceneSpeaker.OTHER)))
                assertTrue("Real scene must retain short turns", generated.draft.units.all { it.textZh.length <= 48 })
                summary.put("generatedTurns", generated.draft.units.size)
                stage = "chinese_confirmation"
                val reviewVm = model(SceneChineseReviewViewModel::class.java, graph.sceneReviewFactory(HomeDiaryRoute.SceneChineseReview(sceneSession, generated.draft)))
                withContext(Dispatchers.Main) { reviewVm.onAction(SceneChineseReviewAction.ConfirmRequested) }
                val confirmed = event(reviewVm.effects, reviewVm.uiState, { it.error != null }, { "$stage; $lastStatus" }) as SceneChineseReviewEffect.OpenSafetyCheck
                val origin = HomeDiaryRoute.SceneChineseReview(sceneSession, confirmed.confirmedDraft)
                val source = confirmed.source
                val sourceEntryId = UUID.randomUUID().toString()
                stage = "safety"
                val safetyVm = model(SafetyCheckViewModel::class.java, graph.safetyCheckFactory(source, UUID.randomUUID().toString()))
                val safe = event(safetyVm.effects, safetyVm.uiState, { it.failed }, { "$stage; $lastStatus" })
                assertTrue("Safety did not accept neutral synthetic scene; $lastStatus", safe is SafetyCheckEffect.OpenFullSentenceSplit)
                stage = "semantic_split_save"
                saveAndColdRead(HomeDiaryRoute.SemanticSplit(source.text, source.sourceType, source.contentShape, origin, sourceEntryId))
                stage = "semantic_split"
                val splitVm = model(SemanticSplitViewModel::class.java, graph.semanticSplitFactory(source, UUID.randomUUID().toString()))
                val split = event(splitVm.effects, splitVm.uiState, { it.failed }, { "$stage; $lastStatus" }) as SemanticSplitEffect.OpenFocusSelection
                val units = split.units
                assertTrue("One paragraph was returned instead of short scene units", units.size >= 3)
                stage = "focus_selection"
                saveAndColdRead(HomeDiaryRoute.FocusSelection(source.text, units, source.sourceType, source.contentShape, origin, sourceEntryId = sourceEntryId))
                val focusVm = model(FocusSelectionViewModel::class.java, graph.focusSelectionFactory(units))
                withContext(Dispatchers.Main) {
                    units.take(2).forEach { focusVm.onAction(FocusSelectionAction.UnitToggled(it.unitId)) }
                    focusVm.onAction(FocusSelectionAction.ConfirmRequested)
                }
                val chosen = (withTimeout(5_000) { focusVm.effects.first() } as FocusSelectionEffect.OpenChapterFormation).selectedUnitIds
                assertEquals(units.take(2).map { it.unitId }, chosen)
                stage = "chapter_formation_save"
                saveAndColdRead(HomeDiaryRoute.ChapterFormation(source.text, units, chosen, source.sourceType, source.contentShape, origin, sourceEntryId))
                stage = "chapter_formation"
                val chapterVm = model(ChapterFormationViewModel::class.java, graph.chapterFormationFactory(units, chosen, UUID.randomUUID().toString()))
                val chapters = (event(chapterVm.effects, chapterVm.uiState, { it.failed }, { "$stage; $lastStatus" }) as ChapterFormationEffect.OpenEnglishConfirmation).chapters
                val englishRoute = HomeDiaryRoute.EnglishGeneration(source.text, units, chosen, chapters, source.sourceType, source.contentShape, origin, sourceEntryId)
                stage = "english_generation_save"
                saveAndColdRead(englishRoute)
                stage = "english_generation"
                val englishVm = model(EnglishGenerationViewModel::class.java, graph.englishGenerationFactory(englishRoute, UUID.randomUUID().toString()))
                val english = (event(englishVm.effects, englishVm.uiState, { it.failed }, { "$stage; $lastStatus" }) as EnglishGenerationEffect.OpenConfirmation).chapters
                stage = "english_confirmation"
                saveAndColdRead(HomeDiaryRoute.EnglishConfirmation(source.text, units, english, source.sourceType, source.contentShape, origin, sourceEntryId))
                val confirmationVm = model(EnglishConfirmationViewModel::class.java, graph.englishConfirmationFactory(english))
                withContext(Dispatchers.Main) {
                    english.flatMap { it.sentences }.forEach { sentence -> confirmationVm.onAction(EnglishConfirmationAction.CandidateSelected(sentence.unitId, sentence.candidates.first().candidateId)) }
                    confirmationVm.onAction(EnglishConfirmationAction.ContinueRequested)
                }
                val selections = (withTimeout(5_000) { confirmationVm.effects.first() } as EnglishConfirmationEffect.OpenLearningTargets).selectedCandidateIds
                val targetsRoute = HomeDiaryRoute.LearningTargets(source.text, units, english, selections, source.sourceType, source.contentShape, origin, sourceEntryId)
                stage = "learning_target_loading_save"
                saveAndColdRead(targetsRoute)
                stage = "learning_target_extraction"
                val targetVm = model(LearningTargetLoadingViewModel::class.java, graph.learningTargetLoadingFactory(targetsRoute, UUID.randomUUID().toString()))
                val targets = event(targetVm.effects, targetVm.uiState, { it.failed }, { "$stage; $lastStatus" }) as LearningTargetLoadingEffect.OpenSelection
                assertTrue("Real extraction produced no optional learning targets", targets.targets.isNotEmpty())
                val targetEntry = UUID.randomUUID().toString()
                val selectedTargets = targets.targets.take(1).map { it.targetId }.toSet()
                val targetSelection = HomeDiaryRoute.LearningTargetSelection(source.text, units, english, selections, targets.targets, targets.queryableExpressions,
                    initialSelectedTargetIds = selectedTargets, sourceType = source.sourceType, contentShape = source.contentShape,
                    entryId = targetEntry, sourceReturnRoute = origin, sourceEntryId = sourceEntryId)
                stage = "learning_target_selection"
                saveAndColdRead(targetSelection)
                val selectionVm = model(LearningTargetViewModel::class.java, graph.learningTargetSelectionFactory(targetSelection))
                repeat(chosen.size) {
                    saveAndColdRead(targetSelection.copy(initialSentenceIndex = selectionVm.uiState.value.focusSentenceIndex))
                    withContext(Dispatchers.Main) { selectionVm.onAction(LearningTargetAction.ContinueRequested) }
                }
                val finalSelection = withTimeout(5_000) { selectionVm.effects.first() } as LearningTargetEffect.OpenFinalConfirmation
                assertEquals(selectedTargets, finalSelection.selectedTargetIds)
                val finalRoute = HomeDiaryRoute.FinalFix(source.text, units, english, selections, targets.targets, selectedTargets,
                    source.sourceType, source.contentShape, UUID.randomUUID().toString(), origin, sourceEntryId)
                stage = "final_fix_save"
                val finalSnapshot = saveAndColdRead(finalRoute)
                assertEquals(chosen.lastIndex, finalSnapshot.targetSentenceIndex)
                val projection = requireNotNull(finalSnapshot.toFixedSourceProjection(finalRoute.entryId))
                stage = "final_fix_sqlite"
                val fixVm = model(FinalFixViewModel::class.java, graph.finalFixFactory(finalRoute, projection))
                withContext(Dispatchers.Main) { fixVm.onAction(FinalFixAction.ConfirmRequested) }
                val fixed = event(fixVm.effects, fixVm.uiState, { it.fixFailed }, { "$stage; $lastStatus" }) as FinalFixEffect.OpenFirstChapter
                val beforeCold = requestCount.get()
                stage = "cold_fixed_source_and_chapter_read"
                val coldRuntime = session()
                assertEquals(FixedSourceLoad.Loaded(projection), AndroidFixedSourceRepository(context, coldRuntime).load(finalRoute.entryId))
                val loaded = AndroidFixedChapterRepository(context, coldRuntime).load(fixed.chapterId)
                assertTrue("New reader cannot load the committed chapter", loaded is FixedChapterLoadResult.Loaded)
                val chapter = (loaded as FixedChapterLoadResult.Loaded).chapter
                val expected = english.single { it.chapterId == chapters.first().chapterId }
                assertEquals(expected.sentences.map { it.textZh }, chapter.sentences.map { it.textZh })
                assertEquals(expected.sentences.map { sentence -> sentence.candidates.single { it.candidateId == selections[sentence.unitId] }.textEn }, chapter.sentences.map { it.textEn })
                assertEquals(expected.sentences.map { it.isFocus }, chapter.sentences.map { it.isFocus })
                withContext(Dispatchers.Main) { store.clear() }
                val coldGraph = graph()
                stage = "cold_learning_entry"
                val entryVm = model(ChapterEntryViewModel::class.java, coldGraph.chapterEntryFactory(fixed.chapterId))
                val entry = withTimeout(10_000) { entryVm.uiState.first { !it.loading } }
                assertFalse("New graph cannot enter committed learning chapter", entry.failed)
                assertEquals(chapter, entry.chapter)
                withContext(Dispatchers.Main) { entryVm.onAction(ChapterEntryAction.StartRequested) }
                val start = withTimeout(5_000) { entryVm.effects.first() } as ChapterEntryEffect.OpenExplanation
                assertEquals(chapter.sentences.first { it.isFocus }.sentenceId, start.focusSentenceId)
                val resumed = coldGraph.resolveResumeRoute(fixed.chapterId, "LR-01", "start")
                assertTrue("Saved chapter did not resolve to the first real learning step", resumed is HomeDiaryRoute.ChapterExplanation)
                assertEquals(start.focusSentenceId, (resumed as HomeDiaryRoute.ChapterExplanation).focusSentenceId)
                assertEquals("Cold resume must not regenerate accepted data", beforeCold, requestCount.get())
                // Production cleanup happens only after SQLite has committed its fixed source.
                coldGraph.completeFormation(projection)
                assertNull(coldGraph.readFormation(sourceEntryId))
                assertEquals(FixedSourceLoad.Loaded(projection), AndroidFixedSourceRepository(context, session()).load(finalRoute.entryId))
                summary.put("questionTextRoundTrip", true).put("coldResumeWithoutProviderRequests", true)
                    .put("sqliteFixed", true).put("learningEntryOpened", true).put("focusUnits", chosen.size)
                    .put("availableTargets", targets.targets.size).put("selectedTargets", selectedTargets.size)
                stage = "complete"
                success = true
            }
        } catch (failure: Throwable) {
            // Record only phases/status/counts, never source text, token, credentials or raw responses.
            summary.put("failureType", failure.javaClass.simpleName)
            throw AssertionError("Real scene failed at $stage; $lastStatus; ${failure.javaClass.simpleName}", failure)
        } finally {
            summary.put("success", success).put("stage", stage).put("lastHttpStatus", lastStatus)
                .put("requests", JSONArray(requests.toList())).put("savedAndColdReadPhases", JSONArray(saves))
            resultFile.writeText(summary.toString())
            withContext(NonCancellable + Dispatchers.Main) { store.clear() }
            withContext(NonCancellable + Dispatchers.IO) {
                val hash = MessageDigest.getInstance("SHA-256").digest(account.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                AndroidFormationPayloadKeys().deleteAccount(hash)
                AndroidFixedSourcePayloadKeys().deleteAccount(hash)
                val database = context.getDatabasePath("real-language-learning.db").canonicalFile
                check(database.parentFile == base.cacheDir.canonicalFile)
                check(database.name.startsWith("live-scene-$runId-"))
                context.deleteDatabase("real-language-learning.db")
                synchronized(preferences) { preferences.toList() }.forEach { name ->
                    check(name.startsWith("live-scene-$runId-"))
                    base.deleteSharedPreferences(name)
                }
                check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
                check(directory.name == "live-scene-$runId")
                check(!directory.exists() || directory.deleteRecursively())
            }
        }
    }

    private suspend fun <T, S> event(effects: Flow<T>, states: StateFlow<S>, failed: (S) -> Boolean, diagnostic: () -> String): T =
        withTimeout(150_000) {
            coroutineScope {
                val success = async { effects.first() }
                val failure = async { states.first(failed); throw AssertionError("Production operation failed: ${diagnostic()}") }
                try { select { success.onAwait { it }; failure.onAwait { throw AssertionError("Unexpected failure branch") } } }
                finally { success.cancel(); failure.cancel() }
            }
        }
}
