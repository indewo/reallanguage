package com.reallanguage.app.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

data class ConnectionOwner(val accountId: String?, val epoch: Long)
data class ConnectionIncident(val generation: Long, val owner: ConnectionOwner)

/** Session-bound connectivity boundary. It never retries or replays a business request. */
class ConnectionGuardedHttpTransport(
    private val delegate: HttpTransport,
    private val readOwner: () -> ConnectionOwner,
) : HttpTransport {
    private val mutableIncident = MutableStateFlow<ConnectionIncident?>(null)
    val incident = mutableIncident.asStateFlow()
    private var generation = 0L

    /** A pending permission decision may proceed only in the interaction that requested it. */
    @Synchronized fun interactionPermit(): (() -> Boolean)? {
        val owner = readOwner()
        if (owner.accountId == null || mutableIncident.value != null) return null
        val issuedGeneration = generation
        return { synchronized(this) {
            issuedGeneration == generation && mutableIncident.value == null && readOwner() == owner
        } }
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val lease = synchronized(this) {
            val owner = readOwner()
            if (mutableIncident.value?.owner == owner) throw IOException("Connection and account verification required")
            ConnectionIncident(generation, owner)
        }
        try {
            return delegate.execute(request).also { response ->
                if (response.statusCode == 401 && request.headers.keys.any { it.equals("Authorization", ignoreCase = true) }) {
                    reportFailure(lease)
                }
            }
        } catch (failure: IOException) {
            reportFailure(lease)
            throw failure
        }
    }

    @Synchronized private fun reportFailure(lease: ConnectionIncident) {
        if (lease.generation != generation || lease.owner != readOwner()) return
        if (mutableIncident.value == null || mutableIncident.value?.owner != lease.owner) {
            mutableIncident.value = ConnectionIncident(generation, lease.owner)
        }
    }

    /** Only the account verifier may acknowledge this exact incident. Never issues HTTP. */
    @Synchronized fun isCurrent(expected: ConnectionIncident): Boolean =
        mutableIncident.value == expected && readOwner() == expected.owner

    /** Identity may have been cleared by verification; this alone never grants access. */
    @Synchronized fun hasIncident(expected: ConnectionIncident): Boolean = mutableIncident.value == expected

    @Synchronized fun restoreVerified(expected: ConnectionIncident): Boolean {
        if (!isCurrent(expected)) return false
        generation++
        mutableIncident.value = null
        return true
    }

    @Synchronized fun networkLost() { reportFailure(ConnectionIncident(generation, readOwner())) }

    /** Startup may confirm an already anonymous identity at a newer local epoch. */
    @Synchronized fun alignVerifiedAnonymousStartup(): Boolean {
        val previous = mutableIncident.value ?: return false
        val current = readOwner()
        if (previous.owner.accountId != null || current.accountId != null) return false
        if (previous.owner == current) return true
        // Keep the cover closed. Only its stale anonymous lease is replaced;
        // explicit service verification is still required and nothing is replayed.
        generation++
        mutableIncident.value = ConnectionIncident(generation, current)
        return true
    }

    @Synchronized fun acceptVerifiedSignedOut(expected: ConnectionIncident, signedOutProof: () -> Boolean): Boolean {
        val signedOutOwner = readOwner()
        if (mutableIncident.value != expected || expected.owner.accountId == null || signedOutOwner.accountId != null || !signedOutProof()) return false
        // Only the verifier's still-current clear receipt permits this transition.
        // Recheck after the callback, which may observe a concurrently replaced identity.
        if (mutableIncident.value != expected || readOwner() != signedOutOwner) return false
        generation++
        mutableIncident.value = null
        return true
    }

    @Synchronized fun acceptVerifiedNewSession(expected: ConnectionIncident?): Boolean {
        val current = readOwner()
        if (expected == null || mutableIncident.value != expected || current.accountId == null || current == expected.owner) return false
        generation++
        mutableIncident.value = null
        return true
    }
}
