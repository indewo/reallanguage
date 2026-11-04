package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class KnowledgeExampleBoundaryTest {
    private val request = KnowledgeExampleGenerationRequest("t1", KnowledgeTargetType.VOCABULARY, "colleague", "同事",
        listOf(KnowledgeSourceSentence("I asked a colleague.", "我问了一位同事。")), "stable-command")
    private val response = """{"itemId":"t1","phonetic":null,"pronunciationText":"colleague","representativeScene":"工作沟通","naturalRevalidation":null,"examples":[{"textEn":"My colleague called.","textZh":"同事打来了电话。","pronunciationText":"My colleague called."},{"textEn":"I asked a colleague.","textZh":"我问了一位同事。","pronunciationText":"I asked a colleague."},{"textEn":"She is a colleague.","textZh":"她是一位同事。","pronunciationText":"She is a colleague."}]}"""

    @Test fun oldAccountCannotBorrowNewCredentialsToGenerate() = runBlocking {
        val runtime = SessionRuntime().apply { update("isolated", "owner") }
        var calls = 0
        val repository = HttpKnowledgeExampleRepository(HttpTransport { calls++; HttpResponse(200, response) }, runtime)
        runtime.update("other-isolated", "other")
        assertEquals(KnowledgeExampleGenerationResult.Failed, repository.generate(request))
        assertEquals(0, calls)
    }

    @Test fun lateResponseAfterAccountSwitchIsNotDelivered() = runBlocking {
        val runtime = SessionRuntime().apply { update("isolated", "owner") }
        val repository = HttpKnowledgeExampleRepository(HttpTransport {
            runtime.update("other-isolated", "other"); HttpResponse(200, response)
        }, runtime)
        assertEquals(KnowledgeExampleGenerationResult.Failed, repository.generate(request))
    }

    @Test fun cancellationIsNotConvertedIntoAGenerationFailure() = runBlocking {
        val runtime = SessionRuntime().apply { update("isolated", "owner") }
        val repository = HttpKnowledgeExampleRepository(HttpTransport { throw CancellationException("navigation cancelled") }, runtime)
        var propagated = false
        try { repository.generate(request) } catch (_: CancellationException) { propagated = true }
        assertTrue("Cancellation must propagate to the owning operation", propagated)
    }

    @Test fun blankExamplesCannotBeSavedAsCompleteLearningDetails() = runBlocking {
        val runtime = SessionRuntime().apply { update("isolated", "owner") }
        val repository = HttpKnowledgeExampleRepository(HttpTransport { HttpResponse(200, response.replace("My colleague called.", "")) }, runtime)
        assertEquals(KnowledgeExampleGenerationResult.Failed, repository.generate(request))
    }

    @Test fun tokenRefreshForSameAccountKeepsTheRequestAndResultValid() = runBlocking {
        val runtime = SessionRuntime().apply { update("isolated", "owner") }
        val repository = HttpKnowledgeExampleRepository(HttpTransport {
            runtime.update("refreshed-isolated", "owner"); HttpResponse(200, response)
        }, runtime)
        assertTrue(repository.generate(request) is KnowledgeExampleGenerationResult.Generated)
    }
}
