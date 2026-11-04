package com.reallanguage.app.core.app

import java.security.MessageDigest

/** One explicit submission owns its retry identity, independently of transient screen instances. */
internal fun safetyCheckCommandId(route: HomeDiaryRoute.SafetyCheck): String {
    return confirmedSourceCommandId("safety-v1-", route.entryId, route.sourceType.name, route.contentShape.name, route.text)
}

internal fun confirmedSourceCommandId(prefix: String, entryId: String, sourceType: String, contentShape: String, text: String): String {
    val identity = listOf(entryId, sourceType, contentShape, text)
        .joinToString(separator = "") { "${it.length}:$it" }
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
    return prefix + digest.joinToString("") { byte -> "%02x".format(byte) }
}
