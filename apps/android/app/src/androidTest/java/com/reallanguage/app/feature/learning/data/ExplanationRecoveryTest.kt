package com.reallanguage.app.feature.learning.data

import androidx.test.core.app.ApplicationProvider
import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ExplanationRecoveryTest {
    @Test fun lateExplanationCannotRecreateContentForDeletedChapter() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val account = "late-cache-${java.util.UUID.randomUUID()}"
        val cache = AndroidExplanationCache(context)
        org.junit.Assert.assertTrue(runCatching { cache.write(account, "deleted", "s1", "base", "late response") }.isFailure)
        org.junit.Assert.assertNull(cache.read(account, "deleted", "s1", "base"))
    }
    @Test fun version14MigrationAddsCacheWithoutChangingExistingContent() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        android.database.sqlite.SQLiteDatabase.create(null).use { db ->
            db.execSQL("create table preserved_content(body text not null)")
            db.execSQL("insert into preserved_content values ('existing learning content')")
            com.reallanguage.app.feature.formation.data.FormationDatabase(context).use { helper -> helper.onUpgrade(db, 14, 15) }
            db.rawQuery("select body from preserved_content", null).use { it.moveToFirst(); assertEquals("existing learning content", it.getString(0)) }
            db.rawQuery("select count(*) from learning_explanation_cache", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }
    @Test fun reopeningDeliveredExplanationDoesNotGenerateAgain() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val runtime = SessionRuntime().apply { update("test-token", "explanation-recovery-${java.util.UUID.randomUUID()}") }
        val chapter = FixedLearningChapter("recovery-chapter", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true)))
        seedChapter(context, runtime.accountId!!, chapter.chapterId)
        var calls = 0
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                calls++
                return HttpResponse(200, """{"kind":"SENTENCE","explanationZh":"felt 表达过去的感受。"}""")
            }
        }
        val first = HttpExplanationReadingProvider(transport, runtime, chapter, "s1", "first-entry", cache = AndroidExplanationCache(context)).prepare()
        val reopened = HttpExplanationReadingProvider(transport, runtime, chapter, "s1", "second-entry", cache = AndroidExplanationCache(context)).prepare()
        org.junit.Assert.assertTrue(first is ExplanationPreparationResult.Ready)
        assertEquals(first, reopened)
        assertEquals("Reopening delivered content must not invoke AI again", 1, calls)
        com.reallanguage.app.feature.formation.data.FormationDatabase(context).use {
            it.writableDatabase.delete("learning_explanation_cache", "account_id=?", arrayOf(runtime.accountId))
            it.writableDatabase.delete("chapters", "account_id=?", arrayOf(runtime.accountId))
        }
        Unit
    }

    @Test fun cacheIsAccountIsolatedAndFailedResponsesAreNotDelivered() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val account = "cache-boundary-${java.util.UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("test-token", account) }
        val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true)))
        seedChapter(context, account, "c1")
        seedChapter(context, "$account-other", "c1")
        val responses = ArrayDeque(listOf(
            HttpResponse(503, ""), HttpResponse(200, """{"kind":"SENTENCE","explanationZh":""}"""),
            HttpResponse(200, """{"kind":"SENTENCE","explanationZh":"第一位用户的讲解。"}"""),
            HttpResponse(200, """{"kind":"SENTENCE","explanationZh":"第二位用户的讲解。"}"""),
        ))
        var calls = 0
        val transport = object : HttpTransport { override suspend fun execute(request: HttpRequest): HttpResponse { calls++; return responses.removeFirst() } }
        fun provider() = HttpExplanationReadingProvider(transport, runtime, chapter, "s1", java.util.UUID.randomUUID().toString(), cache = AndroidExplanationCache(context))
        try {
            assertEquals(ExplanationPreparationResult.Failed, provider().prepare())
            assertEquals(ExplanationPreparationResult.Failed, provider().prepare())
            val first = provider().prepare() as ExplanationPreparationResult.Ready
            assertEquals(first, provider().prepare())
            assertEquals(3, calls)
            runtime.update("another-test-token", "$account-other")
            assertEquals("第二位用户的讲解。", (provider().prepare() as ExplanationPreparationResult.Ready).baseExplanationZh)
            assertEquals(4, calls)
            runtime.clear()
            assertEquals(ExplanationPreparationResult.Failed, provider().prepare())
            assertEquals(4, calls)
        } finally {
            com.reallanguage.app.feature.formation.data.FormationDatabase(context).use {
                it.writableDatabase.delete("learning_explanation_cache", "account_id in (?,?)", arrayOf(account, "$account-other"))
                it.writableDatabase.delete("chapters", "account_id in (?,?)", arrayOf(account, "$account-other"))
            }
        }
    }
    private fun seedChapter(context: android.content.Context, account: String, chapter: String) {
        com.reallanguage.app.feature.formation.data.FormationDatabase(context).use {
            it.writableDatabase.execSQL("insert into chapters(account_id,flow_id,chapter_id,chapter_order,status) values (?,?,?,0,'NOT_STARTED')", arrayOf(account, "test-flow", chapter))
        }
    }
}
