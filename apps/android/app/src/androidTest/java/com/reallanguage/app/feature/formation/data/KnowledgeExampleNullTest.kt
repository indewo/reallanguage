package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test

class KnowledgeExampleNullTest {
    @Test fun absentPhoneticIsNotRenderedAsTheWordNull() = runBlocking {
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest) = HttpResponse(200, """{"itemId":"t1","phonetic":null,"pronunciationText":"colleague","representativeScene":"工作沟通","naturalRevalidation":null,"examples":[{"textEn":"My colleague called.","textZh":"同事打来了电话。","pronunciationText":"My colleague called."},{"textEn":"I asked a colleague.","textZh":"我问了一位同事。","pronunciationText":"I asked a colleague."},{"textEn":"She is a colleague.","textZh":"她是一位同事。","pronunciationText":"She is a colleague."}]}""")
        }
        val repository = HttpKnowledgeExampleRepository(transport, SessionRuntime().apply { update("test-token", "test-account") })
        val result = repository.generate(KnowledgeExampleGenerationRequest("t1", KnowledgeTargetType.VOCABULARY, "colleague", "同事", listOf(KnowledgeSourceSentence("I asked a colleague.", "我问了一位同事。")), "cmd"))
        assertNull((result as KnowledgeExampleGenerationResult.Generated).details.phonetic)
    }
}
