package com.v2ray.ang.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.*
import com.v2ray.ang.core.health.*
import com.v2ray.ang.core.lab.LabRecords
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient

/** Separate opt-in lifetime, same daemon authority. It never starts a stopped VPN. */
class ConnectionHealthService : Service() {
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.IO)
    private val commands = Mutex()
    @Volatile private var runJob: Job? = null
    @Volatile private var commandGeneration = 0L
    @Volatile private var latestStartId = 0
    override fun attachBaseContext(base: Context?) { super.attachBaseContext(base?.let(AppLocaleManager::localizedContext)) }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val stop = PendingIntent.getService(this, 15, Intent(this, ConnectionHealthService::class.java).setAction("health.stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationHelper.startForeground(this, NotificationChannelType.CONNECTION_HEALTH,
            getString(R.string.health_title), getString(R.string.health_running),
            NotificationCompat.Action.Builder(R.drawable.ic_stop_24dp, getString(R.string.health_stop), stop).build())
        // Invalidate immediately, before a queued stop waits for the command mutex or native cleanup.
        if (intent?.action == "health.stop") { commandGeneration++; runJob?.cancel() }
        scope.launch {
            commands.withLock {
                val request = intent?.serializable<HealthRequest>("request")
                val decision = healthCommand(intent?.action, request?.valid() == true, labHasWork(runJob))
                if (decision == HealthCommand.STOP) {
                    runJob?.cancelAndJoin(); runJob = null
                    try {
                        MmkvManager.readHealthRuns().firstOrNull { it.status == HealthStatus.RUNNING }?.let {
                            MmkvManager.saveHealthRun(it.copy(status = HealthStatus.INTERRUPTED))
                        }
                    } catch (e: Exception) { failure("stop-history", e) }
                    stopSelfResult(startId)
                } else if (decision == HealthCommand.START && request != null) {
                    val generation = commandGeneration
                    runJob = scope.launch { run(request, generation) }
                } else if (decision == HealthCommand.REJECT) stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    // Android 15 dataSync timeout must release the foreground service promptly.
    override fun onTimeout(startId: Int, fgsType: Int) { commandGeneration++; owner.cancel(); stopSelf() }
    override fun onDestroy() {
        commandGeneration++; owner.cancel()
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }

    private suspend fun run(request: HealthRequest, generation: Long) {
        val created = System.currentTimeMillis()
        var record = HealthRun(request.id, created, created, request.intervalSeconds, request.recover)
        val started = SystemClock.elapsedRealtime()
        val policy = HealthPolicy(started, request.intervalSeconds * 1_000L)
        val cm = getSystemService(ConnectivityManager::class.java)
        val networks = HealthNetworkObserver(cm)
        val job = currentCoroutineContext().job
        fun current() = job.isActive && generation == commandGeneration
        fun save(phase: HealthPhase, kind: String = "unknown", guid: String = "", session: String = "", latency: Long? = null) {
            record = record.event(HealthEvent(maxOf(created, System.currentTimeMillis()), phase, kind, guid, session, latency))
            MmkvManager.saveHealthRun(record)
        }
        try {
            MmkvManager.saveHealthRun(record) // Persist acknowledgement before networking or recovery.
            networks.register()
            while (current() && record.requests <= 478 && !policy.expired(SystemClock.elapsedRealtime())) {
                val now = SystemClock.elapsedRealtime()
                val snapshot = ConnectionTelemetry.snapshot()
                val link = networks.current()
                val phase = when {
                    snapshot?.running != true || !CoreServiceManager.isRunning() -> HealthPhase.WAITING_VPN
                    link == null || link.captive -> HealthPhase.WAITING_NETWORK
                    else -> null
                }
                if (phase != null) {
                    policy.observe(null, now)
                    save(phase, link?.kind ?: "unknown", snapshot?.profileGuid.orEmpty(), snapshot?.sessionId.orEmpty())
                } else {
                    check(snapshot != null && link != null)
                    val profile = MmkvManager.decodeServerConfig(snapshot.profileGuid)
                    if (profile == null) {
                        policy.observe(null, now); save(HealthPhase.CHANGED)
                    } else {
                        val revision = LabRecords.revision(profile)
                        val key = "${snapshot.sessionId}:$revision:${link.network.networkHandle}:${link.validated}"
                        if (!policy.observe(key, now)) save(HealthPhase.GRACE, link.kind, snapshot.profileGuid, snapshot.sessionId)
                        else {
                            val awg = profile.configType == EConfigType.AMNEZIAWG
                            val route = client(cm, awg)
                            if (route == null) {
                                policy.observe(null, now)
                                save(HealthPhase.UNSUPPORTED, link.kind, snapshot.profileGuid, snapshot.sessionId)
                            } else {
                                val client = route.http
                                val stopToken = HealthRecoveryFence.token()
                                val count = AtomicInteger()
                                val outcome = try {
                                    AwgConnectionProbe(Call.Factory { r -> count.incrementAndGet(); client.newCall(r) })
                                        .measure("https://cp.cloudflare.com/generate_204", "https://www.gstatic.com/generate_204")
                                } finally {
                                    record = record.copy(requests = record.requests + count.get())
                                    client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
                                }
                                currentCoroutineContext().ensureActive()
                                val after = ConnectionTelemetry.snapshot()
                                val unchanged = current() && route.valid() && after?.running == true && after.sessionId == snapshot.sessionId &&
                                    after.profileGuid == snapshot.profileGuid && networks.current()?.let { it.network == link.network && it.validated == link.validated && !it.captive } == true &&
                                    MmkvManager.decodeServerConfig(snapshot.profileGuid)?.let { LabRecords.revision(it) == revision } == true &&
                                    HealthRecoveryFence.permits(stopToken) && CoreServiceManager.isRunning()
                                if (!unchanged) { policy.observe(null, SystemClock.elapsedRealtime()); save(HealthPhase.CHANGED) }
                                else {
                                    val success = outcome.failure == null
                                    policy.result(success)
                                    record = record.copy(checks = policy.checks, failedChecks = record.failedChecks + if (success) 0 else 1)
                                    save(if (success) HealthPhase.HEALTHY else HealthPhase.FAILED_CHECK, link.kind,
                                        snapshot.profileGuid, snapshot.sessionId, outcome.delayMillis.takeIf { success })
                                    val validLink = networks.current()
                                    if (!awg && policy.canRecover(SystemClock.elapsedRealtime(), request.recover,
                                            validLink?.validated == true && !validLink.captive)) {
                                        policy.reserveRecovery(SystemClock.elapsedRealtime())
                                        record = record.copy(attempts = policy.attempts)
                                        save(HealthPhase.RECOVERY, link.kind, snapshot.profileGuid, snapshot.sessionId)
                                        // Once native stop begins, observer cancellation must not leave the VPN blackholed.
                                        val result = withContext(NonCancellable) {
                                            LauncherManager.recoverHealth(snapshot.sessionId, snapshot.profileGuid, revision, stopToken) {
                                                current() && networks.current()?.let { it.network == link.network && it.validated && !it.captive } == true
                                            }
                                        }
                                        save(when (result) {
                                            HealthRecoveryResult.RECOVERED -> HealthPhase.RECOVERED
                                            HealthRecoveryResult.FAILED -> HealthPhase.RECOVERY_FAILED
                                            else -> HealthPhase.RECOVERY_SKIPPED
                                        }, link.kind, snapshot.profileGuid, snapshot.sessionId)
                                    }
                                }
                            }
                        }
                    }
                }
                delay(request.intervalSeconds * 1_000L)
            }
            record = record.copy(status = if (current()) HealthStatus.ENDED else HealthStatus.CANCELLED)
        } catch (e: CancellationException) { record = record.copy(status = HealthStatus.CANCELLED) }
        catch (e: Exception) { failure("monitor", e); record = record.copy(status = HealthStatus.ERROR) }
        finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { networks.close() } catch (e: Exception) { failure("network-cleanup", e) }
                try { save(HealthPhase.FINISHED) } catch (e: Exception) { failure("history-finalize", e) }
                stopSelfResult(latestStartId)
            }
        }
    }

    private data class ProbeRoute(val http: OkHttpClient, val valid: () -> Boolean)
    private suspend fun client(cm: ConnectivityManager, awg: Boolean): ProbeRoute? {
        if (awg) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || CoreServiceManager.externalVpnControl?.get()?.engineRunning != true) return null
            val network = findOwnedAwgVpn(cm) ?: return null
            return ProbeRoute(awgProbeClient(network.socketFactory, Dns { host -> network.getAllByName(host).toList() })
                .newBuilder().retryOnConnectionFailure(false).build()) { ownsAwgVpn(cm, network) }
        }
        if (CoreServiceManager.externalVpnControl?.get() != null || SettingsManager.isRootMode()) return null
        val port = CoreServiceManager.healthSocksPort() ?: return null
        // This app is excluded from the Xray VPN; explicit SOCKS is mandatory, with no direct fallback.
        return ProbeRoute(awgProbeClient(javax.net.SocketFactory.getDefault(), Dns.SYSTEM).newBuilder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .retryOnConnectionFailure(false).build()) { CoreServiceManager.externalVpnControl?.get() == null }
    }

    private fun failure(phase: String, e: Exception) = LogUtil.e(AppConfig.TAG,
        "Health mode=observer phase=$phase", IllegalStateException(e.javaClass.simpleName))
}
