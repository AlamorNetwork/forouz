package com.v2ray.ang.core.health

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HealthRuntimeTest {
    @Test fun `uses runtime random port rather than current preference`() {
        assertEquals(32123, healthSocksPort("""{"inbounds":[{"protocol":"socks","listen":"127.0.0.1","port":32123,"settings":{"auth":"noauth"}}]}"""))
    }
    @Test fun `missing disabled or authenticated listener cannot cause direct probe fallback`() {
        assertNull(healthSocksPort("{}"))
        assertNull(healthSocksPort("""{"inbounds":[{"protocol":"socks","listen":"::1","port":10808,"settings":{}}]}"""))
        assertNull(healthSocksPort("""{"inbounds":[{"protocol":"socks","listen":"127.0.0.1","port":10808,"settings":{"auth":"password"}}]}"""))
        assertNull(healthSocksPort("""{"inbounds":[{"protocol":"http","listen":"127.0.0.1","port":10808,"settings":{}}]}"""))
    }
    @Test fun `invalid and nonlocal socket endpoints are rejected`() {
        assertNull(healthSocksPort("""{"inbounds":[{"protocol":"socks","listen":"192.0.2.1","port":10808,"settings":{}}]}"""))
        assertNull(healthSocksPort("""{"inbounds":[{"protocol":"socks","listen":"127.0.0.1","port":0,"settings":{}}]}"""))
        assertThrows(Exception::class.java) { healthSocksPort("invalid") }
    }
}
