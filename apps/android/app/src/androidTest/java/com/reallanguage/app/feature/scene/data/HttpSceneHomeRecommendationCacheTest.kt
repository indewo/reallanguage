package com.reallanguage.app.feature.scene.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.scene.domain.SceneRecommendationResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpSceneHomeRecommendationCacheTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val runtime = SessionRuntime().apply { update("token", "owner") }

    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "scene-recommendation-cache",
        )
        database = FormationDatabase(context)
        database.writableDatabase
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase("real-language-learning.db")
    }

    @Test fun reopeningUsesDurableAccountCacheWithoutAnotherRequest() = runTest {
        insertFixedSource("flow-1", "2026-09-13", "第一次和国外同事吃午饭")
        val firstTransport = CountingTransport(recommendations("rec-1", "和新同事吃午饭", "flow-1"))
        val firstRepository = HttpSceneHomeRepository(context, firstTransport, runtime)
        assertEquals(SceneRecommendationResult.Unavailable, firstRepository.loadRecommendations())
        val first = firstRepository.refreshRecommendationsIfNeeded()!!
        assertEquals(listOf("和新同事吃午饭"), first.titles())
        assertEquals(1, firstTransport.calls)

        val reopenedTransport = CountingTransport(HttpResponse(503, ""))
        val reopened = HttpSceneHomeRepository(context, reopenedTransport, runtime)
        assertEquals(listOf("和新同事吃午饭"), reopened.loadRecommendations().titles())
        assertNull(reopened.refreshRecommendationsIfNeeded())
        assertEquals(0, reopenedTransport.calls)
        assertEquals(1, count("scene_recommendation_cache_items", "owner"))
    }

    @Test fun changedFixedContentKeepsOldCardInstantlyThenRefreshesOnce() = runTest {
        insertFixedSource("flow-1", "2026-09-12", "旧内容")
        val initial = HttpSceneHomeRepository(
            context,
            CountingTransport(recommendations("old", "旧主题", "flow-1")),
            runtime,
        )
        assertEquals(SceneRecommendationResult.Unavailable, initial.loadRecommendations())
        initial.refreshRecommendationsIfNeeded()

        insertFixedSource("flow-2", "2026-09-14", "新内容")
        val transport = CountingTransport(recommendations("new", "新主题", "flow-2"))
        val reopened = HttpSceneHomeRepository(context, transport, runtime)
        assertEquals(listOf("旧主题"), reopened.loadRecommendations().titles())
        assertEquals(0, transport.calls)
        assertEquals(listOf("新主题"), reopened.refreshRecommendationsIfNeeded()!!.titles())
        assertEquals(1, transport.calls)
        assertNull(reopened.refreshRecommendationsIfNeeded())
        assertEquals(1, transport.calls)
    }

    @Test fun emptySnapshotIsCachedAndAccountsNeverShareCards() = runTest {
        val emptyTransport = CountingTransport(HttpResponse(500, ""))
        val ownerRepository = HttpSceneHomeRepository(context, emptyTransport, runtime)
        assertEquals(SceneRecommendationResult.Unavailable, ownerRepository.loadRecommendations())
        assertTrue((ownerRepository.refreshRecommendationsIfNeeded() as SceneRecommendationResult.Ready).recommendations.isEmpty())
        assertEquals(0, emptyTransport.calls)

        val reopened = HttpSceneHomeRepository(context, emptyTransport, runtime)
        assertTrue((reopened.loadRecommendations() as SceneRecommendationResult.Ready).recommendations.isEmpty())
        assertNull(reopened.refreshRecommendationsIfNeeded())
        assertEquals(0, emptyTransport.calls)

        runtime.update("other-token", "other")
        val otherRepository = HttpSceneHomeRepository(context, emptyTransport, runtime)
        assertEquals(SceneRecommendationResult.Unavailable, otherRepository.loadRecommendations())
        assertTrue((otherRepository.refreshRecommendationsIfNeeded() as SceneRecommendationResult.Ready).recommendations.isEmpty())
        assertEquals(0, count("scene_recommendation_cache_items", "other"))
    }

    @Test fun schema25UpgradeAddsCacheWithoutChangingExistingLearningRows() {
        insertFixedSource("legacy", "2026-09-13", "保留的旧学习内容")
        val db = database.writableDatabase
        db.execSQL("drop table scene_recommendation_cache_items")
        db.execSQL("drop table scene_recommendation_cache_state")
        db.version = 25
        database.close()

        database = FormationDatabase(context)
        assertEquals(26, database.readableDatabase.version)
        assertEquals(1, count("formation_flows", "owner"))
        assertEquals(0, count("scene_recommendation_cache_items", "owner"))
        assertTrue(tableExists("scene_recommendation_cache_state"))
        assertTrue(tableExists("scene_recommendation_cache_items"))
    }

    private fun insertFixedSource(flowId: String, date: String, title: String) {
        val db = database.writableDatabase
        db.execSQL("insert into formation_flows values(?,?,?,?,?)", arrayOf("owner", flowId, "FIXED", "DIARY", title))
        db.execSQL("insert into chapters values(?,?,?,?,?)", arrayOf<Any>("owner", flowId, "chapter-$flowId", 0, "READY"))
        db.execSQL(
            "insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,?,?,?,?,?)",
            arrayOf<Any>("owner", "chapter-$flowId", "unit-$flowId", 0, title, "English", "FOCUS"),
        )
        db.execSQL(
            "insert into chapter_archives(account_id,chapter_id,command_id,summary,completed_local_date) values(?,?,?,?,?)",
            arrayOf("owner", "chapter-$flowId", "archive-$flowId", title, date),
        )
    }

    private fun count(table: String, accountId: String) = database.readableDatabase.rawQuery(
        "select count(*) from $table where account_id=?",
        arrayOf(accountId),
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun tableExists(table: String) = database.readableDatabase.rawQuery(
        "select 1 from sqlite_master where type='table' and name=?",
        arrayOf(table),
    ).use { it.moveToFirst() }

    private fun SceneRecommendationResult.titles() =
        (this as SceneRecommendationResult.Ready).recommendations.map { it.shortTopicZh }

    private fun recommendations(id: String, title: String, source: String) = HttpResponse(
        200,
        """{"recommendations":[{"recommendationId":"$id","shortTopicZh":"$title","whyRelevantZh":"与近期学习相关","sourceRefs":["$source"]}]}""",
    )

    private class CountingTransport(private val response: HttpResponse) : HttpTransport {
        var calls = 0
        override suspend fun execute(request: HttpRequest): HttpResponse { calls++; return response }
    }
}
