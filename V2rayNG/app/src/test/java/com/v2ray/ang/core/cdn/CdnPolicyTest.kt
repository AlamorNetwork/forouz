package com.v2ray.ang.core.cdn

import com.google.gson.JsonParser
import com.v2ray.ang.core.lab.LabRecords
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.util.JsonUtil
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random
import org.mockito.Mockito

class CdnPolicyTest {
    @Test fun `published production feed schema is accepted without private or overlapping prefixes`() {
        val raw = requireNotNull(javaClass.getResource("/cdn-feed.json")).readText()
        val generated = JsonParser.parseString(raw).asJsonObject.get("generated_at").asString
        val catalog = CdnCatalog(raw, java.time.Instant.parse(generated).toEpochMilli())
        assertEquals(15, catalog.ranges.getValue("cloudflare").size)
        assertEquals(97, catalog.ranges.getValue("cloudfront").size)
        assertEquals("cloudfront", catalog.provider(listOf("3.169.90.56")))
    }
    private val stamp = "2026-11-14T12:00:00Z"
    private val now = java.time.Instant.parse(stamp).toEpochMilli()
    private fun feed(cf: String = "8.8.8.0/24", observed: String = "8.8.8.8") = """{"schema_version":1,"generated_at":"$stamp","providers":{
        "cloudflare":{"official_ipv4":["$cf"],"edge_observed_ipv4":["$observed"]},
        "cloudfront":{"official_ipv4":["9.9.9.0/24"],"edge_observed_ipv4":[]}}}"""
    private fun profile() = ProfileItem(configType = EConfigType.VLESS, server = "8.8.8.1", network = "ws",
        security = "tls", sni = "edge.example", host = "edge.example", path = "/path", password = "fixture-only",
        echConfigList = "ech-fixture", finalMask = "mask-fixture", subscriptionId = "group")

    @Test fun `catalog accepts publisher UTC offset when legacy Android Instant parser rejects it`() {
        val offsetStamp = "2026-11-14T12:00:00.000000+00:00"
        // Android 11/12 InstantPrinterParser ends in a literal Z; model that platform boundary.
        Mockito.mockStatic(java.time.Instant::class.java, Mockito.CALLS_REAL_METHODS).use { legacy ->
            legacy.`when`<java.time.Instant> { java.time.Instant.parse(offsetStamp) }.thenThrow(
                java.time.format.DateTimeParseException("legacy offset boundary", offsetStamp, 26))
            assertEquals(now, CdnCatalog(feed().replace(stamp, offsetStamp), now).generatedAt)
        }
    }
    @Test fun `catalog accepts equivalent offsets but rejects missing invalid and trailing date text`() {
        for (value in listOf("2026-11-14T12:00:00Z", "2026-11-14T12:00:00+00:00", "2026-11-14T15:30:00+03:30"))
            assertEquals(now, CdnCatalog(feed().replace(stamp, value), now).generatedAt)
        for (value in listOf("2026-11-14T12:00:00", "2026-02-30T12:00:00Z", "2026-11-14T12:00:00+25:00", "2026-11-14T12:00:00Z junk"))
            assertThrows(Exception::class.java) { CdnCatalog(feed().replace(stamp, value), now) }
    }

