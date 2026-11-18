package com.v2ray.ang.core.cdn

/** Exact user-supplied public IPv4 addresses only; never expands a range or accepts a proxy URL. */
internal object CdnManualIps {
    fun parse(raw: String): List<String> {
        require(raw.isNotBlank() && raw.length <= 16_384) { "Invalid manual IP list" }
        val addresses = raw.trim().split(Regex("[\\s,;]+"))
        require(addresses.all(CdnPolicy::publicAddress)) { "Invalid manual IP list" }
        return addresses.distinct().also { require(it.size in 1..512) { "Invalid manual IP list" } }
    }
}
