package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

enum class FragmentPreset { BASELINE, LIGHT, SMALL }

/** Isolated local trials; never edits the source, global settings or server. */
object XrayFragmentVariants {
    /** Cheap menu predicate. create() revalidates JSON on the repository's IO dispatcher. */
    fun canOffer(profile: ProfileItem): Boolean =
        profile.configType in setOf(EConfigType.VLESS, EConfigType.VMESS, EConfigType.TROJAN) &&
            (profile.network.orEmpty() in setOf("", "tcp", "raw") ||
                (profile.network == "ws" && profile.security == "tls") ||
                (profile.network == "xhttp" && supportsXhttpTransport(profile))) &&
            profile.security in setOf("tls", "reality") && profile.finalMask.isNullOrBlank() &&
            profile.browserDialerMode.isNullOrBlank() &&
            !profile.server.isNullOrBlank() && (profile.serverPort?.toIntOrNull() ?: 0) in 1..65535

    fun supports(profile: ProfileItem): Boolean = canOffer(profile) &&
        (profile.network != "xhttp" || supportsXhttpExtra(profile.xhttpExtra))

    private fun supportsXhttpTransport(profile: ProfileItem): Boolean {
        // Pinned Xray a4a3dff chooses UDP for XHTTP+h3 and creates a distinct stream for
        // downloadSettings. Do not advertise a TCP-only trial for either of those paths.
        val alpn = profile.alpn.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (profile.security != "tls" || "h3" in alpn || (alpn.isNotEmpty() && "h2" !in alpn)) return false
        if (profile.xhttpMode.orEmpty() !in setOf("", "auto", "packet-up", "stream-up", "stream-one")) return false
        return true
    }

    private fun supportsXhttpExtra(value: String?): Boolean {
        if (value.isNullOrBlank()) return true
        return try {
            val extra = JsonParser.parseString(value)
            extra.isJsonObject && extra.asJsonObject.entrySet().none {
                it.key.equals("downloadSettings", true) && !it.value.isJsonNull
            }
        } catch (_: Exception) {
            false // Preserve the source; never drop malformed or unsupported extra settings.
        }
    }

    fun create(source: ProfileItem, preset: FragmentPreset, name: String, nowMillis: Long): ProfileItem? {
        if (!supports(source) || name.isBlank()) return null
        val mask = JsonObject()
        if (preset != FragmentPreset.BASELINE) {
            val settings = JsonObject().apply {
                // TCP write slicing preserves the encrypted stream, including REALITY.
                // Bounded trial settings; DPI effectiveness requires a network comparison.
                addProperty("packets", if (preset == FragmentPreset.LIGHT) "1-1" else "1-3")
                addProperty("length", if (preset == FragmentPreset.LIGHT) "100-200" else "50-100")
                addProperty("delay", if (preset == FragmentPreset.LIGHT) "1-3" else "3-5")
                addProperty("maxSplit", if (preset == FragmentPreset.LIGHT) "3-5" else "5-8")
            }
            mask.add("tcp", JsonArray().apply {
                add(JsonObject().apply { addProperty("type", "fragment"); add("settings", settings) })
            })
        }
        return source.copy(
            subscriptionId = "", // Subscription refresh must not replace a local trial.
            addedTime = nowMillis,
            remarks = name,
            // Explicit {} overrides the global fragment setting for a genuine baseline.
            finalMask = mask.toString(),
        )
    }
}
