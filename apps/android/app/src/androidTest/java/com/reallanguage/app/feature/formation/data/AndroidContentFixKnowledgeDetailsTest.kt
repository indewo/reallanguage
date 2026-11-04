package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.testing.withExplicitSource
import com.reallanguage.app.testing.explicitSourceFixRepository
import com.reallanguage.app.feature.formation.domain.FixedSourceUnit

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.ContentFixRequest
import com.reallanguage.app.feature.formation.domain.ContentFixResult
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import com.reallanguage.app.feature.formation.domain.KnowledgeExampleDraft
import com.reallanguage.app.feature.formation.domain.KnowledgeExampleGenerationRequest
import com.reallanguage.app.feature.formation.domain.KnowledgeExampleGenerationResult
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.KnowledgeTargetDetails
import com.reallanguage.app.feature.formation.domain.KnowledgeTargetType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidContentFixKnowledgeDetailsTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private lateinit var runtime: SessionRuntime
    private lateinit var account: String

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        account = "fix-proof-${java.util.UUID.randomUUID()}"
        runtime = SessionRuntime().apply { update("isolated-token", account) }
        database = FormationDatabase(context)
        database.writableDatabase
    }
    @After fun tearDown() {
        listOf("fixed_source_payloads", "fix_commands", "target_vocabulary_details", "target_pattern_details", "target_selections", "fixed_sentences", "chapters", "formation_flows").forEach {
            database.writableDatabase.delete(it, "account_id in (?,?)", arrayOf(account, "$account-other"))
        }
        database.close()
    }

    @Test fun oldRepositoryCannotFixIntoTheNewlySignedInAccount() = runBlocking {
        var calls = 0
        val repository = explicitSourceFixRepository(context, runtime, knowledgeExamples = { calls++; KnowledgeExampleGenerationResult.Generated(details) })
        runtime.update("other-isolated-token", "$account-other")
        assertEquals(ContentFixResult.Failed, repository.fix(request))
        assertEquals(0, calls)
        assertEquals(0, count("chapters"))
    }

    @Test fun accountSwitchDuringGenerationCannotCommitOrNavigateAsSuccess() = runBlocking {
        val repository = explicitSourceFixRepository(context, runtime, knowledgeExamples = {
            runtime.update("other-isolated-token", "$account-other")
            KnowledgeExampleGenerationResult.Generated(details)
        })
        assertEquals(ContentFixResult.Failed, repository.fix(request))
        assertEquals(0, count("chapters"))
        assertEquals(0, count("fix_commands"))
    }

    @Test fun providerExceptionLeavesNoHalfFixedChapterAndReturnsRetryableFailure() = runBlocking {
        val repository = explicitSourceFixRepository(context, runtime, knowledgeExamples = { throw IllegalStateException("isolated provider failure") })
        assertEquals(ContentFixResult.Failed, repository.fix(request))
        assertEquals(0, count("chapters"))
        assertEquals(0, count("fix_commands"))
    }

    @Test fun generatesDetailBeforeFixAndStoresItOnlyAsChapterTemporaryData() = runBlocking {
        var captured: KnowledgeExampleGenerationRequest? = null
        val repository = explicitSourceFixRepository(context, runtime, knowledgeExamples = { request ->
            captured = request
            KnowledgeExampleGenerationResult.Generated(details)
        })

        assertTrue(repository.fix(request) is ContentFixResult.Fixed)
        assertEquals("I run a cafe.", captured?.sourceScene?.single()?.textEn)
        assertEquals("cmd:t1", captured?.commandId)
        assertEquals(1, count("target_vocabulary_details"))
        assertEquals(0, count("vocabulary_items"))
    }

    @Test fun generationFailureLeavesNoFixedOrFormalLocalContent() = runBlocking {
        val repository = explicitSourceFixRepository(context, runtime, knowledgeExamples = {
            KnowledgeExampleGenerationResult.Failed
        })

        assertEquals(ContentFixResult.Failed, repository.fix(request))
        assertEquals(0, count("chapters"))
        assertEquals(0, count("target_vocabulary_details"))
        assertEquals(0, count("vocabulary_items"))
    }

    @Test fun separateFormationsMayReuseProviderLocalIdentifiers() = runBlocking {
        val repository = explicitSourceFixRepository(context, runtime)
        assertTrue(repository.fix(request) is ContentFixResult.Fixed)
        assertTrue(repository.fix(request.copy(formationFlowId = "second-flow", commandId = "second-command").withExplicitSource("我经营一家咖啡店。", listOf(FixedSourceUnit("u1", 0, "我经营一家咖啡店。")))) is ContentFixResult.Fixed)
        assertEquals(2, count("chapters"))
        assertEquals(2, count("target_selections"))
    }

    @Test fun successfulRetryDoesNotCallAiAgain() = runBlocking {
        var calls = 0
        val repository = explicitSourceFixRepository(context, runtime, knowledgeExamples = {
            calls++
            if (calls == 1) KnowledgeExampleGenerationResult.Generated(details) else KnowledgeExampleGenerationResult.Failed
        })
        val first = repository.fix(request)
        assertTrue(first is ContentFixResult.Fixed)
        assertEquals(first, repository.fix(request))
        assertEquals(1, calls)
    }

    private fun count(table: String): Int = database.readableDatabase
        .rawQuery("select count(*) from $table where account_id in (?,?)", arrayOf(account, "$account-other"))
        .use { it.moveToFirst(); it.getInt(0) }

    private companion object {
        val sentence = EnglishSentenceDraft("u1", "我经营一家咖啡店。", true, listOf(EnglishCandidate("e1", "I run a cafe.")))
        val target = LearningTarget("t1", "u1", "run", "经营", LearningTargetType.WORD, learningScopes = setOf("CET_4"))
        val request = ContentFixRequest("flow", "cmd", listOf(EnglishChapterDraft("c1", listOf(sentence))), mapOf("u1" to "e1"), listOf(target), setOf("t1")).withExplicitSource("我经营一家咖啡店。", listOf(FixedSourceUnit("u1", 0, "我经营一家咖啡店。")))
        val details = KnowledgeTargetDetails(
            "t1", "/rʌn/", "run", "经营社区咖啡店",
            listOf(
                KnowledgeExampleDraft("She runs a cafe.", "她经营一家咖啡店。", "She runs a cafe."),
                KnowledgeExampleDraft("They run the shop.", "他们经营这家店。", "They run the shop."),
                KnowledgeExampleDraft("He runs a business.", "他经营一家企业。", "He runs a business."),
            ),
        )
    }
}
