package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.feature.formation.domain.CandidateExample
import com.reallanguage.app.feature.formation.domain.isValid
import org.json.JSONObject

/** Legacy absence is not a request to generate. A supplied example must be complete. */
internal fun decodeCandidateExample(raw: Any?, normalize: Boolean = true): CandidateExample? {
    if (raw == null || raw === JSONObject.NULL) return null
    require(raw is JSONObject) { "Invalid candidate example" }
    require(raw.keys().asSequence().toSet() == setOf("textEn", "textZh")) { "Invalid candidate example fields" }
    val english = raw.get("textEn") as? String ?: error("Invalid candidate example English")
    val chinese = raw.get("textZh") as? String ?: error("Invalid candidate example Chinese")
    return CandidateExample(if (normalize) english.trim() else english, if (normalize) chinese.trim() else chinese)
        .also { require(it.isValid()) { "Incomplete candidate example" } }
}

internal fun CandidateExample.toCandidateExampleJson(): JSONObject = JSONObject()
    .put("textEn", textEn).put("textZh", textZh)
