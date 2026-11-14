package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ConnectionSnapshot
import com.v2ray.ang.dto.OutboundTrafficStat

/** Pure accumulator for Xray's reset-on-read counters. One owner supplies each delta once. */
internal class ConnectionMeter(initial: ConnectionSnapshot) {
    var snapshot = initial
        private set

    fun sample(now: Long, stats: List<OutboundTrafficStat>): ConnectionSnapshot {
        val previous = snapshot
        if (!previous.running || !previous.trafficAvailable || now <= previous.sampledAt) return previous
        var up = 0L
        var down = 0L
        var direct = 0L
        var proxy = 0L
        stats.forEach { stat ->
            if (stat.value < 0 || stat.tag == AppConfig.TAG_BLOCKED) return@forEach
            when (stat.direction) {
                AppConfig.UPLINK -> up += stat.value
                AppConfig.DOWNLINK -> down += stat.value
                else -> return@forEach
            }
            if (stat.tag == AppConfig.TAG_DIRECT) direct += stat.value else proxy += stat.value
        }
        val seconds = (now - previous.sampledAt) / 1000.0
        snapshot = previous.copy(
            sampledAt = now, sampleReady = true,
            uploaded = previous.uploaded + up, downloaded = previous.downloaded + down,
            uploadRate = (up / seconds).toLong(), downloadRate = (down / seconds).toLong(),
            proxyRate = (proxy / seconds).toLong(), directRate = (direct / seconds).toLong(),
        )
        return snapshot
    }
}
