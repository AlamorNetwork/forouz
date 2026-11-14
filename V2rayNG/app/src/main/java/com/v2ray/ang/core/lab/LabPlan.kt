package com.v2ray.ang.core.lab

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/** A laboratory run owns test order, never the running VPN or its selected profile. */
internal enum class LabTransport {
    XHTTP, WS, GRPC, RAW, HTTP_UPGRADE, HTTP2, KCP, HYSTERIA, WIREGUARD, AMNEZIAWG, COMPOSITE, OTHER;

    companion object {
        fun of(profile: ProfileItem): LabTransport = when (profile.configType) {
            EConfigType.AMNEZIAWG -> AMNEZIAWG
            EConfigType.WIREGUARD -> WIREGUARD
            EConfigType.HYSTERIA, EConfigType.HYSTERIA2 -> HYSTERIA
            EConfigType.CUSTOM, EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN -> COMPOSITE
            else -> when (profile.network?.lowercase()) {
                "xhttp", "splithttp" -> XHTTP
                "ws" -> WS
                "grpc" -> GRPC
                "raw", "tcp" -> RAW
                "httpupgrade" -> HTTP_UPGRADE
                "http", "h2" -> HTTP2
                "kcp" -> KCP
                null, "" -> if (profile.configType in setOf(EConfigType.VLESS, EConfigType.VMESS,
                        EConfigType.TROJAN, EConfigType.SHADOWSOCKS)) RAW else OTHER
                else -> OTHER
            }
        }
    }
}

internal data class LabTarget(val guid: String, val groupId: String, val transport: LabTransport) {
    init { require(guid.isNotBlank()) }
}

internal object LabPlan {
    // Decimal MB: ten configurations preview as 100 MB, before protocol overhead.
    const val DOWNLOAD_BYTES = 10_000_000L

    fun estimatedDownloadBytes(targetCount: Int, rounds: Int = 1): Long {
        require(targetCount >= 0 && rounds >= 0)
        return Math.multiplyExact(Math.multiplyExact(targetCount.toLong(), rounds.toLong()), DOWNLOAD_BYTES)
    }

    /** One finite sweep, without replacement: categories interleave, each GUID occurs once. */
    fun sweep(targets: List<LabTarget>): List<LabTarget> {
        require(targets.map { it.guid }.distinct().size == targets.size)
        val buckets = targets.groupBy { it.transport }.toSortedMap(compareBy { it.ordinal })
        val result = ArrayList<LabTarget>(targets.size)
        var offset = 0
        while (result.size < targets.size) {
            buckets.values.forEach { it.getOrNull(offset)?.let(result::add) }
            offset++
        }
        return result.toList()
    }
}

/** Continuous two-level round robin. Callers supply only currently eligible targets. */
internal class LabRoundRobin {
    private var lastTransport: LabTransport? = null
    private val lastGuid = mutableMapOf<LabTransport, String>()

    fun next(eligible: List<LabTarget>): LabTarget? {
        require(eligible.map { it.guid }.distinct().size == eligible.size)
        val buckets = eligible.groupBy { it.transport }.toSortedMap(compareBy { it.ordinal })
        if (buckets.isEmpty()) return null
        val transport = buckets.keys.firstOrNull { it.ordinal > (lastTransport?.ordinal ?: -1) }
            ?: buckets.firstKey()
        val targets = buckets.getValue(transport)
        val previous = targets.indexOfFirst { it.guid == lastGuid[transport] }
        val target = targets[(previous + 1) % targets.size]
        lastTransport = transport
        lastGuid[transport] = target.guid
        return target
    }
}
