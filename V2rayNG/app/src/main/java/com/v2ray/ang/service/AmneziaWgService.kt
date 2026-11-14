package com.v2ray.ang.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.system.ErrnoException
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ExternalVpnControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.ConnectionTelemetry
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.core.AwgConnectionProbe
import com.v2ray.ang.core.AwgProbeFailure
import com.v2ray.ang.core.AwgProbeOutcome
import com.v2ray.ang.core.AwgProbeRequests
import com.v2ray.ang.core.awgProbeClient
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.AwgConnectionTestRequest
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AmneziaWgFmt
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.lang.ref.SoftReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.amnezia.awg.GoBackend
import okhttp3.Dns

internal fun awgProbeErrorResource(failure: AwgProbeFailure): Int = when (failure) {
    AwgProbeFailure.UNSUPPORTED_ANDROID -> R.string.awg_test_unsupported
    AwgProbeFailure.VPN_UNAVAILABLE -> R.string.awg_test_no_vpn
    AwgProbeFailure.INVALID_URL -> R.string.awg_test_invalid_url
    AwgProbeFailure.TIMEOUT -> R.string.awg_test_timeout
    AwgProbeFailure.HTTP_RESPONSE -> R.string.awg_test_http_response
    AwgProbeFailure.NETWORK -> R.string.awg_test_network_error
    AwgProbeFailure.DNS -> R.string.awg_test_dns_error
    AwgProbeFailure.TLS -> R.string.awg_test_tls_error
    AwgProbeFailure.CONNECT -> R.string.awg_test_socket_error
}

