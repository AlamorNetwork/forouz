package com.v2ray.ang.core.cdn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.lab.LabIssue
import com.v2ray.ang.core.lab.LabRecords
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol

/** Per-controller probe clients, separate from feed downloads and their daemon runtime route. */
internal fun cdnProbeClient(port: Int): OkHttpClient {
    require(port in 1..65535)
    return OkHttpClient.Builder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
}

internal fun cdnHttp1Control(client: OkHttpClient): OkHttpClient =
    client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()

/** Direct physical-network edge reachability only. Never builds or starts an Xray configuration. */
internal class CdnScanRunner(private val context: Context) {
    private class Abort(val issue: LabIssue) : IllegalStateException()
    suspend fun run(request: CdnRequest, progress: (Int, Int) -> Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val selected = AtomicReference<Network?>(null)
        val lost = AtomicBoolean(false)
        val job = currentCoroutineContext().job
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || (selected.get() == network &&
                    (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)))) {
                    lost.set(true); job.cancel(CancellationException("Edge network changed"))
                }
            }
            override fun onLost(network: Network) {
                if (selected.get() == network) { lost.set(true); job.cancel(CancellationException("Edge network lost")) }
            }
        }
        val defaultCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (selected.get()?.let { it != network } == true) {
                    lost.set(true); job.cancel(CancellationException("Edge default network changed"))
                }
            }
        }
        var registered = false
        var defaultRegistered = false
        val now = System.currentTimeMillis()
        var record = CdnRun(request, now, now, CdnStatus.RUNNING)
        try {
            request.validate()
            require(request.edgeScan && !request.download)
            MmkvManager.saveCdnRun(record)
            if (!request.manual) {
                val catalog = CdnCatalog(requireNotNull(MmkvManager.readCdnFeed()))
                require(request.addresses.all { ip -> catalog.ranges.getValue(request.provider).any { it.contains(ip) } })
                require(request.prefixes.map(CdnPrefix::parse).all { prefix ->
                    catalog.ranges.getValue(request.provider).any { prefix.first >= it.first && prefix.last <= it.last }
                })
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
            registered = true
            cm.registerDefaultNetworkCallback(defaultCallback)
            defaultRegistered = true
            val physical = cm.activeNetwork?.takeIf { it.networkHandle == request.networkHandle } ?: throw Abort(LabIssue.NETWORK)
            selected.set(physical)
            fun guardInputs() {
                val caps = cm.getNetworkCapabilities(physical)
                if (!CdnBaselineControls.networkAllowed(physical.networkHandle, cm.activeNetwork?.networkHandle,
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true,
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true, lost.get())) throw Abort(LabIssue.NETWORK)
                if (SettingsManager.isRootMode()) throw Abort(LabIssue.UNSUPPORTED)
                if (MmkvManager.decodeServerConfig(request.guid)?.let { LabRecords.revision(it) == request.revision } != true)
                    throw Abort(LabIssue.CHANGED)
            }
            guardInputs()
            val profile = MmkvManager.decodeServerConfig(request.guid) ?: throw Abort(LabIssue.CHANGED)
            require(CdnPolicy.edgeSupported(profile))
            val serverName = CdnEdgeProbe.sni(profile.sni.orEmpty())
            record = record.copy(networkHandle = physical.networkHandle)
            MmkvManager.saveCdnRun(record)
            // Network-owned sockets select the physical network individually; no process binding or DNS for candidates.
            val probe = CdnEdgeProbe(physical.socketFactory, onFailure = { phase, error -> failure(phase, error, request.guid) })
            val plan = CdnBranchPlan(request.prefixes.map(CdnPrefix::parse), request.addresses, request.budget)
            withTimeout(1_800_000) {
                while (true) {
                    currentCoroutineContext().ensureActive(); guardInputs()
                    val address = plan.next() ?: break
                    val result = probe.measure(address, serverName)
                    currentCoroutineContext().ensureActive(); guardInputs()
                    record = record.copy(rows = record.rows + CdnRow(address, edge = result),
                        updatedAt = maxOf(now, System.currentTimeMillis()))
                    MmkvManager.saveCdnRun(record)
                    progress(record.rows.size, request.budget)
                    plan.report(address, result.reachable())
                }
            }
            guardInputs()
            record = record.copy(status = CdnStatus.COMPLETE)
        } catch (e: CancellationException) {
            record = record.copy(status = if (lost.get() || e is TimeoutCancellationException) CdnStatus.INTERRUPTED else CdnStatus.CANCELLED,
                issue = if (lost.get()) LabIssue.NETWORK else if (e is TimeoutCancellationException) LabIssue.TIMEOUT else LabIssue.NONE)
        } catch (e: Exception) {
            failure("scan", e, request.guid)
            record = record.copy(status = CdnStatus.FAILED, issue = (e as? Abort)?.issue ?: LabIssue.CONFIG)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (registered) try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) { failure("unregister", e, request.guid) }
                if (defaultRegistered) try { cm.unregisterNetworkCallback(defaultCallback) } catch (e: Exception) { failure("default-unregister", e, request.guid) }
                try { MmkvManager.saveCdnRun(record.copy(updatedAt = maxOf(now, System.currentTimeMillis()))) }
                catch (e: Exception) { failure("finalize", e, request.guid) }
            }
        }
    }
    private fun failure(phase: String, error: Exception, guid: String) =
        LogUtil.e(AppConfig.TAG, "CDN mode=edge phase=$phase profile=$guid", IllegalStateException(error.javaClass.simpleName))
}
