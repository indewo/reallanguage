package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.safety.domain.*
import com.reallanguage.app.feature.scene.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FormationRecoveryPayloadCodecTest {
    private val codec = FormationRecoveryPayloadCodec()

    @Test fun damagedStructureCannotBeDecodedOrReencodedAsAcceptedFormationContent() {
        val valid = patternSample(listOf("drink", "名词"))
        val encoded = String(codec.encode(valid), Charsets.UTF_8)
        for (parts in listOf(JSONArray().put(""), JSONArray().put(" "), JSONArray().put("drink").put("\n\t"),
            JSONArray().put("\u00a0\u3000"), JSONArray(), JSONArray().put(42))) {
            val damaged = JSONObject(encoded)
            damaged.getJSONArray("targets").getJSONObject(0).put("structureParts", parts)
            assertNull("Corrupt structure is not recoverable accepted content: $parts", codec.decode(damaged.toString().toByteArray(Charsets.UTF_8)))
        }
        assertThrows(IllegalArgumentException::class.java) { codec.encode(patternSample(listOf(" "))) }
    }

    @Test fun legacyAbsentStructureIsPreservedAsAbsentAndValidModernPartsRoundTripVerbatim() {
        val valid = patternSample(listOf(" drink ", "名词"))
        assertEquals(valid, codec.decode(codec.encode(valid)))
        val legacy = patternSample(emptyList()).let { it.copy(targets = it.targets.map { target -> target.copy(sourceEvidence = null) }) }
        assertEquals(legacy, codec.decode(codec.encode(legacy)))
        assertTrue(requireNotNull(codec.decode(codec.encode(legacy))).targets.single().structureParts.isEmpty())
        // A required JSON field being removed is malformed, not permission to invent it.
        val missing = JSONObject(String(codec.encode(legacy), Charsets.UTF_8))
        missing.getJSONArray("targets").getJSONObject(0).remove("structureParts")
        assertNull(codec.decode(missing.toString().toByteArray(Charsets.UTF_8)))
    }

    @Test fun preservesCompleteSourceOutsideChapterUnitsChoicesEvidenceAndReusedRefsOrder() {
        val snapshot = sample()
        assertEquals(snapshot, codec.decode(codec.encode(snapshot)))
    }

    @Test fun missingLegacyReusedRefsReadsAsEmptyButWrongTypesAndDuplicatesAreRejected() {
        val original = JSONObject(String(codec.encode(sample()), Charsets.UTF_8))
        fun candidate(json: JSONObject) = json.getJSONArray("englishChapters").getJSONObject(0)
            .getJSONArray("sentences").getJSONObject(0).getJSONArray("candidates").getJSONObject(0)
        candidate(original).remove("reusedKnowledgeRefs")
        // A modern target reference cannot survive without its selected candidate's reference evidence.
        assertNull(codec.decode(original.toString().toByteArray()))
        original.getJSONArray("targets").getJSONObject(0).remove("knownKnowledgeRef")
        val legacy = requireNotNull(codec.decode(original.toString().toByteArray()))
        assertTrue(legacy.englishChapters.single().sentences.single().candidates.first().reusedKnowledgeRefs.isEmpty())
        listOf<Any>(JSONObject.NULL, 42, "VOCABULARY:tea", JSONArray().put(42), JSONArray().put("VOCABULARY:tea").put("VOCABULARY:tea")).forEach { bad ->
            val malformed = JSONObject(original.toString())
            candidate(malformed).put("reusedKnowledgeRefs", bad)
            assertNull(codec.decode(malformed.toString().toByteArray()))
        }
        val originalTarget = JSONObject(String(codec.encode(sample()), Charsets.UTF_8))
        originalTarget.getJSONArray("targets").getJSONObject(0).remove("knownKnowledgeRef")
        assertNull(requireNotNull(codec.decode(originalTarget.toString().toByteArray())).targets.single().knownKnowledgeRef)
        originalTarget.getJSONArray("targets").getJSONObject(0).put("knownKnowledgeRef", JSONObject.NULL)
        assertNull(requireNotNull(codec.decode(originalTarget.toString().toByteArray())).targets.single().knownKnowledgeRef)
        listOf<Any>(42, "OTHER:tea", JSONArray().put("VOCABULARY:tea")).forEach { bad ->
            val malformed = JSONObject(originalTarget.toString())
            malformed.getJSONArray("targets").getJSONObject(0).put("knownKnowledgeRef", bad)
            assertNull(codec.decode(malformed.toString().toByteArray()))
        }
    }

    @Test fun recordingAndExplicitUnavailableOriginsAreNotGuessedIntoDiary() {
        val recording = sample().copy(source = sample().source.copy(sourceType = SourceType.RECORDING),
            origin = FormationSourceOrigin.Recording("recording-isolated", "transcript-isolated"))
        assertEquals(recording, codec.decode(codec.encode(recording)))
        val missing = recording.copy(origin = FormationSourceOrigin.Unavailable("review-isolated"))
        assertEquals(missing, codec.decode(codec.encode(missing)))
    }

    @Test fun sceneDomainEditorSnapshotRetainsRealAnswersAndCurrentDraftNotAnAndroidRoute() {
        val session = SceneQuestionSession(SceneStart.DirectDescription("隔离场景"), SceneContentType.ARTICLE,
            listOf(SceneAnswer("q1", "确认事实")), listOf("确认事实"), emptyList(), "operation-isolated")
        val draft = SceneGeneratedDraft("draft-isolated", SceneContentType.ARTICLE,
            listOf(SceneDraftUnit("u1", null, "今天喝茶。", listOf("q1")), SceneDraftUnit("u2", null, "还有一句未入章。", emptyList())))
        val scene = sample().copy(source = sample().source.copy(sourceType = SourceType.AI_SCENE), origin = FormationSourceOrigin.Scene(session, draft))
        assertEquals(scene, codec.decode(codec.encode(scene)))
    }

    @Test fun realSceneQuestionsSurviveExactRoundTripForEveryEntryAndContentType() {
        val starts = listOf(
            SceneStart.DirectDescription("手机没电后联系朋友说明迟到"),
            SceneStart.Template(PublicSceneTemplates.first { it.templateId == "planning" }),
            SceneStart.Recommendation(RecentSceneRecommendation("recent-1", "向朋友说明迟到", "", emptyList())),
        )
        for (start in starts) for (type in SceneContentType.entries) {
            val session = SceneQuestionSession(start, type,
                listOf(SceneAnswer("setting", "在苏州街联系等我的朋友", "你在哪里，对方是谁？")),
                listOf("手机刚充上电", "我在苏州街"), emptyList(), "scene-op")
            val units = listOf(
                SceneDraftUnit("u1", if (type == SceneContentType.DIALOGUE) SceneSpeaker.USER else null,
                    "不好意思，我刚才手机没电了。", listOf("手机刚充上电")),
                SceneDraftUnit("u2", if (type == SceneContentType.DIALOGUE) SceneSpeaker.OTHER else null,
                    "没事，你现在在哪里？", emptyList()),
                SceneDraftUnit("u3", if (type == SceneContentType.DIALOGUE) SceneSpeaker.USER else null,
                    "我在苏州街。", listOf("我在苏州街")),
            )
            val text = units.joinToString("\n") {
                if (type == SceneContentType.DIALOGUE) "${if (it.speaker == SceneSpeaker.USER) "用户" else "对方"}：${it.textZh}"
                else it.textZh
            }
            val snapshot = FormationRecoverySnapshot("scene-source", 0, FormationRecoveryPhase.SEMANTIC_SPLIT,
                ConfirmedSource(text, SourceType.AI_SCENE, SourceContentShape.valueOf(type.name)), "迟到说明",
                FormationSourceOrigin.Scene(session, SceneGeneratedDraft("draft", type, units)))
            assertTrue(snapshot.isValidForRecovery())
            assertEquals("The production answer includes the actual question; dropping it makes saved/read-back unequal ($start/$type)",
                snapshot, codec.decode(codec.encode(snapshot)))

            // Read the exact legacy format already saved by the affected APK, without
            // erasing it, fabricating a missing question, or discarding known answers.
            val legacy = JSONObject(String(codec.encode(snapshot), Charsets.UTF_8))
            legacy.getJSONObject("origin").getJSONObject("session").getJSONArray("answers")
                .getJSONObject(0).remove("questionZh")
            val legacyDecoded = requireNotNull(codec.decode(legacy.toString().toByteArray()))
            val expectedLegacy = snapshot.copy(origin = FormationSourceOrigin.Scene(
                session.copy(answers = session.answers.map { it.copy(questionZh = null) }),
                SceneGeneratedDraft("draft", type, units)))
            assertEquals(expectedLegacy, legacyDecoded)
            assertEquals(expectedLegacy, codec.decode(codec.encode(legacyDecoded)))
        }
    }

    @Test fun rawTypesUnknownFieldsAndUnknownVersionFailRatherThanBecomingEmptySuccess() {
        val encoded = String(codec.encode(sample()), Charsets.UTF_8)
        listOf("schemaVersion" to 2, "sourceRevision" to "0", "selectedUnitIds" to 1,
            "route" to "ignored-private-route", "sourceTitle" to JSONObject.NULL).forEach { (field, value) ->
            val malformed = JSONObject(encoded).put(field, value)
            assertNull(codec.decode(malformed.toString().toByteArray()))
        }
        assertNull(codec.decode(byteArrayOf(0xc3.toByte(), 0x28)))
    }

    @Test fun twoDifferentSourcesAndSelectionsDoNotShareARecoveredPayload() {
        val first = sample()
        val other = first.copy(sourceEntryId = "another-source", sourceTitle = "另一次内容", selectedTargetIds = emptySet(), fixEntryId = "another-fix")
        val firstBytes = codec.encode(first)
        val otherBytes = codec.encode(other)
        assertFalse(firstBytes.contentEquals(otherBytes))
        assertEquals(first, codec.decode(firstBytes))
        assertEquals(other, codec.decode(otherBytes))
    }

    @Test fun actualSecondFocusPositionRoundTripsWithoutResettingToFirstSentence() {
        val first = sample()
        val two = first.copy(selectedUnitIds = listOf("u1", "u2"), chapters = listOf(ChapterDraft("c1", listOf("u1", "u2"))),
            englishChapters = listOf(first.englishChapters.single().copy(sentences = first.englishChapters.single().sentences +
                EnglishSentenceDraft("u2", "还有一句未入章。", true, listOf(EnglishCandidate("e3", "I read."))))),
            selectedCandidateIds = first.selectedCandidateIds + ("u2" to "e3"), targetSentenceIndex = 1)
        assertEquals(two, codec.decode(codec.encode(two)))
    }

    private fun patternSample(parts: List<String>) = sample().let { snapshot -> snapshot.copy(
        targets = listOf(LearningTarget("t1", "u1", "drink + noun", "喝某物", LearningTargetType.PATTERN,
            structureParts = parts, sourceEvidence = SourceEvidence(1, 2, 11, "drink tea", "drink + noun", listOf("drink tea")))),
    ) }

    private fun sample(): FormationRecoverySnapshot = FormationRecoverySnapshot(
        sourceEntryId = "source-isolated", sourceRevision = 0, phase = FormationRecoveryPhase.FINAL_FIX,
        source = ConfirmedSource("今天喝茶。\n还有一句未入章。", SourceType.DIARY, SourceContentShape.ARTICLE),
        sourceTitle = "隔离日记", origin = FormationSourceOrigin.Diary,
        units = listOf(SemanticUnit("u1", 0, "今天喝茶。", "完整意思"), SemanticUnit("u2", 1, "还有一句未入章。")),
        selectedUnitIds = listOf("u1"), chapters = listOf(ChapterDraft("c1", listOf("u1"))),
        englishChapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "今天喝茶。", true,
            listOf(EnglishCandidate("e1", "I drink tea.", listOf("PATTERN:drink", "VOCABULARY:tea")), EnglishCandidate("e2", "I have tea.")))))),
        selectedCandidateIds = mapOf("u1" to "e1"),
        targets = listOf(LearningTarget("t1", "u1", "tea", "茶", LearningTargetType.WORD, relatedVocabulary = listOf("drink"),
            learningScopes = setOf("HIGH_SCHOOL"), sourceEvidence = SourceEvidence(1, 8, 11, "tea", "tea", listOf("tea")),
            knownKnowledgeRef = "VOCABULARY:tea")),
        queryableExpressions = listOf(QueryableExpression("word:8", "u1", "tea")), selectedTargetIds = setOf("t1"),
        targetSelectionEntryId = "targets-isolated", fixEntryId = "fix-isolated",
    )
}
