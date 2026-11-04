package com.reallanguage.app.core.app

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class PasswordResetDeepLink(
    val challengeId: String,
    val bearer: String,
)

fun parsePasswordResetDeepLink(rawUri: String?): PasswordResetDeepLink? {
    if (rawUri.isNullOrBlank()) return null
    val uri = runCatching { URI(rawUri) }.getOrNull() ?: return null
    if (uri.scheme != "reallanguage" || uri.host != "password-reset" || uri.path != "/verify") {
        return null
    }
    val parameters = uri.rawQuery
        ?.split('&')
        ?.mapNotNull { item ->
            val separator = item.indexOf('=')
            if (separator <= 0) null else decode(item.take(separator)) to decode(item.drop(separator + 1))
        }
        ?.toMap()
        .orEmpty()
    val challengeId = parameters["challengeId"]?.takeIf(String::isNotBlank) ?: return null
    val bearer = parameters["bearer"]?.takeIf(String::isNotBlank) ?: return null
    return PasswordResetDeepLink(challengeId, bearer)
}

private fun decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