    @Test fun `catalog validates schema freshness ranges and narrow prefixes`() {
        val catalog = CdnCatalog(feed(), now)
        assertEquals("cloudflare", catalog.provider(listOf("8.8.8.7")))
        assertEquals("cloudfront", catalog.provider(listOf("9.9.9.7")))
        assertNull(catalog.provider(listOf("8.8.8.7", "9.9.9.7")))
        assertNull(catalog.provider(listOf("11.1.1.1")))
        assertEquals("cloudflare", CdnCatalog(feed("8.8.8.8/32"), now).provider(listOf("8.8.8.8")))
        for (raw in listOf("{}", feed().replace("\"schema_version\":1", "\"schema_version\":2"),
            feed("10.0.0.0/8"), feed("172.0.0.0/8"), feed("224.0.0.0/4"), feed("invalid")))
            assertThrows(Exception::class.java) { CdnCatalog(raw, now) }
        assertEquals(CdnCatalogIssue.OLD, assertThrows(CdnCatalogException::class.java) { CdnCatalog(feed(), now + 31L * 86400_000) }.issue)
        assertEquals(CdnCatalogIssue.FUTURE, assertThrows(CdnCatalogException::class.java) { CdnCatalog(feed(), now - 601_000) }.issue)
    }
    @Test fun `sampling is bounded unique honors membership and never expands a prefix`() {
        val catalog = CdnCatalog(feed("8.0.0.0/8"), now)
        val values = catalog.candidates("cloudflare", Random(7))
        assertEquals(8, values.size); assertEquals(8, values.distinct().size)
        assertTrue(values.all { CdnPrefix.parse("8.0.0.0/8").contains(it) })
        assertEquals(listOf("8.8.8.8"), CdnCatalog(feed("8.8.8.8/32"), now).candidates("cloudflare", Random(7)))
        assertFalse(CdnCatalog(feed(observed = "10.0.0.1"), now).candidates("cloudflare").contains("10.0.0.1"))
    }
    @Test fun `eligibility rejects incompatible modes but retains ordinary XHTTP extra`() {
        val p = profile()
        assertTrue(CdnPolicy.supported(p))
        assertTrue(CdnPolicy.supported(p.copy(network = "xhttp", xhttpExtra = """{"xPaddingBytes":"100-1000","xmux":{"cMaxReuseTimes":8}}""")))
        for (bad in listOf(p.copy(security = "reality"), p.copy(network = "grpc"), p.copy(insecure = true),
            p.copy(sni = ""), p.copy(host = ""), p.copy(alpn = "h3"), p.copy(browserDialerMode = "browser"),
            p.copy(xhttpExtra = """{"downloadSettings":{}}"""), p.copy(xhttpExtra = "bad"),
            p.copy(configType = EConfigType.AMNEZIAWG))) assertFalse(CdnPolicy.supported(bad))
    }
    @Test fun `local trial changes only address and presentation and rejects changed source`() {
        val source = profile()
        val trial = CdnPolicy.trial(source, LabRecords.revision(source), "8.8.8.9")
        assertEquals(source.copy(server = trial.server, subscriptionId = "", addedTime = trial.addedTime), trial)
        assertEquals("8.8.8.1", source.server)
        assertThrows(Exception::class.java) { CdnPolicy.trial(source.copy(path = "/changed"), LabRecords.revision(source), "8.8.8.9") }
        for (ip in listOf("127.0.0.1", "224.0.0.1", "host.invalid", "1.2.3.999"))
            assertThrows(Exception::class.java) { CdnPolicy.trial(source, LabRecords.revision(source), ip) }
    }
    @Test fun `native test config preserves TLS and transport while changing only vnext address`() {
        val raw = """{"outbounds":[{"protocol":"vless","mux":{"enabled":true,"concurrency":8},"settings":{"vnext":[{"address":"edge.example","port":443,"users":[{"id":"fixture"}]}]},
          "streamSettings":{"network":"ws","security":"tls","tlsSettings":{"serverName":"edge.example","allowInsecure":false},
          "wsSettings":{"path":"/path","headers":{"Host":"edge.example"}}}}]}"""
        val baseline = JsonParser.parseString(CdnPolicy.config(raw, 12000, null)).asJsonObject
        val candidate = JsonParser.parseString(CdnPolicy.config(raw, 12000, "8.8.8.9")).asJsonObject
        assertTrue(candidate.getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("mux").get("enabled").asBoolean)
        baseline.getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject.addProperty("address", "8.8.8.9")
        assertEquals(baseline, candidate)
        assertThrows(Exception::class.java) { CdnPolicy.config(raw.replace("\"allowInsecure\":false", "\"allowInsecure\":true"), 12000, "8.8.8.9") }
        assertThrows(Exception::class.java) { CdnPolicy.config(raw.replace("\"network\":\"ws\"", "\"network\":\"raw\""), 12000, null) }
    }
    @Test fun `current runtime outbound model supports baseline and changes only flat endpoint address`() {
        for (protocol in listOf("vless", "vmess")) for (transport in listOf("ws", "xhttp")) {
            val outbound = V2rayConfig.OutboundBean(protocol = protocol,
                settings = V2rayConfig.OutboundBean.OutSettingsBean(address = "edge.example", port = 443, id = "fixture", encryption = "none"),
                streamSettings = V2rayConfig.OutboundBean.StreamSettingsBean(network = transport, security = "tls",
                    tlsSettings = V2rayConfig.OutboundBean.StreamSettingsBean.TlsSettingsBean(serverName = "edge.example")))
            val tree = JsonParser.parseString(JsonUtil.toJson(outbound)).asJsonObject
            tree.add("mux", JsonParser.parseString("""{"enabled":true,"concurrency":8}"""))
            tree.getAsJsonObject("streamSettings").add("finalmask", JsonParser.parseString("""{"tcp":[{"type":"fragment","settings":{"packets":"1-1","length":"100-200","delay":"1-3","maxSplit":"3-5"}}]}"""))
            tree.getAsJsonObject("streamSettings").add(if (transport == "ws") "wsSettings" else "xhttpSettings",
                JsonParser.parseString(if (transport == "ws") """{"path":"/path","host":"edge.example"}""" else
                    """{"path":"/path","host":"edge.example","mode":"packet-up","extra":{"xPaddingBytes":"100-1000","xmux":{"cMaxReuseTimes":8}}}"""))
            val raw = JsonObject().apply { add("outbounds", JsonArray().apply { add(tree) }) }.toString()
            val baseline = JsonParser.parseString(CdnPolicy.config(raw, 12000, null)).asJsonObject
            assertEquals(tree, baseline.getAsJsonArray("outbounds")[0].asJsonObject)
            val candidate = JsonParser.parseString(CdnPolicy.config(raw, 12000, "8.8.8.9")).asJsonObject
            val expected = JsonParser.parseString(CdnPolicy.config(raw, 12000, null)).asJsonObject
            expected.getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("settings").addProperty("address", "8.8.8.9")
            assertEquals(expected, candidate)
            assertFalse(tree.getAsJsonObject("settings").has("vnext"))
        }
    }
    @Test fun `endpoint schema rejects ambiguous missing or multiple destinations`() {
        val stream = """"streamSettings":{"network":"ws","security":"tls","tlsSettings":{"allowInsecure":false}}"""
        for (settings in listOf("{}", """{"address":"edge.example","vnext":[{"address":"other.example"}]}""",
            """{"vnext":[{"address":"one.example"},{"address":"two.example"}]}""", """{"address":[]}""", """{"address":""}""")) {
            val raw = """{"outbounds":[{"protocol":"vless","settings":$settings,$stream}]}"""
            assertThrows(Exception::class.java) { CdnPolicy.config(raw, 12000, "8.8.8.9") }
        }
    }

