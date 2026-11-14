package com.v2ray.ang.core.privacy

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Single, user-requested observation; deliberately never persisted, logged or exported. */
data class PrivacyObservation(
    val vpn: Boolean,
    val physicalNetwork: Long?,
    val measuredAt: Long,
    val elapsedAt: Long,
    val ipv4: String? = null,
    val ipv6: String? = null,
    val resolvers: Set<String> = emptySet(),
    val proxyPath: Boolean = false,
)

/** Daemon-admitted route, never inferred from the selected profile or edited settings. */
enum class PrivacyRoute { UNAVAILABLE, DIRECT, OWNED_VPN, XRAY_SOCKS }

internal fun privacyRoute(vpn: Boolean, admitted: PrivacyRoute, port: Int?): PrivacyRoute = when {
    !vpn -> PrivacyRoute.DIRECT // The adapter must additionally verify that no VPN is present.
    admitted == PrivacyRoute.OWNED_VPN -> admitted
    admitted == PrivacyRoute.XRAY_SOCKS && port != null && port in 1..65535 -> admitted
    else -> PrivacyRoute.UNAVAILABLE
}

enum class PrivacyIssue { UNSUPPORTED, VPN_UNAVAILABLE, DISCONNECT_FIRST, NETWORK_CHANGED, FAILED }
sealed interface PrivacyProbeReply {
    data class Observed(val observation: PrivacyObservation) : PrivacyProbeReply
    data class Unavailable(val issue: PrivacyIssue) : PrivacyProbeReply
}

enum class DnsComparison { NO_BASELINE, BASELINE_EXPIRED, DIFFERENT_NETWORK, UNAVAILABLE, POSSIBLE_BYPASS, NO_MATCH_IN_SAMPLE }

/** Shared public resolvers and forwarders make address overlap a warning, never proof of a leak. */
fun compareDns(sample: PrivacyObservation, baseline: PrivacyObservation?, now: Long): DnsComparison = when {
    sample.proxyPath -> DnsComparison.UNAVAILABLE
    !sample.vpn || baseline == null || baseline.vpn -> DnsComparison.NO_BASELINE
    now < baseline.elapsedAt || now - baseline.elapsedAt > BASELINE_MAX_AGE -> DnsComparison.BASELINE_EXPIRED
    sample.physicalNetwork == null || sample.physicalNetwork != baseline.physicalNetwork -> DnsComparison.DIFFERENT_NETWORK
    sample.resolvers.isEmpty() || baseline.resolvers.isEmpty() -> DnsComparison.UNAVAILABLE
    sample.resolvers.any { it in baseline.resolvers } -> DnsComparison.POSSIBLE_BYPASS
    else -> DnsComparison.NO_MATCH_IN_SAMPLE
}

const val BASELINE_MAX_AGE = 30 * 60 * 1000L
const val PRIVACY_SAMPLE_MAX_AGE = 120_000L

/** Only numeric literals reach InetAddress: never turn an untrusted API response into a lookup. */
fun numericIp(value: String, family: Int? = null): String? {
    if (value.length !in 2..45 || value != value.trim()) return null
    if (':' !in value) {
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || !it.all { c -> c in '0'..'9' } ||
                (it.length > 1 && it[0] == '0') || (it.toIntOrNull() ?: 256) !in 0..255 }) return null
    } else if (!value.all { it in "0123456789abcdefABCDEF:." }) return null
    return try {
        val address = InetAddress.getByName(value)
        if ((family == 4 && address !is Inet4Address) || (family == 6 && address !is Inet6Address)) null
        else address.hostAddress?.lowercase()
    } catch (_: Exception) { null }
}

/** Bounded TXT decoder for Akamai's documented whoami response. No geography/ASN inference. */
object ResolverAnswer {
    const val DOMAIN = "whoami.ds.akahelp.net"
    fun query(id: Int): ByteArray {
        require(id in 0..65535)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeShort(id); out.writeShort(0x0100); out.writeShort(1)
            out.writeShort(0); out.writeShort(0); out.writeShort(0)
            DOMAIN.split('.').forEach { label -> out.writeByte(label.length); out.writeBytes(label) }
            out.writeByte(0); out.writeShort(16); out.writeShort(1)
        }
        return buffer.toByteArray()
    }
    fun decode(bytes: ByteArray, expectedId: Int? = null): Set<String> =
        try { Reader(bytes, expectedId).decode() } catch (_: IllegalArgumentException) { emptySet() }

    private class Reader(val bytes: ByteArray, val expectedId: Int?) {
        var at = 0
        fun need(n: Int) { require(n >= 0 && at + n <= bytes.size) }
        fun byte(): Int { need(1); return bytes[at++].toInt() and 255 }
        fun word(): Int = (byte() shl 8) or byte()
        fun name(): String {
            val labels = mutableListOf<String>()
            var cursor = at
            var end = -1
            val seen = hashSetOf<Int>()
            repeat(128) {
                require(cursor in bytes.indices && seen.add(cursor))
                val length = bytes[cursor++].toInt() and 255
                if (length == 0) { at = if (end >= 0) end else cursor; return labels.joinToString(".") }
                if (length and 0xc0 == 0xc0) {
                    require(cursor in bytes.indices)
                    val pointer = ((length and 63) shl 8) or (bytes[cursor++].toInt() and 255)
                    if (end < 0) end = cursor
                    cursor = pointer
                } else {
                    require(length in 1..63 && cursor + length <= bytes.size)
                    labels.add(String(bytes, cursor, length, Charsets.US_ASCII).lowercase())
                    cursor += length
                }
            }
            throw IllegalArgumentException("DNS name")
        }
        fun decode(): Set<String> {
            require(bytes.size in 12..65535)
            val id = word(); require(expectedId == null || id == expectedId)
            val flags = word()
            require(flags and 0x8000 != 0 && flags and 0x7a0f == 0)
            require(word() == 1)
            val answers = word(); word(); word()
            require(answers in 1..128 && name() == DOMAIN && word() == 16 && word() == 1)
            val found = linkedSetOf<String>()
            repeat(answers) {
                val owner = name(); val type = word(); val klass = word()
                word(); word(); val length = word(); need(length)
                val end = at + length
                if (owner == DOMAIN && type == 16 && klass == 1) {
                    val parts = mutableListOf<String>()
                    while (at < end) {
                        val size = byte(); require(at + size <= end && parts.size < 32)
                        parts.add(String(bytes, at, size, Charsets.US_ASCII)); at += size
                    }
                    if (parts.size == 2 && parts[0] == "ns") numericIp(parts[1])?.let { found.add(it) }
                }
                at = end
            }
            return found.take(8).toSet()
        }
    }
}
