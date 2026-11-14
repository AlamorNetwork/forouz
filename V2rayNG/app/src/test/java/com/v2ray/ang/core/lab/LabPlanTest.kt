package com.v2ray.ang.core.lab

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LabPlanTest {
    private fun target(id: String, kind: LabTransport) = LabTarget(id, "group", kind)

    @Test fun `classification uses explicit transport and preserves complex or unknown types`() {
        val profile = ProfileItem(configType = EConfigType.VLESS)
        for ((network, bucket) in mapOf("ws" to LabTransport.WS, "xhttp" to LabTransport.XHTTP,
            "splithttp" to LabTransport.XHTTP, "grpc" to LabTransport.GRPC, "raw" to LabTransport.RAW,
            "tcp" to LabTransport.RAW, "h2" to LabTransport.HTTP2, "httpupgrade" to LabTransport.HTTP_UPGRADE,
            "kcp" to LabTransport.KCP, "future" to LabTransport.OTHER)) {
            assertEquals(bucket, LabTransport.of(profile.copy(network = network)))
        }
        assertEquals(LabTransport.RAW, LabTransport.of(profile))
        for (type in listOf(EConfigType.CUSTOM, EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN))
            assertEquals(LabTransport.COMPOSITE, LabTransport.of(profile.copy(configType = type, network = "ws")))
        assertEquals(LabTransport.AMNEZIAWG, LabTransport.of(profile.copy(configType = EConfigType.AMNEZIAWG)))
        assertEquals(LabTransport.WIREGUARD, LabTransport.of(profile.copy(configType = EConfigType.WIREGUARD)))
        assertEquals(LabTransport.HYSTERIA, LabTransport.of(profile.copy(configType = EConfigType.HYSTERIA2)))
        assertEquals(LabTransport.OTHER, LabTransport.of(profile.copy(configType = EConfigType.SOCKS)))
    }

    @Test fun `finite sweep interleaves categories without charging repeated small categories`() {
        val input = listOf(target("w1", LabTransport.WS), target("w2", LabTransport.WS),
            target("x1", LabTransport.XHTTP), target("g1", LabTransport.GRPC), target("w3", LabTransport.WS))
        assertEquals(listOf("x1", "w1", "g1", "w2", "w3"), LabPlan.sweep(input).map { it.guid })
        assertTrue(LabPlan.sweep(emptyList()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { LabPlan.sweep(input + input.first()) }
    }

    @Test fun `continuous scheduler rotates categories and their own profiles independently`() {
        val eligible = listOf(target("w1", LabTransport.WS), target("w2", LabTransport.WS),
            target("x", LabTransport.XHTTP), target("g", LabTransport.GRPC))
        val rr = LabRoundRobin()
        assertEquals(listOf("x", "w1", "g", "x", "w2", "g", "x", "w1"),
            (1..8).map { rr.next(eligible)!!.guid })
        assertNull(rr.next(emptyList()))
        assertEquals("w2", rr.next(listOf(eligible[1]))!!.guid)
        assertThrows(IllegalArgumentException::class.java) { rr.next(eligible + eligible.first()) }
    }

    @Test fun `preview uses decimal ten MB and fails safely on invalid or overflowing budgets`() {
        assertEquals(100_000_000L, LabPlan.estimatedDownloadBytes(10))
        assertEquals(200_000_000L, LabPlan.estimatedDownloadBytes(10, 2))
        assertEquals(0L, LabPlan.estimatedDownloadBytes(0))
        assertThrows(IllegalArgumentException::class.java) { LabPlan.estimatedDownloadBytes(-1) }
        assertThrows(IllegalArgumentException::class.java) { LabPlan.estimatedDownloadBytes(1, -1) }
        assertThrows(ArithmeticException::class.java) { LabPlan.estimatedDownloadBytes(Int.MAX_VALUE, Int.MAX_VALUE) }
    }
}
