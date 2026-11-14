package com.v2ray.ang.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.lab.*
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libv2ray.CoreCallbackHandler
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/** Dedicated :lab process: native globals and physical-network binding never alter the VPN daemon. */
class LaboratoryService : Service() {
    private class Abort(val issue: LabIssue) : IllegalStateException()
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.IO)
    private val commands = Mutex()
    private var runJob: Job? = null
    @Volatile private var lastStartId = 0

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val cancel = PendingIntent.getService(this, 14, Intent(this, LaboratoryService::class.java)
            .setAction("lab.cancel"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationHelper.startForeground(this, NotificationChannelType.LABORATORY,
            getString(R.string.lab_title), getString(R.string.lab_running),
            NotificationCompat.Action.Builder(R.drawable.ic_stop_24dp, getString(R.string.action_cancel), cancel).build())
        scope.launch {
            commands.withLock {
                val request = intent?.serializable<LabStartRequest>("request")
                val decision = labCommand(intent?.action, request != null, labHasWork(runJob))
                if (decision == LabCommand.CANCEL) {
                    runJob?.cancelAndJoin()
                    runJob = null
                    // A prior process may have died. Explicit cancel also closes its persisted run.
                    try {
                        MmkvManager.readLaboratoryRuns().firstOrNull { it.status == LabRunStatus.RUNNING }?.let { old ->
                            MmkvManager.saveLaboratoryRun(old.copy(status = LabRunStatus.INTERRUPTED,
                                updatedAt = maxOf(old.createdAt, System.currentTimeMillis()),
                                rows = old.rows.map { if (it.status in listOf(LabRowStatus.WAITING, LabRowStatus.TESTING))
                                    it.copy(status = LabRowStatus.CANCELLED) else it }))
                        }
                    } catch (e: Exception) { logFailure("cancel-history", e) }
                    stopSelfResult(startId)
                } else if (decision == LabCommand.START && request != null) {
                    runJob = scope.launch { run(request, startId) }
                } else if (decision == LabCommand.STOP) stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    // Android 15 dataSync timeout: cancel owned work; all socket/native cleanup remains on IO.
    override fun onTimeout(startId: Int, fgsType: Int) { owner.cancel(); stopSelf() }

    override fun onDestroy() {
        owner.cancel()
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }

    private suspend fun run(request: LabStartRequest, startId: Int) {
        val cm = getSystemService(ConnectivityManager::class.java)
        var bound = false
        var registered = false
        val selectedNetwork = java.util.concurrent.atomic.AtomicReference<Network?>(null)
        val discovered = CompletableDeferred<Network>()
        val networkLost = java.util.concurrent.atomic.AtomicBoolean(false)
        val job = currentCoroutineContext().job
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) discovered.complete(network)
            }
            override fun onLost(network: Network) { if (network == selectedNetwork.get()) {
                networkLost.set(true); job.cancel(CancellationException("lab network changed"))
            } }
        }
        val now = System.currentTimeMillis()
        var record = LabRunRecord(request.id, request.groupId, now, now, LabRunStatus.RUNNING,
            request.candidates.map { LabRow(it.guid, it.revision, it.transport) })
        try {
            require(request.id.isNotBlank() && request.candidates.isNotEmpty() &&
                request.candidates.size <= LabRecords.MAX_TARGETS && LabHttpProbe.validUrl(request.url))
            require(request.candidates.map { it.guid }.distinct().size == request.candidates.size)
            MmkvManager.saveLaboratoryRun(record) // Fail closed before network I/O if history is unreadable.
            if (SettingsManager.isRootMode()) throw Abort(LabIssue.UNSUPPORTED)
            cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
            registered = true
            val active = cm.activeNetwork?.takeIf { network -> cm.getNetworkCapabilities(network)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true }
            val physical = active ?: withTimeoutOrNull(5_000) { discovered.await() } ?: throw Abort(LabIssue.NETWORK)
            val caps = cm.getNetworkCapabilities(physical) ?: throw Abort(LabIssue.NETWORK)
            record = record.copy(networkKind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            })
            selectedNetwork.set(physical)
            if (!cm.bindProcessToNetwork(physical)) throw Abort(LabIssue.NETWORK)
            bound = true
            check(cm.boundNetworkForProcess == physical)
            val targets = LabPlan.sweep(request.candidates.map { LabTarget(it.guid, request.groupId, it.transport) })
            CoreNativeManager.initCoreEnv(this)
            for (target in targets) {
                currentCoroutineContext().ensureActive()
                check(cm.boundNetworkForProcess == physical && cm.getNetworkCapabilities(physical) != null)
                val candidate = request.candidates.first { it.guid == target.guid }
                val profile = MmkvManager.decodeServerConfig(target.guid)
                var row = record.rows.first { it.guid == target.guid }
                row = when {
                    profile == null || LabRecords.revision(profile) != candidate.revision -> row.copy(status = LabRowStatus.SKIPPED, issue = LabIssue.CHANGED)
                    !LabRecords.supported(profile) -> row.copy(status = LabRowStatus.SKIPPED, issue = LabIssue.UNSUPPORTED)
                    else -> row.copy(status = LabRowStatus.TESTING)
                }
                record = updateRow(record, row)
                MmkvManager.saveLaboratoryRun(record)
                if (row.status != LabRowStatus.TESTING) continue
                val result = probe(target.guid, request.url)
                currentCoroutineContext().ensureActive()
                val unchanged = MmkvManager.decodeServerConfig(target.guid)?.let { LabRecords.revision(it) == candidate.revision } == true
                check(cm.boundNetworkForProcess == physical && cm.getNetworkCapabilities(physical) != null)
                row = row.copy(status = if (!unchanged) LabRowStatus.SKIPPED else
                    if (result.issue == LabIssue.NONE) LabRowStatus.SUCCESS else LabRowStatus.FAILED,
                    issue = if (!unchanged) LabIssue.CHANGED else result.issue,
                    bytes = result.bytes, responseMillis = result.responseMillis, elapsedMillis = result.elapsedMillis,
                    testedAt = System.currentTimeMillis())
                record = updateRow(record, row)
                MmkvManager.saveLaboratoryRun(record)
                NotificationHelper.updateNotification(NotificationChannelType.LABORATORY, this,
                    getString(R.string.lab_title), getString(R.string.lab_progress,
                        record.rows.count { it.status !in listOf(LabRowStatus.WAITING, LabRowStatus.TESTING) }, record.rows.size))
            }
            record = record.copy(status = LabRunStatus.COMPLETE)
        } catch (e: CancellationException) {
            record = record.copy(status = if (networkLost.get()) LabRunStatus.INTERRUPTED else LabRunStatus.CANCELLED,
                issue = if (networkLost.get()) LabIssue.NETWORK else LabIssue.NONE)
        } catch (e: Exception) {
            logFailure("run", e)
            record = record.copy(status = LabRunStatus.FAILED, issue = (e as? Abort)?.issue ?: LabIssue.IO)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (registered) try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) { logFailure("network-unregister", e) }
                if (bound) try { check(cm.bindProcessToNetwork(null)) } catch (e: Exception) { logFailure("network-unbind", e) }
                record = record.copy(updatedAt = maxOf(record.createdAt, System.currentTimeMillis()),
                    rows = record.rows.map { if (it.status in listOf(LabRowStatus.WAITING, LabRowStatus.TESTING))
                        it.copy(status = LabRowStatus.CANCELLED) else it })
                try { MmkvManager.saveLaboratoryRun(record) } catch (e: Exception) { logFailure("history-finalize", e) }
                stopSelfResult(lastStartId)
            }
        }
    }

    private suspend fun probe(guid: String, url: String): LabDownloadResult {
        val controller = CoreNativeManager.newCoreController(object : CoreCallbackHandler {
            override fun startup() = 0L
            override fun shutdown() = 0L
            override fun onEmitStatus(l: Long, s: String?) = 0L
        })
        var client: OkHttpClient? = null
        try {
            val source = CoreConfigManager.getV2rayConfig4Speedtest(this, guid)
            if (!source.status) return LabDownloadResult(LabIssue.CONFIG, 0)
            val port = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val config = LabProbeConfig.build(source.content, port)
            controller.startLoop(config, 0)
            if (!controller.isRunning) return LabDownloadResult(LabIssue.CONFIG, 0)
            client = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS).build()
            return LabHttpProbe(client).download(url, java.util.UUID.randomUUID().toString())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { logFailure("candidate", e, guid); return LabDownloadResult(LabIssue.CONFIG, 0) }
        finally {
            withContext(NonCancellable + Dispatchers.IO) {
                client?.dispatcher?.cancelAll(); client?.connectionPool?.evictAll(); client?.dispatcher?.executorService?.shutdown()
                try { controller.stopLoop() } catch (e: Exception) { logFailure("candidate-stop", e, guid) }
            }
        }
    }

    private fun updateRow(record: LabRunRecord, row: LabRow) = record.copy(
        updatedAt = maxOf(record.createdAt, System.currentTimeMillis()), rows = record.rows.map { if (it.guid == row.guid) row else it })

    private fun logFailure(operation: String, error: Exception, guid: String = "") {
        // Native/parser exceptions may contain endpoint credentials. Keep only their class in diagnostics.
        LogUtil.e(AppConfig.TAG, "Lab isolated mode phase=$operation profile=$guid", IllegalStateException(error.javaClass.simpleName))
    }
}
