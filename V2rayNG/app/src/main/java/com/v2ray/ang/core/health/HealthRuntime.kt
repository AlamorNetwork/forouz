package com.v2ray.ang.core.health

import com.google.gson.JsonParser

/** Never guesses a port from edited settings or falls back to a direct connection. */
internal fun healthSocksPort(content: String): Int? {
    val inbounds = JsonParser.parseString(content).asJsonObject.getAsJsonArray("inbounds") ?: return null
    return inbounds.firstNotNullOfOrNull { entry ->
        val inbound = entry.asJsonObject
        if (inbound.get("protocol")?.asString != "socks") return@firstNotNullOfOrNull null
        if (inbound.get("listen")?.asString !in listOf("127.0.0.1", "0.0.0.0")) return@firstNotNullOfOrNull null
        val settings = inbound.getAsJsonObject("settings") ?: return@firstNotNullOfOrNull null
        if (settings.get("auth")?.asString !in listOf(null, "noauth")) return@firstNotNullOfOrNull null
        inbound.get("port")?.asInt?.takeIf { it in 1..65535 }
    }
}
