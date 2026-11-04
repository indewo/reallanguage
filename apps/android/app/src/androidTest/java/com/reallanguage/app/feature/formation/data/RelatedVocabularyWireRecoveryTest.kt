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

/** Real wire/recovery codecs and isolated encrypted files/Keystore, never the user's DB or AI. */
class RelatedVocabularyWireRecoveryTest {
    private val codec = JsonLearningTargetWireCodec()
    private val item = RelatedVocabularyItem("explanation-tea", "u1", "tea", "茶", LearningTargetType.WORD,
        SourceEvidence(1, 8, 11, "tea", "tea", listOf("tea")))
    private val pattern = LearningTarget("pattern", "u1", "drink + noun", "喝某物", LearningTargetType.PATTERN,
        structureParts = listOf("drink", "noun"), relatedVocabulary = listOf("tea"),
        sourceEvidence = SourceEvidence(1, 2, 11, "drink tea", "drink + noun", listOf("drink tea")),
        relatedVocabularyItems = listOf(item), relatedVocabularyItemRefs = listOf(item.itemRef))
    private val word = LearningTarget("word", "u1", "tea", "茶", LearningTargetType.WORD, sourceEvidence = item.sourceEvidence,
        relatedVocabularyItems = emptyList(), relatedVocabularyItemRefs = emptyList())

    @Test fun realHttpRetainsCurrentMeaningIdentityAndDoesNotAddARequestOrSelectedTarget() = runBlocking {
        var count = 0
        val result = repository(wire()) { request ->
            count++
            val body = JSONObject(request)
            assertEquals("stable-related-command", body.getString("commandId"))
            assertFalse(body.has("relatedVocabularyItems"))
        }.extract() as LearningTargetExtractionResult.Extracted
        assertEquals(listOf(pattern, word).map { it.relatedVocabularyItems }, result.targets.map { it.relatedVocabularyItems })
        assertEquals(listOf(item.itemRef), result.targets.first().relatedVocabularyItemRefs)
        assertEquals(listOf("pattern", "word"), result.targets.map { it.targetId })
        assertEquals(1, count)
    }

