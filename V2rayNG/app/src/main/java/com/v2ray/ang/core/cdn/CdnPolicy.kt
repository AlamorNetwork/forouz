package com.v2ray.ang.core.cdn

import com.google.gson.JsonParser
import com.v2ray.ang.core.lab.LabProbeConfig
import com.v2ray.ang.core.lab.LabRecords
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.time.OffsetDateTime
import kotlin.random.Random

internal data class CdnPrefix(val first: Long, val last: Long) {
    fun cidr(): String = format(first) + "/" + (32 - java.lang.Long.numberOfTrailingZeros(last - first + 1))
    companion object {
        fun ipv4(value: String): Long? {
            val parts = value.split('.')
            if (parts.size != 4) return null
            var result = 0L
            for (part in parts) {
                if (!part.matches(Regex("0|[1-9][0-9]{0,2}"))) return null
                val n = part.toIntOrNull()?.takeIf { it <= 255 } ?: return null
                result = (result shl 8) or n.toLong()
            }
            return result
        }
        fun parse(value: String): CdnPrefix {
            val parts = value.split('/')
            require(parts.size == 2)
            val address = requireNotNull(ipv4(parts[0]))
            val bits = parts[1].toInt().also { require(it in 1..32) }
            val size = 1L shl (32 - bits)
            val first = address / size * size
            return CdnPrefix(first, first + size - 1)
        }
        fun format(value: Long) = (3 downTo 0).joinToString(".") { ((value shr (it * 8)) and 255).toString() }
    }
    fun contains(value: String) = ipv4(value)?.let { it in first..last } == true
    fun overlaps(other: CdnPrefix) = first <= other.last && other.first <= last
}

/** Feed ownership and native test policy are separate from UI state and profile persistence. */
internal enum class CdnCatalogIssue { OLD, FUTURE }
internal class CdnCatalogException(val issue: CdnCatalogIssue) : IllegalArgumentException(issue.name)

internal class CdnCatalog(raw: String, now: Long = System.currentTimeMillis()) {
    val generatedAt: Long
    val ranges: Map<String, List<CdnPrefix>>
    private val observed: Map<String, List<String>>
    init {
        require(raw.length <= 2_000_000)
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.get("schema_version").asString == "1")
        // The feed carries an ISO offset, including +00:00; parse that offset explicitly.
        generatedAt = OffsetDateTime.parse(root.get("generated_at").asString).toInstant().toEpochMilli()
        if (generatedAt < now - 30L * 86400_000) throw CdnCatalogException(CdnCatalogIssue.OLD)
        if (generatedAt > now + 600_000) throw CdnCatalogException(CdnCatalogIssue.FUTURE)
        val providers = root.getAsJsonObject("providers")
        ranges = listOf("cloudflare", "cloudfront").associateWith { provider ->
            val entries = providers.getAsJsonObject(provider).getAsJsonArray("official_ipv4")
            require(entries.size() in 1..2048)
            entries.map { CdnPrefix.parse(it.asString) }.sortedBy { it.first }.also { list ->
                require(list.all { CdnPolicy.public(it) })
                require(list.zipWithNext().all { (a, b) -> a.last < b.first })
            }
        }
        observed = ranges.keys.associateWith { provider ->
            val values = providers.getAsJsonObject(provider).getAsJsonArray("edge_observed_ipv4")
            require(values.size() <= 128)
            values.map { it.asString }.distinct().filter { ip -> ranges.getValue(provider).any { it.contains(ip) } }
        }
    }
    fun provider(addresses: List<String>): String? = ranges.keys.filter { name ->
        addresses.any { ip -> ranges.getValue(name).any { it.contains(ip) } }
    }.singleOrNull()

    fun candidates(provider: String, random: Random = Random.Default): List<String> {
        val prefixes = ranges.getValue(provider)
        val chosen = observed.getValue(provider).shuffled(random).take(4).toMutableSet()
        val total = prefixes.sumOf { it.last - it.first + 1 }
        repeat(128) {
            if (chosen.size >= CdnPolicy.MAX_ADDRESSES) return@repeat
            var offset = random.nextLong(total)
            for (range in prefixes) {
                val size = range.last - range.first + 1
                if (offset < size) { chosen.add(CdnPrefix.format(range.first + offset)); break }
                offset -= size
            }
        }
        return chosen.toList()
    }
}

