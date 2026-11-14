package com.v2ray.ang.fmt

import com.google.gson.Gson
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AmneziaWgFmtTest {
    private val configuration = """
[Interface]
PrivateKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
Address = 10.8.0.2/32, fd00::2/128
DNS = 1.1.1.1
Jc = 4
Jmin = 40
Jmax = 70
S1 = 12
S2 = 16
H1 = 123456
H2 = 234567
H3 = 345678
H4 = 456789

[Peer]
PublicKey = AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=
Endpoint = [2001:db8::1]:51820
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
    """.trimIndent()

    @Test fun `obfuscation parameters survive conversion to native config`() {
        val config = AmneziaWgFmt.parseConfig(configuration)
        val native = config.toAwgUserspaceString()
        for (attribute in listOf("jc=4", "jmin=40", "jmax=70", "s1=12", "s2=16", "h1=123456", "h4=456789")) {
            assertTrue(native.lines().contains(attribute), attribute)
        }
        assertTrue(native.contains("allowed_ip=0:0:0:0:0:0:0:0/0"))
        assertEquals(2, config.getInterface().addresses.size)
    }

    @Test fun `IPv6 endpoint is stored separately from its port without secret in profile`() {
        val profile = AmneziaWgFmt.parse(configuration)
        assertEquals("2001:db8::1", profile.server)
        assertEquals("51820", profile.serverPort)
        assertEquals(EConfigType.AMNEZIAWG, profile.configType)
        assertNull(profile.secretKey)
        assertFalse(profile.toString().contains("PrivateKey"))
    }

    @Test fun `configuration files accept leading comments whitespace and BOM`() {
        assertTrue(AmneziaWgFmt.isQuickConfig("\uFEFF# comment\n\n [Interface]\n"))
        assertFalse(AmneziaWgFmt.isQuickConfig("vless://example.com"))
        assertEquals(AmneziaWgFmt.parse(configuration).nativeConfigHash,
            AmneziaWgFmt.parse("\uFEFF# comment\n" + configuration).nativeConfigHash)
    }

    @Test fun `plain WireGuard is not silently classified as AWG`() {
        assertFalse(AmneziaWgFmt.isAmneziaConfig("[Interface]\nPrivateKey=x\n[Peer]\nPublicKey=y"))
        assertFalse(AmneziaWgFmt.isAmneziaConfig("# Jc = 4\n[Interface]"))
        assertTrue(AmneziaWgFmt.isAmneziaConfig("[Interface]\n  jc = 4"))
    }

    @Test fun `unknown attributes are rejected rather than dropped`() {
        assertThrows(Exception::class.java) { AmneziaWgFmt.parseConfig(configuration.replace("Jc = 4", "UnsupportedSetting = 4")) }
    }

    @Test fun `missing address is rejected`() {
        assertThrows(Exception::class.java) { AmneziaWgFmt.parseConfig(configuration.replace("Address = 10.8.0.2/32, fd00::2/128", "")) }
    }

    @Test fun `missing endpoint is rejected`() {
        assertThrows(Exception::class.java) { AmneziaWgFmt.parseConfig(configuration.replace("Endpoint = [2001:db8::1]:51820", "")) }
    }

    @Test fun `invalid private key is rejected`() {
        assertThrows(Exception::class.java) { AmneziaWgFmt.parseConfig(configuration.replace(Regex("PrivateKey = .*"), "PrivateKey = invalid")) }
    }

    @Test fun `missing peer and oversized configuration are rejected`() {
        assertThrows(Exception::class.java) { AmneziaWgFmt.parseConfig(configuration.substringBefore("[Peer]")) }
        assertThrows(Exception::class.java) { AmneziaWgFmt.parseConfig(" ".repeat(262145)) }
    }

    @Test fun `duplicate detection distinguishes native configs with the same endpoint`() {
        val original = AmneziaWgFmt.parse(configuration).duplicateIdentity()
        val changed = AmneziaWgFmt.parse(configuration.replace("Jc = 4", "Jc = 5")).duplicateIdentity()
        assertNotEquals(original, changed)
        assertEquals(original, AmneziaWgFmt.parse(configuration).duplicateIdentity())
    }

    @Test fun `persisted legacy VLESS and WireGuard types remain unchanged`() {
        val gson = Gson()
        assertEquals(EConfigType.VLESS, gson.fromJson("{\"configType\":\"VLESS\",\"server\":\"example.com\"}", ProfileItem::class.java).configType)
        assertEquals(EConfigType.WIREGUARD, EConfigType.fromInt(7))
        assertEquals(EConfigType.AMNEZIAWG, EConfigType.fromInt(11))
        val profile = AmneziaWgFmt.parse(configuration)
        assertEquals(profile, gson.fromJson(gson.toJson(profile), ProfileItem::class.java))
    }

    @Test fun `native export round trips keys routes DNS and obfuscation`() {
        val original = configuration.replace("S2 = 16", "S2 = 16\nS3 = 8\nS4 = 4\nI1 = <b 0x1234>")
        val exported = AmneziaWgFmt.exportNative(original)
        val parsed = AmneziaWgFmt.parseConfig(exported)
        assertEquals(AmneziaWgFmt.parseConfig(original).toAwgUserspaceString(), parsed.toAwgUserspaceString())
        assertTrue(exported.contains("PrivateKey = "))
        assertTrue(exported.contains("DNS = "))
        assertFalse(exported.contains("auth_data"))
        assertFalse(exported.contains("api_key"))
    }

    @Test fun `native export rejects subscription links and incomplete native configs`() {
        for (raw in listOf("vpn://synthetic", "{\"auth_data\":{\"api_key\":\"synthetic\"}}", "[Interface]\nJc = 4")) {
            assertThrows(Exception::class.java) { AmneziaWgFmt.exportNative(raw) }
        }
    }
}
