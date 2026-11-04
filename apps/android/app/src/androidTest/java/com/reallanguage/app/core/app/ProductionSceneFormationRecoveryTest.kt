package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.feature.formation.data.*
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.formation.presentation.*
import com.reallanguage.app.feature.home.domain.ResumeLearningKind
import com.reallanguage.app.feature.home.presentation.IncompleteLearningViewModel
import com.reallanguage.app.feature.learning.presentation.ChapterEntryAction
import com.reallanguage.app.feature.learning.presentation.ChapterEntryEffect
import com.reallanguage.app.feature.learning.presentation.ChapterEntryViewModel
import com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import com.reallanguage.app.feature.safety.presentation.SafetyCheckEffect
import com.reallanguage.app.feature.safety.presentation.SafetyCheckViewModel
import com.reallanguage.app.feature.scene.domain.*
import com.reallanguage.app.feature.scene.presentation.*
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule

/**
 * Real production factories, question/generation/review VMs, HTTP codecs, Android Keystore,
 * encrypted files, recovery journal, final SQLite content fixation, learning entry and home
 * resume. Only the remote responses are
 * deterministic fixtures; this is persistence/contract evidence, not live AI-quality evidence.
 * In particular every answer is produced by SceneQuestionsViewModel with non-null questionZh,
 * as on a user's phone. The historical hand-built two-field SceneAnswer fixtures missed this.
 */
class ProductionSceneFormationRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val runId = UUID.randomUUID().toString()
    private val account = "scene-recovery-$runId"
    private val directory = File(base.cacheDir, "scene-recovery-$runId")
    private val isolated = IsolatedLearningDatabaseContext(base, "scene-recovery-$runId")
    private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
    private val context = object : ContextWrapper(isolated) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(directory, "private")
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val privateName = "scene-recovery-$runId-$name"
            preferences.add(privateName)
            return base.getSharedPreferences(privateName, mode)
        }
    }
    private val store = ViewModelStore()
    private var modelSequence = 0
    private val requests = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private val fixtureFailure = AtomicReference<Throwable?>(null)
    private val token = "isolated-scene-token-$runId"
    private val questions = listOf("你现在要联系谁，想让对方知道什么？", "你现在在哪里，手机已经能用了吗？")
    private val answers = listOf("联系朋友，告诉他我可能迟到。", "我在苏州街，手机刚充上电。")
    private val facts = listOf("手机刚充上电", "可能迟到", "正在联系朋友", "现在在苏州街")
    private val lines = listOf("不好意思，手机刚才没电了。", "我可能得晚点到。", "没事，你现在在哪里？", "我在苏州街。")
    private val speakers = listOf(SceneSpeaker.USER, SceneSpeaker.USER, SceneSpeaker.OTHER, SceneSpeaker.USER)
    private val editedSecondLine = "我可能会晚一点到。"
    private val english = listOf("Sorry, my phone's battery died.", "I might be a little late.", "No problem. Where are you now?", "I'm on Suzhou Street.")
    private val chapterId = "scene-chapter-$runId"
    private val candidates = lines.indices.associate { "turn-$it" to "english-$it" }
    private val targets = listOf(
        wordTarget("battery", "turn-0", "battery", "电池", 0, "The battery is low.", "电池电量不足。"),
        wordTarget("late", "turn-1", "late", "迟到的", 1, "She was late.", "她迟到了。"),
    )
    private var expectedStart: SceneStart? = null
    private var editSecondLine = false
    private data class MountedGraph(val graph: ProductionAppGraph, val route: HomeDiaryRoute)
    private val host = mutableStateOf<MountedGraph?>(null)
    private var mounted = false
    private val baseExplanation = "这句话在向朋友解释手机刚才没电了。battery 在这里表示手机的电池。"
    private val secondRoundHint = "battery died 表示电池已经耗尽电量。开头的 Sorry 是对迟到原因的简短说明。"
    private var fixedLearningChapter: com.reallanguage.app.feature.learning.domain.FixedLearningChapter? = null

    @Test(timeout = 90_000)
    fun publicTemplateAnswersAndFourTurnsSaveAndResumeThroughProductionGraph() = runBlocking {
        runScene(SceneStart.Template(PublicSceneTemplates.single { it.templateId == "planning" }))
    }

    @Test(timeout = 90_000)
    fun recentRecommendationMetadataAndQuestionTextSurviveColdGraphRecovery() = runBlocking {
        runScene(SceneStart.Recommendation(RecentSceneRecommendation(
            recommendationId = "recent-late-$runId",
            shortTopicZh = "告诉朋友自己可能迟到",
            whyRelevantZh = "最近练习过约见朋友时临时改变计划",
            sourceRefs = listOf("recent-source-20260915", "recent-source-20260914"),
        )))
    }

    @Test(timeout = 90_000)
    fun directlyDescribedSceneRetainsReviewedEditsAndAllAnswersAfterColdRecovery() = runBlocking {
        runScene(SceneStart.DirectDescription(
            "我约了朋友见面，但手机没电了。我在苏州街找地方充上电后，想联系朋友让他等我一会儿。",
        ), edit = true)
    }

    private suspend fun runScene(start: SceneStart, edit: Boolean = false) {
        expectedStart = start
        editSecondLine = edit
        val graph = newGraph()
        val questionVm = model<SceneQuestionsViewModel>(graph.sceneQuestionsFactory(
            HomeDiaryRoute.SceneQuestions(start, SceneContentType.DIALOGUE, "scene-operation-$runId"),
        ))
        repeat(questions.size) { index ->
            val state = await(questionVm.uiState) { !it.loading && it.currentQuestion?.questionId == "q$index" }
            assertEquals(questions[index], state.currentQuestion?.questionZh)
            assertNull(state.error)
            withContext(Dispatchers.Main) {
                questionVm.onAction(SceneQuestionsAction.AnswerChanged(answers[index]))
                questionVm.onAction(SceneQuestionsAction.AnswerSubmitted)
            }
        }
        val generation = await(questionVm.effects) { it is SceneQuestionsEffect.OpenGeneration }
            as SceneQuestionsEffect.OpenGeneration
        assertEquals(questions, generation.session.answers.map { it.questionZh })
        assertEquals(answers, generation.session.answers.map { it.answerZh })
        assertEquals(start, generation.session.start)
        val generationVm = model<SceneGenerationViewModel>(graph.sceneGenerationFactory(
            HomeDiaryRoute.SceneGeneration(generation.session, "generation-$runId"),
        ))
        val generated = await(generationVm.effects) { it is SceneGenerationEffect.OpenChineseReview }
            as SceneGenerationEffect.OpenChineseReview
        assertEquals(lines, generated.draft.units.map { it.textZh })
        assertEquals(speakers, generated.draft.units.map { it.speaker })
        val reviewVm = model<SceneChineseReviewViewModel>(graph.sceneReviewFactory(
            HomeDiaryRoute.SceneChineseReview(generated.session, generated.draft),
        ))
        withContext(Dispatchers.Main) {
            if (edit) reviewVm.onAction(SceneChineseReviewAction.UnitChanged("turn-1", editedSecondLine))
            reviewVm.onAction(SceneChineseReviewAction.ConfirmRequested)
        }
        val confirmed = await(reviewVm.effects) { it is SceneChineseReviewEffect.OpenSafetyCheck }
            as SceneChineseReviewEffect.OpenSafetyCheck
        val expectedLines = acceptedLines()
        assertEquals(expectedLines, confirmed.confirmedDraft.units.map { it.textZh })
        assertEquals(expectedText(), confirmed.source.text)
        assertEquals(SourceType.AI_SCENE, confirmed.source.sourceType)
        assertEquals(SourceContentShape.DIALOGUE, confirmed.source.contentShape)

        // The unchanged production safety step remains present; success is not manufactured
        // by skipping it or by pre-populating an accepted source in the persistence layer.
        val safetyVm = model<SafetyCheckViewModel>(graph.safetyCheckFactory(confirmed.source, "safety-$runId"))
        val safe = await(safetyVm.effects) { it is SafetyCheckEffect.OpenFullSentenceSplit }
            as SafetyCheckEffect.OpenFullSentenceSplit
        assertEquals(confirmed.source, safe.source)
        val origin = HomeDiaryRoute.SceneChineseReview(generated.session, confirmed.confirmedDraft)
        val splitRoute = HomeDiaryRoute.SemanticSplit(
            safe.source.text, safe.source.sourceType, safe.source.contentShape, origin, "accepted-scene-$runId",
        )
        val beforeSplit = graph.prepareFormation(splitRoute)
        assertNotNull("A real answered AI scene must survive encrypted write-and-read verification", beforeSplit)
        assertEquals(FormationSourceOrigin.Scene(generated.session, confirmed.confirmedDraft), beforeSplit?.origin)
        val coldGraph = newGraph()
        assertEquals(beforeSplit, coldGraph.readFormation(splitRoute.entryId))
        assertEquals(splitRoute, coldGraph.resolveResumeRoute(splitRoute.entryId, "FORMATION:SEMANTIC_SPLIT", null))

        val splitVm = model<SemanticSplitViewModel>(coldGraph.semanticSplitFactory(confirmed.source, "split-$runId"))
        val split = await(splitVm.effects) { it is SemanticSplitEffect.OpenFocusSelection }
            as SemanticSplitEffect.OpenFocusSelection
        assertEquals(expectedLines, split.units.map { it.textZh })
        val focusRoute = HomeDiaryRoute.FocusSelection(
            confirmed.source.text, split.units, SourceType.AI_SCENE, SourceContentShape.DIALOGUE,
            origin, setOf("turn-0", "turn-1"), splitRoute.entryId,
        )
        val focusSaved = coldGraph.prepareFormation(focusRoute)
        assertNotNull("Advancing to selected focus sentences must retain the complete scene origin", focusSaved)
        assertEquals(listOf("turn-0", "turn-1"), focusSaved?.selectedUnitIds)
        val requestCountBeforeRestore = requests.size
        withContext(Dispatchers.Main) { store.clear() }

        // Fresh session, graph, journal, and encrypted-file reader: no prior VM state is used.
        val restarted = newGraph()
        val restored = restarted.readFormation(splitRoute.entryId)
        assertEquals(focusSaved, restored)
        val savedOrigin = restored?.origin as FormationSourceOrigin.Scene
        assertEquals(start, savedOrigin.session.start)
        assertEquals(questions, savedOrigin.session.answers.map { it.questionZh })
        assertEquals(answers, savedOrigin.session.answers.map { it.answerZh })
        assertEquals(facts, savedOrigin.session.collectedFacts)
        assertEquals(listOf("朋友的姓名未提供"), savedOrigin.session.missingNonBlockingFacts)
        assertEquals(confirmed.confirmedDraft, savedOrigin.draft)
        assertEquals(focusRoute, restarted.resolveResumeRoute(splitRoute.entryId, "FORMATION:FOCUS_SELECTION", null))
        val incompleteVm = model<IncompleteLearningViewModel>(restarted.incompleteLearningFactory())
        val incomplete = await(incompleteVm.uiState) { !it.loading }
        assertFalse(incomplete.loadFailed)
        assertEquals(listOf(splitRoute.entryId), incomplete.entries.map { it.learningId })
        assertEquals(ResumeLearningKind.FORMATION, incomplete.entries.single().kind)
        assertEquals("Resume must not regenerate questions, content or consume another AI request",
            requestCountBeforeRestore, requests.size)
        assertEquals(if (edit) 7 else 6, requests.size)
        continueToFixedLearning(restarted, focusRoute)
        assertNull(fixtureFailure.get())
    }

    private suspend fun continueToFixedLearning(graph: ProductionAppGraph, focus: HomeDiaryRoute.FocusSelection) {
        val selectedUnits = listOf("turn-0", "turn-1")
        val origin = requireNotNull(focus.sourceReturnRoute)
        val chaptersRoute = HomeDiaryRoute.ChapterFormation(focus.text, focus.units, selectedUnits,
            focus.sourceType, focus.contentShape, origin, focus.sourceEntryId)
        assertNotNull(graph.prepareFormation(chaptersRoute))
        val chaptersVm = model<ChapterFormationViewModel>(graph.chapterFormationFactory(focus.units, selectedUnits, "chapters-$runId"))
        val chapters = await(chaptersVm.effects) { it is ChapterFormationEffect.OpenEnglishConfirmation }
            as ChapterFormationEffect.OpenEnglishConfirmation
        val englishRoute = HomeDiaryRoute.EnglishGeneration(focus.text, focus.units, selectedUnits, chapters.chapters,
            focus.sourceType, focus.contentShape, origin, focus.sourceEntryId)
        assertNotNull(graph.prepareFormation(englishRoute))
        val englishVm = model<EnglishGenerationViewModel>(graph.englishGenerationFactory(englishRoute, "english-$runId"))
        val generated = await(englishVm.effects) { it is EnglishGenerationEffect.OpenConfirmation }
            as EnglishGenerationEffect.OpenConfirmation
        assertEquals(4, generated.chapters.single().sentences.size)
        val confirmationRoute = HomeDiaryRoute.EnglishConfirmation(focus.text, focus.units, generated.chapters,
            focus.sourceType, focus.contentShape, origin, focus.sourceEntryId)
        assertNotNull(graph.prepareFormation(confirmationRoute))
        val confirmationVm = model<EnglishConfirmationViewModel>(graph.englishConfirmationFactory(generated.chapters))
        assertFalse(confirmationVm.uiState.value.continueEnabled)
        withContext(Dispatchers.Main) {
            confirmationVm.onAction(EnglishConfirmationAction.CandidateSelected("turn-1", "english-1"))
            confirmationVm.onAction(EnglishConfirmationAction.ContinueRequested)
        }
        val accepted = await(confirmationVm.effects) { it is EnglishConfirmationEffect.OpenLearningTargets }
            as EnglishConfirmationEffect.OpenLearningTargets
        assertEquals(candidates, accepted.selectedCandidateIds)
        val targetsRoute = HomeDiaryRoute.LearningTargets(focus.text, focus.units, accepted.chapters,
            accepted.selectedCandidateIds, focus.sourceType, focus.contentShape, origin, focus.sourceEntryId)
        assertNotNull(graph.prepareFormation(targetsRoute))
        val extractionVm = model<LearningTargetLoadingViewModel>(graph.learningTargetLoadingFactory(targetsRoute, "targets-$runId"))
        val extracted = await(extractionVm.effects) { it is LearningTargetLoadingEffect.OpenSelection }
            as LearningTargetLoadingEffect.OpenSelection
        assertEquals(targets, extracted.targets)
        val selectionRoute = HomeDiaryRoute.LearningTargetSelection(focus.text, focus.units, accepted.chapters,
            accepted.selectedCandidateIds, extracted.targets, extracted.queryableExpressions,
            sourceType = focus.sourceType, contentShape = focus.contentShape, entryId = "target-selection-$runId",
            sourceReturnRoute = origin, sourceEntryId = focus.sourceEntryId)
        assertNotNull(graph.prepareFormation(selectionRoute))
        val selectionVm = model<LearningTargetViewModel>(graph.learningTargetSelectionFactory(selectionRoute))
        assertEquals(setOf("battery", "late"), selectionVm.uiState.value.selectedTargetIds)
        withContext(Dispatchers.Main) {
            selectionVm.onAction(LearningTargetAction.TargetToggled("late"))
            selectionVm.onAction(LearningTargetAction.ContinueRequested)
        }
        assertEquals(1, selectionVm.uiState.value.focusSentenceIndex)
        assertEquals(setOf("battery"), selectionVm.uiState.value.selectedTargetIds)
        val selectedRoute = selectionRoute.copy(initialSelectedTargetIds = setOf("battery"), initialSentenceIndex = 1)
        val selectedSnapshot = graph.prepareFormation(selectedRoute, targetSentenceIndex = 1)
        assertNotNull(selectedSnapshot)
        withContext(Dispatchers.Main) { store.clear() }
        val fresh = newGraph()
        val requestsBeforeRestore = requests.size
        assertEquals(selectedSnapshot, fresh.readFormation(focus.sourceEntryId))
        val restored = fresh.resolveResumeRoute(focus.sourceEntryId, "FORMATION:LEARNING_TARGET_SELECTION", null)
        assertEquals(selectedRoute, restored)
        val resumedSelection = model<LearningTargetViewModel>(fresh.learningTargetSelectionFactory(
            restored as HomeDiaryRoute.LearningTargetSelection))
        assertEquals(1, resumedSelection.uiState.value.focusSentenceIndex)
        assertEquals(setOf("battery"), resumedSelection.uiState.value.selectedTargetIds)
        assertEquals(requestsBeforeRestore, requests.size)
        withContext(Dispatchers.Main) { resumedSelection.onAction(LearningTargetAction.ContinueRequested) }
        val final = await(resumedSelection.effects) { it is LearningTargetEffect.OpenFinalConfirmation }
            as LearningTargetEffect.OpenFinalConfirmation
        assertEquals(setOf("battery"), final.selectedTargetIds)
        val fixRoute = HomeDiaryRoute.FinalFix(focus.text, focus.units, accepted.chapters, accepted.selectedCandidateIds,
            final.targets, final.selectedTargetIds, focus.sourceType, focus.contentShape, "fixed-scene-$runId", origin,
            focus.sourceEntryId)
        val fixSnapshot = fresh.prepareFormation(fixRoute, targetSentenceIndex = 1)
        assertNotNull(fixSnapshot)
        val projection = requireNotNull(fixSnapshot?.toFixedSourceProjection(fixRoute.entryId))
        val fixVm = model<FinalFixViewModel>(fresh.finalFixFactory(fixRoute, projection))
        assertEquals(listOf("battery"), fixVm.uiState.value.selectedTargets.map { it.targetId })
        withContext(Dispatchers.Main) { fixVm.onAction(FinalFixAction.ConfirmRequested) }
        val fixed = await(fixVm.effects) { it is FinalFixEffect.OpenFirstChapter } as FinalFixEffect.OpenFirstChapter
        assertEquals(FixedFormationCleanup.Cleaned, fresh.completeFormation(projection))
        assertNull(fresh.readFormation(focus.sourceEntryId))
        val entryVm = model<ChapterEntryViewModel>(fresh.chapterEntryFactory(fixed.chapterId))
        val entryState = await(entryVm.uiState) { !it.loading }
        assertFalse(entryState.failed)
        assertEquals(english, entryState.chapter?.sentences?.map { it.textEn })
        assertEquals(acceptedLines(), entryState.chapter?.sentences?.map { it.textZh })
        assertEquals(listOf(true, true, false, false), entryState.chapter?.sentences?.map { it.isFocus })
        withContext(Dispatchers.Main) { entryVm.onAction(ChapterEntryAction.StartRequested) }
        val learning = await(entryVm.effects) { it is ChapterEntryEffect.OpenExplanation }
            as ChapterEntryEffect.OpenExplanation
        assertEquals(fixed.chapterId, learning.chapter.chapterId)
        assertEquals(learning.chapter.sentences.first().sentenceId, learning.focusSentenceId)
        fixedLearningChapter = learning.chapter
        withContext(Dispatchers.Main) { store.clear() }

        // A VM effect alone is not navigation: the real HomeProductionFlow must mount
        // the next page so LearningCheckpointGate commits LR-02 before learning can run.
        mountLearning(fresh, HomeDiaryRoute.ChapterLearning(fixed.chapterId))
        clickTag("chapter_entry_start")
        assertLearningPage(learning.focusSentenceId)
        withTimeout(10_000) {
            while (fresh.learningCheckpointRepository.read(fixed.chapterId) !=
                OrdinaryLearningCheckpoint(fixed.chapterId, "LR-02", "explanation:${learning.focusSentenceId}")) delay(20)
        }
        clickTag("explanation_understood")
        val progress = AndroidLearningProgressStore(context, SessionRuntime().apply { update(token, account) },
            fixed.chapterId, learning.focusSentenceId, "LR-02")
        val savedReading = withTimeout(10_000) {
            var value = progress.read()
            while (value?.get("reads") != "1" || value?.get("hint") != secondRoundHint || value?.containsKey("pendingHintRound") == true) {
                delay(20)
                value = progress.read()
            }
            requireNotNull(value)
        }
        compose.onNodeWithText(secondRoundHint).performScrollTo().assertIsDisplayed()
        val postCountBeforeRestart = requests.count { it.method == "POST" }
        compose.runOnIdle { host.value = null }
        compose.waitForIdle()
        val afterFix = newGraph()
        val beforeFinalRestore = requests.size
        val homeVm = model<IncompleteLearningViewModel>(afterFix.incompleteLearningFactory())
        val homeState = await(homeVm.uiState) { !it.loading }
        assertFalse(homeState.loadFailed)
        val homeEntry = homeState.entries.single()
        assertEquals(fixed.chapterId, homeEntry.learningId)
        assertEquals(ResumeLearningKind.ORDINARY, homeEntry.kind)
        assertEquals("LR-02", homeEntry.savedStepId)
        assertEquals("explanation:${learning.focusSentenceId}", homeEntry.checkpointStepId)
        val resumedLearning = afterFix.resolveResumeRoute(homeEntry.learningId, homeEntry.savedStepId, homeEntry.checkpointStepId)
        assertTrue(resumedLearning is HomeDiaryRoute.ChapterExplanation)
        assertEquals(learning.chapter, (resumedLearning as HomeDiaryRoute.ChapterExplanation).chapter)
        assertEquals(learning.focusSentenceId, resumedLearning.focusSentenceId)
        val savedSource = AndroidFixedSourceRepository(context, SessionRuntime().apply { update(token, account) })
            .load(fixRoute.entryId)
        assertEquals(FixedSourceLoad.Loaded(projection), savedSource)
        assertEquals(setOf("battery"), projection.selectedTargetIds)
        assertEquals(FixedSourceOrigin.Scene("scene-operation-$runId", "draft-$runId"), projection.origin)
        withContext(Dispatchers.IO) {
            FormationDatabase(context).use { helper ->
                helper.readableDatabase.rawQuery("select display from target_selections where account_id=?", arrayOf(account)).use {
                    assertTrue(it.moveToFirst()); assertEquals("battery", it.getString(0)); assertFalse(it.moveToNext())
                }
                // Fixation stores selected learning details here. Vocabulary-book items
                // are created only when the chapter is completed and archived.
                helper.readableDatabase.rawQuery(
                    "select target_id,pronunciation_text,examples_json from target_vocabulary_details where account_id=?",
                    arrayOf(account),
                ).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(UUID.nameUUIDFromBytes("${fixRoute.entryId}:target:battery".toByteArray(Charsets.UTF_8)).toString(), it.getString(0))
                    assertEquals("battery", it.getString(1))
                    val examples = JSONArray(it.getString(2))
                    assertEquals(3, examples.length())
                    assertEquals(listOf("The battery is low.", "My battery died.", "I need a new battery."),
                        List(examples.length()) { index -> examples.getJSONObject(index).getString("textEn") })
                    assertFalse("The deselected late target must have no fixed learning details", it.moveToNext())
                }
                helper.readableDatabase.rawQuery("select count(*) from vocabulary_items where account_id=?", arrayOf(account)).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("A partially learned scene must not be prematurely archived in the vocabulary book", 0, it.getInt(0))
                }
                helper.readableDatabase.rawQuery("select count(*) from chapters where account_id=?", arrayOf(account)).use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                }
            }
        }
        assertEquals("Reading the fixed home entry must not request new AI content", beforeFinalRestore, requests.size)
        compose.runOnIdle { host.value = MountedGraph(afterFix, HomeDiaryRoute.Home) }
        clickTag("home-incomplete-card")
        clickTag("incomplete-learning-resume-${fixed.chapterId}")
        assertLearningPage(learning.focusSentenceId)
        compose.onNodeWithText(secondRoundHint).performScrollTo().assertIsDisplayed()
        assertEquals(savedReading, progress.read())
        assertEquals("Cold learning resume must reuse both delivered explanations", postCountBeforeRestart,
            requests.count { it.method == "POST" })
        assertEquals(if (editSecondLine) 16 else 15, requests.size)
    }

    private fun mountLearning(graph: ProductionAppGraph, route: HomeDiaryRoute) {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        host.value = MountedGraph(graph, route)
        compose.setContent { RlTheme { RlPaperBackground {
            host.value?.let { current -> key(current.graph) { HomeProductionFlow(current.graph, initialRoute = current.route) } }
        } } }
        mounted = true
    }

    private fun assertLearningPage(focusId: String) {
        compose.waitUntil(15_000) {
            fixtureFailure.get()?.let { throw AssertionError("Scene fixture failed", it) }
            compose.onAllNodesWithTag("learning-focus-$focusId").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(baseExplanation).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(baseExplanation).performScrollTo().assertIsDisplayed()
    }

    private fun clickTag(tag: String) {
        compose.waitUntil(15_000) {
            fixtureFailure.get()?.let { throw AssertionError("Scene fixture failed", it) }
            compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun wordTarget(id: String, unitId: String, display: String, meaning: String, index: Int, exampleEn: String, exampleZh: String) =
        LearningTarget(id, unitId, display, meaning, LearningTargetType.WORD,
            sourceEvidence = SourceEvidence(1, english[index].indexOf(display), english[index].indexOf(display) + display.length,
                display, display, listOf(display)), candidateExample = CandidateExample(exampleEn, exampleZh),
            relatedVocabularyItems = emptyList(), relatedVocabularyItemRefs = emptyList())

    private fun newGraph(): ProductionAppGraph = ProductionAppGraph(
        context,
        SessionRuntime().apply { update(token, account) },
        HttpTransport { request ->
            requests.add(request)
            try { fixtureResponse(request) }
            catch (failure: Throwable) {
                fixtureFailure.compareAndSet(null, failure)
                HttpResponse(500, "{\"error\":\"isolated-scene-fixture\"}")
            }
        },
        object : SessionCredentialStore {
            override suspend fun load(): StoredSession? = error("No real credential reads")
            override suspend fun save(session: StoredSession): Unit = error("No real credential writes")
            override suspend fun clear(): Unit = error("No real credential changes")
        },
    ).also { it.closeReminderForThisSession(account) }

    private fun fixtureResponse(request: HttpRequest): HttpResponse {
        assertEquals("Bearer $token", request.headers["Authorization"])
        if (request.method == "GET") {
            return when (request.path) {
                "/api/v1/settings/current" -> HttpResponse(200, JSONObject().put("basicVocabulary", "CET_4")
                    .put("purposeVocabularies", JSONArray()).toString())
                "/api/v1/settings/current/revision" -> {
                    assertNotNull(fixedLearningChapter)
                    val settings = JSONObject().put("basicVocabulary", "CET_4").put("purposeVocabularies", JSONArray())
                        .put("formalityLevel", 3).put("slangEnabled", false).put("styleReference", "")
                        .put("voiceEnabled", false).put("autoPlayEnabled", false).put("speechSpeedLevel", 3)
                    HttpResponse(200, JSONObject().put("rowVersion", 1).put("settings", settings).toString())
                }
                else -> error("Unexpected GET ${request.path}")
            }
        }
        assertEquals("POST", request.method)
        val body = JSONObject(requireNotNull(request.body))
        assertTrue(body.getString("commandId").isNotBlank())
        val result = when (request.path) {
            "/api/v1/sources/scenes/next-question" -> {
                assertStart(body.getJSONObject("start"))
                assertEquals("DIALOGUE", body.getString("contentType"))
                val confirmed = body.getJSONArray("confirmedAnswers")
                repeat(confirmed.length()) { i ->
                    assertEquals("q$i", confirmed.getJSONObject(i).getString("questionId"))
                    assertEquals(questions[i], confirmed.getJSONObject(i).getString("questionZh"))
                    assertEquals(answers[i], confirmed.getJSONObject(i).getString("answerZh"))
                }
                val index = body.getInt("stepIndex")
                assertEquals(confirmed.length(), index)
                assertEquals(10 - index, body.getInt("remainingQuestions"))
                if (index < questions.size) JSONObject().put("action", "ASK").put("questionId", "q$index")
                    .put("questionZh", questions[index]).put("options", JSONArray())
                else JSONObject().put("action", "GENERATE").put("collectedFacts", JSONArray(facts))
                    .put("missingNonBlockingFacts", JSONArray(listOf("朋友的姓名未提供")))
            }
            "/api/v1/sources/scenes/drafts" -> {
                assertEquals("SCENE_DIALOGUE_TURN_V1", body.getString("task"))
                assertEquals(6, body.getInt("remainingContentBudget"))
                val session = body.getJSONObject("session")
                assertStart(session.getJSONObject("start"))
                repeat(questions.size) { i ->
                    assertEquals(questions[i], session.getJSONArray("answers").getJSONObject(i).getString("questionZh"))
                }
                JSONObject().put("draftId", "draft-$runId").put("contentType", "DIALOGUE")
                    .put("units", JSONArray(lines.mapIndexed { i, text ->
                        JSONObject().put("unitId", "turn-$i").put("speaker", speakers[i].name)
                            .put("textZh", text).put("factReferences", JSONArray(listOf("fact-$i")))
                    }))
            }
            "/api/v1/sources/scenes/edit-reviews" -> {
                assertTrue(editSecondLine)
                assertEquals(editedSecondLine, body.getJSONArray("editedUnits").getJSONObject(1).getString("textZh"))
                JSONObject().put("decision", "PASS").put("issues", JSONArray())
            }
            "/api/v1/safety/checks" -> {
                assertEquals(expectedText(), body.getString("text"))
                assertEquals("AI_SCENE", body.getString("sourceType"))
                assertEquals("DIALOGUE", body.getString("contentShape"))
                JSONObject().put("status", "PASSED")
            }
            "/api/v1/content-formation/semantic-units/split" -> {
                assertEquals(expectedText(), body.getString("text"))
                assertEquals("DIALOGUE", body.getString("contentShape"))
                JSONObject().put("units", JSONArray(acceptedLines().mapIndexed { i, text ->
                    JSONObject().put("unitId", "turn-$i").put("order", i).put("textZh", text)
                        .put("recommendationReasonZh", JSONObject.NULL)
                })).put("coverage", JSONObject().put("complete", true)
                    .put("missingSpans", JSONArray()).put("duplicatedSpans", JSONArray()))
            }
            "/api/v1/content-formation/chapters/context-selection" -> {
                assertEquals(listOf("turn-0", "turn-1"), body.getJSONArray("selectedUnitIds").strings())
                assertEquals(acceptedLines(), body.getJSONArray("units").let { units ->
                    List(units.length()) { units.getJSONObject(it).getString("textZh") }
                })
                JSONObject().put("chapters", JSONArray().put(JSONObject().put("chapterId", chapterId)
                    .put("unitIds", JSONArray(lines.indices.map { "turn-$it" }))))
            }
            "/api/v1/content-formation/english-candidates" -> {
                assertEquals("AI_SCENE", body.getString("sourceType"))
                assertEquals(listOf("turn-0", "turn-1"), body.getJSONArray("selectedUnitIds").strings())
                assertEquals(0, body.getJSONArray("knownKnowledge").length())
                englishResponse()
            }
            "/api/v1/content-formation/learning-targets" -> {
                assertEquals(1, body.getJSONArray("chapters").length())
                val sentences = body.getJSONArray("chapters").getJSONObject(0).getJSONArray("sentences")
                repeat(4) { index ->
                    val value = sentences.getJSONObject(index)
                    assertEquals(english[index], value.getString("textEn"))
                    assertEquals(acceptedLines()[index], value.getString("textZh"))
                    assertEquals(index < 2, value.getBoolean("isFocus"))
                }
                JSONObject().put("targets", JSONArray(targets.map(::targetJson)))
                    .put("queryableExpressions", JSONArray()).put("learningScopes", JSONArray())
            }
            "/api/v1/knowledge/examples" -> {
                assertEquals("Only the selected target may produce examples or learning records", "battery", body.getString("itemId"))
                assertEquals("VOCABULARY", body.getString("targetType"))
                assertEquals("battery", body.getString("display"))
                assertEquals("电池", body.getString("meaningZh"))
                val source = body.getJSONArray("sourceScene")
                assertEquals(4, source.length())
                repeat(4) { index ->
                    assertEquals(english[index], source.getJSONObject(index).getString("textEn"))
                    assertEquals(acceptedLines()[index], source.getJSONObject(index).getString("textZh"))
                }
                JSONObject().put("itemId", "battery").put("phonetic", JSONObject.NULL)
                    .put("pronunciationText", "battery").put("representativeScene", english.first())
                    .put("naturalRevalidation", true).put("examples", JSONArray(listOf(
                        "The battery is low." to "电池电量不足。",
                        "My battery died." to "我的电池没电了。",
                        "I need a new battery." to "我需要一块新电池。",
                    ).map { (en, zh) -> JSONObject().put("textEn", en).put("textZh", zh).put("pronunciationText", en) }))
            }
            "/api/v1/learning/explanations" -> {
                val chapter = requireNotNull(fixedLearningChapter)
                assertEquals(chapter.chapterId, body.getJSONObject("chapter").getString("chapterId"))
                assertEquals(chapter.sentences.first().sentenceId, body.getString("focusSentenceId"))
                val round = body.getInt("round")
                assertTrue(round in 1..2)
                val locked = body.getJSONArray("lockedTargets")
                assertEquals("Only the selected battery target may reach learning", 1, locked.length())
                assertEquals("battery", locked.getJSONObject(0).getString("display"))
                assertEquals("电池", locked.getJSONObject(0).getString("explanationZh"))
                assertEquals("WORD", locked.getJSONObject(0).getString("type"))
                assertFalse(body.has("requestedExpression"))
                if (round == 1) assertFalse(body.has("existingBaseExplanationZh"))
                else assertEquals(baseExplanation, body.getString("existingBaseExplanationZh"))
                assertEquals("A cached reading round must not be requested again", 1,
                    requests.count { it.path == request.path && JSONObject(requireNotNull(it.body)).getInt("round") == round })
                JSONObject().put("kind", "SENTENCE").put("explanationZh", if (round == 1) baseExplanation else secondRoundHint)
            }
            else -> error("Unexpected request ${request.method} ${request.path}")
        }
        return HttpResponse(200, result.toString())
    }

    private fun englishResponse() = JSONObject().put("chapters", JSONArray().put(JSONObject()
        .put("chapterId", chapterId).put("sentences", JSONArray(acceptedLines().mapIndexed { i, text ->
            val choices = JSONArray()
            if (i == 1) choices.put(JSONObject().put("candidateId", "unchosen-english-1")
                .put("english", "I may arrive a little late.").put("reusedKnowledgeRefs", JSONArray()))
            choices.put(JSONObject().put("candidateId", "english-$i").put("english", english[i])
                .put("reusedKnowledgeRefs", JSONArray()))
            JSONObject().put("sourceUnitId", "turn-$i").put("textZh", text).put("isFocus", i < 2)
                .put("candidates", choices)
        }))))

    private fun targetJson(target: LearningTarget) = JSONObject().put("targetId", target.targetId)
        .put("unitId", target.unitId).put("type", target.type.name).put("display", target.display)
        .put("explanationZh", target.explanationZh).put("sourceEvidence", requireNotNull(target.sourceEvidence).toJson())
        .put("structureParts", JSONArray()).put("relatedVocabulary", JSONArray()).put("relatedVocabularyItems", JSONArray())
        .put("relatedVocabularyItemRefs", JSONArray()).put("candidateExample", JSONObject()
            .put("textEn", requireNotNull(target.candidateExample).textEn).put("textZh", requireNotNull(target.candidateExample).textZh))

    private fun JSONArray.strings() = List(length()) { getString(it) }

    private fun assertStart(value: JSONObject) {
        when (val start = requireNotNull(expectedStart)) {
            is SceneStart.Template -> {
                assertEquals("TEMPLATE", value.getString("kind"))
                assertEquals(start.template.templateId, value.getString("templateId"))
                assertEquals(start.template.titleZh, value.getString("titleZh"))
                assertEquals(start.template.scopeZh, value.getString("scopeZh"))
            }
            is SceneStart.Recommendation -> {
                assertEquals("RECOMMENDATION", value.getString("kind"))
                assertEquals(start.recommendation.recommendationId, value.getString("recommendationId"))
                assertEquals(start.recommendation.shortTopicZh, value.getString("shortTopicZh"))
                assertEquals(start.recommendation.whyRelevantZh, value.getString("whyRelevantZh"))
                assertEquals(start.recommendation.sourceRefs, value.getJSONArray("sourceRefs").let { array ->
                    List(array.length()) { array.getString(it) }
                })
            }
            is SceneStart.DirectDescription -> {
                assertEquals("DIRECT_DESCRIPTION", value.getString("kind"))
                assertEquals(start.description, value.getString("description"))
            }
        }
    }

    private fun acceptedLines() = lines.mapIndexed { i, text -> if (editSecondLine && i == 1) editedSecondLine else text }
    private fun expectedText() = acceptedLines().mapIndexed { i, text ->
        "${if (speakers[i] == SceneSpeaker.USER) "用户" else "对方"}：$text"
    }.joinToString("\n")

    private suspend inline fun <reified T : ViewModel> model(factory: ViewModelProvider.Factory): T =
        withContext(Dispatchers.Main) {
            factory.create(T::class.java).also { store.put("scene-vm-${modelSequence++}", it) }
        }

    private suspend fun <T> await(flow: Flow<T>, predicate: (T) -> Boolean): T = try {
        withTimeout(7_000) { flow.first(predicate) }
    } catch (failure: Throwable) {
        fixtureFailure.get()?.let { throw AssertionError("Scene HTTP fixture contract failed", it) }
        throw failure
    }

    @After fun removeOnlyThisTestOwnedScene() = runBlocking {
        if (mounted) {
            compose.runOnIdle { host.value = null }
            compose.waitForIdle()
        }
        withContext(Dispatchers.Main) { store.clear() }
        withContext(Dispatchers.IO) {
            val hash = MessageDigest.getInstance("SHA-256").digest(account.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            AndroidFormationPayloadKeys().deleteAccount(hash)
            AndroidFixedSourcePayloadKeys().deleteAccount(hash)
            val database = context.getDatabasePath("real-language-learning.db").canonicalFile
            check(database.parentFile == base.cacheDir.canonicalFile)
            check(database.name.startsWith("scene-recovery-$runId-"))
            context.deleteDatabase("real-language-learning.db")
            synchronized(preferences) { preferences.toList() }.forEach { name ->
                check(name.startsWith("scene-recovery-$runId-"))
                base.deleteSharedPreferences(name)
            }
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            check(directory.name == "scene-recovery-$runId")
            check(!directory.exists() || directory.deleteRecursively())
        }
    }
}
