package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.feature.formation.domain.SourceEvidence
import com.reallanguage.app.feature.formation.domain.hasValidStructure
import org.json.JSONArray
import org.json.JSONObject

/** No getString/getInt coercion: malformed explicit evidence is never treated as legacy. */
internal fun decodeSourceEvidence(raw: Any?): SourceEvidence? {
    if (raw == null || raw === JSONObject.NULL) return null
    require(raw is JSONObject)
    require(raw.keys().asSequence().toSet() == setOf("version", "startUtf16", "endUtf16", "surfaceForm", "normalizedForm", "allowedForms"))
    fun integer(key: String): Int {
        val value = raw.get(key)
        require(value is Int || value is Long)
        val number = (value as Number).toLong()
        require(number in 0..Int.MAX_VALUE.toLong())
        return number.toInt()
    }
    fun text(key: String) = raw.get(key).let { require(it is String); it }
    val allowed = raw.get("allowedForms")
    require(allowed is JSONArray)
    return SourceEvidence(integer("version"), integer("startUtf16"), integer("endUtf16"), text("surfaceForm"), text("normalizedForm"),
        List(allowed.length()) { allowed.get(it).let { value -> require(value is String); value } },
    ).also { require(it.hasValidStructure()) }
}

internal fun SourceEvidence.toJson() = JSONObject().put("version", version).put("startUtf16", startUtf16)
    .put("endUtf16", endUtf16).put("surfaceForm", surfaceForm).put("normalizedForm", normalizedForm)
    .put("allowedForms", JSONArray(allowedForms))
