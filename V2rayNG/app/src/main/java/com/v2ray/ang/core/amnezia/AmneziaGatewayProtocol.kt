package com.v2ray.ang.core.amnezia

import com.google.gson.JsonObject
import com.v2ray.ang.fmt.AmneziaVpnLink
import com.v2ray.ang.fmt.AmneziaWgFmt

/** GatewayRequestData omits empty optional strings; sending an empty country fails validation. */
internal object AmneziaGatewayProtocol {
    const val CONFIG_ENDPOINT = "http://gw.amnezia.org/v1/config"
    fun nativeConfig(result: JsonObject, privateKey: String): String {
        val payload = result["config"]?.asString ?: error("Missing gateway configuration")
        // Upstream accepts both a vpn:// link and its bare base64url payload.
        val envelope = AmneziaVpnLink.decode("vpn://" + payload.removePrefix("vpn://"))
        val text = AmneziaVpnLink.embeddedConfig(envelope)
            .replace("$" + "WIREGUARD_CLIENT_PRIVATE_KEY", privateKey)
        AmneziaWgFmt.parseConfig(text)
        return text
    }

    fun request(api: JsonObject, auth: JsonObject, publicKey: String, installation: String): JsonObject =
        JsonObject().apply {
            addProperty("os_version", "android")
            addProperty("app_version", "5.0.3.0")
            addProperty("app_language", "en")
            addProperty("installation_uuid", installation)
            api["user_country_code"]?.asString?.takeIf { it.isNotBlank() }?.let {
                addProperty("user_country_code", it)
            }
            addProperty("service_type", "amnezia-free")
            addProperty("service_protocol", "awg")
            add("auth_data", auth)
            addProperty("public_key", publicKey)
        }
}
