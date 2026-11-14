package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ConnectionSnapshot
import com.v2ray.ang.dto.OutboundTrafficStat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionMeterTest {
    @Test fun `sampling retains the daemon privacy route and actual port`() {
        val initial = ConnectionSnapshot("session", "guid", 1_000, 1_000, trafficAvailable = true,
            privacyRoute = com.v2ray.ang.core.privacy.PrivacyRoute.XRAY_SOCKS, privacyProxyPort = 10808)
        val sampled = ConnectionMeter(initial).sample(4_000, emptyList())
        assertEquals(initial.privacyRoute, sampled.privacyRoute)
        assertEquals(initial.privacyProxyPort, sampled.privacyProxyPort)
    }
    private fun initial(available: Boolean = true) = ConnectionSnapshot("session", "guid", 1_000, 1_000,
        trafficAvailable = available)

    @Test fun `initial session is not a measured zero rate`() {
        val meter = ConnectionMeter(initial())
        assertFalse(meter.snapshot.sampleReady)
        assertEquals(0L, meter.snapshot.uploaded)
    }

    @Test fun `actual elapsed interval determines rates and direct traffic is included once`() {
        val result = ConnectionMeter(initial()).sample(5_000, listOf(
            OutboundTrafficStat(AppConfig.TAG_PROXY, AppConfig.DOWNLINK, 8_000),
            OutboundTrafficStat("custom-chain", AppConfig.UPLINK, 2_000),
            OutboundTrafficStat(AppConfig.TAG_DIRECT, AppConfig.UPLINK, 2_000),
            OutboundTrafficStat(AppConfig.TAG_BLOCKED, AppConfig.DOWNLINK, 999_999),
        ))
        assertEquals(2_000L, result.downloadRate)
        assertEquals(1_000L, result.uploadRate)
        assertEquals(2_500L, result.proxyRate)
        assertEquals(500L, result.directRate)
        assertEquals(8_000L, result.downloaded)
        assertEquals(4_000L, result.uploaded)
    }

    @Test fun `reset deltas accumulate and idle sample preserves total`() {
        val meter = ConnectionMeter(initial())
        meter.sample(4_000, listOf(OutboundTrafficStat("proxy", AppConfig.DOWNLINK, 3_000)))
        val second = meter.sample(7_000, listOf(OutboundTrafficStat("proxy", AppConfig.DOWNLINK, 6_000)))
        assertEquals(9_000L, second.downloaded)
        assertEquals(2_000L, second.downloadRate)
        val idle = meter.sample(10_000, emptyList())
        assertEquals(9_000L, idle.downloaded)
        assertEquals(0L, idle.downloadRate)
        assertTrue(idle.sampleReady)
    }

    @Test fun `invalid negative and unknown direction counters are omitted`() {
        val result = ConnectionMeter(initial()).sample(4_000, listOf(
            OutboundTrafficStat("proxy", AppConfig.UPLINK, -1),
            OutboundTrafficStat("direct", "unknown", 50_000),
        ))
        assertEquals(0L, result.uploaded + result.downloaded)
    }

    @Test fun `unsupported stopped and nonmonotonic samples cannot manufacture readings`() {
        for (start in listOf(initial(false), initial().copy(running = false))) {
            val meter = ConnectionMeter(start)
            assertSame(start, meter.sample(4_000, listOf(OutboundTrafficStat("proxy", AppConfig.UPLINK, 3_000))))
        }
        val meter = ConnectionMeter(initial())
        assertSame(meter.snapshot, meter.sample(1_000, emptyList()))
        assertSame(meter.snapshot, meter.sample(999, emptyList()))
    }
}
