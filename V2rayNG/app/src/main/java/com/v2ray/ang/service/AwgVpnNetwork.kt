package com.v2ray.ang.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import com.v2ray.ang.core.isOwnedAwgVpn
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Temporary observer owned by the probe coroutine, not a persistent network-monitor service. */
@RequiresApi(Build.VERSION_CODES.R)
internal suspend fun findOwnedAwgVpn(connectivity: ConnectivityManager): Network? = withTimeoutOrNull(1_500) {
    val candidates = Channel<Network>(Channel.CONFLATED)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            candidates.trySend(network)
        }
    }
    val request = NetworkRequest.Builder().clearCapabilities()
        .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build()
    connectivity.registerNetworkCallback(request, callback)
    try {
        for (network in candidates) {
            if (ownsAwgVpn(connectivity, network)) return@withTimeoutOrNull network
        }
        null
    } finally {
        connectivity.unregisterNetworkCallback(callback)
        candidates.close()
    }
}

internal fun ownsAwgVpn(connectivity: ConnectivityManager, network: Network): Boolean {
    // Android 11 introduced the public owner UID. Earlier Android must report unsupported,
    // rather than guessing that the default VPN belongs to this AWG session.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
    return isOwnedAwgVpn(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        capabilities.ownerUid, Process.myUid())
}
