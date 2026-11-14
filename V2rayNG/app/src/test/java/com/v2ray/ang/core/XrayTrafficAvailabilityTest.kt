package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class XrayTrafficAvailabilityTest {
    @Test fun `only actual configured outbound counters enable telemetry`() {
        assertTrue(XrayTrafficAvailability.enabled("""{"stats":{},"policy":{"system":{"statsOutboundUplink":true,"statsOutboundDownlink":true}}}"""))
        for (raw in listOf("", "broken", "{}", """{"stats":{},"policy":{"levels":{}}}""",
            """{"stats":{},"policy":{"system":{"statsOutboundUplink":false,"statsOutboundDownlink":true}}}""",
            """{"policy":{"system":{"statsOutboundUplink":true,"statsOutboundDownlink":true}}}""")) {
            assertFalse(XrayTrafficAvailability.enabled(raw), raw)
        }
    }
}
