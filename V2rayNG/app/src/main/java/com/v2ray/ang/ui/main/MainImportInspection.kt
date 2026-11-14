package com.v2ray.ang.ui.main

import com.google.gson.JsonParser
import java.net.URI

/** Format-only preview. No configuration, credential, address, or raw error reaches UI state. */
data class MainImportInspection(val profiles: Int = 0, val subscriptions: Int = 0, val advanced: Int = 0,
    val malformed: Int = 0, val unsupported: Int = 0, val repeated: Int = 0, val tooLarge: Boolean = false) {
    val canImport: Boolean get() = !tooLarge && profiles + subscriptions + advanced > 0
}
data class MainImportReview(val id: String, val inspection: MainImportInspection, val groupId: String = "")

/** Recognized syntax is not a working server. The existing importer still performs final validation. */
internal fun inspectProfileImport(raw: String, decode: (String) -> String = { "" }): MainImportInspection {
    if (raw.length > 8_000_000) return MainImportInspection(tooLarge = true)
    var text = raw.trim().removePrefix("\uFEFF").trim()
    if (text.isBlank()) return MainImportInspection(malformed = 1)
    if (!text.contains("://") && !text.startsWith("[") && !text.startsWith("{")) {
        val decoded = try { decode(text) } catch (_: Exception) { "" }
        if (decoded.isNotBlank()) text = decoded.trim()
    }
    if (text.startsWith("[Interface]", true)) return MainImportInspection(
        advanced = if (text.contains("[Peer]", true)) 1 else 0, malformed = if (text.contains("[Peer]", true)) 0 else 1)
    if (text.startsWith("{") || text.startsWith("[")) return try {
        val json = JsonParser.parseString(text)
        if (json.isJsonObject || json.isJsonArray) MainImportInspection(advanced = 1) else MainImportInspection(malformed = 1)
    } catch (_: Exception) { MainImportInspection(malformed = 1) }
    val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).take(20_001).toList()
    if (lines.size > 20_000) return MainImportInspection(tooLarge = true)
    val schemes = setOf("vless", "vmess", "ss", "socks", "socks4", "socks5", "trojan", "wireguard", "wg", "hysteria2", "hy2")
    var result = MainImportInspection(repeated = lines.size - lines.distinct().size)
    for (line in lines) {
        val uri = try { URI(line) } catch (_: Exception) { result = result.copy(malformed = result.malformed + 1); continue }
        result = when (uri.scheme?.lowercase()) {
            "http", "https" -> if (!uri.host.isNullOrBlank()) result.copy(subscriptions = result.subscriptions + 1)
                else result.copy(malformed = result.malformed + 1)
            "vpn" -> if (!uri.rawAuthority.isNullOrBlank()) result.copy(advanced = result.advanced + 1)
                else result.copy(malformed = result.malformed + 1)
            in schemes -> if (!uri.rawAuthority.isNullOrBlank()) result.copy(profiles = result.profiles + 1)
                else result.copy(malformed = result.malformed + 1)
            else -> result.copy(unsupported = result.unsupported + 1)
        }
    }
    return result
}
