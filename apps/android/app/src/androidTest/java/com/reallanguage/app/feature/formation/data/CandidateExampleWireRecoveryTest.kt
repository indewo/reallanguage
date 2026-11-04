package com.reallanguage.app.feature.formation.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.safety.domain.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real JSON, private test-only encrypted files and real temporary Android Keystore keys; 0 AI. */
class CandidateExampleWireRecoveryTest {
    private val codec = JsonLearningTargetWireCodec()
    private val examples = listOf(CandidateExample("She drinks tea at home.", "她在家喝茶。"),
        CandidateExample("We take a walk after lunch.", "我们午饭后散步。"))

    @Test fun realHttpCodecKeepsEachTargetsOwnExampleWithoutChangingItsRequestOrSourceEvidence() = runBlocking {
        var requests = 0
        val snapshot = sample()
        val runtime = SessionRuntime().apply { update("isolated-example-token", "isolated-example-owner") }
        val repository = HttpLearningTargetRepository(HttpTransport { request ->
            requests++
            val body = JSONObject(requireNotNull(request.body))
            assertEquals("stable-candidate-command", body.getString("commandId"))
            assertFalse(body.has("candidateExample"))
            assertEquals("I drink tea.", body.getJSONArray("chapters").getJSONObject(0)
                .getJSONArray("sentences").getJSONObject(0).getString("textEn"))
            HttpResponse(200, wire().toString())
        }, runtime, snapshot.englishChapters, snapshot.selectedCandidateIds, "stable-candidate-command")
        val result = repository.extract() as LearningTargetExtractionResult.Extracted
        assertEquals(examples, result.targets.map { it.candidateExample })
        assertEquals(listOf("u1", "u2"), result.targets.map { it.unitId })
        assertEquals(snapshot.targets.map { it.sourceEvidence }, result.targets.map { it.sourceEvidence })
        assertEquals(1, requests)
    }

    @Test fun nonNullExampleRequiresOnlyTwoNonBlankRealStringsAndDoesNotCoercePartialData() {
        val invalid = listOf<Any>(42, true, "not-an-object", JSONArray(), JSONObject(),
            JSONObject().put("textEn", "A sample."), JSONObject().put("textZh", "示例。"),
            JSONObject().put("textEn", 42).put("textZh", "示例。"),
            JSONObject().put("textEn", "A sample.").put("textZh", JSONObject.NULL),
            JSONObject().put("textEn", " \n\t").put("textZh", "示例。"),
            JSONObject().put("textEn", "A sample.").put("textZh", "\u00a0\u3000"),
            exampleJson(examples[0]).put("sourceEvidence", "must not be accepted"))
        for (value in invalid) {
            val body = wire()
            body.getJSONArray("targets").getJSONObject(0).put("candidateExample", value)
            assertThrows("Malformed candidate example: $value", Exception::class.java) { codec.payload(body.toString()) }
        }
    }

    @Test fun missingAndExplicitNullExamplesRemainAbsentWithoutInventingTheConfirmedSentence() {
        val body = wire()
        body.getJSONArray("targets").getJSONObject(0).remove("candidateExample")
        body.getJSONArray("targets").getJSONObject(1).put("candidateExample", JSONObject.NULL)
        assertEquals(listOf(null, null), codec.payload(body.toString()).targets.map { it.candidateExample })
    }

    @Test fun completeExampleStringsAreTrimmedWithoutChangingTheirWordsOrTargetIdentity() {
        val body = wire()
        body.getJSONArray("targets").getJSONObject(0).put("candidateExample", JSONObject()
            .put("textEn", "  ${examples[0].textEn}\n").put("textZh", "\t${examples[0].textZh}  "))
        val target = codec.payload(body.toString()).targets.first()
        assertEquals("t1", target.targetId)
        assertEquals("u1", target.unitId)
        assertEquals(examples[0], target.candidateExample)
    }

    @Test fun legacyMissingAndNullExamplesDoNotAddANewKeyWhenTheDraftIsSavedAgain() {
        val body = wire()
        body.getJSONArray("targets").getJSONObject(0).remove("candidateExample")
        body.getJSONArray("targets").getJSONObject(1).put("candidateExample", JSONObject.NULL)
        val snapshot = sample().copy(targets = codec.payload(body.toString()).targets)
        val recovery = FormationRecoveryPayloadCodec()
        val encoded = recovery.encode(snapshot)
        val targets = JSONObject(String(encoded, Charsets.UTF_8)).getJSONArray("targets")
        repeat(targets.length()) { index ->
            assertFalse("An absent illustration must not make a legacy draft unreadable by its old strict reader",
                targets.getJSONObject(index).has("candidateExample"))
        }
        assertEquals(snapshot, recovery.decode(encoded))
    }

