package com.v2ray.ang.core

import android.content.Context
import android.os.SystemClock
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ConnectionSnapshot
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Each daemon owns this lifetime. UI and notification share its single reset-counter reader. */
internal object ConnectionTelemetry {
    private var owner: Job? = null
    private var meter: ConnectionMeter? = null
    private val counterGate = CounterReadGate()
    @Volatile private var latest: ConnectionSnapshot? = null

    internal fun snapshot(): ConnectionSnapshot? = latest

    @Synchronized
    fun start(context: Context, guid: String, trafficAvailable: Boolean,
        privacyRoute: com.v2ray.ang.core.privacy.PrivacyRoute = com.v2ray.ang.core.privacy.PrivacyRoute.UNAVAILABLE,
        privacyProxyPort: Int? = null) {
        owner?.cancel()
        owner = null
        val now = SystemClock.elapsedRealtime()
        val session = ConnectionSnapshot(UUID.randomUUID().toString(), guid, now, now,
            trafficAvailable = trafficAvailable, privacyRoute = privacyRoute, privacyProxyPort = privacyProxyPort)
        val accumulator = ConnectionMeter(session)
        meter = accumulator
        val token = counterGate.activate()
        publish(context, session)
        if (!trafficAvailable) return
        val job = SupervisorJob()
        owner = job
        CoroutineScope(job + Dispatchers.IO).launch {
            while (isActive) {
                delay(3_000)
                try {
                    val stats = counterGate.read(token) { CoreServiceManager.queryAllOutboundTrafficStats() }
                        ?: continue
                    synchronized(this@ConnectionTelemetry) {
                        if (meter !== accumulator || !isActive) return@synchronized
                        val snapshot = accumulator.sample(SystemClock.elapsedRealtime(), stats)
                        publish(context, snapshot)
                        NotificationManager.updateTraffic(snapshot)
                    }
                } catch (e: Exception) {
                    synchronized(this@ConnectionTelemetry) {
                        if (meter !== accumulator || !isActive) return@synchronized
                        LogUtil.e(AppConfig.TAG, "Xray telemetry query failed profile=$guid", e)
                        val unavailable = accumulator.snapshot.copy(trafficAvailable = false, sampleReady = false)
                        publish(context, unavailable)
                        NotificationManager.updateTraffic(unavailable)
                        job.cancel()
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop(context: Context) {
        owner?.cancel()
        counterGate.invalidate()
        owner = null
        meter = null
        latest?.takeIf { it.running }?.let {
            publish(context, it.copy(running = false, sampledAt = SystemClock.elapsedRealtime(),
                uploadRate = 0, downloadRate = 0, proxyRate = 0, directRate = 0))
        }
    }

    @Synchronized
    fun sendCurrent(context: Context) {
        latest?.let { MessageHelper.sendMsg2UI(context, AppConfig.MSG_CONNECTION_SNAPSHOT, it) }
    }

    fun <T> withoutCounterRead(action: () -> T): T = counterGate.exclusive(action)

    private fun publish(context: Context, snapshot: ConnectionSnapshot) {
        latest = snapshot
        MessageHelper.sendMsg2UI(context, AppConfig.MSG_CONNECTION_SNAPSHOT, snapshot)
    }
}
