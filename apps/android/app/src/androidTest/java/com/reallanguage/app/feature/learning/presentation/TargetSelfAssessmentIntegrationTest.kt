package com.reallanguage.app.feature.learning.presentation

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.data.AndroidTargetSelfAssessmentRepository
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Real UI, ViewModel and SQLite; fixed content is isolated test input, not an AI result. */
class TargetSelfAssessmentIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var context: Context
    private lateinit var db: FormationDatabase
    private val runtime = SessionRuntime().apply { update("isolated-token", "account") }
    private fun repository() = AndroidTargetSelfAssessmentRepository(context, runtime, "c", "s", "LR-02")

    @Before fun setUp() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "self-assessment-ui")
        db = FormationDatabase(context)
        db.writableDatabase.apply {
            execSQL("insert into formation_flows values ('account','f','FIXED','DIARY','测试')")
            execSQL("insert into chapters values ('account','f','c',0,'IN_PROGRESS')")
            execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values ('account','c','s',0,'我很平静。','I felt calm.','FOCUS')")
            execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary) values ('account','c','t','s','WORD','calm','平静的','','')")
            execSQL("insert into learning_checkpoints values ('account','c','LR-02','explanation:s')")
        }
    }
    @After fun tearDown() { db.close(); context.deleteDatabase("real-language-learning.db") }

    @Test fun clickReadsBackSavedChoiceAndNewEntryKeepsItWithoutCreatingFormalScores() {
        var generation by mutableIntStateOf(0)
        val reading = ExplanationReadingUiState(FixedLearningChapter("c", 1, listOf(
            FixedLearningSentence("s", "I felt calm.", "我很平静。", true),
        )), "s", "说明当前感受。", expressions = listOf(SelectableExpression("x", "calm", 7, 11)),
            selectedExpression = ExpressionExplanation("x", "在这里描述平静的心情。"))
        compose.setContent { RlTheme { RlPaperBackground {
            key(generation) {
                val store = remember { ViewModelStore() }
                DisposableEffect(store) { onDispose { store.clear() } }
                val vm = remember { ViewModelProvider.create(store, viewModelFactory {
                    initializer { TargetSelfAssessmentViewModel(repository()) }
                })[TargetSelfAssessmentViewModel::class] }
                val assessment by vm.uiState.collectAsState()
                ExplanationReadingScreen(reading, {}, targetAssessment = { display ->
                    TargetSelfAssessmentControls(assessment, vm::onAction, display = display, cardAction = true)
                })
            }
        } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("会了").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("会了").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("暂定已会").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(setOf(SelfAssessmentAbility.MEANING_WRITING), runBlocking { repository().load().single().assessedAbilities })
        compose.runOnIdle { generation++ }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("暂定已会").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("暂定已会").assertIsDisplayed().assertIsNotEnabled()
        listOf("vocabulary_items", "sentence_capabilities", "pattern_items").forEach { table ->
            assertEquals(0, db.readableDatabase.rawQuery("select count(*) from $table", null).use { it.moveToFirst(); it.getInt(0) })
        }
        val output = java.io.File(context.getExternalFilesDir(null), "self-assessment-ui-saved.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
