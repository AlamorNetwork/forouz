package com.v2ray.ang.core.cdn

import com.v2ray.ang.handler.cdnFeedUrl
import com.v2ray.ang.handler.fetchCdnFeed
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CdnOfflineInputsTest {
    @Test fun `custom catalog URL is normalized HTTPS with no credential query or fragment`() {
        assertEquals("https://mirror.example/forouz/feed.json", cdnFeedUrl("  https://MIRROR.example/forouz/feed.json  "))
        for (url in listOf("http://mirror.example/feed", "https://user:private-fixture@mirror.example/feed",
            "https://mirror.example/feed?token=private-fixture", "https://mirror.example/feed#private-fixture",
            "file:///private-fixture", "https://mirror.example/\nfeed", "x".repeat(2049))) {
            val error = assertThrows(IllegalArgumentException::class.java) { cdnFeedUrl(url) }
            assertFalse(error.message.orEmpty().contains("private-fixture"))
        }
    }
    @Test fun `invalid source never creates a request`() = runBlocking {
        var called = false
        val factory = Call.Factory { called = true; error("must not call") }
        assertTrue(runCatching { fetchCdnFeed(factory, "http://mirror.example/feed") }.isFailure)
        assertFalse(called)
    }
    @Test fun `manual IPs accept common separators deduplicate and reject whole malformed input`() {
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), CdnManualIps.parse("1.1.1.1\n8.8.8.8,1.1.1.1;8.8.8.8"))
        for (raw in listOf("", "127.0.0.1", "1.1.1.1\n192.168.1.1", "01.1.1.1", "::1", "https://1.1.1.1", "private-fixture"))
            assertThrows(IllegalArgumentException::class.java) { CdnManualIps.parse(raw) }
        val many = (0..512).joinToString("\n") { "8.8.${it / 256}.${it % 256}" }
        assertThrows(IllegalArgumentException::class.java) { CdnManualIps.parse(many) }
        assertThrows(IllegalArgumentException::class.java) { CdnManualIps.parse("1".repeat(16_385)) }
    }
    @Test fun `manual request requires exact public IP scopes and no catalog provider claim`() {
        val req = CdnRequest("run", "guid", "a".repeat(64), "manual", listOf("1.1.1.1", "8.8.8.8"), false,
            budget = 2, edgeScan = true, manual = true)
        req.validate()
        for (bad in listOf(req.copy(provider = "cloudflare"), req.copy(edgeScan = false),
            req.copy(prefixes = listOf("0.0.0.0/1")), req.copy(budget = 32), req.copy(download = true)))
            assertThrows(IllegalArgumentException::class.java) { bad.validate() }
        val run = CdnRun(req, 1, 1, CdnStatus.CANCELLED)
        assertEquals(run, CdnRecords.decode(CdnRecords.encode(run)))
    }
}
