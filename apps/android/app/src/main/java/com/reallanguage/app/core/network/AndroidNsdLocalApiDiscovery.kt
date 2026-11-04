package com.reallanguage.app.core.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.Inet6Address
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolves the Mac-side API from DNS-SD at request time. Unlike an embedded
 * DHCP address, the result follows the current Wi-Fi network without a new APK.
 */
class AndroidNsdLocalApiDiscovery(
    context: Context,
    private val serviceType: String,
    private val serviceNamePrefix: String = "RealLanguage",
    private val timeoutMilliseconds: Long = 4_000,
) {
    private val applicationContext = context.applicationContext
    private val nsdManager = applicationContext.getSystemService(NsdManager::class.java)
    private val wifiManager = applicationContext.getSystemService(WifiManager::class.java)

    suspend fun discoverBaseUrls(): List<String> {
        if (serviceType.isBlank()) return emptyList()
        val multicastLock = wifiManager?.createMulticastLock("reallanguage-local-api-discovery")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
        return try {
            withTimeoutOrNull(timeoutMilliseconds) { discoverOne() }
                ?.let(::listOf)
                .orEmpty()
        } finally {
            if (multicastLock?.isHeld == true) multicastLock.release()
        }
    }

    private suspend fun discoverOne(): String? = suspendCancellableCoroutine { continuation ->
        val finished = AtomicBoolean(false)
        val resolving = AtomicBoolean(false)
        var discoveryStarted = false
        lateinit var listener: NsdManager.DiscoveryListener

        fun stopDiscovery() {
            if (!discoveryStarted) return
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            discoveryStarted = false
        }

        fun complete(baseUrl: String?) {
            if (!finished.compareAndSet(false, true)) return
            stopDiscovery()
            if (continuation.isActive) continuation.resume(baseUrl)
        }

        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                discoveryStarted = true
                if (finished.get()) stopDiscovery()
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceName.startsWith(serviceNamePrefix) ||
                    !service.serviceType.trimEnd('.').equals(serviceType.trimEnd('.'), ignoreCase = true) ||
                    !resolving.compareAndSet(false, true)
                ) return
                @Suppress("DEPRECATION")
                try {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving.set(false)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host ?: run {
                            resolving.set(false)
                            return
                        }
                        val address = host.hostAddress ?: run {
                            resolving.set(false)
                            return
                        }
                        val urlHost = if (host is Inet6Address) "[${address.replace("%", "%25")}]" else address
                        complete("http://$urlHost:${serviceInfo.port}")
                    }
                    })
                } catch (_: IllegalArgumentException) {
                    resolving.set(false)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = complete(null)
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        continuation.invokeOnCancellation {
            if (finished.compareAndSet(false, true)) stopDiscovery()
        }
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: SecurityException) {
            complete(null)
        } catch (_: IllegalArgumentException) {
            complete(null)
        }
    }
}
