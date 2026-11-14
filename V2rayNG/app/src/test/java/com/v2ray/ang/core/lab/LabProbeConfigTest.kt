package com.v2ray.ang.core.lab

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LabProbeConfigTest {
    @Test fun `isolated config strips listeners and routes only to the candidate with transport intact`() {
        val raw = """{"log":{"loglevel":"debug"},"api":{},"observatory":{},"inbounds":[{"protocol":"tun"}],
            "outbounds":[{"tag":"proxy","protocol":"vless","streamSettings":{"network":"xhttp","security":"tls",
            "tlsSettings":{"serverName":"original.invalid"},"xhttpSettings":{"path":"/original/","mode":"packet-up"}}},
            {"tag":"direct","protocol":"freedom"}],"routing":{"rules":[{"outboundTag":"direct"}]}}"""
        val result = JsonParser.parseString(LabProbeConfig.build(raw, 15999)).asJsonObject
        val inbound = result.getAsJsonArray("inbounds").single().asJsonObject
        assertEquals("127.0.0.1", inbound.get("listen").asString)
        assertEquals("socks", inbound.get("protocol").asString)
        assertEquals(15999, inbound.get("port").asInt)
        assertFalse(result.has("api")); assertFalse(result.has("observatory"))
        assertEquals("proxy", result.getAsJsonObject("routing").getAsJsonArray("rules").single().asJsonObject.get("outboundTag").asString)
        assertEquals(JsonParser.parseString(raw).asJsonObject.get("outbounds"), result.get("outbounds"))
        assertEquals("none", result.getAsJsonObject("log").get("loglevel").asString)
    }

    @Test fun `a direct default or invalid config cannot masquerade as a candidate probe`() {
        for (raw in listOf("{}", "invalid", """{"outbounds":[]}""", """{"outbounds":[{"protocol":"freedom"}]}"""))
            assertThrows(Exception::class.java) { LabProbeConfig.build(raw, 15999) }
        assertThrows(IllegalArgumentException::class.java) { LabProbeConfig.build("{}", 80) }
    }
}