/** Owns the AWG TUN, native handle and commands; Xray's services are not started for AWG. */
class AmneziaWgService : VpnService(), ExternalVpnControl {
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.IO)
    private val lifecycle = Mutex()
    private var setupJob: kotlinx.coroutines.Job? = null
    private var probeJob: kotlinx.coroutines.Job? = null
    private val probeRequests = AwgProbeRequests()
    private var selectedGuid: String? = null
    private var handle = -1
    private var receiverRegistered = false
    private val admission = com.v2ray.ang.core.AwgLifecycle()
    private val stopping: Boolean get() = admission.stopping
    override val engineRunning: Boolean get() = admission.running
    @Volatile override var runningProfileName = ""
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (isOrderedBroadcast) resultCode = android.app.Activity.RESULT_OK
                    MessageHelper.sendMsg2UI(this@AmneziaWgService,
                        if (engineRunning) AppConfig.MSG_STATE_RUNNING else AppConfig.MSG_STATE_NOT_RUNNING, "")
                    ConnectionTelemetry.sendCurrent(this@AmneziaWgService)
                }
                AppConfig.MSG_STATE_STOP -> stopService()
                AppConfig.MSG_STATE_RESTART -> {
                    if (isOrderedBroadcast) resultCode = android.app.Activity.RESULT_OK
                    requestStop(restart = true)
                }
                AppConfig.MSG_MEASURE_DELAY -> {
                    if (isOrderedBroadcast) resultCode = android.app.Activity.RESULT_OK
                    measureAwgDelay(intent.getStringExtra("content").orEmpty())
                }
                AppConfig.MSG_MEASURE_AWG_DELAY -> {
                    val request = intent.serializable<AwgConnectionTestRequest>("content") ?: return
                    if (isOrderedBroadcast) resultCode = android.app.Activity.RESULT_OK
                    if (request.matches(selectedGuid, engineRunning) && !stopping) measureAwgDelay(request.requestId)
                    else MessageHelper.sendMsg2UI(this@AmneziaWgService, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", request.requestId)
                }
                AppConfig.MSG_CANCEL_AWG_DELAY -> {
                    if (probeRequests.cancel(intent.getStringExtra("content").orEmpty())) probeJob?.cancel()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CoreServiceManager.externalVpnControl = SoftReference(this)
        NotificationManager.ensureForeground()
        if (!admission.tryStart()) return START_NOT_STICKY
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, receiver,
                IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE), Utils.receiverFlags())
            receiverRegistered = true
        }
        setupJob = scope.launch {
            lifecycle.withLock {
                try {
                    if (stopping) return@withLock
                    val guid = MmkvManager.getSelectServer() ?: error("Missing profile")
                    selectedGuid = guid
                    val profile = MmkvManager.decodeServerConfig(guid) ?: error("Missing profile")
                    require(profile.configType == EConfigType.AMNEZIAWG)
                    val raw = MmkvManager.decodeServerRaw(guid) ?: error("Missing configuration")
                    val config = AmneziaWgFmt.parseConfig(raw)
                    require(prepare(this@AmneziaWgService) == null)
                    for (peer in config.peers) require(peer.endpoint.get().resolved.isPresent)
                    ensureActive()
                    if (stopping) return@withLock
                    val builder = Builder().setSession(profile.remarks).setMtu(config.getInterface().mtu.orElse(1280))
                    val iface = config.getInterface()
                    for (address in iface.addresses) builder.addAddress(address.address, address.mask)
                    for (dns in iface.dnsServers) builder.addDnsServer(dns)
                    for (domain in iface.dnsSearchDomains) builder.addSearchDomain(domain)
                    for (app in iface.includedApplications) builder.addAllowedApplication(app)
                    for (app in iface.excludedApplications) builder.addDisallowedApplication(app)
                    for (peer in config.peers) for (route in peer.allowedIps) builder.addRoute(route.address, route.mask)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
                    builder.setBlocking(true)
                    System.loadLibrary("wg-go")
                    val settings = config.toAwgUserspaceString()
                    // Native awgTurnOn takes ownership of the detached descriptor, including on failure.
                    val tun = builder.establish() ?: error("TUN unavailable")
                    handle = GoBackend.awgTurnOn("awg0", tun.detachFd(), settings)
                    check(handle >= 0)
                    for (socket in listOf(GoBackend.awgGetSocketV4(handle), GoBackend.awgGetSocketV6(handle))) {
                        if (socket >= 0) check(protect(socket))
                    }
                    if (stopping) { closeNative(); return@withLock }
                    runningProfileName = profile.remarks
                    if (!admission.markRunning()) { closeNative(); return@withLock }
                    withContext(Dispatchers.Main.immediate) {
                        if (!stopping) {
                            probeRequests.activate(guid)
                            NotificationManager.showNotification(profile)
                            NotificationManager.markConnected()
                            val ownAppIncluded = (iface.includedApplications.isEmpty() ||
                                packageName in iface.includedApplications) && packageName !in iface.excludedApplications
                            ConnectionTelemetry.start(this@AmneziaWgService, guid, trafficAvailable = false,
                                privacyRoute = if (ownAppIncluded) com.v2ray.ang.core.privacy.PrivacyRoute.OWNED_VPN
                                    else com.v2ray.ang.core.privacy.PrivacyRoute.UNAVAILABLE)
                            MessageHelper.sendMsg2UI(this@AmneziaWgService, AppConfig.MSG_STATE_START_SUCCESS, "")
                        }
                    }
                } catch (e: CancellationException) {
                    closeNative()
                    throw e
                } catch (e: Exception) {
                    // Do not log parser/native exception messages: they may contain private config values.
                    LogUtil.e(AppConfig.TAG, "AWG VPN setup failed profile=$selectedGuid",
                        IllegalStateException("AWG setup ${e.javaClass.simpleName}"))
                    closeNative()
                    withContext(Dispatchers.Main.immediate) {
                        MessageHelper.sendMsg2UI(this@AmneziaWgService, AppConfig.MSG_STATE_START_FAILURE,
                            getString(R.string.awg_connection_failed))
                        requestStop()
                    }
                }
            }
        }
        // Explicit manual retry is required; a sticky restart would need a persisted engine/profile contract.
        return START_NOT_STICKY
    }

    private fun closeNative() {
        if (handle >= 0) {
            val owned = handle
            handle = -1
            GoBackend.awgTurnOff(owned)
        }
    }

    private fun requestStop(restart: Boolean = false) {
        if (!admission.beginStop()) return
        probeRequests.stop()
        probeJob?.cancel()
        ConnectionTelemetry.stop(this)
        setupJob?.cancel()
        scope.launch {
            setupJob?.join()
            probeJob?.join()
            lifecycle.withLock {
                try { closeNative() }
                catch (e: Exception) { LogUtil.e(AppConfig.TAG, "AWG VPN teardown failed profile=$selectedGuid",
                    IllegalStateException("AWG teardown ${e.javaClass.simpleName}")) }
            }
            withContext(Dispatchers.Main.immediate) {
                if (receiverRegistered) {
                    unregisterReceiver(receiver)
                    receiverRegistered = false
                }
                MessageHelper.sendMsg2UI(this@AmneziaWgService, AppConfig.MSG_STATE_STOP_SUCCESS, "")
                NotificationManager.cancelNotification()
                if (CoreServiceManager.externalVpnControl?.get() === this@AmneziaWgService) {
                    CoreServiceManager.externalVpnControl = null
                }
                val restartAwg = restart && MmkvManager.getSelectServer()?.let {
                    MmkvManager.decodeServerConfig(it)?.configType == EConfigType.AMNEZIAWG
                } == true
                admission.finishStop()
                if (restartAwg) {
                    admission.prepareRestart()
                } else {
                    stopSelf()
                    scope.cancel()
                }
                if (restart) LauncherManager.startService(this@AmneziaWgService)
            }
        }
    }

    private fun measureAwgDelay(requestId: String) {
        if (!engineRunning || stopping || requestId.isBlank()) {
            MessageHelper.sendMsg2UI(this, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
            return
        }
        val ticket = probeRequests.begin(requestId) ?: return // Duplicate ordered commands share the original request.
        probeJob?.cancel()
        probeJob = scope.launch {
            val outcome = try {
                probeOwnedNetwork()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "AWG VPN connection test failed profile=${ticket.profileGuid}",
                    IllegalStateException("AWG probe ${e.javaClass.simpleName}"))
                AwgProbeOutcome(failure = AwgProbeFailure.NETWORK)
            }
            val failure = outcome.failure
            if (failure != null) LogUtil.w(AppConfig.TAG,
                "AWG VPN connection test profile=${ticket.profileGuid} result=$failure")
            val message = failure?.let { AppLocaleManager.localizedContext(this@AmneziaWgService)
                .getString(awgProbeErrorResource(it)) + outcome.diagnosticCode?.let { code -> " [$code]" }.orEmpty() }.orEmpty()
            withContext(Dispatchers.Main.immediate) {
                if (engineRunning && !stopping && probeRequests.finish(ticket)) {
                    MessageHelper.sendMsg2UI(this@AmneziaWgService, AppConfig.MSG_MEASURE_DELAY_RESULT,
                        ConnectionTestResult(outcome.delayMillis, message), ticket.id)
                } else {
                    MessageHelper.sendMsg2UI(this@AmneziaWgService, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", ticket.id)
                }
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause is CancellationException) MessageHelper.sendMsg2UI(this,
                    AppConfig.MSG_MEASURE_DELAY_CANCEL, "", ticket.id)
            }
        }
    }

    private suspend fun probeOwnedNetwork(): AwgProbeOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return AwgProbeOutcome(failure = AwgProbeFailure.UNSUPPORTED_ANDROID)
        }
        val connectivity = getSystemService(ConnectivityManager::class.java)
            ?: return AwgProbeOutcome(failure = AwgProbeFailure.VPN_UNAVAILABLE)
        val network = findOwnedAwgVpn(connectivity)
            ?: return AwgProbeOutcome(failure = AwgProbeFailure.VPN_UNAVAILABLE)
        val client = awgProbeClient(network.socketFactory, Dns { host -> network.getAllByName(host).toList() })
        var probeErrno: String? = null
        try {
            val outcome = AwgConnectionProbe(client, onFailure = { stage, error ->
                val chain = generateSequence(error as Throwable) { it.cause }.take(4).toList()
                probeErrno = chain.filterIsInstance<ErrnoException>().firstOrNull()?.let { OsConstants.errnoName(it.errno) }
                // Exception messages can contain the configured URL. Log only stage, class names and errno.
                LogUtil.e(AppConfig.TAG, "AWG VPN probe profile=$selectedGuid stage=$stage errno=$probeErrno",
                    IllegalStateException(chain.joinToString("/") { it.javaClass.simpleName }))
            }).measure(SettingsManager.getDelayTestUrl(), SettingsManager.getDelayTestUrl(true))
            return if (ownsAwgVpn(connectivity, network)) outcome.copy(diagnosticCode = if (outcome.failure != null) probeErrno else null)
                else AwgProbeOutcome(failure = AwgProbeFailure.VPN_UNAVAILABLE)
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    override fun onRevoke() { requestStop() }
    override fun onDestroy() {
        // Android can destroy an active service without a stop broadcast; cleanup stays off main.
        requestStop()
        super.onDestroy()
    }
    override fun getService(): Service = this
    override fun startService() = Unit
    override fun stopService() { requestStop() }
    override fun vpnProtect(socket: Int): Boolean = protect(socket)
    override fun setUnderlyingNetworks(networks: Array<android.net.Network>?): Boolean =
        super<VpnService>.setUnderlyingNetworks(networks)
}
