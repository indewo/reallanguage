package com.reallanguage.app.core.session

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class UrlConnectionSessionTransport(
    baseUrl: String,
    allowCleartextForLocalDevelopment: Boolean = false,
) : HttpTransport {
    private val baseUrl = baseUrl.trimEnd('/')
    private val bypassSystemProxy = shouldBypassSystemProxyForLocalApi(URL(this.baseUrl))

    init {
        requireValidApiBaseUrl(this.baseUrl, allowCleartextForLocalDevelopment)
    }

    override suspend fun execute(request: HttpRequest): HttpResponse =
        withTimeoutOrNull(SessionNetworkTimeouts.totalMilliseconds(request)) {
            executeCancellable(request)
        } ?: throw SocketTimeoutException("HTTP request exceeded its complete-request deadline")

    private suspend fun executeCancellable(request: HttpRequest): HttpResponse =
        suspendCancellableCoroutine { continuation ->
            val endpoint = URL(baseUrl + request.path)
            // Android may retain a VPN/Wi-Fi proxy after its UI switch is turned off.
            // Browsers normally bypass such a proxy for RFC1918 hosts, while a raw
            // URLConnection may not. The on-LAN API must always use the direct route.
            val connection = (if (bypassSystemProxy) endpoint.openConnection(Proxy.NO_PROXY)
                else endpoint.openConnection()) as HttpURLConnection
            continuation.invokeOnCancellation { connection.disconnect() }
            // This worker remains a child of the calling request. Cancellation closes the
            // actual socket as well as the continuation; it cannot revoke server-side work.
            CoroutineScope(continuation.context).launch(Dispatchers.IO) {
                try {
                    ensureActive()
                    connection.requestMethod = request.method
                    connection.connectTimeout = SessionNetworkTimeouts.CONNECT_MILLISECONDS
                    connection.readTimeout = SessionNetworkTimeouts.readMilliseconds(request)
                    connection.doInput = true
                    connection.doOutput = request.body.isNotEmpty()
                    connection.setRequestProperty("Accept", "application/json")
                    if (request.body.isNotEmpty()) {
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                    connection.setRequestProperty("x-request-id", UUID.randomUUID().toString())
                    request.headers.forEach(connection::setRequestProperty)
                    ensureActive()
                    if (request.body.isNotEmpty()) {
                        connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
                    }
                    ensureActive()
                    val statusCode = connection.responseCode
                    val source = if (statusCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                    val response = HttpResponse(
                        statusCode = statusCode,
                        body = source?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty(),
                    )
                    continuation.resume(response)
                } catch (failure: Exception) {
                    continuation.resumeWithException(failure)
                } finally {
                    connection.disconnect()
                }
            }
        }

}

internal object SessionNetworkTimeouts {
    const val CONNECT_MILLISECONDS = 3_000
    const val READ_MILLISECONDS = 8_000
    const val AI_READ_MILLISECONDS = 180_000

    fun totalMilliseconds(request: HttpRequest): Long =
        CONNECT_MILLISECONDS.toLong() + readMilliseconds(request)

    fun readMilliseconds(request: HttpRequest): Int {
        val path = request.path.substringBefore('?')
        val waitsForAi = request.method == "POST" &&
            (path in aiPostPaths || safetyHandlingPath.matches(path))
        return if (waitsForAi) AI_READ_MILLISECONDS else READ_MILLISECONDS
    }

    private val safetyHandlingPath = Regex("^/api/v1/safety/cases/[^/]+/handling$")

    private val aiPostPaths = setOf(
        "/api/v1/safety/checks",
        "/api/v1/content-formation/semantic-units/split",
        "/api/v1/content-formation/chapters/context-selection",
        "/api/v1/content-formation/english-candidates",
        "/api/v1/content-formation/learning-targets",
        "/api/v1/knowledge/examples",
        "/api/v1/sources/scenes/recommendations",
        "/api/v1/sources/scenes/next-question",
        "/api/v1/sources/scenes/drafts",
        "/api/v1/sources/scenes/edit-reviews",
        "/api/v1/media/recording-transcriptions",
        "/api/v1/media/transcript-repairs",
        "/api/v1/learning/reading-feedback",
        "/api/v1/learning/speaking-transcriptions",
        "/api/v1/learning/full-sentence-speaking-evaluations",
        "/api/v1/learning/pattern-practice-sets",
        "/api/v1/learning/pattern-practice-feedback",
        "/api/v1/learning/pattern-independent-evaluation",
        "/api/v1/learning/scene-response-tasks",
        "/api/v1/learning/scene-response-training-feedback",
        "/api/v1/learning/scene-response-evaluations",
        "/api/v1/learning/explanations",
    )
}

internal fun requireValidApiBaseUrl(baseUrl: String, allowCleartextForLocalDevelopment: Boolean) {
    val isHttps = baseUrl.startsWith("https://")
    val isAllowedDevelopmentHttp =
        allowCleartextForLocalDevelopment && baseUrl.startsWith("http://")
    require(isHttps || isAllowedDevelopmentHttp) {
        "Session API must use HTTPS outside local development"
    }
}

internal fun shouldBypassSystemProxyForLocalApi(url: URL): Boolean {
    if (url.protocol != "http") return false
    val host = url.host.lowercase()
    if (host == "localhost" || host == "::1" || host.startsWith("127.")) return true
    // Bonjour/mDNS names are LAN-only too. Sending them through a retained
    // VPN or Wi-Fi proxy makes a reachable Mac look offline on a real phone.
    if (host.endsWith(".local")) return true
    if (host.startsWith("10.") || host.startsWith("192.168.")) return true
    val parts = host.split('.')
    val second = parts.getOrNull(1)?.toIntOrNull()
    return parts.size == 4 && parts[0] == "172" && second != null && second in 16..31
}

internal fun requiresNearbyDevicesPermissionForLocalApi(baseUrl: String, sdkInt: Int): Boolean =
    sdkInt >= 33 && runCatching { shouldBypassSystemProxyForLocalApi(URL(baseUrl)) }.getOrDefault(false)

class JsonSessionWireCodec : SessionWireCodec {
    override fun login(email: String, password: String, deviceId: String): String =
        JSONObject()
            .put("email", email)
            .put("password", password)
            .put("deviceId", deviceId)
            .toString()

    override fun refresh(refreshToken: String, deviceId: String): String =
        JSONObject()
            .put("refreshToken", refreshToken)
            .put("deviceId", deviceId)
            .toString()

    override fun revoke(refreshToken: String): String =
        JSONObject().put("refreshToken", refreshToken).toString()

    override fun session(body: String): SessionApiResult {
        val json = JSONObject(body)
        return when (json.getString("status")) {
            "AUTHENTICATED" -> SessionApiResult.Authenticated(
                accountId = json.getString("accountId"),
                accessToken = json.getString("accessToken"),
                refreshToken = json.getString("refreshToken"),
                settingsCompleted = json.getBoolean("settingsCompleted"),
            )
            "INVALID_CREDENTIALS", "INVALID_OR_EXPIRED" -> SessionApiResult.InvalidOrExpired
            else -> error("Unsupported session response")
        }
    }

    override fun revoked(body: String): Boolean =
        JSONObject(body).optString("status") == "REVOKED"
}
