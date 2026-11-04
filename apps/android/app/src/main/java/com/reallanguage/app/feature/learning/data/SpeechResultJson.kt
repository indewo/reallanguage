package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.learning.domain.ReadingFeedbackAction
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackAspect
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackDetail
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackEvidence
import com.reallanguage.app.feature.learning.domain.SpeakingTranscription
import org.json.JSONArray
import org.json.JSONObject

// Mirror the existing packages/contracts speech result schemas without JSONObject's coercions.
// Unknown additional fields remain forward-compatible; required evidence cannot be defaulted.
internal fun JSONObject.speechString(key: String, allowEmpty: Boolean = false): String {
    val value = get(key) as? String ?: error("Invalid speech string: $key")
    require(allowEmpty || value.isNotEmpty()) { "Empty speech string: $key" }
    return value
}

internal fun JSONObject.speechBoolean(key: String): Boolean =
    get(key) as? Boolean ?: error("Invalid speech boolean: $key")

private fun JSONObject.speechNumber(key: String, maximum: Double): Double {
    val value = (get(key) as? Number)?.toDouble() ?: error("Invalid speech number: $key")
    require(value.isFinite() && value >= 0.0 && value <= maximum) { "Out-of-range speech number: $key" }
    return value
}

internal fun JSONObject.speechConfidence(key: String): Double = speechNumber(key, 1.0)

internal fun JSONObject.speechCompletenessScore(): Int {
    val value = speechNumber("completenessScore", 100.0)
    require(value % 1.0 == 0.0) { "Non-integer completeness score" }
    return value.toInt()
}

internal data class ReadingFeedbackWire(
    val feedbackZh: String,
    val contentComplete: Boolean,
    val feedbackConfidence: Double,
    val feedbackItems: List<ReadingFeedbackDetail>,
    val action: ReadingFeedbackAction,
)

internal fun JSONObject.readingFeedbackWire(): ReadingFeedbackWire {
    speechString("transcript", allowEmpty = true)
    val confidence = speechConfidence("feedbackConfidence")
    val feedbackItems = optJSONArray("feedbackItems")?.let { items ->
        List(items.length()) { index ->
            val item = items.get(index) as? JSONObject ?: error("Invalid reading feedback item")
            val targetText = when {
                !item.has("targetText") -> null
                item.get("targetText") === JSONObject.NULL -> null
                else -> item.speechString("targetText")
            }
            ReadingFeedbackDetail(
                aspect = ReadingFeedbackAspect.valueOf(item.speechString("aspect")),
                targetText = targetText,
                observationZh = item.speechString("observationZh"),
                suggestionZh = item.speechString("suggestionZh"),
                evidence = ReadingFeedbackEvidence.valueOf(item.speechString("evidence")),
            )
        }.also { parsed ->
            require(parsed.size == 3)
            require(parsed.map { it.aspect } == listOf(
                ReadingFeedbackAspect.WORD_OR_PHRASE,
                ReadingFeedbackAspect.RHYTHM,
                ReadingFeedbackAspect.COMPLETENESS,
            ))
        }
    }.orEmpty()
    return ReadingFeedbackWire(
        speechString("feedbackZh"),
        speechBoolean("contentComplete"),
        confidence,
        feedbackItems,
        ReadingFeedbackAction.valueOf(speechString("action")),
    )
}

internal fun JSONObject.speakingTranscriptionWire(): SpeakingTranscription {
    val transcript = speechString("transcript", allowEmpty = true)
    val segments = get("lowConfidenceSegments") as? JSONArray ?: error("Invalid speech segments")
    val strings = List(segments.length()) { index ->
        segments.get(index) as? String ?: error("Invalid speech segment")
    }
    // JSONObject.isNull also returns true for a missing property, which the contract forbids.
    require(has("confidence")) { "Missing speech confidence" }
    val confidence = if (get("confidence") === JSONObject.NULL) null else speechConfidence("confidence")
    return SpeakingTranscription(transcript, strings, confidence)
}