    @Test fun wireRejectsPartialGroupsCoercionOrAdditionalItemFieldsInsteadOfDroppingEvidence() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.remove("relatedVocabularyItems") }, { it.remove("relatedVocabularyItemRefs") },
            { it.put("relatedVocabularyItems", JSONObject.NULL) },
            { it.put("relatedVocabularyItemRefs", true) },
            { it.put("relatedVocabularyItems", JSONArray().put(42)) },
            { it.getJSONArray("relatedVocabularyItems").getJSONObject(0).put("senseZh", 42) },
            { it.getJSONArray("relatedVocabularyItems").getJSONObject(0).put("senseZh", "\u3000") },
            { it.getJSONArray("relatedVocabularyItems").getJSONObject(0).remove("unitId") },
            { it.getJSONArray("relatedVocabularyItems").getJSONObject(0).put("untrustedExtra", true) },
            { it.getJSONArray("relatedVocabularyItems").getJSONObject(0).put("sourceEvidence", JSONObject.NULL) },
            { it.getJSONArray("relatedVocabularyItems").getJSONObject(0).put("unitType", "PATTERN") },
            { it.put("relatedVocabularyItemRefs", JSONArray().put(42)) },
            { it.put("relatedVocabularyItemRefs", JSONArray().put(item.itemRef).put(item.itemRef)) },
            { it.put("relatedVocabulary", JSONArray().put(42)) },
        )
        mutations.forEachIndexed { index, mutate ->
            val payload = wire()
            mutate(payload.getJSONArray("targets").getJSONObject(0))
            assertThrows("Invalid related group case $index", Exception::class.java) { codec.payload(payload.toString()) }
        }
        val halfNullLegacy = wire().getJSONArray("targets").getJSONObject(0)
        halfNullLegacy.remove("relatedVocabularyItems")
        halfNullLegacy.put("relatedVocabularyItemRefs", JSONObject.NULL)
        assertThrows(Exception::class.java) { decodeRelatedVocabularyGroup(halfNullLegacy) }
    }

    @Test fun repositoryRejectsWrongSentenceBadSpanAndConflictingUnselectedVocabulary() = runBlocking {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONArray("relatedVocabularyItems").getJSONObject(0).put("unitId", "u2") },
            { it.getJSONArray("relatedVocabularyItems").getJSONObject(0).put("display", "drink") },
            { it.getJSONArray("relatedVocabularyItems").getJSONObject(0).put("sourceEvidence",
                SourceEvidence(1, 9, 11, "ea", "tea", listOf("ea")).toJson()) },
            { it.put("relatedVocabulary", JSONArray().put("water")) },
        )
        mutations.forEachIndexed { index, mutate ->
            val body = wire()
            mutate(body.getJSONArray("targets").getJSONObject(0))
            assertEquals("Current-sentence rejection $index", LearningTargetExtractionResult.Failed, repository(body).extract())
        }
        val conflict = wire()
        conflict.getJSONArray("targets").getJSONObject(1).put("explanationZh", "晚餐")
        assertEquals(LearningTargetExtractionResult.Failed, repository(conflict).extract())
    }

    @Test fun realWireRejectsOverlongRefsAndDuplicateOccurrencesWithDifferentRefs() {
        val overlong = wire()
        overlong.getJSONArray("targets").getJSONObject(0).apply {
            getJSONArray("relatedVocabularyItems").getJSONObject(0).put("itemRef", "r".repeat(129))
            put("relatedVocabularyItemRefs", JSONArray().put("r".repeat(129)))
        }
        assertThrows(Exception::class.java) { codec.payload(overlong.toString()) }
        val duplicate = wire()
        duplicate.getJSONArray("targets").getJSONObject(0).apply {
            getJSONArray("relatedVocabularyItems").put(itemJson(item.copy(itemRef = "r-duplicate")))
            put("relatedVocabularyItemRefs", JSONArray().put(item.itemRef).put("r-duplicate"))
            put("relatedVocabulary", JSONArray().put("tea").put("tea"))
        }
        assertThrows(Exception::class.java) { codec.payload(duplicate.toString()) }
    }

    @Test fun sharedGroupCodecPreservesRawValuesAndReferenceOrderWithoutCreatingTargetIdentity() {
        val drink = item.copy(itemRef = "r-drink", display = "drink", senseZh = " 喝 ",
            sourceEvidence = SourceEvidence(1, 2, 7, "drink", "drink", listOf("drink")))
        val current = pattern.copy(relatedVocabularyItems = listOf(item, drink),
            relatedVocabularyItemRefs = listOf(drink.itemRef, item.itemRef), relatedVocabulary = listOf("drink", "tea"))
        val json = JSONObject()
        encodeRelatedVocabularyGroup(current, json)
        assertEquals(current.relatedVocabularyItems to current.relatedVocabularyItemRefs, decodeRelatedVocabularyGroup(json))
        assertFalse(json.has("selectedTargetIds"))
        assertEquals(" 喝 ", decodeRelatedVocabularyGroup(json).first!!.last().senseZh)
    }

    @Test fun legacyMissingAndDoubleNullDoNotAddGroupKeysOrInventMeaningsDuringRecovery() {
        val body = wire()
        val targets = body.getJSONArray("targets")
        targets.getJSONObject(0).apply { remove("relatedVocabularyItems"); remove("relatedVocabularyItemRefs") }
        targets.getJSONObject(1).apply {
            put("relatedVocabularyItems", JSONObject.NULL); put("relatedVocabularyItemRefs", JSONObject.NULL)
        }
        val legacy = sample().copy(targets = codec.payload(body.toString()).targets)
        assertTrue(legacy.targets.all { it.relatedVocabularyItems == null && it.relatedVocabularyItemRefs == null })
        val recovery = FormationRecoveryPayloadCodec()
        val bytes = recovery.encode(legacy)
        val encodedTargets = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("targets")
        repeat(encodedTargets.length()) {
            assertFalse(encodedTargets.getJSONObject(it).has("relatedVocabularyItems"))
            assertFalse(encodedTargets.getJSONObject(it).has("relatedVocabularyItemRefs"))
        }
        assertEquals(legacy, recovery.decode(bytes))
        assertEquals(listOf("tea"), requireNotNull(recovery.decode(bytes)).targets.first().relatedVocabulary)
    }

    @Test fun recoveryRejectsDamagedGroupAndKeepsExplanationsWhenOnlyThePatternIsSelected() {
        val recovery = FormationRecoveryPayloadCodec()
        val snapshot = sample()
        val bytes = recovery.encode(snapshot)
        assertEquals(snapshot, recovery.decode(bytes))
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        json.getJSONArray("targets").getJSONObject(0).getJSONArray("relatedVocabularyItems")
            .getJSONObject(0).put("senseZh", " ")
        assertNull(recovery.decode(json.toString().toByteArray()))
        assertEquals(setOf("pattern"), requireNotNull(recovery.decode(bytes)).selectedTargetIds)
    }

    @Test fun newEncryptedRepositoryRestoresUnselectedWordExplanationWithNoPlaintextOrWrongOwnerRead() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "related-vocabulary-$uuid")
        val account = MessageDigest.getInstance("SHA-256").digest("related-vocabulary-$uuid".toByteArray())
            .joinToString("") { "%02x".format(it) }
        var owner = account
        fun newRepository() = EncryptedFormationRecoveryRepository(account, FormationRecoveryFileStore(directory,
            FormationPayloadCipher(AndroidFormationPayloadKeys()), { it == owner }))
        try {
            val snapshot = sample()
            assertEquals(FormationRecoverySave.Saved(0), newRepository().save(snapshot, null))
            assertEquals(FormationRecoveryLoad.Loaded(0, snapshot), newRepository().load(snapshot.sourceEntryId))
            directory.walkTopDown().filter { it.isFile }.forEach {
                val text = String(it.readBytes(), Charsets.UTF_8)
                assertFalse(text.contains("explanation-tea")); assertFalse(text.contains("喝某物"))
            }
            owner = "another-isolated-owner"
            assertEquals(FormationRecoveryLoad.Unavailable, newRepository().load(snapshot.sourceEntryId))
            owner = account
            assertEquals(FormationRecoveryLoad.Loaded(0, snapshot), newRepository().load(snapshot.sourceEntryId))
        } finally {
            AndroidFormationPayloadKeys().deleteAccount(account)
            check(directory.parentFile.canonicalFile == context.cacheDir.canonicalFile)
            check(directory.name == "related-vocabulary-$uuid")
            check(!directory.exists() || directory.deleteRecursively())
        }
    }

    private fun repository(body: JSONObject, request: (String) -> Unit = {}) = HttpLearningTargetRepository(
        HttpTransport { request(requireNotNull(it.body)); HttpResponse(200, body.toString()) },
        SessionRuntime().apply { update("isolated-related-token", "isolated-related-owner") },
        sample().englishChapters, sample().selectedCandidateIds, "stable-related-command")
    private fun itemJson(value: RelatedVocabularyItem) = JSONObject().put("itemRef", value.itemRef).put("unitId", value.unitId)
        .put("display", value.display).put("senseZh", value.senseZh).put("unitType", value.unitType.name)
        .put("sourceEvidence", value.sourceEvidence.toJson())
    private fun wire() = JSONObject().put("learningScopes", JSONArray()).put("queryableExpressions", JSONArray())
        .put("targets", JSONArray(listOf(pattern, word).map { target ->
            JSONObject().put("targetId", target.targetId).put("unitId", target.unitId).put("display", target.display)
                .put("explanationZh", target.explanationZh).put("type", target.type.name)
                .put("structureParts", JSONArray(target.structureParts)).put("relatedVocabulary", JSONArray(target.relatedVocabulary))
                .put("sourceEvidence", target.sourceEvidence!!.toJson())
                .put("relatedVocabularyItems", JSONArray(target.relatedVocabularyItems!!.map(::itemJson)))
                .put("relatedVocabularyItemRefs", JSONArray(target.relatedVocabularyItemRefs!!))
        }))
    private fun sample() = FormationRecoverySnapshot("related-source", 0, FormationRecoveryPhase.LEARNING_TARGET_SELECTION,
        ConfirmedSource("我喝茶。", SourceType.DIARY, SourceContentShape.ARTICLE), "隔离关联词", FormationSourceOrigin.Diary,
        units = listOf(SemanticUnit("u1", 0, "我喝茶。")), selectedUnitIds = listOf("u1"),
        chapters = listOf(ChapterDraft("c1", listOf("u1"))),
        englishChapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "我喝茶。", true,
            listOf(EnglishCandidate("e1", "I drink tea.")))))), selectedCandidateIds = mapOf("u1" to "e1"),
        targets = listOf(pattern, word), selectedTargetIds = setOf("pattern"), targetSelectionEntryId = "related-selection")
}