    @Test fun `stock latency config replacement adds no SOCKS routing or runtime fields in either endpoint schema`() {
        for (settings in listOf("""{"address":"edge.example","port":443,"id":"fixture"}""",
            """{"vnext":[{"address":"edge.example","port":443,"users":[{"id":"fixture"}]}]}""")) {
            val raw = """{"inbounds":[],"routing":{"rules":[]},"outbounds":[{"protocol":"vless","settings":$settings,
                "streamSettings":{"network":"ws","security":"tls","tlsSettings":{"serverName":"edge.example","allowInsecure":false},
                "wsSettings":{"path":"/path","host":"edge.example"},"finalmask":{"tcp":[]}}}]}"""
            assertEquals(JsonParser.parseString(raw), JsonParser.parseString(CdnPolicy.withAddress(raw, null)))
            val result = JsonParser.parseString(CdnPolicy.withAddress(raw, "8.8.8.9")).asJsonObject
            val expected = JsonParser.parseString(raw.replace("\"address\":\"edge.example\"", "\"address\":\"8.8.8.9\"")).asJsonObject
            assertEquals(expected, result)
            assertEquals(0, result.getAsJsonArray("inbounds").size())
            assertEquals(0, result.getAsJsonObject("routing").getAsJsonArray("rules").size())
            assertFalse(result.has("dns")); assertFalse(result.has("policy"))
            assertThrows(Exception::class.java) { CdnPolicy.withAddress(raw, "127.0.0.1") }
            assertThrows(Exception::class.java) { CdnPolicy.withAddress(raw.replace("\"allowInsecure\":false", "\"allowInsecure\":true"), null) }
        }
    }
}
