package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.amnezia.awg.config.Config

/** Native AWG profiles retain their full configuration in the existing raw-profile store. */
object AmneziaWgFmt {
    private val awgAttribute = Regex("(?im)^\\s*(Jc|Jmin|Jmax|S[1-4]|H[1-4]|I[1-5]|K[1-5])\\s*=")

    fun isQuickConfig(text: String): Boolean = text.lineSequence()
        .map { it.removePrefix("\uFEFF").substringBefore('#').trim() }
        .firstOrNull { it.isNotEmpty() }.equals("[Interface]", ignoreCase = true)

    fun isAmneziaConfig(text: String): Boolean = awgAttribute.containsMatchIn(text)

    fun parseConfig(text: String): Config {
        require(text.length <= 262144)
        val config = Config.parse(text.removePrefix("\uFEFF").byteInputStream(Charsets.UTF_8))
        require(config.getInterface().addresses.isNotEmpty())
        require(config.peers.isNotEmpty())
        require(config.peers.all { it.endpoint.isPresent && it.allowedIps.isNotEmpty() })
        return config
    }

    fun parse(text: String): ProfileItem {
        val parsed = parseConfig(text)
        val endpoint = parsed.peers.first().endpoint.get()
        return ProfileItem.create(EConfigType.AMNEZIAWG).apply {
            remarks = "AmneziaWG"
            nativeConfigHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(parsed.toAwgQuickString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            server = endpoint.host.removeSurrounding("[", "]")
            serverPort = endpoint.port.toString()
        }
    }

    fun exportNative(text: String): String {
        require(isAmneziaConfig(text))
        return parseConfig(text).toAwgQuickString()
    }
}
