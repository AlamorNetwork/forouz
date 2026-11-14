package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class XrayFragmentVariantsTest {
    private fun source() = ProfileItem(configType = EConfigType.VLESS, server = "example.invalid", serverPort = "443",
        network = "tcp", security = "tls", password = "local-test-id", subscriptionId = "subscription", remarks = "original",
        sni = "example.invalid", fingerPrint = "chrome", alpn = "h2", insecure = false, addedTime = 12)

    @Test fun `light trial preserves server identity credentials and TLS policy without mutating source`() {
        val original = source()
        val before = original.copy()
        val trial = checkNotNull(XrayFragmentVariants.create(original, FragmentPreset.LIGHT, "trial", 40))
        assertEquals(before, original)
        assertEquals("", trial.subscriptionId)
        assertEquals(40L, trial.addedTime)
        assertEquals(original, trial.copy(subscriptionId = original.subscriptionId, addedTime = original.addedTime,
            remarks = original.remarks, finalMask = original.finalMask))
        val mask = JsonParser.parseString(trial.finalMask).asJsonObject
        assertFalse(mask.has("udp"))
        val item = mask.getAsJsonArray("tcp").single().asJsonObject
        assertEquals("fragment", item["type"].asString)
        val settings = item.getAsJsonObject("settings")
        assertEquals("1-1", settings["packets"].asString)
        assertEquals("100-200", settings["length"].asString)
        assertEquals("1-3", settings["delay"].asString)
        assertEquals("3-5", settings["maxSplit"].asString)
    }

    @Test fun `smaller fragments only slice first three writes with bounded delays and splits`() {
        val trial = checkNotNull(XrayFragmentVariants.create(source(), FragmentPreset.SMALL, "small", 40))
        val settings = JsonParser.parseString(trial.finalMask).asJsonObject.getAsJsonArray("tcp")[0].asJsonObject.getAsJsonObject("settings")
        assertEquals("1-3", settings["packets"].asString)
        assertEquals("50-100", settings["length"].asString)
        assertEquals("3-5", settings["delay"].asString)
        assertEquals("5-8", settings["maxSplit"].asString)
    }

    @Test fun `explicit baseline prevents global fragmentation and does not strip TLS`() {
        val trial = checkNotNull(XrayFragmentVariants.create(source(), FragmentPreset.BASELINE, "baseline", 40))
        assertEquals("{}", trial.finalMask)
        assertEquals("tls", trial.security)
        assertEquals(false, trial.insecure)
    }

    @Test fun `REALITY identity and flow survive both fragmentation variants`() {
        val reality = source().copy(security = "reality", publicKey = "test-public-key", shortId = "abcd", flow = "xtls-rprx-vision")
        for (preset in FragmentPreset.entries) {
            val trial = checkNotNull(XrayFragmentVariants.create(reality, preset, "trial", 40))
            assertEquals(reality.publicKey, trial.publicKey)
            assertEquals(reality.shortId, trial.shortId)
            assertEquals(reality.flow, trial.flow)
            assertEquals("reality", trial.security)
            assertFalse(trial.finalMask.orEmpty().contains("tlshello"))
        }
    }

    @Test fun `does not replace custom final masks or weaken incompatible profiles`() {
        val invalid = listOf(source().copy(finalMask = "{\"tcp\":[{\"type\":\"custom\"}]}"),
            source().copy(configType = EConfigType.AMNEZIAWG), source().copy(configType = EConfigType.CUSTOM),
            source().copy(network = "kcp"), source().copy(network = "xhttp", security = "reality"), source().copy(network = "hysteria"),
            source().copy(security = "none"), source().copy(server = ""), source().copy(serverPort = "0"),
            source().copy(serverPort = "65536"), source().copy(serverPort = "bad"))
        invalid.forEach { assertFalse(XrayFragmentVariants.supports(it)); assertNull(XrayFragmentVariants.create(it, FragmentPreset.LIGHT, "trial", 40)) }
        assertNull(XrayFragmentVariants.create(source(), FragmentPreset.LIGHT, " ", 40))
    }

    @Test fun `WebSocket TLS keeps host path certificate name and mask through outbound generation`() {
        val original = source().copy(network = "ws", host = "cdn.example.invalid", path = "/existing-ws-path?ed=2048",
            sni = "cert.example.invalid")
        for (preset in FragmentPreset.entries) {
            val trial = checkNotNull(XrayFragmentVariants.create(original, preset, "trial", 40))
            val stream = com.v2ray.ang.dto.V2rayConfig.OutboundBean.StreamSettingsBean()
            val transportSni = CoreOutboundBuilder.populateTransportSettings(stream, trial)
            CoreOutboundBuilder.populateTlsSettings(stream, trial, transportSni)
            assertEquals("ws", stream.network)
            assertEquals(original.host, stream.wsSettings?.host)
            assertEquals(original.path, stream.wsSettings?.path)
            assertEquals(original.sni, stream.tlsSettings?.serverName)
            assertEquals(original.fingerPrint, stream.tlsSettings?.fingerprint)
            assertEquals(false, stream.tlsSettings?.allowInsecure)
            assertEquals(JsonParser.parseString(trial.finalMask), JsonParser.parseString(JsonUtil.toJson(stream.finalmask)))
        }
        assertFalse(XrayFragmentVariants.supports(original.copy(security = "reality")))
        assertFalse(XrayFragmentVariants.supports(original.copy(security = "none")))
        assertFalse(XrayFragmentVariants.supports(original.copy(browserDialerMode = "WebView")))
        assertFalse(XrayFragmentVariants.supports(original.copy(browserDialerMode = "OkHttp")))
    }

    @Test fun `preceding profile format and persisted trial JSON round trip preserve all fields`() {
        val old = """{"configType":"VLESS","server":"example.invalid","serverPort":"443","network":"tcp","security":"tls","password":"local-test-id"}"""
        val original = checkNotNull(JsonUtil.fromJson(old, ProfileItem::class.java))
        assertNull(original.finalMask)
        for (preset in FragmentPreset.entries) {
            val trial = checkNotNull(XrayFragmentVariants.create(original, preset, "trial", 40))
            assertEquals(trial, JsonUtil.fromJson(JsonUtil.toJson(trial), ProfileItem::class.java))
        }
    }

    @Test fun `trial mask reaches the actual outbound transport without global settings or UDP changes`() {
        for (preset in FragmentPreset.entries) {
            val trial = checkNotNull(XrayFragmentVariants.create(source(), preset, "trial", 40))
            val stream = com.v2ray.ang.dto.V2rayConfig.OutboundBean.StreamSettingsBean()
            CoreOutboundBuilder.populateTransportSettings(stream, trial)
            CoreOutboundBuilder.populateTlsSettings(stream, trial, null)
            assertEquals(JsonParser.parseString(trial.finalMask), JsonParser.parseString(JsonUtil.toJson(stream.finalmask)))
            assertEquals("tls", stream.security)
            assertEquals(false, stream.tlsSettings?.allowInsecure)
            assertEquals("example.invalid", stream.tlsSettings?.serverName)
        }
    }

    @Test fun `XHTTP TLS preserves mode extra host path and TLS in every trial`() {
        val extra = """{"xmux":{"maxConcurrency":"4-8","hMaxRequestTimes":"10-20"},"xPaddingBytes":"100-1000","headers":{"X-Test":"unchanged"}}"""
        for (mode in listOf(null, "", "auto", "packet-up", "stream-up", "stream-one")) {
            val original = source().copy(network = "xhttp", host = "cdn.example.invalid", path = "/existing-xhttp/",
                xhttpMode = mode, xhttpExtra = extra, alpn = "h2,http/1.1")
            val before = original.copy()
            for (preset in FragmentPreset.entries) {
                val trial = checkNotNull(XrayFragmentVariants.create(original, preset, "trial", 40))
                val stream = com.v2ray.ang.dto.V2rayConfig.OutboundBean.StreamSettingsBean()
                val sni = CoreOutboundBuilder.populateTransportSettings(stream, trial)
                CoreOutboundBuilder.populateTlsSettings(stream, trial, sni)
                assertEquals("xhttp", stream.network)
                assertEquals(original.host, stream.xhttpSettings?.host)
                assertEquals(original.path, stream.xhttpSettings?.path)
                assertEquals(mode, stream.xhttpSettings?.mode)
                assertEquals(JsonParser.parseString(extra), stream.xhttpSettings?.extra)
                assertEquals(original.sni, stream.tlsSettings?.serverName)
                assertEquals(listOf("h2", "http/1.1"), stream.tlsSettings?.alpn)
                assertEquals(false, stream.tlsSettings?.allowInsecure)
                assertEquals(JsonParser.parseString(trial.finalMask), JsonParser.parseString(JsonUtil.toJson(stream.finalmask)))
            }
            assertEquals(before, original)
        }
    }

    @Test fun `XHTTP TCP trials reject H3 separate download malformed extra and unknown modes`() {
        val xhttp = source().copy(network = "xhttp")
        val unsupported = listOf(xhttp.copy(alpn = "h3"), xhttp.copy(alpn = "h2, H3"),
            xhttp.copy(alpn = "http/1.1"), xhttp.copy(alpn = "unknown"),
            xhttp.copy(xhttpExtra = """{"downloadSettings":{"address":"example.invalid","port":443}}"""),
            xhttp.copy(xhttpExtra = """{"DownloadSettings":{}}"""), xhttp.copy(xhttpExtra = "[1]"),
            xhttp.copy(xhttpExtra = "null"), xhttp.copy(xhttpExtra = "{broken"),
            xhttp.copy(xhttpMode = "unknown"), xhttp.copy(security = "reality"), xhttp.copy(security = "none"))
        unsupported.forEach {
            assertFalse(XrayFragmentVariants.supports(it))
            assertNull(XrayFragmentVariants.create(it, FragmentPreset.LIGHT, "trial", 40))
        }
    }

    @Test fun `XHTTP empty extra and null download are eligible and remain untouched`() {
        for (extra in listOf(null, "", "  ", "{}", "{\"downloadSettings\":null}")) {
            val xhttp = source().copy(network = "xhttp", xhttpExtra = extra)
            val trial = checkNotNull(XrayFragmentVariants.create(xhttp, FragmentPreset.BASELINE, "trial", 40))
            assertEquals(extra, trial.xhttpExtra)
            assertEquals("{}", trial.finalMask)
        }
    }

    @Test fun `menu predicate does not parse extra but creation still rejects unsupported download`() {
        val profile = source().copy(network = "xhttp", xhttpExtra = "{\"downloadSettings\":{}}")
        assertTrue(XrayFragmentVariants.canOffer(profile))
        assertFalse(XrayFragmentVariants.supports(profile))
        assertNull(XrayFragmentVariants.create(profile, FragmentPreset.LIGHT, "trial", 40))
    }
}
