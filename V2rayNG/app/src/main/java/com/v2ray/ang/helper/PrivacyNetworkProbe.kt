package com.v2ray.ang.helper

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.CancellationSignal
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.privacy.*
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A finite UI-owned diagnostic, separate from service health checks; never changes routing/DNS. */
internal class PrivacyNetworkProbe(private val context: Context) {
    suspend fun measure(vpn: Boolean, admitted: PrivacyRoute = PrivacyRoute.UNAVAILABLE, port: Int? = null): PrivacyProbeReply = withContext(Dispatchers.IO) {
        // Public VPN ownerUid is Android 11+. Remove this boundary only with an equally strong owner proof.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext PrivacyProbeReply.Unavailable(PrivacyIssue.UNSUPPORTED)
        val route = privacyRoute(vpn, admitted, port)
        if (route == PrivacyRoute.UNAVAILABLE) return@withContext PrivacyProbeReply.Unavailable(PrivacyIssue.UNSUPPORTED)
        try { withTimeout(25_000) { measureOwned(vpn, route, port) } }
        catch (e: TimeoutCancellationException) { PrivacyProbeReply.Unavailable(PrivacyIssue.FAILED) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            logFailure("network", e)
            PrivacyProbeReply.Unavailable(PrivacyIssue.FAILED)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun measureOwned(vpn: Boolean, route: PrivacyRoute, port: Int?): PrivacyProbeReply = coroutineScope {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return@coroutineScope PrivacyProbeReply.Unavailable(PrivacyIssue.FAILED)
        val networks = ConcurrentHashMap<Network, NetworkCapabilities>()
        val leased = AtomicReference<Network?>(null)
        val ownerScope = this
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                networks[network] = caps
                val current = leased.get() ?: return
                if ((!vpn && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) ||
                    (network == current && vpn && !owned(caps))) ownerScope.cancel("Privacy route changed")
            }
            override fun onLost(network: Network) {
                networks.remove(network)
                if (network == leased.get()) ownerScope.cancel("Privacy network lost")
            }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), callback)
        try {
            delay(400) // Collect existing networks; no traffic is sent during discovery.
            val available = networks.toMap()
            val selected = if (vpn) available.filterValues(::owned).keys.singleOrNull()
                else cm.activeNetwork?.takeIf { active -> available[active]?.let { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true }
            if ((!vpn && available.values.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }) || selected == null)
                return@coroutineScope PrivacyProbeReply.Unavailable(if (vpn) PrivacyIssue.VPN_UNAVAILABLE else PrivacyIssue.DISCONNECT_FIRST)
            leased.set(selected)
            fun valid(): Boolean {
                val caps = cm.getNetworkCapabilities(selected) ?: return false
                return if (vpn) owned(caps) else !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    cm.activeNetwork == selected && networks.values.none { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            }
            if (!valid()) return@coroutineScope PrivacyProbeReply.Unavailable(PrivacyIssue.NETWORK_CHANGED)
            val physical = physicalNetwork(selected, networks.toMap(), vpn)
            // Xray's own UID is excluded by CoreVpnService on all supported Android versions.
            // Do not bind its sockets/DNS to TUN; remove this split only if that exclusion is removed
            // and both routing modes pass physical-device checks. No physical-network fallback.
            val proxyPath = route == PrivacyRoute.XRAY_SOCKS
            val client = if (proxyPath) privacyProxyClient(requireNotNull(port)) else OkHttpClient.Builder().socketFactory(selected.socketFactory)
                .dns(Dns { name -> selected.getAllByName(name).toList() })
                .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
            try {
                val v4 = async { address(client, "https://api.ipify.org", 4) }
                val v6 = async { address(client, "https://api6.ipify.org", 6) }
                val dns = async {
                    // A SOCKS egress check cannot observe Android's DNS path for other apps.
                    if (proxyPath) return@async emptySet<String>()
                    val found = linkedSetOf<String>()
                    repeat(3) {
                        ensureActive()
                        if (valid()) try {
                            withTimeoutOrNull(3_500) { resolver(selected) }?.let { found.addAll(it) }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { logFailure("dns", e) }
                    }
                    found.toSet()
                }
                val result = PrivacyObservation(vpn, physical, System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                    v4.await(), v6.await(), dns.await(), proxyPath = proxyPath)
                if (!valid() || physical != physicalNetwork(selected, networks.toMap(), vpn))
                    PrivacyProbeReply.Unavailable(PrivacyIssue.NETWORK_CHANGED)
                else if (result.ipv4 == null && result.ipv6 == null && result.resolvers.isEmpty()) PrivacyProbeReply.Unavailable(PrivacyIssue.FAILED)
                else PrivacyProbeReply.Observed(result.copy(measuredAt = System.currentTimeMillis(), elapsedAt = SystemClock.elapsedRealtime()))
            } finally {
                client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
            }
        } finally {
            leased.set(null)
            cm.unregisterNetworkCallback(callback)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun owned(caps: NetworkCapabilities): Boolean = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.ownerUid == Process.myUid()

    @RequiresApi(Build.VERSION_CODES.R)
    private fun physicalNetwork(selected: Network, networks: Map<Network, NetworkCapabilities>, vpn: Boolean): Long? {
        if (!vpn) return selected.networkHandle
        // Android's public SDK does not expose the VPN underlying-network list. Compare only
        // when exactly one internet-capable physical network is observable; ambiguity stays unknown.
        return networks.filterValues { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }.keys.singleOrNull()?.networkHandle
    }

    private suspend fun address(client: OkHttpClient, url: String, family: Int): String? = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-store").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isActive) return
                logFailure("ipv$family", e); continuation.resumeWith(Result.success(null))
            }
            override fun onResponse(call: Call, response: Response) {
                val value = try {
                    response.use {
                        if (!it.isSuccessful) null else it.body?.source()?.let { source ->
                            source.request(257)
                            if (source.buffer.size > 256) null else numericIp(source.readUtf8(source.buffer.size).trim(), family)
                        }
                    }
                } catch (e: Exception) { logFailure("ipv$family", e); null }
                if (continuation.isActive) continuation.resumeWith(Result.success(value))
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun resolver(network: Network): Set<String> = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        val queryId = java.security.SecureRandom().nextInt(65536)
        // Use the documented wire-message overload for TXT, outside the A/AAAA/HTTPS typed API.
        // Always skip Android's lookup cache; recursive/forwarder caches remain a stated limitation.
        DnsResolver.getInstance().rawQuery(network, ResolverAnswer.query(queryId),
            DnsResolver.FLAG_NO_CACHE_LOOKUP, Executor { it.run() }, signal,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(if (rcode == 0) ResolverAnswer.decode(answer, queryId) else emptySet()))
                }
                override fun onError(error: DnsResolver.DnsException) {
                    if (continuation.isActive) { logFailure("dns", error); continuation.resumeWith(Result.success(emptySet())) }
                }
            })
    }

    private fun logFailure(stage: String, error: Exception) {
        // IPs, URLs, resolver replies and underlying network identity never enter diagnostics.
        LogUtil.e(AppConfig.TAG, "Privacy observation stage=$stage", IllegalStateException(error.javaClass.simpleName))
    }
}
