package com.reallanguage.app.core.network

data class HttpRequest(
    val method: String,
    val path: String,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

data class HttpResponse(val statusCode: Int, val body: String)

fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}
