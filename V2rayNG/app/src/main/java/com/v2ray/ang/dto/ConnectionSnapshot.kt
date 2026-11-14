package com.v2ray.ang.dto

import java.io.Serializable

/** Daemon-owned monotonic times and byte counts; contains no configuration or endpoint. */
data class ConnectionSnapshot(
    val sessionId: String,
    val profileGuid: String,
    val startedAt: Long,
    val sampledAt: Long,
    val running: Boolean = true,
    val trafficAvailable: Boolean = false,
    val sampleReady: Boolean = false,
    val uploaded: Long = 0,
    val downloaded: Long = 0,
    val uploadRate: Long = 0,
    val downloadRate: Long = 0,
    val proxyRate: Long = 0,
    val directRate: Long = 0,
    val privacyRoute: com.v2ray.ang.core.privacy.PrivacyRoute = com.v2ray.ang.core.privacy.PrivacyRoute.UNAVAILABLE,
    val privacyProxyPort: Int? = null,
) : Serializable
