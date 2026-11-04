package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.SelfAssessmentAbility
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class TargetSelfAssessmentRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: FormationDatabase
    private val runtime = SessionRuntime().apply { update("isolated-token", "account") }
    private fun repository() = AndroidTargetSelfAssessmentRepository(context, runtime, "c", "s", "LR-02")

    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "self-assessment")
        db = FormationDatabase(context)
        db.writableDatabase.apply {
            execSQL("insert into formation_flows values ('account','f','FIXED','DIARY','测试')")
            execSQL("insert into chapters values ('account','f','c',0,'IN_PROGRESS')")
            execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values ('account','c','s',0,'我计划去。','I plan to go.','FOCUS')")
            execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values ('account','c','other',1,'其他句。','Another sentence.','FOCUS')")
            execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary) values ('account','c','word','s','WORD','plan','计划','','')")
            execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary) values ('account','c','pattern','s','PATTERN','plan to','计划做','','')")
            execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary) values ('account','c','other-word','other','WORD','another','另一个','','')")
            execSQL("insert into learning_checkpoints values ('account','c','LR-02','explanation:s')")
        }
    }
    @After fun tearDown() { db.close(); context.deleteDatabase("real-language-learning.db") }

    @Test fun listsOnlySelectedTargetsFromThisSentenceWithTheirOwnAbilities() = runBlocking {
        val targets = repository().load()
        assertEquals(setOf("word", "pattern"), targets.map { it.targetId }.toSet())
        assertEquals(setOf(SelfAssessmentAbility.MEANING_WRITING), targets.first { it.targetId == "word" }.abilities)
        assertEquals(setOf(SelfAssessmentAbility.PATTERN_WRITING, SelfAssessmentAbility.PATTERN_SPEAKING), targets.first { it.targetId == "pattern" }.abilities)
        assertTrue(targets.all { it.assessedAbilities.isEmpty() })
    }

    @Test fun completedChapterAllowsOnlyFixedExpressionReadingNotNewSelfAssessment() = runBlocking {
        db.writableDatabase.execSQL("update chapters set status='COMPLETED'")
        assertEquals(listOf("plan"), repository().selectedExpressionDisplays())
        assertFalse(repository().mark("word", SelfAssessmentAbility.MEANING_WRITING))
        assertTrue(runCatching { repository().load() }.isFailure)
        val old = repository()
        runtime.update("other-token", "other")
        assertTrue(runCatching { old.selectedExpressionDisplays() }.isFailure)
    }

    @Test fun patternModuleCanAssessCurrentPatternsSourceWithoutRewritingItsEntryCheckpoint() = runBlocking {
        db.writableDatabase.execSQL("update learning_checkpoints set module_id='LR-07',step_id='other'")
        val repository = AndroidTargetSelfAssessmentRepository(context, runtime, "c", "s", "LR-07")
        assertTrue(repository.mark("pattern", SelfAssessmentAbility.PATTERN_SPEAKING))
        assertFalse(repository.mark("other-word", SelfAssessmentAbility.PATTERN_SPEAKING))
        assertEquals("other", db.readableDatabase.rawQuery("select step_id from learning_checkpoints", null).use { it.moveToFirst(); it.getString(0) })
    }

    @Test fun fixedPhraseUsesSavedCurrentSentenceTargetsAndReentryReusesDeliveredExplanation() = runBlocking {
        db.writableDatabase.execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary) values ('account','c','phrase','s','FIXED_EXPRESSION','plan to go','计划去','','')")
        var calls = 0
        val transport = object : com.reallanguage.app.core.network.HttpTransport {
            override suspend fun execute(request: com.reallanguage.app.core.network.HttpRequest): com.reallanguage.app.core.network.HttpResponse {
                calls++
                val requested = org.json.JSONObject(request.body).optJSONObject("requestedExpression")
                return com.reallanguage.app.core.network.HttpResponse(200, if (requested == null) {
                    """{"kind":"SENTENCE","explanationZh":"描述计划。"}"""
                } else {
                    assertEquals("plan to go", requested.getString("display"))
                    org.json.JSONObject().put("kind", "EXPRESSION").put("expressionId", requested.getString("expressionId"))
                        .put("explanationZh", "计划去。").put("phonetic", org.json.JSONObject.NULL).toString()
                })
            }
        }
        val chapter = com.reallanguage.app.feature.learning.domain.FixedLearningChapter("c", 1, listOf(
            com.reallanguage.app.feature.learning.domain.FixedLearningSentence("s", "I plan to go.", "我计划去。", true)))
        fun provider() = HttpExplanationReadingProvider(transport, runtime, chapter, "s", java.util.UUID.randomUUID().toString(),
            cache = AndroidExplanationCache(context), selectedExpressions = repository()::selectedExpressionDisplays)
        val prepared = provider().prepare() as com.reallanguage.app.feature.learning.domain.ExplanationPreparationResult.Ready
        val phrase = prepared.expressions.first { it.display == "plan to go" }
        assertEquals(2, phrase.startOffset)
        assertTrue(provider().explainExpression("s", phrase.expressionId) is com.reallanguage.app.feature.learning.domain.ExplanationContentResult.ExpressionReady)
        assertEquals(prepared, provider().prepare())
        assertTrue(provider().explainExpression("s", phrase.expressionId) is com.reallanguage.app.feature.learning.domain.ExplanationContentResult.ExpressionReady)
        assertEquals("One sentence + one phrase delivery; reentry does not request again", 2, calls)
    }

    @Test fun repeatedMarkSurvivesNewRepositoryWithoutCreatingFormalKnowledge() = runBlocking {
        repeat(2) { assertTrue(repository().mark("word", SelfAssessmentAbility.MEANING_WRITING)) }
        assertEquals(setOf(SelfAssessmentAbility.MEANING_WRITING), repository().load().first { it.targetId == "word" }.assessedAbilities)
        listOf("vocabulary_items", "pattern_items", "sentence_capabilities", "learning_obligations").forEach { assertEquals(0, count(it)) }
    }

    @Test fun rejectsWrongSentenceUnknownTargetAndWrongAbility() = runBlocking {
        assertFalse(repository().mark("other-word", SelfAssessmentAbility.MEANING_WRITING))
        assertFalse(repository().mark("unselected", SelfAssessmentAbility.MEANING_WRITING))
        assertFalse(repository().mark("word", SelfAssessmentAbility.PATTERN_SPEAKING))
        assertFalse(repository().mark("pattern", SelfAssessmentAbility.MEANING_WRITING))
    }

    @Test fun oldAccountCannotMarkAfterAccountSwitch() = runBlocking {
        val old = repository()
        runtime.update("other-token", "other-account")
        assertFalse(old.mark("word", SelfAssessmentAbility.MEANING_WRITING))
        assertTrue(runCatching { old.load() }.isFailure)
    }

    @Test fun latePageCannotMarkAfterModuleChangesOrChapterIsCompleted() = runBlocking {
        db.writableDatabase.execSQL("update learning_checkpoints set module_id='LR-03'")
        assertFalse(repository().mark("word", SelfAssessmentAbility.MEANING_WRITING))
        db.writableDatabase.execSQL("update learning_checkpoints set module_id='LR-02'")
        db.writableDatabase.execSQL("update chapters set status='COMPLETED'")
        assertFalse(repository().mark("word", SelfAssessmentAbility.MEANING_WRITING))
    }

    @Test fun version20UpgradePreservesTargetsAndCheckpointWithoutInventingSelfAssessment() = runBlocking {
        db.writableDatabase.execSQL("drop table learning_self_assessments")
        db.writableDatabase.execSQL("alter table target_selections drop column source_evidence")
        com.reallanguage.app.testing.removeKnowledgeReuseSchemaForLegacyFixture(db.writableDatabase)
        db.writableDatabase.version = 20
        db.close()
        db = FormationDatabase(context)
        assertEquals(26, db.readableDatabase.version)
        assertEquals(3, count("target_selections"))
        assertEquals(1, count("learning_checkpoints"))
        assertEquals(0, count("learning_self_assessments"))
        assertTrue(repository().load().all { it.assessedAbilities.isEmpty() })
    }

    @Test fun failedSaveIsNotAcknowledgedAndRetryCanPersist() = runBlocking {
        db.writableDatabase.execSQL("create trigger reject_self before insert on learning_self_assessments begin select raise(ABORT,'isolated failure'); end")
        assertFalse(repository().mark("word", SelfAssessmentAbility.MEANING_WRITING))
        assertTrue(repository().load().all { it.assessedAbilities.isEmpty() })
        db.writableDatabase.execSQL("drop trigger reject_self")
        assertTrue(repository().mark("word", SelfAssessmentAbility.MEANING_WRITING))
    }

    @Test fun abandoningOnlyClearsThisChaptersDraftAndRejectsLateMark() = runBlocking {
        assertTrue(repository().mark("word", SelfAssessmentAbility.MEANING_WRITING))
        val result = AndroidChapterAbandonRepository(context, runtime).abandonUnarchivedChapter("c", "abandon")
        assertTrue(result is com.reallanguage.app.feature.learning.domain.ChapterAbandonResult.Abandoned)
        assertEquals(0, count("learning_self_assessments"))
        assertFalse(repository().mark("word", SelfAssessmentAbility.MEANING_WRITING))
    }

    private fun count(table: String) = db.readableDatabase.rawQuery("select count(*) from $table", null).use { it.moveToFirst(); it.getInt(0) }
}
