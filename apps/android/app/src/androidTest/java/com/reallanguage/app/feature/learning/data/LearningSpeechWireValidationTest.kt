package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.learning.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real Android JSONObject and production codecs, with neutral in-memory JSON only.
 * Bounds/types are packages/contracts speech-result schemas, not new confidence/mastery thresholds.
 * Parser exceptions or Failed are permitted rejection paths; Inconclusive is a real delivery, not a parse failure.
 * No repository, disk, account, microphone, transport, provider or pronunciation-quality evidence. */
class LearningSpeechWireValidationTest {
    private val read: ReadSpeakWireCodec = JsonReadSpeakWireCodec()
    private val full: FullSentenceSpeakingWireCodec = JsonFullSentenceSpeakingWireCodec()
    private val pattern: PatternSpeakingWireCodec = JsonPatternSpeakingWireCodec()
    private val scene: SceneResponseWireCodec = JsonSceneResponseWireCodec()

    @Test fun readFeedbackRejectsMalformedRequiredFieldsInsteadOfMarkingContentComplete() {
        rejectMutations("Read", READING, readingMutations(), read::result)
    }

    @Test fun fullTrainingRejectsUnknownActionAndMalformedRequiredFeedbackFields() {
        rejectMutations("Full training", READING, readingMutations(), full::trainingResult)
    }

    @Test fun fullAndSceneTranscriptionsRequireActualStringsAndExplicitBoundedOrNullConfidence() {
        val mutations = strings("transcript", allowEmpty = true) +
            cases("lowConfidenceSegments", JSONObject.NULL, Missing, "segment", JSONObject(),
                JSONArray().put(7), JSONArray().put(true), JSONArray().put(JSONObject.NULL), JSONArray().put(JSONObject())) +
            cases("confidence", "0.9", -0.01, 1.01, true, JSONObject(), Missing)
        val rejected = listOf("Full" to full::transcriptionResult, "Scene" to scene::transcriptionResult)
            .flatMap { (name, decode) -> acceptedMutations(name, TRANSCRIPTION, mutations, decode) }
        assertEquals("Malformed transcription must not become confirmation-ready", emptyList<String>(), rejected)
    }

    @Test fun fullFinalRejectsCoercedOrOutOfRangePassEvidence() {
        rejectMutations("Full final", FULL_FINAL,
            cases("decision", "READY", "pass", 1, JSONObject.NULL, Missing) +
                cases("completenessScore", "90", 90.5, -1, 101, 4_294_967_386L, JSONObject.NULL, true, Missing) +
                booleans("coreMeaningRecognizable") + booleans("pronunciationIntelligible") +
                strings("feedbackZh") + confidence("pronunciationConfidence"), full::evaluationResult)
    }

    @Test fun patternFeedbackRejectsUnknownStatusAndCoercedRepeatOrTranscript() {
        rejectMutations("Pattern feedback", PATTERN,
            cases("status", "FAILED", "PROCESSING", "ready", 1, JSONObject.NULL, Missing) +
                strings("feedbackZh") + strings("transcript", allowEmpty = true) +
                booleans("repeatRecommended") + confidence("confidence"), pattern::feedbackResult)
    }

    @Test fun sceneTrainingRejectsUnknownStatusInsteadOfTreatingItAsDeliveredFeedback() {
        rejectMutations("Scene training", SCENE_TRAINING,
            cases("status", "FAILED", "PROCESSING", "ready", 1, JSONObject.NULL, Missing) +
                strings("feedbackZh") + confidence("pronunciationConfidence"), scene::feedbackResult)
    }

    @Test fun sceneFinalRejectsCoercedBooleansAndInvalidConfidenceInsteadOfPassCandidate() {
        rejectMutations("Scene final", SCENE_FINAL,
            cases("decision", "READY", "pass", 1, JSONObject.NULL, Missing) +
                booleans("correctTurn") + booleans("targetSentenceComplete") +
                booleans("pronunciationIntelligible") + strings("feedbackZh") +
                confidence("pronunciationConfidence"), scene::evaluationResult)
    }

