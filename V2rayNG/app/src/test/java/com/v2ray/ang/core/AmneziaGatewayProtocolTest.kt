package com.v2ray.ang.core

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import java.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import com.v2ray.ang.core.amnezia.AmneziaGatewayProtocol
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AmneziaGatewayProtocolTest {
    @Test fun `import uses regular config endpoint and omits optional blank country`() {
        val api = JsonObject().apply { addProperty("user_country_code", "ir") }
        val auth = JsonObject().apply { addProperty("api_key", "synthetic") }
        val payload = AmneziaGatewayProtocol.request(api, auth, "public-test", "installation-test")
        assertEquals("http://gw.amnezia.org/v1/config", AmneziaGatewayProtocol.CONFIG_ENDPOINT)
        assertFalse(payload.has("server_country_code"))
        assertEquals("ir", payload["user_country_code"].asString)
        assertEquals(auth, payload["auth_data"])
        assertEquals("public-test", payload["public_key"].asString)
    }
    @Test fun `blank optional user country is omitted as in upstream`() {
        val api = JsonObject().apply { addProperty("user_country_code", "") }
        assertFalse(AmneziaGatewayProtocol.request(api, JsonObject(), "public", "id").has("user_country_code"))
    }

    @Test fun `regular gateway encoded response becomes usable native AWG config`() {
        val key = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
        val last = JsonObject().apply {
            addProperty("config", "[Interface]\nPrivateKey = ${'$'}WIREGUARD_CLIENT_PRIVATE_KEY\nAddress = 10.8.0.2/32\n[Peer]\nPublicKey = AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=\nAllowedIPs = 0.0.0.0/0\nEndpoint = 192.0.2.1:51820")
            addProperty("Jc", 4)
            addProperty("H1", "123456")
        }
        val envelope = JsonObject().apply {
            add("containers", JsonArray().apply {
                add(JsonObject().apply { add("awg", JsonObject().apply { addProperty("last_config", last.toString()) }) })
            })
        }.toString().toByteArray()
        val compressed = ByteArrayOutputStream().apply {
            write(java.nio.ByteBuffer.allocate(4).putInt(envelope.size).array())
            DeflaterOutputStream(this).use { it.write(envelope) }
        }.toByteArray()
        for (bytes in listOf(envelope, compressed)) for (prefix in listOf("", "vpn://")) {
            val response = JsonObject().apply {
                addProperty("config", prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
            }
            val native = AmneziaGatewayProtocol.nativeConfig(response, key)
            assertTrue(native.contains("PrivateKey = $key"))
            assertFalse(native.contains("WIREGUARD_CLIENT_PRIVATE_KEY"))
            assertTrue(native.contains("Jc = 4"))
            assertTrue(native.contains("H1 = 123456"))
        }
    }
}