    @Test fun recoveryCodecRoundTripsExamplesAndAcceptsOldMissingAndNullFields() {
        val snapshot = sample()
        val recovery = FormationRecoveryPayloadCodec()
        assertEquals(snapshot, recovery.decode(recovery.encode(snapshot)))
        val legacy = JSONObject(String(recovery.encode(snapshot), Charsets.UTF_8))
        legacy.getJSONArray("targets").getJSONObject(0).remove("candidateExample")
        legacy.getJSONArray("targets").getJSONObject(1).put("candidateExample", JSONObject.NULL)
        val restored = requireNotNull(recovery.decode(legacy.toString().toByteArray()))
        assertEquals(listOf(null, null), restored.targets.map { it.candidateExample })
        assertEquals(snapshot.targets.map { it.sourceEvidence }, restored.targets.map { it.sourceEvidence })
        for (value in listOf<Any>(42, JSONObject().put("textEn", "A sample."),
            exampleJson(examples[0]).put("textZh", " "), exampleJson(examples[0]).put("extra", true))) {
            val damaged = JSONObject(legacy.toString())
            damaged.getJSONArray("targets").getJSONObject(0).put("candidateExample", value)
            assertNull(recovery.decode(damaged.toString().toByteArray()))
        }
    }

    @Test fun encryptedNewRepositoryRestoresEachExampleWithNoPlaintextFilesAndAccountIsolation() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "candidate-example-$uuid")
        val account = MessageDigest.getInstance("SHA-256").digest("candidate-example-$uuid".toByteArray())
            .joinToString("") { "%02x".format(it) }
        var owner = account
        fun repository() = EncryptedFormationRecoveryRepository(account, FormationRecoveryFileStore(directory,
            FormationPayloadCipher(AndroidFormationPayloadKeys()), { it == owner }))
        try {
            val snapshot = sample()
            assertEquals(FormationRecoverySave.Saved(0), repository().save(snapshot, null))
            assertEquals(FormationRecoveryLoad.Loaded(0, snapshot), repository().load(snapshot.sourceEntryId))
            directory.walkTopDown().filter { it.isFile }.forEach { file ->
                val bytesAsText = String(file.readBytes(), Charsets.UTF_8)
                examples.forEach { assertFalse(bytesAsText.contains(it.textEn)); assertFalse(bytesAsText.contains(it.textZh)) }
            }
            owner = "another-isolated-owner"
            assertEquals(FormationRecoveryLoad.Unavailable, repository().load(snapshot.sourceEntryId))
            owner = account
            assertEquals(FormationRecoveryLoad.Loaded(0, snapshot), repository().load(snapshot.sourceEntryId))
        } finally {
            AndroidFormationPayloadKeys().deleteAccount(account)
            check(directory.parentFile.canonicalFile == context.cacheDir.canonicalFile)
            check(directory.name == "candidate-example-$uuid")
            check(!directory.exists() || directory.deleteRecursively())
        }
    }

    private fun exampleJson(example: CandidateExample) = JSONObject().put("textEn", example.textEn).put("textZh", example.textZh)
    private fun wire(): JSONObject = JSONObject().put("learningScopes", JSONArray().put("CET_4"))
        .put("queryableExpressions", JSONArray()).put("targets", JSONArray(sample().targets.map { target ->
            JSONObject().put("targetId", target.targetId).put("unitId", target.unitId).put("display", target.display)
                .put("explanationZh", target.explanationZh).put("type", target.type.name).put("structureParts", JSONArray())
                .put("relatedVocabulary", JSONArray()).put("sourceEvidence", target.sourceEvidence!!.toJson())
                .put("candidateExample", exampleJson(requireNotNull(target.candidateExample)))
        }))
    private fun sample(): FormationRecoverySnapshot {
        val targets = listOf(
            LearningTarget("t1", "u1", "tea", "茶", LearningTargetType.WORD, sourceEvidence = SourceEvidence(1, 8, 11, "tea", "tea", listOf("tea")), candidateExample = examples[0]),
            LearningTarget("t2", "u2", "walk", "散步", LearningTargetType.WORD, sourceEvidence = SourceEvidence(1, 9, 13, "walk", "walk", listOf("walk")), candidateExample = examples[1]),
        )
        return FormationRecoverySnapshot("candidate-source", 0, FormationRecoveryPhase.LEARNING_TARGET_SELECTION,
            ConfirmedSource("我喝茶。\n我散步。", SourceType.DIARY, SourceContentShape.ARTICLE), "隔离例句", FormationSourceOrigin.Diary,
            units = listOf(SemanticUnit("u1", 0, "我喝茶。"), SemanticUnit("u2", 1, "我散步。")), selectedUnitIds = listOf("u1", "u2"),
            chapters = listOf(ChapterDraft("c1", listOf("u1", "u2"))),
            englishChapters = listOf(
                EnglishChapterDraft("c1", listOf(
                    EnglishSentenceDraft("u1", "我喝茶。", true, listOf(EnglishCandidate("e1", "I drink tea."))),
                    EnglishSentenceDraft("u2", "我散步。", true, listOf(EnglishCandidate("e2", "I take a walk."))),
                )),
            ),
            selectedCandidateIds = mapOf("u1" to "e1", "u2" to "e2"), targets = targets, selectedTargetIds = setOf("t1", "t2"),
            targetSelectionEntryId = "candidate-selection", targetSentenceIndex = 1)
    }
}