    @Test fun legitimateBoundariesEmptyTranscriptAndBothTrainingActionsKeepTheirExactValues() {
        for (boundary in listOf(0, 1)) for (action in ReadingFeedbackAction.entries) {
            val body = JSONObject(READING).put("transcript", "").put("contentComplete", false)
                .put("feedbackConfidence", boundary).put("action", action.name).toString()
            val readResult = read.result(body) as ReadSpeakFeedbackResult.Ready
            assertFalse(readResult.contentComplete)
            assertEquals(FEEDBACK, readResult.feedbackZh)
            val training = full.trainingResult(body) as ReadingFeedbackResult.Ready
            assertEquals(action, training.feedback.action)
            assertEquals(FEEDBACK, training.feedback.feedbackZh)
        }
        for (boundary in listOf(0, 1)) {
            val body = JSONObject(TRANSCRIPTION).put("transcript", "")
                .put("lowConfidenceSegments", JSONArray().put("")).put("confidence", boundary).toString()
            listOf(full.transcriptionResult(body), scene.transcriptionResult(body)).forEach {
                val value = (it as SpeakingTranscriptionResult.Ready).value
                assertEquals("", value.transcript)
                assertEquals(listOf(""), value.lowConfidenceSegments)
                assertEquals(boundary.toDouble(), requireNotNull(value.confidence), 0.0)
            }
        }
        for (decision in SpeakingEvaluationDecision.entries) for (score in listOf(0, 100)) {
            val body = JSONObject(FULL_FINAL).put("decision", decision.name).put("completenessScore", score)
                .put("coreMeaningRecognizable", false).put("pronunciationIntelligible", false).toString()
            val value = (full.evaluationResult(body) as SpeakingEvaluationResult.Ready).candidate
            assertEquals(decision, value.decision)
            assertEquals(score, value.completenessScore)
            assertFalse(value.coreMeaningRecognizable)
            assertFalse(value.pronunciationIntelligible)
        }
        for (decision in SceneEvaluationDecision.entries) {
            val body = JSONObject(SCENE_FINAL).put("decision", decision.name).put("correctTurn", false)
                .put("targetSentenceComplete", false).put("pronunciationIntelligible", false).toString()
            val value = (scene.evaluationResult(body) as SceneEvaluationResult.Ready).candidate
            assertEquals(decision, value.decision)
            assertFalse(value.correctTurn)
            assertFalse(value.targetSentenceComplete)
            assertFalse(value.pronunciationIntelligible)
        }
        for (repeat in listOf(false, true)) {
            val value = (pattern.feedbackResult(JSONObject(PATTERN).put("repeatRecommended", repeat)
                .put("transcript", "").toString()) as PatternPracticeFeedbackResult.Ready).feedback
            assertEquals(repeat, value.repeatRecommended)
            assertEquals("", value.transcript)
        }
        assertEquals(SceneTrainingFeedbackResult.Ready(FEEDBACK), scene.feedbackResult(SCENE_TRAINING))
    }

    @Test fun forwardCompatibleExtraPropertiesAndStatusOnlyInconclusiveRemainAccepted() {
        // These public schemas use z.object, not strictObject. Unknown properties do not grant results.
        fun extra(body: String) = JSONObject(body).put("futureMetadata", JSONObject().put("version", 2)).toString()
        assertTrue(read.result(extra(READING)) is ReadSpeakFeedbackResult.Ready)
        assertTrue(full.trainingResult(extra(READING)) is ReadingFeedbackResult.Ready)
        assertTrue(full.transcriptionResult(extra(TRANSCRIPTION)) is SpeakingTranscriptionResult.Ready)
        assertTrue(scene.transcriptionResult(extra(TRANSCRIPTION)) is SpeakingTranscriptionResult.Ready)
        assertTrue(full.evaluationResult(extra(FULL_FINAL)) is SpeakingEvaluationResult.Ready)
        assertTrue(pattern.feedbackResult(extra(PATTERN)) is PatternPracticeFeedbackResult.Ready)
        assertTrue(scene.feedbackResult(extra(SCENE_TRAINING)) is SceneTrainingFeedbackResult.Ready)
        assertTrue(scene.evaluationResult(extra(SCENE_FINAL)) is SceneEvaluationResult.Ready)
        val inconclusive = """{"status":"INCONCLUSIVE"}"""
        assertEquals(PatternPracticeFeedbackResult.Inconclusive, pattern.feedbackResult(inconclusive))
        assertEquals(SceneTrainingFeedbackResult.Inconclusive, scene.feedbackResult(inconclusive))
        // In this union variant the discarded READY-only fields are additional, not required evidence.
        val unrelated = JSONObject(inconclusive).put("feedbackZh", 7).put("confidence", "unused").toString()
        assertEquals(PatternPracticeFeedbackResult.Inconclusive, pattern.feedbackResult(unrelated))
        assertEquals(SceneTrainingFeedbackResult.Inconclusive, scene.feedbackResult(unrelated))
    }

