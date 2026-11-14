package com.v2ray.ang.core.privacy

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PrivacyRouteTest {
    @Test fun `Xray only admits the actual runtime listener and never guesses a fallback`() {
        assertEquals(PrivacyRoute.XRAY_SOCKS, privacyRoute(true, PrivacyRoute.XRAY_SOCKS, 10808))
        for (port in listOf(null, -1, 0, 65536))
            assertEquals(PrivacyRoute.UNAVAILABLE, privacyRoute(true, PrivacyRoute.XRAY_SOCKS, port))
        assertEquals(PrivacyRoute.UNAVAILABLE, privacyRoute(true, PrivacyRoute.UNAVAILABLE, 10808))
    }
    @Test fun `owned VPN does not require a proxy listener`() {
        assertEquals(PrivacyRoute.OWNED_VPN, privacyRoute(true, PrivacyRoute.OWNED_VPN, null))
        assertEquals(PrivacyRoute.DIRECT, privacyRoute(false, PrivacyRoute.UNAVAILABLE, null))
    }
    @Test fun `proxy egress is never classified as Android DNS evidence`() {
        val direct = PrivacyObservation(false, 1, 100, 100, resolvers = setOf("1.1.1.1"))
        val proxy = direct.copy(vpn = true, proxyPath = true)
        assertEquals(DnsComparison.UNAVAILABLE, compareDns(proxy, direct, 200))
        assertEquals(DnsComparison.UNAVAILABLE, compareDns(proxy.copy(resolvers = setOf("8.8.8.8")), direct, 200))
    }
}
