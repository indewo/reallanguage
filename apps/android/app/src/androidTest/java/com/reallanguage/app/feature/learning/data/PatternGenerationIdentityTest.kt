package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Runs the production JSONObject codec with an isolated transport; no AI/network/data writes. */
class PatternGenerationIdentityTest {
    private val chapter = FixedLearningChapter("identity-chapter", 1,
        listOf(FixedLearningSentence("s1", "I used to write.", "我过去常常写字。", true)))
    private val pattern = LockedSpeakingPattern("p1", "s1", "used to + verb", "过去的习惯")
    private val noCapture = object : ReadSpeakAudioCapture {
        override fun start() = true
        override fun finish(): CapturedReadSpeakAudio? = null
        override fun cancel() = Unit
    }

    @Test fun sameGenerationRetainsItsJsonIdentityAcrossNetworkFailureAndNewRepository() = runBlocking {
        val requests = mutableListOf<HttpRequest>()
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                requests += request
                if (requests.size == 1) throw java.io.IOException("isolated lost response")
                return HttpResponse(200, readyBody())
            }
        }
        val runtime = session("account")
        val original = AndroidPatternSpeakingRepository(transport, runtime, chapter, noCapture)
        assertEquals(PatternPracticeGenerationResult.Failed, original.generate(pattern))
        runtime.update("refreshed-test-token", "account")
        val restarted = AndroidPatternSpeakingRepository(transport, runtime, chapter, noCapture)
        assertTrue(restarted.generate(pattern) is PatternPracticeGenerationResult.Ready)
        assertEquals(requests[0].body, requests[1].body)
        assertEquals("Bearer refreshed-test-token", requests[1].headers["Authorization"])
        assertTrue(restarted.generate(pattern) is PatternPracticeGenerationResult.Ready)
        assertEquals("Already delivered practice must stay local", 2, requests.size)
    }

    @Test fun exactAccountChapterSentencePatternAndSourceFactsDefineTheRequestIdentity() = runBlocking {
        suspend fun command(account: String = "account", source: FixedLearningChapter = chapter, target: LockedSpeakingPattern = pattern): String {
            var body: String? = null
            val repository = AndroidPatternSpeakingRepository(object : HttpTransport {
                override suspend fun execute(request: HttpRequest): HttpResponse { body = request.body; return HttpResponse(503, "") }
            }, session(account), source, noCapture)
            assertEquals(PatternPracticeGenerationResult.Failed, repository.generate(target))
            return JSONObject(requireNotNull(body)).getString("commandId")
        }
        val base = command()
        assertEquals(base, command())
        val variants = listOf(
            command(account = "another-account"),
            command(source = chapter.copy(chapterId = "another-chapter")),
            command(source = chapter.copy(sentences = listOf(chapter.sentences.single().copy(sentenceId = "s2"))), target = pattern.copy(sourceSentenceId = "s2")),
            command(target = pattern.copy(patternId = "p2")),
            command(target = pattern.copy(structure = "be used to + noun")),
            command(target = pattern.copy(explanationZh = "适应某种习惯")),
            command(source = chapter.copy(sentences = listOf(chapter.sentences.single().copy(textEn = "I used to read.")))),
            command(source = chapter.copy(sentences = listOf(chapter.sentences.single().copy(textZh = "我过去常常写作。")))),
        )
        assertTrue(variants.all { it != base })
        assertEquals(variants.size, variants.toSet().size)
    }

    @Test fun explicitOtherGenerationIdentityIsNotOverriddenByTheSharedCodec() {
        val codec = JsonPatternSpeakingWireCodec()
        val first = JSONObject(codec.generationRequest(chapter.chapterId, chapter.sentences[0].textEn, chapter.sentences[0].textZh, pattern, "explicit-first"))
        val second = JSONObject(codec.generationRequest(chapter.chapterId, chapter.sentences[0].textEn, chapter.sentences[0].textZh, pattern, "explicit-second"))
        assertEquals("explicit-first", first.getString("commandId"))
        assertEquals("explicit-second", second.getString("commandId"))
        // Independent review uses the default: its distinct generation must not borrow LR07 identity.
        val independentA = JSONObject(codec.generationRequest(chapter.chapterId, chapter.sentences[0].textEn, chapter.sentences[0].textZh, pattern)).getString("commandId")
        val independentB = JSONObject(codec.generationRequest(chapter.chapterId, chapter.sentences[0].textEn, chapter.sentences[0].textZh, pattern)).getString("commandId")
        assertNotEquals(independentA, independentB)
    }

    @Test fun unsuccessfulStatusCannotMasqueradeAsAReadyPracticeSet() = runBlocking {
        val repository = AndroidPatternSpeakingRepository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest) = HttpResponse(200, readyBody().replace("READY", "FAILED"))
        }, session("account"), chapter, noCapture)
        assertEquals(PatternPracticeGenerationResult.Failed, repository.generate(pattern))
    }

    private fun session(account: String) = SessionRuntime().apply { update("isolated-test-token", account) }
    private fun readyBody() = """{"status":"READY","patternId":"p1","examples":[{"exampleId":"e1","sceneZh":"旧习惯","aiPromptEn":"What did you do?","targetResponseEn":"I used to read.","targetResponseZh":"我过去常常阅读。"}]}"""
}