    private fun readingMutations() = cases("action", "FAILED", "continue", 1, JSONObject.NULL, Missing) +
        strings("feedbackZh") + strings("transcript", allowEmpty = true) +
        booleans("contentComplete") + confidence("feedbackConfidence")
    private fun booleans(key: String) = cases(key, "true", "false", 1, 0, JSONObject.NULL, Missing)
    private fun strings(key: String, allowEmpty: Boolean = false) =
        cases(key, 7, true, JSONObject.NULL, JSONObject(), JSONArray(), Missing) +
            if (allowEmpty) emptyList() else cases(key, "")
    private fun confidence(key: String) = cases(key, "0.9", -0.01, 1.01, true, JSONObject.NULL, Missing)
    private data class Mutation(val label: String, val key: String, val value: Any)
    private object Missing
    private fun cases(key: String, vararg values: Any) = values.mapIndexed { index, value ->
        Mutation("$key[$index]", key, value)
    }
    private fun rejectMutations(name: String, body: String, mutations: List<Mutation>, decode: (String) -> Any) {
        assertEquals("Malformed required evidence must fail closed", emptyList<String>(),
            acceptedMutations(name, body, mutations, decode))
    }
    private fun acceptedMutations(name: String, body: String, mutations: List<Mutation>, decode: (String) -> Any): List<String> =
        mutations.filterNot { mutation ->
            val malformed = JSONObject(body).apply {
                if (mutation.value === Missing) remove(mutation.key) else put(mutation.key, mutation.value)
            }.toString()
            rejected { decode(malformed) }
        }.map { "$name:${it.label}" }
    private fun rejected(decode: () -> Any): Boolean {
        val result = try { decode() } catch (_: Exception) { return true }
        return result in setOf(ReadSpeakFeedbackResult.Failed, ReadingFeedbackResult.Failed,
            SpeakingTranscriptionResult.Failed, SpeakingEvaluationResult.Failed,
            PatternPracticeFeedbackResult.Failed, SceneTrainingFeedbackResult.Failed, SceneEvaluationResult.Failed)
    }
    private companion object {
        const val FEEDBACK = "隔离语音反馈。"
        const val READING = """{"feedbackZh":"隔离语音反馈。","transcript":"I drink tea.","contentComplete":true,"feedbackConfidence":0.9,"action":"CONTINUE"}"""
        const val TRANSCRIPTION = """{"transcript":"I drink tea.","lowConfidenceSegments":[],"confidence":0.9}"""
        const val FULL_FINAL = """{"decision":"PASS","completenessScore":90,"coreMeaningRecognizable":true,"pronunciationIntelligible":true,"feedbackZh":"隔离语音反馈。","pronunciationConfidence":0.9}"""
        const val PATTERN = """{"status":"READY","transcript":"I drink tea.","feedbackZh":"隔离语音反馈。","repeatRecommended":false,"confidence":0.9}"""
        const val SCENE_TRAINING = """{"status":"READY","feedbackZh":"隔离语音反馈。","pronunciationConfidence":0.9}"""
        const val SCENE_FINAL = """{"decision":"PASS","correctTurn":true,"targetSentenceComplete":true,"pronunciationIntelligible":true,"feedbackZh":"隔离语音反馈。","pronunciationConfidence":0.9}"""
    }
}
