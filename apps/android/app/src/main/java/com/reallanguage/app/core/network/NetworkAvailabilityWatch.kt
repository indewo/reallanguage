package com.reallanguage.app.core.network

fun interface NetworkAvailabilitySource {
    fun subscribe(listener: (Boolean) -> Unit): AutoCloseable
}

class NetworkAvailabilityWatch(
    private val source: NetworkAvailabilitySource,
    private val onUnavailable: () -> Unit,
) : AutoCloseable {
    private var started = false
    private var closed = false
    private var available: Boolean? = null
    private var subscription: AutoCloseable? = null

    fun start() {
        synchronized(this) {
            if (started || closed) return
            started = true
        }
        val handle = try { source.subscribe(::accept) }
        catch (_: Exception) { accept(false); return }
        val release = synchronized(this) {
            if (closed) true else { subscription = handle; false }
        }
        if (release) handle.close()
    }

    @Synchronized private fun accept(next: Boolean) {
        if (closed || !started || available == next) return
        available = next
        if (!next) onUnavailable()
    }

    override fun close() {
        val handle = synchronized(this) {
            if (closed) return
            closed = true
            subscription.also { subscription = null }
        }
        handle?.close()
    }
}
