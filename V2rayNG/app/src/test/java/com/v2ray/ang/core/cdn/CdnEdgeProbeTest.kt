package com.v2ray.ang.core.cdn

import com.v2ray.ang.core.lab.LabIssue
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class CdnEdgeProbeTest {
    private class Fixture {
        val factory = mock<SocketFactory>()
        val ssl = mock<SSLSocketFactory>()
        val port80 = mock<Socket>()
        val port443 = mock<Socket>()
        val tls = mock<SSLSocket>()
        init {
            whenever(factory.createSocket()).thenReturn(port80, port443)
            whenever(ssl.createSocket(port443, "edge.example", 443, true)).thenReturn(tls)
            whenever(tls.sslParameters).thenReturn(SSLParameters())
        }
        fun probe() = CdnEdgeProbe(factory, ssl, onFailure = { _, _ -> })
    }
    @Test fun `direct literal connections use ports80 and443 with verified SNI TLS and no HTTP`() = runBlocking {
        val f = Fixture()
        val result = f.probe().measure("8.8.8.8", "edge.example")
        assertTrue(result.reachable()); assertTrue(result.tls.passed())
        val targets = argumentCaptor<java.net.SocketAddress>()
        verify(f.port80).connect(targets.capture(), eq(3000))
        verify(f.port443).connect(targets.capture(), eq(3000))
        assertEquals(listOf(80, 443), targets.allValues.map { (it as InetSocketAddress).port })
        assertTrue(targets.allValues.all { (it as InetSocketAddress).address.hostAddress == "8.8.8.8" })
        val params = argumentCaptor<SSLParameters>()
        verify(f.tls).sslParameters = params.capture()
        assertEquals("HTTPS", params.firstValue.endpointIdentificationAlgorithm)
        assertEquals("edge.example", (params.firstValue.serverNames.single() as SNIHostName).asciiName)
        verify(f.tls).startHandshake()
        verify(f.port80, never()).getOutputStream(); verify(f.tls, never()).getOutputStream()
        verify(f.port80, atLeastOnce()).close(); verify(f.port443, atLeastOnce()).close(); verify(f.tls, atLeastOnce()).close()
    }
    @Test fun `TCP timeout and TLS certificate failure remain distinct while443 stays reachable`() = runBlocking {
        val f = Fixture()
        doThrow(SocketTimeoutException("private-fixture")).whenever(f.port80).connect(any(), any())
        doThrow(SSLHandshakeException("private-fixture").apply { initCause(CertificateException("private-fixture")) })
            .whenever(f.tls).startHandshake()
        val result = f.probe().measure("8.8.8.8", "edge.example")
        assertEquals(LabIssue.TIMEOUT, result.tcp80.issue)
        assertTrue(result.tcp443.passed()); assertEquals(LabIssue.TLS, result.tls.issue)
        assertNotNull(result.tls.tls); assertTrue(result.reachable())
    }
    @Test fun `TLS is skipped after443 failure but port80 can still be reachable`() = runBlocking {
        val f = Fixture()
        doThrow(SocketException("private-fixture")).whenever(f.port443).connect(any(), any())
        val result = f.probe().measure("8.8.8.8", "edge.example")
        assertTrue(result.tcp80.passed()); assertTrue(result.reachable()); assertTrue(result.tls.skipped)
        verify(f.ssl, never()).createSocket(any<Socket>(), any<String>(), any<Int>(), any<Boolean>())
        Unit
    }
    @Test fun `cancel closes in-flight TLS socket instead of waiting for its timeout`() = runBlocking {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val released = CountDownLatch(1)
        doAnswer { entered.complete(Unit); released.await(5, TimeUnit.SECONDS); throw SocketException("closed") }
            .whenever(f.tls).startHandshake()
        doAnswer { released.countDown(); null }.whenever(f.tls).close()
        val job = launch { f.probe().measure("8.8.8.8", "edge.example") }
        withTimeout(2000) { entered.await() }
        withTimeout(2000) { job.cancelAndJoin() }
        verify(f.tls, atLeastOnce()).close()
    }
    @Test fun `SNI normalization rejects IPs URLs whitespace and malformed labels`() {
        assertEquals("edge.example", CdnEdgeProbe.sni("EDGE.example"))
        for (value in listOf("", "8.8.8.8", "https://edge.example", "edge.example/path", "edge .example", "-bad.example", "a..example"))
            assertThrows(Exception::class.java) { CdnEdgeProbe.sni(value) }
    }
    @Test fun `edge history preserves independent metrics and copy exports only eligible IPs`() {
        val ok = CdnEdgeCheck(10)
        val fail = CdnEdgeCheck(issue = LabIssue.IO)
        val edge = CdnEdgeResult(ok, ok, fail)
        val request = CdnRequest("run", "guid", "a".repeat(64), "cloudflare", listOf("8.8.8.8"), false, edgeScan = true)
        val row = CdnRow("8.8.8.8", edge = edge)
        val run = CdnRun(request, 1, 1, CdnStatus.COMPLETE, listOf(row), 7)
        assertEquals(run, CdnRecords.decode(CdnRecords.encode(run)))
        assertEquals("8.8.8.8", run.edgeAddresses().joinToString("\n")); assertFalse(run.canSave(2, 7))
        assertNull(row.median); assertFalse(row.usable(false))
        assertThrows(Exception::class.java) { CdnRecords.encode(run.copy(rows = listOf(row.copy(nativeSamples = listOf(1))))) }
        val negative = row.copy(edge = edge.copy(tcp80 = CdnEdgeCheck(-1)))
        assertThrows(Exception::class.java) { CdnRecords.encode(run.copy(rows = listOf(negative))) }
    }
    @Test fun `copy order favors verified TLS then443 then80 excludes failures and deduplicates`() {
        val ok = CdnEdgeCheck(10); val failed = CdnEdgeCheck(issue = LabIssue.IO); val skip = CdnEdgeCheck(skipped = true)
        val req = CdnRequest("edge", "guid", "a".repeat(64), "cloudflare", listOf("8.8.8.8"), false,
            prefixes = listOf("8.8.8.0/24"), edgeScan = true)
        val rows = listOf(CdnRow("8.8.8.8", edge = CdnEdgeResult(ok, failed, skip)),
            CdnRow("8.8.8.9", edge = CdnEdgeResult(failed, failed, skip)),
            CdnRow("8.8.8.10", edge = CdnEdgeResult(failed, ok, failed)),
            CdnRow("8.8.8.11", edge = CdnEdgeResult(ok, ok, CdnEdgeCheck(90))))
        val run = CdnRun(req, 1, 1, CdnStatus.CANCELLED, rows, 7)
        assertEquals(listOf("8.8.8.11", "8.8.8.10", "8.8.8.8"), run.edgeAddresses())
        assertEquals(run.edgeAddresses(), run.copy(rows = rows + rows.first()).edgeAddresses())
        assertEquals(run, CdnRecords.decode(CdnRecords.encode(run)))
        assertThrows(Exception::class.java) { req.copy(download = true).validate() }
    }
}
