package com.reallanguage.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Observes a route, not internet quality. A local Wi-Fi API does not require VALIDATED internet. */
class AndroidNetworkAvailabilitySource(context: Context) : NetworkAvailabilitySource {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun subscribe(listener: (Boolean) -> Unit): AutoCloseable {
        val manager = checkNotNull(connectivity)
        val closed = AtomicBoolean(false)
        val mainHandler = Handler(Looper.getMainLooper())
        val pendingUnavailable = AtomicReference<Runnable?>(null)
        fun current(): Boolean {
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        fun cancelPendingUnavailable() {
            pendingUnavailable.getAndSet(null)?.let(mainHandler::removeCallbacks)
        }
        fun report() {
            mainHandler.post {
                if (closed.get()) return@post
                if (current()) {
                    cancelPendingUnavailable()
                    listener(true)
                    return@post
                }
                // Android commonly emits onLost for the old default route before the
                // replacement Wi-Fi/VPN route becomes active. Do not turn that brief
                // hand-off into a product-wide connection incident.
                if (pendingUnavailable.get() != null) return@post
                lateinit var confirmation: Runnable
                confirmation = Runnable {
                    if (pendingUnavailable.compareAndSet(confirmation, null) && !closed.get()) {
                        listener(current())
                    }
                }
                pendingUnavailable.set(confirmation)
                mainHandler.postDelayed(confirmation, UNAVAILABLE_CONFIRMATION_MILLISECONDS)
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = report()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = report()
            override fun onLost(network: Network) = report()
            override fun onUnavailable() = report()
        }
        manager.registerDefaultNetworkCallback(callback)
        try { report() } catch (failure: Exception) {
            closed.set(true)
            cancelPendingUnavailable()
            manager.unregisterNetworkCallback(callback)
            throw failure
        }
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                cancelPendingUnavailable()
                manager.unregisterNetworkCallback(callback)
            }
        }
    }

    private companion object {
        const val UNAVAILABLE_CONFIRMATION_MILLISECONDS = 1_500L
    }
}
