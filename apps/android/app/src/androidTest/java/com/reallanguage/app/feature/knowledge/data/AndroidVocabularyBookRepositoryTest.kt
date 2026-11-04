package com.reallanguage.app.feature.knowledge.data

import android.content.ContentValues
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.vocabulary.AndroidEmbeddedVocabularyCatalog
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyLoadResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidVocabularyBookRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val runtime = SessionRuntime().apply { update("token", "account") }

    @Before fun setUp() {
        context = com.reallanguage.app.testing.IsolatedLearningDatabaseContext(
            InstrumentationRegistry.getInstrumentation().targetContext, "vocabulary-book")
        database = FormationDatabase(context)
        database.writableDatabase
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase("real-language-learning.db")
    }

    @Test fun loadsAccountIsolatedSensesWordlistsCountsAndVerifiedTotal() = runBlocking {
        seed("run-business", "account", "run", "经营", "VERIFIED", 2, "CET_4")
        seed("run-move", "account", "run", "跑步", "LEARNING", 0, "CET_4")
        seed("other", "another", "run", "其他账号", "VERIFIED", 9, "CET_4")
        occurrence("run-business", "s1", true)
        occurrence("run-business", "s2", false)

        val result = AndroidVocabularyBookRepository(context, runtime) { setOf("CET_4") }
            .loadAllRecords() as VocabularyLoadResult.Ready

        assertEquals(setOf("CET_4"), result.currentScopes)
        assertEquals(listOf("run-business", "run-move"), result.records.map { it.senseId })
        assertEquals(KnowledgeStatus.MASTERED, result.records.first().status)
        assertEquals(2, result.records.first().effectiveLearnedCount)
        assertEquals(1, result.records.first().focusOccurrenceCount)
        assertEquals(1, result.records.first().contextOccurrenceCount)
    }

    @Test fun changingCurrentScopeDoesNotDeleteStoredOtherScopeRecords() = runBlocking {
        seed("cet4", "account", "calm", "平静", "LEARNING", 0, "CET_4")
        seed("cet6", "account", "allocate", "分配", "LEARNING", 0, "CET_6")

        val result = AndroidVocabularyBookRepository(context, runtime) { setOf("CET_6") }
            .loadAllRecords() as VocabularyLoadResult.Ready

        assertEquals(setOf("CET_6"), result.currentScopes)
        assertEquals(setOf("cet4", "cet6"), result.records.mapTo(mutableSetOf()) { it.senseId })
        assertEquals(2, count("vocabulary_items"))
        assertTrue(result.records.first { it.senseId == "cet4" }.learningScopes.contains("CET_4"))
    }

    @Test fun loadsOnlyTheStableThreeExampleInlineDetailForTheAccount() = runBlocking {
        seed("run-business", "account", "run", "经营", "VERIFIED", 3, "CET_4")
        database.writableDatabase.execSQL(
            "update vocabulary_items set phonetic=?,pronunciation_audio_id=?,representative_scene=? where account_id=? and item_id=?",
            arrayOf("/rʌn/", "run", "经营社区咖啡店", "account", "run-business"),
        )
        repeat(3) { index ->
            database.writableDatabase.insertOrThrow("vocabulary_examples", null, ContentValues().apply {
                put("account_id", "account"); put("item_id", "run-business"); put("example_order", index)
                put("text_en", "Example $index"); put("text_zh", "例句 $index"); put("audio_id", "audio-$index")
            })
        }

        val record = (AndroidVocabularyBookRepository(context, runtime) { setOf("CET_4") }
            .loadAllRecords() as VocabularyLoadResult.Ready).records.single()

        assertEquals("/rʌn/", record.phonetic)
        assertEquals("run", record.pronunciationText)
        assertEquals("经营社区咖啡店", record.representativeScene)
        assertEquals(listOf("Example 0", "Example 1", "Example 2"), record.examples.map { it.textEn })
    }

    @Test fun legacyCompleteSentencesAreQuarantinedAndLiteralNullMetadataIsNotShown() = runBlocking {
        seed("bad-sentence", "account", "Can you hear what I'm saying right now?", "你现在能听清吗？", "LEARNING", 1, "CET_6")
        seed("valid-word", "account", "hear", "听见", "LEARNING", 1, "CET_6")
        database.writableDatabase.execSQL(
            "update vocabulary_items set phonetic=?,pronunciation_audio_id=?,representative_scene=? where account_id=? and item_id=?",
            arrayOf("null", "NULL", " null ", "account", "valid-word"),
        )

        val records = (AndroidVocabularyBookRepository(context, runtime) { setOf("CET_6") }
            .loadAllRecords() as VocabularyLoadResult.Ready).records

        assertEquals(listOf("valid-word"), records.map { it.senseId })
        assertEquals(null, records.single().phonetic)
        assertEquals(null, records.single().pronunciationText)
        assertEquals(null, records.single().representativeScene)
    }

    @Test fun oldReaderCannotBorrowNewAccountOrRequestItsScope() = runBlocking {
        seed("sense", "account", "run", "跑", "LEARNING", 0, "CET_4")
        val old = SessionRuntime().apply { update("old-token", "old-account") }
        var scopeCalls = 0
        val repository = AndroidVocabularyBookRepository(context, old) { scopeCalls++; setOf("CET_4") }
        old.update("new-token", "account")
        assertEquals(VocabularyLoadResult.Failed, repository.loadAllRecords())
        assertEquals(0, scopeCalls)
    }

    @Test fun changedAccountDuringScopeReadCannotExposePreviousAccountsWords() = runBlocking {
        seed("sense", "account", "run", "跑", "LEARNING", 0, "CET_4")
        val repository = AndroidVocabularyBookRepository(context, runtime) {
            runtime.update("new-token", "another")
            setOf("CET_6")
        }
        assertEquals(VocabularyLoadResult.Failed, repository.loadAllRecords())
        assertEquals(1, count("vocabulary_items"))
    }

    @Test fun scopeReadExceptionIsExplicitFailureNotAnEscapingLoadingTask() = runBlocking {
        val repository = AndroidVocabularyBookRepository(context, runtime) { error("isolated network failure") }
        assertEquals(VocabularyLoadResult.Failed, repository.loadAllRecords())
    }

    @Test fun pendingAndSelfAssessedRemainDistinctFromTrainingAndVerification() = runBlocking {
        seed("pending", "account", "calm", "平静", "PENDING", 0, "CET_4")
        seed("self", "account", "run", "跑", "SELF_ASSESSED", 0, "CET_4")
        val records = (AndroidVocabularyBookRepository(context, runtime) { setOf("CET_4") }
            .loadAllRecords() as VocabularyLoadResult.Ready).records
        assertEquals(listOf(KnowledgeStatus.NOT_STARTED, KnowledgeStatus.TENTATIVE), records.map { it.status })
        assertTrue(records.all { it.effectiveLearnedCount == 0 })
    }

    @Test fun pageContainsTheWholeSelectedOfflinePoolAndOverlaysSavedSenseState() = runBlocking {
        seed("saved-ability", "account", "ability", "能力 能耐 本领", "VERIFIED", 2, "CET_4")
        val repository = AndroidVocabularyBookRepository(
            context,
            runtime,
            CurrentVocabularyScopeSource { setOf("CET_4") },
            AndroidEmbeddedVocabularyCatalog(context),
        )

        val result = repository.loadPage("ab", limit = 200, offset = 0) as VocabularyLoadResult.Ready

        assertTrue(result.records.any { it.englishForm == "abandon" && it.status == KnowledgeStatus.NOT_STARTED })
        val ability = result.records.single { it.englishForm == "ability" }
        assertEquals("n.", ability.partOfSpeech)
        assertEquals(KnowledgeStatus.MASTERED, ability.status)
        assertEquals(2, ability.effectiveLearnedCount)
        assertTrue(result.records.all { "CET_4" in it.learningScopes })
    }

    private fun seed(itemId: String, accountId: String, display: String, meaning: String, status: String, count: Int, scope: String) {
        val db = database.writableDatabase
        db.insertOrThrow("vocabulary_items", null, ContentValues().apply {
            put("account_id", accountId); put("item_id", itemId); put("normalized_form", display)
            put("semantic_sense_key", meaning); put("display", display); put("meaning_zh", meaning)
            put("status", status); put("verified_success_count", count)
        })
        db.insertOrThrow("vocabulary_wordlists", null, ContentValues().apply {
            put("account_id", accountId); put("item_id", itemId); put("wordlist", scope)
        })
    }

    private fun occurrence(itemId: String, sentenceId: String, focus: Boolean) {
        database.writableDatabase.insertOrThrow("vocabulary_occurrences", null, ContentValues().apply {
            put("account_id", "account"); put("item_id", itemId); put("chapter_id", "chapter-$sentenceId")
            put("sentence_id", sentenceId); put("is_focus", if (focus) 1 else 0)
        })
    }

    private fun count(table: String): Int = database.readableDatabase
        .rawQuery("select count(*) from $table", null)
        .use { it.moveToFirst(); it.getInt(0) }
}
