package com.v2ray.ang.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.ConcurrentHashMap

/** Metadata only; never binds the process or sends traffic outside the tunnel. */
internal class HealthNetworkObserver(private val cm: ConnectivityManager) {
    data class Link(val network: Network, val kind: String, val validated: Boolean, val captive: Boolean)
    private val links = ConcurrentHashMap<Network, Link>()
    @Volatile private var closed = false
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (closed) return
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
            links[network] = Link(network, when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
        }
        override fun onLost(network: Network) { links.remove(network) }
    }
    fun register() {
        cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
        registered = true
    }
    fun current(): Link? = if (closed) null else links[cm.activeNetwork] ?: links.values
        .sortedWith(compareByDescending<Link> { it.validated }.thenByDescending { it.kind == "wifi" }.thenBy { it.network.networkHandle })
        .firstOrNull()
    fun close() {
        closed = true
        links.clear()
        if (registered) { registered = false; cm.unregisterNetworkCallback(callback) }
    }
}
