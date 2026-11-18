package com.v2ray.ang.core.cdn

import com.v2ray.ang.core.lab.LabDownloadResult
import com.v2ray.ang.core.lab.LabIssue
import com.v2ray.ang.core.lab.LabTlsFailure
import com.v2ray.ang.core.lab.LabProbeFailure
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CdnRecordsTest {
    @Test fun `preceding report format without TLS diagnostic remains readable`() {
        val old = """{"version":1,"run":{"request":{"id":"run","guid":"guid","revision":"${"a".repeat(64)}","provider":"cloudflare",
            "addresses":["8.8.8.8"],"download":false,"budget":64,"prefixes":["8.8.8.8/32"],"networkHandle":7},
            "createdAt":1000,"updatedAt":1000,"status":"COMPLETE","rows":[{"address":"8.8.8.8","samples":[],"failures":1,"bytes":0,"issue":"TLS"}],
            "networkHandle":7,"issue":"NONE"}}"""
        val result = requireNotNull(CdnRecords.decode(old))
        assertEquals(LabIssue.TLS, result.rows.single().issue); assertNull(result.rows.single().tlsFailure)
        assertNull(result.rows.single().http1Control); assertNull(result.rows.single().nativeControlMillis)
        assertNull(result.best())
    }
    @Test fun `controls run once only for failed baselines and never become success samples`() = runBlocking {
        var checks = 0
        val source = CdnRow(null, failures = 1, issue = LabIssue.TLS, tlsFailure = LabTlsFailure.HANDSHAKE)
        val checked = CdnMeasurements.compareBaseline(source, { checks++; LabIssue.NONE }, { checks++; 120L })
        assertEquals(2, checks); assertEquals(LabIssue.NONE, checked.http1Control); assertEquals(120L, checked.nativeControlMillis)
        assertFalse(checked.usable(false)); assertTrue(checked.samples.isEmpty())
        for (skip in listOf(checked, source.copy(address = "8.8.8.8"), source.copy(issue = LabIssue.HTTP), good(null)))
            assertEquals(skip, CdnMeasurements.compareBaseline(skip, { error("unexpected") }, { error("unexpected") }))
        val run = CdnRun(request(), 1000, 1000, CdnStatus.COMPLETE, listOf(checked), 7)
        assertEquals(run, CdnRecords.decode(CdnRecords.encode(run))); assertNull(run.best())
    }
    @Test fun `control failures stay bounded in report and cancellation propagates`() = runBlocking {
        val source = CdnRow(null, failures = 1, issue = LabIssue.TIMEOUT)
        val failed = CdnMeasurements.compareBaseline(source, { LabIssue.TIMEOUT }, { -10L })
        assertEquals(-1L, failed.nativeControlMillis); assertFalse(failed.usable(false))
        val run = CdnRun(request(), 1000, 1000, CdnStatus.COMPLETE, listOf(failed), 7)
        assertThrows(Exception::class.java) { CdnRecords.encode(run.copy(rows = listOf(failed.copy(address = "8.8.8.8")))) }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { runBlocking {
            CdnMeasurements.compareBaseline(source, { throw kotlinx.coroutines.CancellationException() }, { error("unexpected") })
        } }
        Unit
    }
    @Test fun `TLS detail reaches report without exception text or false success`() = runBlocking {
        val row = CdnMeasurements.measure(null, false) { _, _ -> LabDownloadResult(LabIssue.TLS, 0,
            failure = LabProbeFailure(LabIssue.TLS, LabTlsFailure.CHAIN, listOf("SSLHandshakeException", "CertPathValidatorException"))) }
        assertEquals(LabTlsFailure.CHAIN, row.tlsFailure); assertFalse(row.usable(false))
        val run = CdnRun(request(), 1000, 1000, CdnStatus.COMPLETE, listOf(row), 7)
        val raw = CdnRecords.encode(run)
        assertEquals(run, CdnRecords.decode(raw)); assertFalse(raw.contains("SSLHandshakeException"))
        assertThrows(Exception::class.java) { CdnRecords.encode(run.copy(rows = listOf(row.copy(issue = LabIssue.NONE)))) }
        Unit
    }
    private fun request(download: Boolean = false) = CdnRequest("run", "guid", "a".repeat(64), "cloudflare", listOf("8.8.8.8"), download)
    private fun good(address: String? = "8.8.8.8") = CdnRow(address, listOf(80, 100, 120))
    @Test fun `ranking only accepts complete outcomes and original wins a tie`() {
        val run = CdnRun(request(), 1000, 1000, CdnStatus.COMPLETE, listOf(good(), good(null)), 7)
        assertNull(run.best()!!.address)
        assertNull(run.copy(rows = listOf(good().copy(failures = 1))).best())
        assertNull(run.copy(request = request(true)).best())
        assertEquals(100L, good().median); assertEquals(40L, good().variation)
    }
    @Test fun `saving is gated by completed fresh same physical network and candidate success`() {
        val run = CdnRun(request(), 1000, 1000, CdnStatus.COMPLETE, listOf(good()), 7)
        assertTrue(run.canSave(2000, 7))
        assertFalse(run.canSave(999, 7)); assertFalse(run.canSave(902_000, 7))
        assertFalse(run.canSave(2000, 0)); assertFalse(run.canSave(2000, 8))
        assertFalse(run.copy(status = CdnStatus.CANCELLED).canSave(2000, 7))
        assertFalse(run.copy(rows = listOf(good(null))).canSave(2000, 7))
    }
    @Test fun `versioned report round trips without profile payload and rejects corrupt records`() {
        val run = CdnRun(request(), 1000, 1000, CdnStatus.COMPLETE, listOf(good()), 7)
        val raw = CdnRecords.encode(run)
        assertEquals(run, CdnRecords.decode(raw)); assertNull(CdnRecords.decode(null))
        for (bad in listOf("{}", raw.replace("\"version\":1", "\"version\":2"), raw.replace("COMPLETE", "UNKNOWN"),
            raw.replace("\"bytes\":0", "\"bytes\":-1"))) assertThrows(Exception::class.java) { CdnRecords.decode(bad) }
        assertThrows(Exception::class.java) { CdnRecords.encode(run.copy(rows = listOf(good(), good()))) }
        assertFalse(raw.contains("password")); assertFalse(raw.contains("vless://"))
    }
    @Test fun `three HTTPS attempts are required and throughput is explicit`() = runBlocking {
        val calls = mutableListOf<Long>()
        val result = CdnMeasurements.measure("8.8.8.8", true) { _, bytes ->
            calls += bytes; LabDownloadResult(LabIssue.NONE, bytes, 50, 100)
        }
        assertEquals(listOf(1024L, 1024L, 1024L, 10_000_000L), calls)
        assertTrue(result.usable(true))
        calls.clear()
        val failed = CdnMeasurements.measure(null, true) { _, bytes -> calls += bytes; LabDownloadResult(LabIssue.TIMEOUT, 0) }
        assertEquals(1, failed.failures); assertEquals(listOf(1024L), calls)
        assertFalse(failed.usable(false))
    }
}