internal object CdnPolicy {
    fun edgeSupported(p: ProfileItem): Boolean {
        if (p.server.isNullOrBlank()) return false
        return try { CdnEdgeProbe.sni(p.sni.orEmpty()); true } catch (_: IllegalArgumentException) { false }
    }
    const val FEED = "https://raw.githubusercontent.com/AlamorNetwork/forouz/master/catalogs/edge-candidates.json"
    const val MAX_ADDRESSES = 8
    const val SAMPLES = 3
    const val LATENCY_BYTES = 1024L
    const val LATENCY_URL = "https://speed.cloudflare.com/__down?bytes=1024"
    private val blocked = listOf("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
        "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.168.0.0/16",
        "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4").map(CdnPrefix::parse)

    fun public(prefix: CdnPrefix) = blocked.none(prefix::overlaps)
    fun publicAddress(address: String) = CdnPrefix.ipv4(address)?.let { public(CdnPrefix(it, it)) } == true

    fun supported(p: ProfileItem): Boolean = LabRecords.supported(p) &&
        p.configType in setOf(EConfigType.VLESS, EConfigType.VMESS) && p.security == "tls" &&
        p.network in setOf("ws", "xhttp", "splithttp") && !p.sni.isNullOrBlank() && !p.host.isNullOrBlank() &&
        p.alpn.orEmpty().split(',').none { it.trim().startsWith("h3") } && extraSupported(p.xhttpExtra)

    private fun extraSupported(text: String?): Boolean {
        if (text.isNullOrBlank()) return true
        if (text.length > 32768) return false
        return try { !JsonParser.parseString(text).asJsonObject.has("downloadSettings") } catch (_: Exception) { false }
    }

    fun trial(p: ProfileItem, revision: String, address: String): ProfileItem {
        require(supported(p) && LabRecords.revision(p) == revision && publicAddress(address))
        return p.copy(server = address, subscriptionId = "", addedTime = System.currentTimeMillis())
    }

    fun config(raw: String, port: Int, address: String?): String {
        return withAddress(LabProbeConfig.build(raw, port), address)
    }

    /** Also accepts the stock speedtest config: preserve every field except the destination IP. */
    fun withAddress(raw: String, address: String?): String {
        val root = JsonParser.parseString(raw).asJsonObject
        val main = root.getAsJsonArray("outbounds")[0].asJsonObject
        require(main.get("protocol").asString in setOf("vless", "vmess") && !main.has("proxySettings"))
        val stream = main.getAsJsonObject("streamSettings")
        require(stream.get("security").asString == "tls" && stream.get("network").asString in setOf("ws", "xhttp", "splithttp"))
        require(!stream.has("sockopt") || !stream.getAsJsonObject("sockopt").has("dialerProxy"))
        require(stream.getAsJsonObject("tlsSettings").get("allowInsecure")?.asBoolean != true)
        require(stream.getAsJsonObject("xhttpSettings")?.has("downloadSettings") != true)
        val settings = main.getAsJsonObject("settings")
        // Current CoreOutboundBuilder emits a flat endpoint. Keep legacy vnext input readable too.
        // Reject mixed schemas rather than editing one address while another remains in effect.
        val endpoint = if (settings.has("address")) {
            require(!settings.has("vnext"))
            settings
        } else {
            val servers = settings.getAsJsonArray("vnext")
            require(servers != null && servers.size() == 1)
            servers[0].asJsonObject
        }
        val originalAddress = endpoint.get("address")
        require(originalAddress?.isJsonPrimitive == true && originalAddress.asJsonPrimitive.isString && originalAddress.asString.isNotBlank())
        if (address != null) { require(publicAddress(address)); endpoint.addProperty("address", address) }
        return root.toString()
    }
}
