package com.reallanguage.app.feature.review.data

import android.content.ContentValues
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.review.domain.ReviewChapterLoadResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ReviewChapterMetadataRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val runtime = SessionRuntime().apply { update("isolated-token", "owner") }
    private val chapterId = "d44f77d6-6abc-413c-9ded-c1b95a620147"

    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "review-metadata")
        database = FormationDatabase(context)
        val db = database.writableDatabase
        db.insertOrThrow("chapters", null, values("account_id" to "owner", "flow_id" to "flow", "chapter_id" to chapterId,
            "chapter_order" to 4, "status" to "COMPLETED"))
        db.insertOrThrow("review_chapters", null, values("account_id" to "owner", "review_cycle_id" to "cycle",
            "review_chapter_id" to "review", "original_chapter_id" to chapterId, "source_type" to "DIARY", "source_title" to "已保存日记", "status" to "READY"))
        // Deliberately inserted out of original order; all three English strings are equal.
        seedSentence("owner", "focus-two", 8, "FOCUS", "第二重点句")
        seedSentence("owner", "context", 2, "CONTEXT", "非重点上下文")
        seedSentence("owner", "focus-one", 5, "FOCUS", "第一重点句")
        listOf("focus-two", "focus-one").forEachIndexed { priority, sentence ->
            db.insertOrThrow("sentence_capabilities", null, values("account_id" to "owner", "sentence_id" to sentence,
                "capability_type" to "FULL_SPEAKING", "status" to "LEARNING"))
            db.insertOrThrow("learning_obligations", null, values("account_id" to "owner", "obligation_id" to "obligation-$sentence",
                "chapter_id" to chapterId, "sentence_id" to sentence, "capability_type" to "FULL_SPEAKING", "target_id" to ""))
            db.insertOrThrow("review_queue_items", null, values("account_id" to "owner", "review_cycle_id" to "cycle", "queue_item_id" to "queue-$sentence",
                "obligation_id" to "obligation-$sentence", "original_chapter_id" to chapterId, "sentence_id" to sentence,
                "priority_rank" to priority, "entered_at" to priority, "occurrence_count" to 1))
            db.insertOrThrow("review_chapter_items", null, values("account_id" to "owner", "review_chapter_id" to "review",
                "queue_item_id" to "queue-$sentence", "sentence_id" to sentence, "ability_type" to "FULL_SPEAKING"))
        }
        // Same identifiers in another account must not affect source metadata.
        db.insertOrThrow("chapters", null, values("account_id" to "other", "flow_id" to "other-flow", "chapter_id" to chapterId,
            "chapter_order" to 77, "status" to "COMPLETED"))
        seedSentence("other", "focus-two", 0, "FOCUS", "另一账号的内容")
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase("real-language-learning.db")
    }

    @Test fun loadsActualChapterOrderAndAccountBoundSentenceIdentitiesInFixedOrder() = runBlocking {
        val before = counts()
        val snapshot = (AndroidReviewChapterRepository(context, runtime).loadChapter("review") as ReviewChapterLoadResult.Ready).chapter
        assertEquals(5, snapshot.originalChapterNumber)
        val sentences = requireNotNull(snapshot.originalSentences)
        assertEquals(listOf("context", "focus-one", "focus-two"), sentences.map { it.sentenceId })
        assertEquals(listOf(2, 5, 8), sentences.map { it.sentenceOrder })
        assertEquals(listOf(false, true, true), sentences.map { it.isFocus })
        assertEquals(listOf("非重点上下文", "第一重点句", "第二重点句"), sentences.map { it.textZh })
        assertEquals(snapshot.originalScene, sentences.map { it.textEn })
        assertEquals("focus-two", snapshot.abilities.first().sentenceId)
        assertEquals(before, counts())
    }

    @Test fun missingChapterMetadataDoesNotBorrowAnotherAccountsChapterNumber() = runBlocking {
        database.writableDatabase.delete("chapters", "account_id=?", arrayOf("owner"))
        val before = counts()
        val snapshot = (AndroidReviewChapterRepository(context, runtime).loadChapter("review") as ReviewChapterLoadResult.Ready).chapter
        assertNull(snapshot.originalChapterNumber)
        assertEquals(listOf("context", "focus-one", "focus-two"), requireNotNull(snapshot.originalSentences).map { it.sentenceId })
        assertEquals(before, counts())
    }

    @Test fun invalidNegativeChapterOrderStaysUnknownWithoutGuessingFromItsId() = runBlocking {
        database.writableDatabase.execSQL("update chapters set chapter_order=-1 where account_id=?", arrayOf("owner"))
        val before = counts()
        val snapshot = (AndroidReviewChapterRepository(context, runtime).loadChapter("review") as ReviewChapterLoadResult.Ready).chapter
        assertNull(snapshot.originalChapterNumber)
        assertEquals(before, counts())
    }

    private fun seedSentence(account: String, id: String, order: Int, role: String, zh: String) {
        database.writableDatabase.insertOrThrow("fixed_sentences", null, values("account_id" to account, "chapter_id" to chapterId,
            "unit_id" to id, "sentence_order" to order, "text_zh" to zh, "text_en" to "The same words.", "role" to role))
    }

    private fun counts() = listOf("chapters", "fixed_sentences", "review_chapters", "review_chapter_items", "review_working_state", "review_chapter_results").map { table ->
        database.readableDatabase.rawQuery("select count(*) from $table", null).use { it.moveToFirst(); it.getInt(0) }
    }

    private fun values(vararg pairs: Pair<String, Any>) = ContentValues().apply {
        pairs.forEach { (key, value) -> when (value) { is Int -> put(key, value); else -> put(key, value.toString()) } }
    }
}
