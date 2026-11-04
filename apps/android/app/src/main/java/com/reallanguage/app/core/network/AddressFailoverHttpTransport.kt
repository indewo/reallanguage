package com.reallanguage.app.core.network

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps a stable LAN hostname as the authority while allowing a verified
 * current address to recover DNS/route failures. Business responses and read
 * timeouts are never replayed against another address.
 */
class AddressFailoverHttpTransport(
    private val transports: List<HttpTransport>,
    private val discoverTransports: suspend () -> List<HttpTransport> = { emptyList() },
) : HttpTransport {
    init { require(transports.isNotEmpty()) }

    @Volatile private var preferredTransport: HttpTransport? = null
    private val selection = Mutex()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val selected = selectHealthyEndpoint()
        return try {
            selected.execute(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure.isRouteFailure() && preferredTransport === selected) {
                preferredTransport = null
            }
            throw failure
        }
    }

    private suspend fun selectHealthyEndpoint(): HttpTransport =
        preferredTransport ?: selection.withLock {
        preferredTransport ?: run {
            var lastFailure: Exception? = null
            val candidates = buildList {
                addAll(transports)
                try {
                    addAll(discoverTransports())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    lastFailure = failure
                }
            }
            candidates.forEach { candidate ->
                try {
                    val health = candidate.execute(HttpRequest("GET", "/api/v1/health", ""))
                    if (health.statusCode == 200 &&
                        health.body.contains("\"status\":\"ok\"") &&
                        health.body.contains("\"service\":\"api\"")
                    ) {
                        preferredTransport = candidate
                        return@withLock candidate
                    }
                    lastFailure = IllegalStateException("Endpoint health response is not RealLanguage API")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    lastFailure = failure
                }
            }
            throw lastFailure ?: IllegalStateException("No RealLanguage API endpoint")
        }
    }
}

private fun Exception.isRouteFailure(): Boolean =
    this is UnknownHostException || this is ConnectException || this is NoRouteToHostException
