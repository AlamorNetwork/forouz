package com.v2ray.ang.core.privacy

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class PrivacyObservationTest {
    private fun sample(vpn: Boolean, key: Long? = 1, time: Long = 100, resolvers: Set<String> = setOf("1.1.1.1")) =
        PrivacyObservation(vpn, key, time, time, "203.0.113.1", null, resolvers)

    @Test fun `shared resolver is only a potential bypass`() {
        assertEquals(DnsComparison.POSSIBLE_BYPASS, compareDns(sample(true), sample(false), 200))
    }
    @Test fun `different resolver is only no match in sample`() {
        assertEquals(DnsComparison.NO_MATCH_IN_SAMPLE, compareDns(sample(true, resolvers = setOf("8.8.8.8")), sample(false), 200))
    }
    @Test fun `missing or invalid reference cannot classify a leak`() {
        assertEquals(DnsComparison.NO_BASELINE, compareDns(sample(true), null, 200))
        assertEquals(DnsComparison.NO_BASELINE, compareDns(sample(true), sample(true), 200))
        assertEquals(DnsComparison.NO_BASELINE, compareDns(sample(false), sample(false), 200))
    }
    @Test fun `old reference and clock reset are rejected`() {
        assertEquals(DnsComparison.BASELINE_EXPIRED, compareDns(sample(true), sample(false), BASELINE_MAX_AGE + 101))
        assertEquals(DnsComparison.BASELINE_EXPIRED, compareDns(sample(true), sample(false), 99))
    }
    @Test fun `unknown or changed physical network prevents comparison`() {
        for (key in listOf(null, 2L)) assertEquals(DnsComparison.DIFFERENT_NETWORK, compareDns(sample(true, key), sample(false), 200))
    }
    @Test fun `DNS failure is unknown even if IP check succeeded`() {
        assertEquals(DnsComparison.UNAVAILABLE, compareDns(sample(true, resolvers = emptySet()), sample(false), 200))
        assertEquals(DnsComparison.UNAVAILABLE, compareDns(sample(true), sample(false, resolvers = emptySet()), 200))
    }
    @Test fun `IP parser accepts only literal expected family`() {
        assertEquals("203.0.113.7", numericIp("203.0.113.7", 4))
        assertNotNull(numericIp("2001:db8::1", 6))
        for (value in listOf("example.com", "127.1", "01.2.3.4", "256.1.2.3", "1.2.3.4:80", "[::1]", "fe80::1%eth0", "١.٢.٣.٤", " 1.2.3.4"))
            assertNull(numericIp(value), value)
        assertNull(numericIp("203.0.113.7", 6))
        assertNull(numericIp("2001:db8::1", 4))
    }

    private fun packet(key: String = "ns", ip: String = "203.0.113.8", flags: Int = 0x8180): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(42); out.writeShort(flags); out.writeShort(1); out.writeShort(1); out.writeShort(0); out.writeShort(0)
            for (label in ResolverAnswer.DOMAIN.split('.')) { out.writeByte(label.length); out.writeBytes(label) }
            out.writeByte(0); out.writeShort(16); out.writeShort(1)
            out.writeShort(0xc00c); out.writeShort(16); out.writeShort(1); out.writeInt(0)
            out.writeShort(2 + key.length + ip.length)
            out.writeByte(key.length); out.writeBytes(key); out.writeByte(ip.length); out.writeBytes(ip)
        }
        return bytes.toByteArray()
    }
    @Test fun `raw query uses TXT IN and only matching transaction replies are accepted`() {
        val query = ResolverAnswer.query(42)
        assertEquals(42, ((query[0].toInt() and 255) shl 8) or (query[1].toInt() and 255))
        assertArrayEquals(byteArrayOf(0, 16, 0, 1), query.takeLast(4).toByteArray())
        assertTrue(ResolverAnswer.decode(query, 42).isEmpty())
        assertEquals(setOf("203.0.113.8"), ResolverAnswer.decode(packet(), 42))
        assertTrue(ResolverAnswer.decode(packet(), 43).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { ResolverAnswer.query(65536) }
    }
    @Test fun `DNS answer extracts only documented ns TXT addresses`() {
        assertEquals(setOf("203.0.113.8"), ResolverAnswer.decode(packet()))
        assertTrue(ResolverAnswer.decode(packet("ecs", "203.0.113.0/24")).isEmpty())
        assertTrue(ResolverAnswer.decode(packet(ip = "secret.invalid")).isEmpty())
    }
    @Test fun `DNS truncation error opcode and incomplete records give no evidence`() {
        for (flags in listOf(0x8380, 0x8183, 0x8980, 0x0180)) assertTrue(ResolverAnswer.decode(packet(flags = flags)).isEmpty())
        val bytes = packet()
        for (i in bytes.indices) assertTrue(ResolverAnswer.decode(bytes.copyOf(i)).isEmpty(), "length=$i")
    }
    @Test fun `DNS pointer loops and unrelated questions are rejected`() {
        val loop = packet(); loop[12] = 0xc0.toByte(); loop[13] = 12
        assertTrue(ResolverAnswer.decode(loop).isEmpty())
        val other = packet(); other[13] = 'X'.code.toByte()
        assertTrue(ResolverAnswer.decode(other).isEmpty())
    }
}
