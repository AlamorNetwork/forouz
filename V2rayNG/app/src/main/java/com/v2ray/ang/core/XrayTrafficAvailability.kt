package com.v2ray.ang.core

import com.google.gson.JsonParser

/** Custom JSON may retain its own policy. Missing counters must not look like measured zero. */
internal object XrayTrafficAvailability {
    fun enabled(configuration: String): Boolean = runCatching {
        val json = JsonParser.parseString(configuration).asJsonObject
        if (json.get("stats")?.isJsonObject != true) return@runCatching false
        val policy = json.getAsJsonObject("policy")?.getAsJsonObject("system") ?: return@runCatching false
        policy.get("statsOutboundUplink")?.asBoolean == true &&
            policy.get("statsOutboundDownlink")?.asBoolean == true
    }.getOrDefault(false)
}
