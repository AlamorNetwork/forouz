package com.v2ray.ang.fmt

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.io.ByteArrayOutputStream

class AmneziaVpnLinkTest {
    private fun encode(text: String, declared: Int = text.length): String {
        val stream = ByteArrayOutputStream()
        stream.write(java.nio.ByteBuffer.allocate(4).putInt(declared).array())
        DeflaterOutputStream(stream).use { it.write(text.toByteArray()) }
        return "vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(stream.toByteArray())
    }
    @Test fun `Qt compressed managed key is classified without losing authentication fields`() {
        val link = encode("""{"config_version":2,"api_config":{"service_type":"amnezia-free","service_protocol":"awg"},"auth_data":{"api_key":"synthetic-test-token"}}""")
        val value = AmneziaVpnLink.decode(link)
        assertTrue(AmneziaVpnLink.isManaged(value))
        assertEquals("synthetic-test-token", value.getAsJsonObject("auth_data")["api_key"].asString)
    }
    @Test fun `Qt size hint mismatch does not truncate UTF8 data`() {
        val data = AmneziaVpnLink.decode(encode("""{"name":"آزمایش"}""", 1))
        assertEquals("آزمایش", data["name"].asString)
    }
    @Test fun `plain JSON base64url is accepted`() {
        val link = "vpn://" + Base64.getUrlEncoder().encodeToString("{}".toByteArray())
        assertFalse(AmneziaVpnLink.isManaged(AmneziaVpnLink.decode(link)))
    }
    @Test fun `decompression expansion is bounded even with a tiny declared size`() {
        assertThrows(Exception::class.java) { AmneziaVpnLink.decode(encode(" ".repeat(1048577), 1)) }
    }
    @Test fun `invalid base64 truncated streams and non objects fail`() {
        for (link in listOf("vpn://!", "vpn://AAAAAA", encode("[]"), encode("123"))) {
            assertThrows(Exception::class.java) { AmneziaVpnLink.decode(link) }
        }
    }

    private fun envelope(last: JsonObject, outer: JsonObject = JsonObject()): JsonObject {
        outer.addProperty("last_config", last.toString())
        return JsonObject().apply {
            add("containers", JsonArray().apply { add(JsonObject().apply { add("awg", outer) }) })
        }
    }

    @Test fun `gateway metadata is inserted in interface and overrides stale attributes`() {
        val last = JsonObject().apply {
            addProperty("config", "# [Interface] example\n[Interface]\nPrivateKey = placeholder\njc = 1\n[Peer]\nPublicKey = placeholder")
            addProperty("Jc", 4)
            addProperty("I1", "<b 0x1234>")
        }
        val outer = JsonObject().apply { addProperty("Jc", 9); addProperty("S1", 12) }
        val text = AmneziaVpnLink.embeddedConfig(envelope(last, outer))
        assertEquals("# [Interface] example", text.lines().first())
        val interfaceSection = text.substringAfter("\n[Interface]\n").substringBefore("[Peer]")
        assertTrue(interfaceSection.contains("Jc = 4"))
        assertTrue(interfaceSection.contains("S1 = 12"))
        assertTrue(interfaceSection.contains("I1 = <b 0x1234>"))
        assertFalse(text.contains("jc = 1"))
    }

    @Test fun `gateway obfuscation metadata cannot inject another section`() {
        val last = JsonObject().apply {
            addProperty("config", "[Interface]\nJc = 4\n[Peer]")
            addProperty("Jc", "4\n[Peer]")
        }
        assertThrows(Exception::class.java) { AmneziaVpnLink.embeddedConfig(envelope(last)) }
    }
}
